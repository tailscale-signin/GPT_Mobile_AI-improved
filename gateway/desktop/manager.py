"""Per-user supervisor and transactional same-schema updates; never terminates active work."""
import json
import logging
from logging.handlers import RotatingFileHandler
import os
import re
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


class ManagerRequestError(RuntimeError):
    """A matching supervisor reply, optionally carrying a public control error."""
    def __init__(self, response):
        super().__init__(str(response.get('error') or 'Request refused')[:512])
        code = response.get('error_code')
        status = response.get('http_status')
        self.code = code if isinstance(code, str) and re.fullmatch(r'[a-z][a-z0-9_]{0,95}', code) else None
        self.status = status if type(status) is int and 400 <= status <= 599 else None


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


def request(root, action, timeout=50, *, payload=None, settings=None):
    """Local UI/installer control queue. A busy stop is refused, never forced."""
    root = Path(root)
    ident = uuid.uuid4().hex
    command = {'id': ident, 'action': action}
    if payload is not None:
        if not isinstance(payload, dict) or len(json.dumps(payload)) > 32768:
            raise ValueError('Manager command payload is invalid or oversized')
        command['payload'] = payload
    if settings is not None:
        if not isinstance(settings, dict) or len(json.dumps(settings)) > 32768:
            raise ValueError('Manager settings payload is invalid or oversized')
        command['settings'] = settings
    atomic_json(root / 'request.json', command, retry_windows_sharing=True)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            result = json.loads((root / 'response.json').read_text(encoding='utf-8'))
            if result.get('id') == ident:
                if not result.get('accepted'):
                    raise ManagerRequestError(result)
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
            try:
                if action != 'stop':
                    raise RuntimeError('Start Gateway before requesting this action')
                # The launcher may have published the matching response between
                # our first read and acquiring its lock.
                try:
                    final = json.loads((root / 'response.json').read_text(encoding='utf-8'))
                except (OSError, ValueError):
                    final = {}
                if final.get('id') == ident:
                    if not final.get('accepted'):
                        raise ManagerRequestError(final)
                    return final
                try:
                    pending = root / 'request.json'
                    if json.loads(pending.read_text(encoding='utf-8')).get('id') == ident:
                        pending.unlink()
                except FileNotFoundError:
                    pass
                return {'accepted': True, 'supervisorAbsent': True}
            finally:
                lock.release()
        time.sleep(.2)
    try:
        if json.loads((root / 'request.json').read_text(encoding='utf-8')).get('id') == ident:
            (root / 'request.json').unlink()
    except (OSError, ValueError):
        pass
    raise TimeoutError('Gateway did not acknowledge the request; inspect logs')


