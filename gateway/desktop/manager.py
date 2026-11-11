"""Per-user supervisor and transactional same-schema updates; never terminates active work."""
import json
import logging
from logging.handlers import RotatingFileHandler
import os
from pathlib import Path
import secrets
import shutil
import socket
import sqlite3
import subprocess
import sys
import tempfile
import threading
import time
import uuid

import requests
from desktop.configuration import atomic_json, authority, control_host, environment, load
from desktop import releases, windows_job
from v14.state_lock import StateDirectoryLock, StateDirectoryAlreadyOwned
from v14.journal import backup_database

RESTART = 75


def read_pointer(root):
    pointer = json.loads((Path(root) / 'current.json').read_text(encoding='utf-8'))
    releases.version(pointer['version'])
    if pointer.get('previous'):
        releases.version(pointer['previous'])
    return pointer


def release_path(root, number):
    releases.version(number)
    return Path(root) / 'releases' / number


def executable(root, number, command):
    return [str(release_path(root, number) / 'Gateway.exe'), command, '--root', str(root)]


def startup_registration(root, enabled):
    if os.name != 'nt':
        return
    import winreg
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, r'Software\Microsoft\Windows\CurrentVersion\Run') as key:
        if enabled:
            # Fixed interpreter + quoted argument, never interpreted by cmd.exe.
            powershell = str(Path(os.environ['SystemRoot']) / 'System32/WindowsPowerShell/v1.0/powershell.exe')
            command = subprocess.list2cmdline([powershell, '-NoProfile', '-WindowStyle', 'Hidden', '-ExecutionPolicy', 'Bypass', '-File', str(Path(root) / 'Start-Gateway.ps1')])
            winreg.SetValueEx(key, 'GPTMobileGateway', 0, winreg.REG_SZ, command)
        else:
            try:
                winreg.DeleteValue(key, 'GPTMobileGateway')
            except FileNotFoundError:
                pass


def request(root, action, timeout=50):
    """Local UI/installer control queue. A busy stop is refused, never forced."""
    root = Path(root)
    ident = uuid.uuid4().hex
    atomic_json(root / 'request.json', {'id': ident, 'action': action})
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = json.loads((root / 'response.json').read_text(encoding='utf-8'))
            if result.get('id') == ident:
                if not result.get('accepted'):
                    raise RuntimeError(result.get('error', 'Request refused'))
                if action == 'stop':
                    stop_lock = StateDirectoryLock(root / 'manager.sqlite3')
                    try:
                        stop_lock.acquire()
                    except StateDirectoryAlreadyOwned:
                        time.sleep(.1)
                        continue
                    else:
                        stop_lock.release()
                return result
        # On Windows the writer's atomic replacement can briefly leave the
        # destination unreadable to another process. Treat that sharing
        # violation like the other incomplete-response states and retry until
        # the bounded control deadline instead of failing a valid stop.
        except (OSError, json.JSONDecodeError):
            pass
        # No supervisor: remove our request so it cannot stop a future launch.
        lock = StateDirectoryLock(root / 'manager.sqlite3')
        try:
            lock.acquire()
        except StateDirectoryAlreadyOwned:
            pass
        else:
            lock.release()
            if action == 'stop':
                (root / 'request.json').unlink(missing_ok=True)
                return {'accepted': True}
            raise RuntimeError('Start Gateway before requesting this action')
        time.sleep(.2)
    try:
        if json.loads((root / 'request.json').read_text(encoding='utf-8')).get('id') == ident:
            (root / 'request.json').unlink()
    except (OSError, ValueError):
        pass
    raise TimeoutError('Gateway did not acknowledge the request; inspect logs')


