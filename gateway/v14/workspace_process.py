"""PID 1 inside the task container. No host services or credentials are available."""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socketserver
import subprocess
import sys
import threading
import time


def identifier(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-zA-Z0-9_-]{1,64}', value):
        raise ValueError('A process ID must contain 1–64 letters, digits, underscores or hyphens')
    return value


class Processes:
    def __init__(self, workspace, control, deadline, *, user=None):
        self.workspace = workspace.resolve()
        self.control = control
        self.deadline = deadline
        self.user = user
        self.jobs = {}
        self.lock = threading.RLock()

    def dispatch(self, action):
        kind = action['action']
        name = identifier(action.get('process_id'))
        with self.lock:
            row = self.jobs.get(name)
            if kind == 'terminal_start':
                argv = action.get('argv')
                cwd = action.get('cwd', '.')
                timeout = action.get('timeout_seconds', 120)
                env = action.get('env', {})
                if (not isinstance(argv, list) or not 1 <= len(argv) <= 64 or
                        any(not isinstance(v, str) or '\x00' in v or len(v) > 16000 for v in argv) or
                        not isinstance(cwd, str) or len(cwd) > 400 or Path(cwd).is_absolute() or
                        not isinstance(env, dict) or len(env) > 24 or
                        any(not re.fullmatch(r'[A-Z_][A-Z0-9_]{0,63}', k) or not isinstance(v, str) or len(v) > 2048 or '\x00' in v for k, v in env.items()) or
                        type(timeout) is not int or not 1 <= timeout <= 1800):
                    raise ValueError('Invalid workspace command, directory, environment or timeout')
                directory = (self.workspace / cwd).resolve(strict=True)
                if not directory.is_relative_to(self.workspace) or not directory.is_dir():
                    raise ValueError('Working directory must be in the task checkout')
                identity = hashlib.sha256(json.dumps([argv, cwd, env, timeout], sort_keys=True).encode()).hexdigest()
                if row is not None:
                    if row['identity'] != identity:
                        raise ValueError('This process ID already belongs to a different command')
                else:
                    if time.time() >= self.deadline or len(self.jobs) >= 64 or sum(r['exit_code'] is None for r in self.jobs.values()) >= 4:
                        raise ValueError('Task process allowance exhausted')
                    log = self.control / ('process-' + name + '.log')
                    environment = {k: v for k, v in os.environ.items() if k in {
                        'PATH', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'GRADLE_RO_DEP_CACHE', 'COREPACK_HOME', 'VULKAN_SDK', 'CMAKE_PREFIX_PATH', 'LANG'}}
                    environment['HOME'] = str(self.workspace.parent / 'home')
                    environment.update(env)
                    if 'GITHUB_SHA' in os.environ:
                        environment['GITHUB_SHA'] = os.environ['GITHUB_SHA']
                    privilege = {'user': self.user, 'group': self.user, 'extra_groups': []} if self.user is not None else {}
                    proc = subprocess.Popen(argv, cwd=directory, env=environment, start_new_session=True,
                                            stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, **privilege)
                    row = self.jobs[name] = dict(identity=identity, proc=proc, log=log, exit_code=None,
                                                 started_at=time.time(), state='RUNNING', output_bytes=0)
                    threading.Thread(target=self.observe, args=(row, min(self.deadline, time.time() + timeout)), daemon=True).start()
            if row is None:
                raise ValueError('Unknown process ID in this workspace')
            if kind == 'terminal_cancel' and row['exit_code'] is None:
                self.stop(row, 'CANCELLED')
            if kind not in {'terminal_start', 'terminal_poll', 'terminal_cancel'}:
                raise ValueError('Unknown process action')
            offset = action.get('offset', 0)
            if type(offset) is not int or not 0 <= offset <= row['output_bytes']:
                raise ValueError('Invalid process output byte offset')
            raw = b''
            if row['output_bytes'] > offset:
                with row['log'].open('rb') as stream:
                    stream.seek(offset)
                    raw = stream.read(min(2048, row['output_bytes'] - offset))
            return dict(process_id=name, pid=row['proc'].pid, state=row['state'], exit_code=row['exit_code'],
                        output=raw.decode('utf-8', 'replace'), offset=offset, next_offset=offset + len(raw),
                        output_bytes=row['output_bytes'], started_at=row['started_at'], scope='exploratory')

    def stop(self, row, state):
        row['state'] = state
        try:
            os.killpg(row['proc'].pid, signal.SIGKILL)
        except ProcessLookupError:
            pass

    def observe(self, row, deadline):
        import selectors
        proc = row['proc']
        with row['log'].open('wb', buffering=0) as log, selectors.DefaultSelector() as selector, proc.stdout as stream:
            selector.register(stream, selectors.EVENT_READ)
            while selector.get_map() or proc.poll() is None:
                with self.lock:
                    if time.time() >= deadline:
                        self.stop(row, 'TIMED_OUT')
                        break
                    if row['state'] != 'RUNNING':
                        break
                for key, _ in selector.select(.1):
                    block = os.read(key.fd, 8192)
                    if not block:
                        selector.unregister(key.fd)
                        continue
                    with self.lock:
                        if row['output_bytes'] + len(block) > 4 * 1024 * 1024:
                            self.stop(row, 'OUTPUT_LIMIT')
                            break
                        log.write(block)
                        row['output_bytes'] += len(block)
            code = proc.wait()
            with self.lock:
                row['exit_code'] = code
                if row['state'] == 'RUNNING':
                    row['state'] = 'EXITED'


def serve():
    control = Path('/control')
    # The control socket and logs are inaccessible to workspace processes.
    os.umask(0o077)
    deadline = float(sys.argv[1])
    processes = Processes(Path('/task/checkout'), control, deadline, user=65534)

    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            try:
                payload = json.loads(self.rfile.readline(65537))
                result = processes.dispatch(payload)
            except (ValueError, OSError, TypeError, KeyError) as error:
                result = {'error': str(error)[:500]}
            self.wfile.write(json.dumps(result).encode() + b'\n')

    with socketserver.UnixStreamServer(str(control / 'terminal.sock'), Handler) as server:
        server.timeout = .5
        while time.time() < deadline:
            server.handle_request()


if __name__ == '__main__':
    serve()