class Supervisor:
    def __init__(self, root, *, headless=False):
        self.headless = headless
        self.root = Path(root).resolve()
        self.cfg = load(self.root)
        self.token = secrets.token_urlsafe(48)
        self.http = requests.Session()
        self.http.trust_env = False
        self.child = None
        self._request_claimed = self._request_busy = False
        self._request_payload = {}
        self._control_settings = None
        self.owner_controls = None
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
        status = dict(self.status)
        status.update(values, updatedAt=time.time())
        atomic_json(self.root / 'status.json', status, retry_windows_sharing=True)
        self.status = status

    def respond(self, payload):
        # Windows readers can briefly deny a same-directory atomic replacement.
        # Retry the same fsynced response, preserving the exact request identity.
        atomic_json(self.root / 'response.json', payload, retry_windows_sharing=True)

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
                               timeout=(2, 60), stream=True, allow_redirects=False) as response:
                response.raise_for_status()
                if response.headers.get('content-type', '').split(';')[0] != 'application/zip':
                    raise ValueError('Gateway does not support the full diagnostic bundle. Update the complete gateway package.')
                size, deadline = 0, time.monotonic() + 60
                with temporary.open('wb') as out:
                    for chunk in response.iter_content(65536):
                        size += len(chunk)
                        # Sixteen retained captures can each contain 5000 bounded events.
                        if size > 192 * 1024 * 1024 or time.monotonic() > deadline:
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
        self.publish(state='running', version=pointer['version'], backend=health['checks']['backend'], build=health.get('build', {}))
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
        if not self.headless:
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
                    self.observer_stop.wait(5)
        self.observer_thread = threading.Thread(target=observe, name='gateway-dashboard-observer', daemon=True)
        self.observer_thread.start()

    def close_desktop_observer(self):
        self.observer_stop.set()
        if self.tray:
            self.tray.close()
        if self.observer_thread:
            self.observer_thread.join(timeout=5)

    def notify(self, number):
        if self.headless or os.name != 'nt':
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

    def take_control_request(self):
        # Claim before reading so a newer command cannot be unlinked with the
        # previous one. Only this live supervisor may act on its claimed file.
        claimed = self.root / 'request.claimed.json'
        try:
            if not self._request_claimed:
                (self.root / 'request.json').replace(claimed)
                self._request_claimed = True
            try:
                command = json.loads(claimed.read_text(encoding='utf-8'))
                action, ident = command['action'], command['id']
                payload = command.get('payload', {})
                if not isinstance(payload, dict) or len(json.dumps(payload)) > 32768:
                    raise ValueError('Invalid control payload')
                self._request_payload = payload
                self._control_settings = command.get('settings')
            except (ValueError, KeyError, TypeError):
                self.log.warning('Ignored malformed local control request')
                action, ident = None, None
                self._request_payload = {}
                self._control_settings = None
            claimed.unlink()
            self._request_claimed = False
            self._request_busy = False
            return action, ident
        except FileNotFoundError:
            self._request_claimed = False
            self._request_busy = False
        except PermissionError as error:
            if sys.platform != 'win32':
                raise
            if not self._request_busy:
                self.log.warning('Windows temporarily denied access to the control request; retrying on the next poll')
            self._request_busy = True
        return None, None

    def run(self):
        result = self.boot()
        if result:
            return result
        from desktop.control_settings import Controller
        from desktop.control_store import ControlError
        self.owner_controls = Controller(self)
        try:
            self.owner_controls.start_saved()
        except (OSError, ValueError, RuntimeError, ControlError):
            # Ordinary chat survives an unavailable optional management port.
            self.log.warning('Phone controls need attention on this PC')
        self.start_desktop_observer()
        next_check, next_activate, candidate = time.monotonic() + 30, 0, None
        next_settings = 0
        crashes, retry_at = [], None
        while True:
            if self.child is None or self.child.poll() is not None:
                now = time.monotonic()
                if retry_at is not None and now >= retry_at:
                    try:
                        result = self.boot()
                    except (OSError, RuntimeError, requests.RequestException) as error:
                        # Restart failures only return to the watchdog when the
                        # process is gone. Never duplicate a live engine.
                        if self.child is not None and self.child.poll() is None:
                            raise
                        self.log.warning('Gateway recovery boot failed: %s', type(error).__name__)
                        retry_at = None
                    else:
                        if result:
                            return result
                        retry_at = None
                        self.publish(recoveryAttempt=None, recoveryRetryAt=None)
                if (self.child is None or self.child.poll() is not None) and retry_at is None:
                    crashes = [t for t in crashes if now - t < 600]
                    crashes.append(now)
                    delay = min(300, 2 ** min(9, len(crashes)))
                    retry_at = now + delay
                    self.publish(state='recovering', recoveryAttempt=len(crashes), recoveryRetryAt=time.time() + delay)
                    self.startup_reason = 'crash_recovery'
            action, ident = self.take_control_request()
            try:
                if action and action.startswith('control-'):
                    result = self.owner_controls.local(action, self._request_payload)
                    self.respond({'id': ident, 'accepted': True, 'result': result})
                    action, ident = None, None
                current_cfg = load(self.root)
                if action in {'stop', 'restart'}:
                    accepted = self.stop()
                    self.respond({'id': ident, 'accepted': accepted, 'error': 'Active work must finish before restarting'})
                    if accepted:
                        self.publish(state='stopped')
                        return RESTART if action == 'restart' else 0
                elif action == 'read-retained-work':
                    job_id = self._request_payload.get('job_id')
                    if set(self._request_payload) != {'job_id'} or not isinstance(job_id, str) or not re.fullmatch(r'(?:eng_|cp_)[a-f0-9]{32}', job_id):
                        raise ValueError('Choose an original retained engineering or Copilot job.')
                    url = 'http://' + authority(control_host(self.cfg), self.cfg['port']) + '/gateway/admin/retained-work/' + job_id
                    response = self.http.get(url, headers={'X-Gateway-Management': self.token}, timeout=(2, 8), allow_redirects=False)
                    response.raise_for_status()
                    self.respond({'id': ident, 'accepted': True, 'result': response.json()})
                    ident = None
                elif action in {'read-admission', 'change-admission'}:
                    # Keep the compatibility account panel, but the local owner
                    # uses the same revision and command ledger as native Settings.
                    record = self._control_settings
                    if action == 'change-admission' and isinstance(record, dict) and record.get('principal_id') == 'local:single-user':
                        if set(record) != {'gateway_id', 'principal_id', 'revision', 'enabled'}:
                            raise ValueError('Read this installation before editing.')
                        result = self.owner_controls.local('control-settings-patch', {
                            'installation_id': record['gateway_id'], 'expected_revision': record['revision'],
                            'command_id': ident, 'changes': {'engineering_enabled': record['enabled']}})
                        if result['status'] == 'rejected':
                            raise ValueError(result['error']['message'])
                        snapshot = result['snapshot']
                        value = {'gateway_id': snapshot['installation_id'], 'principal_id': snapshot['principal_id'],
                                 'revision': snapshot['revision'], 'enabled': snapshot['desired']['engineering_enabled']}
                    else:
                        url = 'http://' + authority(control_host(self.cfg), self.cfg['port']) + '/gateway/admin/engineering-admission'
                        response = self.http.request('POST' if action == 'change-admission' else 'GET', url,
                            headers={'X-Gateway-Management': self.token}, json=record, timeout=(2, 8), allow_redirects=False)
                        if response.status_code == 409:
                            raise ValueError('The setting changed. Read the current value before editing again.')
                        response.raise_for_status()
                        value = response.json()
                        if action == 'read-admission':
                            snapshot = self.owner_controls.local('control-settings-read', {})
                            for account in value['accounts']:
                                if account['principal_id'] == 'local:single-user':
                                    account.update(revision=snapshot['revision'], enabled=snapshot['desired']['engineering_enabled'])
                    self.respond({'id': ident, 'accepted': True, 'settings': value})
                    ident = None
                elif action == 'export-debug':
                    self.export_diagnostics()
                    self.respond({'id': ident, 'accepted': True})
                    # Do not acknowledge this request again through update checks.
                    ident = None
                elif action not in {None, 'check', 'install'}:
                    raise ValueError('Unknown manager command')
                if self.child is not None and self.child.poll() is None and time.monotonic() >= next_settings:
                    # Optional phone controls must never consume a stop/restart
                    # request or prevent watchdog recovery if their journal or
                    # listener is temporarily unavailable.
                    next_settings = time.monotonic() + 3
                    try:
                        self.owner_controls.reconcile()
                        self.publish(phoneControls=self.owner_controls.description())
                        if self.owner_controls.restart_requested and self.stop():
                            self.publish(state='stopped')
                            return RESTART
                    except (OSError, ValueError, RuntimeError, ControlError, requests.RequestException):
                        self.log.warning('Owner settings could not be reconciled; retrying while Gateway continues')
                if action == 'install' and retry_at is not None:
                    raise RuntimeError('Install is available when Gateway has recovered')
                if action in {'check', 'install'} or current_cfg['update_mode'] != 'off' and time.monotonic() >= next_check:
                    candidate = releases.check(read_pointer(self.root)['version'])
                    next_check = time.monotonic() + current_cfg['update_interval_hours'] * 3600
                    self.publish(updateState='available' if candidate else 'up-to-date', availableVersion=candidate['version'] if candidate else None)
                    if candidate and current_cfg['update_mode'] == 'notify':
                        self.notify(candidate['version'])
                    if ident:
                        self.respond({'id': ident, 'accepted': True})
                automatic = current_cfg['update_mode'] == 'auto' and time.monotonic() >= next_activate
                failed = read_pointer(self.root).get('failedVersion')
                if retry_at is None and candidate and (action == 'install' or automatic and candidate['version'] != failed):
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
                    rejection = {'id': ident, 'accepted': False, 'error': str(exc)}
                    if isinstance(exc, ControlError):
                        code = exc.code if isinstance(exc.code, str) and re.fullmatch(r'[a-z][a-z0-9_]{0,95}', exc.code) else 'control_operation_failed'
                        status = exc.status if type(exc.status) is int and 400 <= exc.status <= 599 else 500
                        # Only a typed, bounded public code crosses this local
                        # recovery boundary; arbitrary exception text is not proof.
                        rejection.update(error=code, error_code=code, http_status=status)
                    self.respond(rejection)
            time.sleep(.5)


def supervise(root, *, headless=False):
    lock = StateDirectoryLock(Path(root) / 'manager.sqlite3')
    try:
        lock.acquire()
    except StateDirectoryAlreadyOwned:
        return 0  # Idempotent launch; existing process continues.
    manager = None
    try:
        manager = Supervisor(root, headless=headless)
        return manager.run()
    except Exception as exc:
        if manager:
            manager.log.exception('Supervisor stopped')
            manager.publish(state='error', error=str(exc))
        return 1
    finally:
        if manager:
            if manager.owner_controls:
                manager.owner_controls.close()
            manager.close_desktop_observer()
            windows_job.close(manager.job)
        lock.release()