class Supervisor:
    def __init__(self, root):
        self.root = Path(root).resolve()
        self.cfg = load(self.root)
        self.token = secrets.token_urlsafe(48)
        self.http = requests.Session()
        self.http.trust_env = False
        self.child = None
        self.observer_stop = threading.Event()
        self.observer_thread = None
        self.tray = None
        self.dashboard_child = None
        self.job = windows_job.create()
        self.log = logging.getLogger('desktop.' + str(self.root))
        self.log.setLevel(logging.INFO)
        (self.root / 'logs').mkdir(parents=True, exist_ok=True)
        handler = RotatingFileHandler(self.root / 'logs' / 'gateway.log', maxBytes=5*1024*1024, backupCount=4, encoding='utf-8')
        self.log.addHandler(handler)
        self.status = {'version': read_pointer(self.root)['version'], 'state': 'starting'}
        self.publish()

    def publish(self, **values):
        self.status.update(values, updatedAt=time.time())
        atomic_json(self.root / 'status.json', self.status)

    def control(self, action=None):
        url = 'http://' + authority(control_host(self.cfg), self.cfg['port']) + '/gateway/admin/maintenance'
        response = self.http.request('POST' if action else 'GET', url,
            headers={'X-Gateway-Management': self.token}, json={'action': action} if action else None, timeout=(2, 8))
        response.raise_for_status()
        return response.json()

    def export_diagnostics(self):
        """Fetch a consistent live snapshot with the existing local manager identity."""
        url = 'http://' + authority(control_host(self.cfg), self.cfg['port']) + '/gateway/admin/debug/export'
        destination = self.root / 'logs' / 'gateway-debug.zip'
        temporary = destination.with_suffix('.zip.tmp')
        try:
            with self.http.get(url, params={'format': 'zip'}, headers={'X-Gateway-Management': self.token},
                               timeout=(2, 15), stream=True, allow_redirects=False) as response:
                response.raise_for_status()
                if response.headers.get('content-type', '').split(';')[0] != 'application/zip':
                    raise ValueError('Gateway does not support the full diagnostic bundle. Update the complete gateway package.')
                size, deadline = 0, time.monotonic() + 30
                with temporary.open('wb') as out:
                    for chunk in response.iter_content(65536):
                        size += len(chunk)
                        if size > 32 * 1024 * 1024 or time.monotonic() > deadline:
                            raise ValueError('Diagnostic bundle exceeded its export bound')
                        out.write(chunk)
                    out.flush()
                    os.fsync(out.fileno())
            os.replace(temporary, destination)
            return destination
        finally:
            temporary.unlink(missing_ok=True)

    def spawn(self, number):
        releases.validate_release(release_path(self.root, number), number)
        # Do not adopt an unrelated listener or touch a live journal after a port conflict.
        with socket.socket(socket.AF_INET6 if ':' in self.cfg['bind_host'] else socket.AF_INET) as check:
            check.bind((self.cfg['bind_host'], self.cfg['port']))
        env = environment(self.root, self.cfg, self.token)
        env['GATEWAY_START_QUIESCED'] = '1'
        env['GATEWAY_STARTUP_REASON'] = getattr(self, 'startup_reason', 'supervisor_start')
        self.child = subprocess.Popen(executable(self.root, number, 'serve-engine'), env=env,
            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
            creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        windows_job.attach(self.job, self.child)
        child = self.child
        def capture():
            # Bound individual lines as well as total retained logs.
            with child.stdout:
                while True:
                    chunk = child.stdout.readline(8192)
                    if not chunk:
                        break
                    self.log.info('%s', chunk.decode('utf-8', errors='replace').rstrip())
        threading.Thread(target=capture, name='gateway-log', daemon=True).start()
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if child.poll() is not None:
                raise RuntimeError('Gateway exited during startup; inspect logs/gateway.log')
            try:
                health = self.control()
                if health.get('protocol') == 1 and health.get('version') == number and health.get('runtimeHealthy'):
                    return health
            except (requests.RequestException, ValueError):
                pass
            time.sleep(.25)
        raise TimeoutError('Gateway runtime health check exceeded 45 seconds')

    def stop(self):
        if self.child is None or self.child.poll() is not None:
            return True
        try:
            self.control('prepare')
            self.control('shutdown')
            self.child.wait(timeout=self.cfg['shutdown_seconds'] + 25)
            return True
        except requests.HTTPError as exc:
            if exc.response.status_code == 409:
                return False
            self.restore_admission()
            raise
        except (requests.RequestException, subprocess.TimeoutExpired, ValueError):
            # A lost response cannot strand a healthy runtime in maintenance mode.
            # If shutdown already exited, reconcile that outcome without replaying it.
            if self.child.poll() is not None:
                return True
            self.restore_admission()
            raise

    def restore_admission(self):
        try:
            self.control('resume')
        except (requests.RequestException, ValueError):
            self.log.warning('Could not restore admission after an interrupted maintenance request')

    def discard_failed_start(self):
        # Only invoked before resume: this process has never admitted new work.
        if self.child is not None and self.child.poll() is None:
            try:
                self.control('shutdown')
                self.child.wait(timeout=self.cfg['shutdown_seconds'] + 25)
            except (requests.RequestException, subprocess.TimeoutExpired):
                self.child.terminate()
                self.child.wait(timeout=15)

    def backup(self):
        destination = self.root / 'data' / 'backups' / ('before-update-' + uuid.uuid4().hex)
        for path in (self.root / 'data').glob('*.sqlite3'):
            with sqlite3.connect(path.resolve().as_uri() + '?mode=ro', uri=True) as db:
                backup_database(db, destination / path.name)
        # Keep the latest three complete backup sets; failed backup aborts activation.
        backups = sorted(destination.parent.glob('before-update-*'), key=lambda p: p.stat().st_mtime, reverse=True)
        for old in backups[3:]:
            shutil.rmtree(old)

    def stage(self, candidate):
        destination = release_path(self.root, candidate['version'])
        if destination.exists():
            releases.validate_release(destination, candidate['version'])
            return
        if shutil.disk_usage(self.root).free < releases.MAX_DOWNLOAD + releases.MAX_EXPANDED:
            raise OSError('At least 2 GiB of free space is required to stage an update')
        with tempfile.TemporaryDirectory(prefix='update-', dir=self.root) as temp:
            archive, unpacked = Path(temp) / 'release.zip', Path(temp) / 'runtime'
            releases.download(candidate, archive)
            releases.extract(archive, unpacked)
            releases.validate_release(unpacked, candidate['version'])
            # Validation cannot migrate or lock the live journal.
            isolated = Path(temp) / 'preflight'
            atomic_json(isolated / 'settings.json', self.cfg)
            result = subprocess.run([str(unpacked / 'Gateway.exe'), 'validate-engine', '--root', str(isolated), '--quiet'],
                env=environment(isolated, self.cfg), stdin=subprocess.DEVNULL, stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT, timeout=45, creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
            if result.returncode:
                raise ValueError('Candidate runtime preflight failed')
            destination.parent.mkdir(parents=True, exist_ok=True)
            os.replace(unpacked, destination)

    def activate(self, number):
        old = read_pointer(self.root)
        if releases.version(number) <= releases.version(old['version']):
            raise ValueError('Automatic downgrade refused')
        releases.validate_release(release_path(self.root, number), number)
        if not self.stop():
            self.publish(state='running', updateState='waiting-for-idle', availableVersion=number)
            return False
        try:
            self.backup()
            atomic_json(self.root / 'current.json', {'version': number, 'previous': old['version'], 'pending': True})
        except Exception:
            self.spawn(old['version'])
            self.control('resume')
            raise
        self.publish(state='restarting', updateState='validating', availableVersion=number)
        return True  # Stable launcher starts the NEW supervisor executable.

    def boot(self):
        pointer = read_pointer(self.root)
        if pointer.get('pending') and pointer.get('attempted'):
            # Power loss or supervisor crash during trial: return to the last good release.
            pointer = {'version': pointer['previous'], 'failedVersion': pointer['version']}
            atomic_json(self.root / 'current.json', pointer)
            return RESTART
        if pointer.get('pending'):
            atomic_json(self.root / 'current.json', dict(pointer, attempted=True))
        try:
            health = self.spawn(pointer['version'])
        except Exception:
            self.discard_failed_start()
            if pointer.get('pending') and pointer.get('previous'):
                atomic_json(self.root / 'current.json', {'version': pointer['previous'], 'failedVersion': pointer['version']})
                self.publish(state='rollback', updateState='startup-failed', failedVersion=pointer['version'])
                self.log.exception('Update startup failed; rolling back')
                return RESTART
            raise
        atomic_json(self.root / 'current.json', {'version': pointer['version'], 'previous': pointer.get('previous'), 'failedVersion': pointer.get('failedVersion')})
        self.control('resume')
        self.publish(state='running', version=pointer['version'], backend=health['checks']['backend'])
        return 0

    def open_window(self, command):
        if command == 'dashboard' and self.dashboard_child and self.dashboard_child.poll() is None:
            # Reopen an existing dashboard without spawning duplicate windows.
            atomic_json(self.root / 'show-dashboard.json', {'requestedAt': time.time()})
            return
        child = subprocess.Popen(executable(self.root, read_pointer(self.root)['version'], command),
                                 creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
        if command == 'dashboard':
            self.dashboard_child = child

    def start_desktop_observer(self):
        from desktop.dashboard_model import counters, read_snapshot
        from desktop.tray import TrayIcon
        def tooltip():
            snapshot = read_snapshot(self.root)
            clients, active, _, _ = counters(snapshot)
            return f'Gateway {self.status.get("state", "starting")} | {clients if clients is not None else "—"} clients | {active if active is not None else "—"} open jobs'
        self.tray = TrayIcon(tooltip, {
            'dashboard': lambda: self.open_window('dashboard'), 'settings': lambda: self.open_window('settings'),
            'restart': lambda: request(self.root, 'restart'), 'stop': lambda: request(self.root, 'stop')}).start()
        def observe():
            with requests.Session() as client:
                client.trust_env = False
                while not self.observer_stop.is_set():
                    try:
                        url = 'http://' + authority(control_host(self.cfg), self.cfg['port']) + '/gateway/admin/dashboard'
                        response = client.get(url, headers={'X-Gateway-Management': self.token}, timeout=(1, 3))
                        response.raise_for_status()
                        snapshot = response.json()
                        if not isinstance(snapshot, dict) or not isinstance(snapshot.get('capturedAt'), (int, float)):
                            raise ValueError('Invalid dashboard observation')
                        atomic_json(self.root / 'dashboard.json', snapshot)
                        atomic_json(self.root / 'data' / 'activity.json', {'activities': snapshot.get('activities', [])})
                    except (requests.RequestException, OSError, ValueError):
                        # UI marks older snapshots stale; metrics never block work/recovery.
                        pass
                    self.observer_stop.wait(2)
        self.observer_thread = threading.Thread(target=observe, name='gateway-dashboard-observer', daemon=True)
        self.observer_thread.start()

    def close_desktop_observer(self):
        self.observer_stop.set()
        if self.tray:
            self.tray.close()
        if self.observer_thread:
            self.observer_thread.join(timeout=5)

    def notify(self, number):
        if os.name != 'nt':
            return
        marker = self.root / 'notified.json'
        try:
            if json.loads(marker.read_text(encoding='utf-8')).get('version') == number:
                return
        except (OSError, ValueError):
            pass
        atomic_json(marker, {'version': number})
        def show():
            import ctypes
            ctypes.windll.user32.MessageBoxW(None,
                'Gateway ' + number + ' is available. Open Gateway Settings from the Start menu and choose Install update when your work is complete.',
                'Gateway update available', 0x40)
        threading.Thread(target=show, name='update-notification', daemon=True).start()

    def run(self):
        result = self.boot()
        if result:
            return result
        self.start_desktop_observer()
        next_check, next_activate, candidate = time.monotonic() + 30, 0, None
        crashes = []
        while True:
            if self.child.poll() is not None:
                crashes = [t for t in crashes if time.monotonic() - t < 600]
                if len(crashes) >= 5:
                    raise RuntimeError('Five unexpected exits within ten minutes; restart stopped')
                crashes.append(time.monotonic())
                self.publish(state='recovering')
                self.startup_reason = 'crash_recovery'
                time.sleep(min(2 ** len(crashes), 30))
                result = self.boot()
                if result:
                    return result
            request_path = self.root / 'request.json'
            action, ident = None, None
            try:
                command = json.loads(request_path.read_text(encoding='utf-8'))
                request_path.unlink()
                action, ident = command['action'], command['id']
            except FileNotFoundError:
                pass
            except (ValueError, KeyError, TypeError):
                request_path.unlink(missing_ok=True)
                self.log.warning('Ignored malformed local control request')
                action, ident = None, None
            try:
                current_cfg = load(self.root)
                if action in {'stop', 'restart'}:
                    accepted = self.stop()
                    atomic_json(self.root / 'response.json', {'id': ident, 'accepted': accepted, 'error': 'Active work must finish before restarting'})
                    if accepted:
                        self.publish(state='stopped')
                        return RESTART if action == 'restart' else 0
                elif action == 'export-debug':
                    self.export_diagnostics()
                    atomic_json(self.root / 'response.json', {'id': ident, 'accepted': True})
                    # Do not acknowledge this request again through update checks.
                    ident = None
                elif action not in {None, 'check', 'install'}:
                    raise ValueError('Unknown manager command')
                if action in {'check', 'install'} or current_cfg['update_mode'] != 'off' and time.monotonic() >= next_check:
                    candidate = releases.check(read_pointer(self.root)['version'])
                    next_check = time.monotonic() + current_cfg['update_interval_hours'] * 3600
                    self.publish(updateState='available' if candidate else 'up-to-date', availableVersion=candidate['version'] if candidate else None)
                    if candidate and current_cfg['update_mode'] == 'notify':
                        self.notify(candidate['version'])
                    if ident:
                        atomic_json(self.root / 'response.json', {'id': ident, 'accepted': True})
                automatic = current_cfg['update_mode'] == 'auto' and time.monotonic() >= next_activate
                failed = read_pointer(self.root).get('failedVersion')
                if candidate and (action == 'install' or automatic and candidate['version'] != failed):
                    self.publish(updateState='downloading')
                    self.stage(candidate)
                    next_activate = time.monotonic() + 60
                    if self.activate(candidate['version']):
                        return RESTART
            except Exception as exc:
                self.log.exception('Manager operation failed')
                self.publish(updateState='error', error=str(exc))
                next_check = time.monotonic() + 3600
                next_activate = time.monotonic() + 3600
                if ident:
                    atomic_json(self.root / 'response.json', {'id': ident, 'accepted': False, 'error': str(exc)})
            time.sleep(.5)


def supervise(root):
    lock = StateDirectoryLock(Path(root) / 'manager.sqlite3')
    try:
        lock.acquire()
    except StateDirectoryAlreadyOwned:
        return 0  # Idempotent launch; existing process continues.
    manager = None
    try:
        manager = Supervisor(root)
        return manager.run()
    except Exception as exc:
        if manager:
            manager.log.exception('Supervisor stopped')
            manager.publish(state='error', error=str(exc))
        return 1
    finally:
        if manager:
            manager.close_desktop_observer()
            windows_job.close(manager.job)
        lock.release()
