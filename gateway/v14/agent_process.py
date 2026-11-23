"""Private PID 1 control service inside a disposable coding-agent container.

This file is copied into a host-approved image and runs independently of the
Gateway connection. The child owns only /task/checkout and /task/home; neither
the control socket nor its dispatch/event ledger is writable by the agent.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import socketserver
import stat
import subprocess
import sys
import threading
import time

MAX_EVENT = 65536
MAX_EVENTS = 4096


def canonical(value):
    return json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)


def checksum(value):
    return hashlib.sha256(canonical(value).encode()).hexdigest()


def blob(raw):
    return hashlib.sha1(b'blob ' + str(len(raw)).encode() + b'\0' + raw).hexdigest()


class AgentProcess:
    def __init__(self, workspace, control, deadline, *, user=None):
        self.workspace, self.control = Path(workspace).resolve(), Path(control)
        self.deadline, self.user = deadline, user
        self.lock, self.write_lock = threading.RLock(), threading.Lock()
        self.condition = threading.Condition(self.lock)
        self.process, self.backend = None, None
        self.pending, self.results, self.operations = {}, {}, {}
        self.events, self.sequence, self.event_bytes = [], 0, 0
        self.session_id, self.turn_id, self.prompt_id = None, None, None
        self.state, self.error_code = 'PREPARED', None
        self.capabilities, self.baseline = {}, {}
        self.model_id, self.model_option = None, None
        self.completed_turns = {}
        self.stopped_state, self.catalog = None, None

    def source_catalog(self):
        """Root-owned index keeps generated/ignored files out of candidates.

        The untrusted agent's .git/config, index, hooks and ignore-exclude file
        never influence a root-side command. Baseline paths are still checked
        independently even if an agent removes them from its own Git index.
        """
        environment = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'HOME': str(self.control),
                       'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_COUNT': '0'}
        self.catalog = self.control / 'catalog'
        subprocess.run(['git', 'init', '--bare', '--template=', '-q', str(self.catalog)], env=environment,
                       check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
        entries = []
        for relative, entry in self.baseline.items():
            if relative.startswith('/') or '\\' in relative or any(v in {'', '.', '..', '.git'} for v in relative.split('/')):
                raise ValueError('Unsafe source manifest path')
            path = self.workspace / relative
            if not path.is_file() or path.is_symlink():
                raise ValueError('Exported checkout differs from the pinned source manifest')
            if ('100755' if path.stat().st_mode & 0o111 else '100644') != entry['mode']:
                raise ValueError('Exported source mode differs from the accepted commit')
            size = path.stat().st_size
            checksum = hashlib.sha1(b'blob ' + str(size).encode() + b'\0')
            with path.open('rb') as stream:
                for part in iter(lambda: stream.read(65536), b''):
                    checksum.update(part)
            if checksum.hexdigest() != entry['sha']:
                raise ValueError('Exported source blob differs from the accepted commit')
            entries.append((entry['mode'] + ' ' + entry['sha'] + '\t' + relative + '\0').encode())
        subprocess.run(['git', '--git-dir=' + str(self.catalog), 'update-index', '-z', '--index-info'],
                       input=b''.join(entries), env=environment, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)

    def candidate_paths(self):
        if self.catalog is None:
            return [path.relative_to(self.workspace).as_posix() for path in self.workspace.rglob('*') if '.git' not in path.relative_to(self.workspace).parts]
        environment = {'PATH': os.environ.get('PATH', '/usr/bin:/bin'), 'HOME': str(self.control),
                       'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': os.devnull, 'GIT_CONFIG_COUNT': '0'}
        observed = subprocess.run(['git', '--git-dir=' + str(self.catalog), '--work-tree=' + str(self.workspace),
                                  '-c', 'core.fsmonitor=false', '-c', 'core.hooksPath=/dev/null',
                                  'ls-files', '--others', '--exclude-standard', '-z'], env=environment,
                                 capture_output=True, timeout=10, check=True)
        if len(observed.stdout) > 4 * 1024 * 1024:
            raise ValueError('Candidate path inventory exceeded its bound')
        extra = observed.stdout.decode('utf-8').rstrip('\0').split('\0') if observed.stdout else []
        if len(extra) > 30000:
            raise ValueError('Candidate contains too many untracked paths')
        return sorted(set(self.baseline) | set(extra))

    def emit(self, kind, value):
        raw = canonical(value).encode()
        if len(raw) > MAX_EVENT:
            value = {'sha256': hashlib.sha256(raw).hexdigest(), 'byte_length': len(raw), 'omitted': True}
        with self.lock:
            if len(self.events) >= MAX_EVENTS or self.event_bytes + len(raw) > 16 * 1024 * 1024:
                self.error_code = 'EVENT_RETENTION_LIMIT'
                self.stop('BLOCKED')
                return
            self.sequence += 1
            self.events.append({'sequence': self.sequence, 'kind': kind, 'payload': value})
            self.event_bytes += min(len(raw), MAX_EVENT)

    def send(self, message):
        raw = (canonical(message) + '\n').encode()
        if len(raw) > 256 * 1024:
            raise ValueError('Agent request exceeds the bounded frame')
        with self.write_lock:
            if self.process is None or self.process.poll() is not None:
                raise ValueError('Agent process is unavailable')
            self.process.stdin.write(raw)
            self.process.stdin.flush()

    def request(self, method, params, *, wait=True):
        with self.lock:
            number = str(len(self.pending) + 1)
            self.pending[number] = method
        self.send({'jsonrpc': '2.0', 'id': number, 'method': method, 'params': params})
        if not wait:
            return number
        deadline = min(self.deadline, time.time() + 20)
        with self.condition:
            while number not in self.results:
                if time.time() >= deadline or self.process.poll() is not None:
                    raise TimeoutError('Agent handshake or control response is not confirmed')
                self.condition.wait(min(.2, max(0, deadline - time.time())))
            result = self.results[number]
        if 'error' in result:
            raise ValueError('Agent rejected the protocol operation')
        return result.get('result', {})

    def read(self):
        try:
            while True:
                raw = self.process.stdout.readline(256 * 1024 + 1)
                if not raw:
                    break
                if len(raw) > 256 * 1024 or not raw.endswith(b'\n'):
                    raise ValueError('Agent protocol frame exceeded its bound')
                value = json.loads(raw)
                if not isinstance(value, dict):
                    raise ValueError('Agent emitted a non-object protocol frame')
                if 'id' in value and ('result' in value or 'error' in value):
                    with self.condition:
                        self.results[str(value['id'])] = value
                        if str(value['id']) == self.prompt_id and self.stopped_state is None:
                            self.state = 'FAILED' if 'error' in value else (
                                'CANCELLED' if value.get('result', {}).get('stopReason') == 'cancelled' else 'COMPLETED')
                        self.condition.notify_all()
                    self.emit('response', value)
                elif 'id' in value and value.get('method'):
                    # No client filesystem, terminal, or arbitrary host tool is
                    # exposed. Ordinary permission prompts remain denied; the
                    # runtime's own sandboxed tools use its configured policy.
                    method = value['method']
                    if method == 'session/request_permission':
                        result = {'outcome': {'outcome': 'cancelled'}}
                    elif method.endswith('/requestApproval'):
                        result = {'decision': 'decline'}
                    else:
                        self.send({'jsonrpc': '2.0', 'id': value['id'], 'error': {'code': -32601, 'message': 'Client capability unavailable'}})
                        self.emit('input_required', {'method': method})
                        continue
                    self.send({'jsonrpc': '2.0', 'id': value['id'], 'result': result})
                    self.emit('input_required', {'method': method})
                else:
                    method, params = value.get('method'), value.get('params', {})
                    if method == 'turn/completed' and params.get('threadId') == self.session_id:
                        turn = params.get('turn', {})
                        self.completed_turns[turn.get('id')] = turn.get('status')
                        if turn.get('id') == self.turn_id and self.stopped_state is None:
                            self.state = {'completed': 'COMPLETED', 'interrupted': 'CANCELLED', 'failed': 'FAILED'}.get(turn.get('status'), 'STATE_UNVERIFIED')
                    if method == 'session/update' and params.get('sessionId') == self.session_id and self.model_option:
                        update = params.get('update', {})
                        if update.get('sessionUpdate') == 'config_option_update':
                            selected = next((v for v in update.get('configOptions', []) if v.get('id') == self.model_option), {})
                            if selected.get('currentValue') != self.model_id:
                                self.error_code = 'AGENT_MODEL_CHANGED'
                                self.stop('BLOCKED')
                    self.emit('notification', value)
        except (ValueError, OSError, UnicodeError):
            self.error_code = 'AGENT_PROTOCOL_INVALID'
            self.stop('FAILED')
        finally:
            with self.condition:
                if self.state in {'PREPARED', 'RUNNING'}:
                    self.state = 'DISCONNECTED'
                self.condition.notify_all()

    def stderr(self):
        # SDK diagnostics are untrusted and may echo credential-bearing errors.
        # Drain without retaining or exposing them; typed protocol failures are
        # the public diagnostic boundary.
        while self.process.stderr.read(8192):
            pass

    def snapshot(self):
        return {'state': self.state, 'session_id': self.session_id, 'remote_run_id': self.turn_id,
                'last_sequence': self.sequence, 'error_code': self.error_code, 'capabilities': self.capabilities}

    def launch(self, config):
        with self.lock:
            if self.stopped_state is not None:
                raise ValueError('A stopped runtime cannot start another turn')
            if self.process is not None:
                return self.snapshot()
        self.backend = config['backend']
        self.model_id = config['model_id']
        self.baseline = config['baseline']
        self.source_catalog()
        environment = {k: v for k, v in os.environ.items() if k in {'PATH', 'JAVA_HOME', 'ANDROID_HOME', 'LANG'}}
        environment.update(config.get('environment', {}))
        environment['HOME'] = str(self.workspace.parent / 'home')
        argv = config['command']
        privileges = {'user': self.user, 'group': self.user, 'extra_groups': []} if self.user is not None else {}
        # Preparation can outlive a cancellation. Publish the child under the
        # same gate as stop: cancellation either prevents launch or owns the
        # published process it must terminate. Protocol waits stay outside it.
        with self.lock:
            if self.stopped_state is not None:
                raise ValueError('A stopped runtime cannot start another turn')
            if self.process is not None:
                return self.snapshot()
            self.process = subprocess.Popen(argv, cwd=self.workspace, env=environment, stdin=subprocess.PIPE,
                                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True, **privileges)
        threading.Thread(target=self.read, daemon=True).start()
        threading.Thread(target=self.stderr, daemon=True).start()
        if self.backend == 'codex':
            self.request('initialize', {'clientInfo': {'name': 'gpt_mobile_gateway', 'version': '1.0.0'}})
            self.send({'jsonrpc': '2.0', 'method': 'initialized', 'params': {}})
            response = self.request('thread/start', {'model': config['model_id'], 'cwd': str(self.workspace),
                                                    'approvalPolicy': 'never', 'sandbox': 'workspace-write'})
            if response.get('model') != self.model_id:
                raise ValueError('Codex did not confirm the exact selected model')
            self.session_id = response['thread']['id']
            self.capabilities = {'steer': True, 'cancel': True, 'resume': True}
        else:
            response = self.request('initialize', {'protocolVersion': 1,
                'clientInfo': {'name': 'gpt-mobile-gateway', 'version': '1.0.0'},
                'clientCapabilities': {'fs': {'readTextFile': False, 'writeTextFile': False}, 'terminal': False}})
            if response.get('protocolVersion') != 1:
                raise ValueError('Unsupported ACP protocol version')
            self.capabilities = response.get('agentCapabilities', {})
            params = {'cwd': str(self.workspace), 'mcpServers': []}
            if self.backend not in {'kiro', 'devin'}:
                params['_meta'] = {'gateway': {key: config[key] for key in ('model_id', 'max_iterations', 'max_budget_usd', 'provider_id') if key in config}}
            response = self.request('session/new', params)
            self.session_id = response['sessionId']
            if self.backend in {'kiro', 'devin'}:
                # Stable ACP v1 negotiates models through advertised config
                # options. Never silently accept an agent's default model.
                options = response.get('configOptions', [])
                selection = next((v for v in options if v.get('type') == 'select' and
                                  (v.get('category') == 'model' or v.get('id') == 'model')), None)
                if not selection:
                    raise ValueError('ACP runtime cannot verify the selected model')
                choices = [leaf for row in selection.get('options', []) for leaf in row.get('options', [row])]
                if self.model_id not in [v.get('value') for v in choices]:
                    raise ValueError('Selected ACP model is unavailable')
                self.model_option = selection['id']
                if selection.get('currentValue') != self.model_id:
                    confirmed = self.request('session/set_config_option', {'sessionId': self.session_id,
                                             'configId': self.model_option, 'value': self.model_id})
                    if not any(v.get('id') == self.model_option and v.get('currentValue') == self.model_id
                               for v in confirmed.get('configOptions', [])):
                        raise ValueError('ACP model selection was not confirmed')
        if not isinstance(self.session_id, str) or not 1 <= len(self.session_id) <= 256:
            raise ValueError('Invalid agent session identity')
        return self.snapshot()

    def prompt(self, text, *, steering=False, expected=None):
        if self.stopped_state is not None:
            raise ValueError('A stopped runtime cannot start another turn')
        if self.backend == 'codex':
            if steering:
                if expected != self.turn_id or self.state != 'RUNNING':
                    raise ValueError('The expected active turn changed')
                self.request('turn/steer', {'threadId': self.session_id, 'expectedTurnId': expected,
                                           'input': [{'type': 'text', 'text': text}]})
            else:
                result = self.request('turn/start', {'threadId': self.session_id, 'input': [{'type': 'text', 'text': text}]})
                self.turn_id = result['turn']['id']
        else:
            if self.state == 'RUNNING':
                raise ValueError('This runtime accepts a follow-up only after the active turn ends')
            self.prompt_id = self.request('session/prompt', {'sessionId': self.session_id,
                                           'prompt': [{'type': 'text', 'text': text}]}, wait=False)
            self.turn_id = self.prompt_id
        if self.stopped_state is not None:
            return self.snapshot()
        self.state = 'RUNNING'
        if self.backend == 'codex' and self.turn_id in self.completed_turns:
            self.state = {'completed': 'COMPLETED', 'interrupted': 'CANCELLED', 'failed': 'FAILED'}.get(self.completed_turns[self.turn_id], 'STATE_UNVERIFIED')
        # A synchronous fake or very fast SDK may finish before the reader sees
        # prompt_id. Reconcile its already-retained response before returning.
        if self.prompt_id and self.prompt_id in self.results:
            response = self.results[self.prompt_id]
            self.state = 'FAILED' if 'error' in response else ('CANCELLED' if response.get('result', {}).get('stopReason') == 'cancelled' else 'COMPLETED')
        return self.snapshot()

    def stop(self, state):
        with self.lock:
            self.stopped_state = self.stopped_state or state
            self.state = self.stopped_state
            process = self.process
        if process is not None and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGKILL)
            except ProcessLookupError:
                pass
        if self.user is not None:
            # A terminal tool can create a new POSIX session. Kill every process
            # owned by the isolated agent UID, including detached descendants.
            # Tests use user=None, so this never scans/kills host-user processes.
            for entry in Path('/proc').iterdir():
                if not entry.name.isdigit():
                    continue
                try:
                    status = (entry / 'status').read_text()
                    uid = re.search(r'^Uid:\s+(\d+)', status, re.M)
                    if uid and int(uid[1]) == self.user:
                        os.kill(int(entry.name), signal.SIGKILL)
                except (OSError, ProcessLookupError):
                    continue

    def changes(self):
        if self.state not in {'COMPLETED', 'CANCELLED', 'FAILED'}:
            raise ValueError('Wait for an ended agent turn before snapshotting its candidate')
        current, changes, total = {}, [], 0
        for relative in self.candidate_paths():
            path = self.workspace / relative
            if '.git' in Path(relative).parts:
                continue
            if any((self.workspace / Path(*Path(relative).parts[:n])).is_symlink() for n in range(1, len(Path(relative).parts) + 1)):
                raise ValueError('Candidate contains a filesystem link')
            try:
                info = path.lstat()
            except FileNotFoundError:
                continue
            if stat.S_ISDIR(info.st_mode) and relative not in self.baseline:
                continue
            if not stat.S_ISREG(info.st_mode):
                raise ValueError('Candidate contains an unsupported nonregular source type')
            with self.open_source(relative) as stream:
                info = os.fstat(stream.fileno())
                mode = '100755' if info.st_mode & 0o111 else '100644'
                if mode != self.baseline.get(relative, {}).get('mode', '100644'):
                    raise ValueError('Candidate contains an unsupported file mode change')
                size = info.st_size
                if len(current) >= 30000 or size > 128 * 1024 * 1024:
                    raise ValueError('Candidate source exceeds its bounded snapshot')
                checksum = hashlib.sha1(b'blob ' + str(size).encode() + b'\0')
                for part in iter(lambda: stream.read(65536), b''):
                    checksum.update(part)
                current[relative] = checksum.hexdigest()
                if self.baseline.get(relative, {}).get('sha') == current[relative]:
                    continue
                if size > 512 * 1024:
                    raise ValueError('Changed source exceeds its bounded snapshot')
                stream.seek(0)
                raw = stream.read(512 * 1024 + 1)
            if blob(raw) != current[relative]:
                raise ValueError('Candidate changed during its snapshot')
            if b'\0' in raw:
                raise ValueError('Only text candidates can be imported')
            total += len(raw)
            if len(changes) >= 20 or total > 512 * 1024:
                raise ValueError('Candidate exceeds the coordinated change budget')
            changes.append({'path': relative, 'expected_blob_sha': self.baseline.get(relative, {}).get('sha'),
                            'content': raw.decode('utf-8'), 'mode': self.baseline.get(relative, {}).get('mode', '100644')})
        for path, original in self.baseline.items():
            if path not in current:
                changes.append({'path': path, 'expected_blob_sha': original['sha'], 'content': None, 'mode': original['mode']})
        if len(changes) > 20:
            raise ValueError('Candidate exceeds the coordinated change budget')
        return {'changes': changes, 'complete': True, 'state': self.state,
                'session_id': self.session_id, 'remote_run_id': self.turn_id}

    def open_source(self, relative):
        """Never let a child race a parent directory into a root-side read."""
        parts = relative.split('/')
        if any(part in {'', '.', '..'} or part.casefold() == '.git' for part in parts):
            raise ValueError('Unsafe candidate source path')
        directory = os.open(self.workspace, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            for part in parts[:-1]:
                following = os.open(part, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW, dir_fd=directory)
                os.close(directory)
                directory = following
            descriptor = os.open(parts[-1], os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK, dir_fd=directory)
        finally:
            os.close(directory)
        if not stat.S_ISREG(os.fstat(descriptor).st_mode):
            os.close(descriptor)
            raise ValueError('Candidate source must remain a regular file')
        return os.fdopen(descriptor, 'rb')

    def dispatch(self, action):
        kind = action.get('action')
        if kind == 'events':
            after = action.get('after', 0)
            if type(after) is not int or not 0 <= after <= self.sequence:
                raise ValueError('Invalid event cursor')
            rows = [v for v in self.events if v['sequence'] > after][:32]
            return {'events': rows, 'next_cursor': rows[-1]['sequence'] if rows else after,
                    'complete': not rows or rows[-1]['sequence'] == self.sequence}
        if kind == 'status':
            return self.snapshot()
        if kind == 'artifacts':
            return self.changes()
        if kind == 'operation':
            saved = self.operations.get(action.get('operation_id'))
            return dict(saved or {'state': 'NOT_DISPATCHED'})
        operation_id = action.get('operation_id')
        if not isinstance(operation_id, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', operation_id):
            raise ValueError('A stable operation identity is required')
        identity = checksum(action)
        with self.lock:
            previous = self.operations.get(operation_id)
            if previous:
                if previous['digest'] != identity:
                    raise ValueError('Operation identity already belongs to a different command')
                if previous['state'] != 'COMPLETED':
                    raise ValueError('Prior operation needs reconciliation')
                return previous['result']
            if len(self.operations) >= 128:
                raise ValueError('Agent command budget exhausted')
            self.operations[operation_id] = {'digest': identity, 'state': 'DISPATCHED'}
        try:
            if kind == 'start':
                config = json.loads((self.control / 'launch.json').read_text())
                self.launch(config)
                result = self.prompt(action['objective'])
            elif kind == 'steer':
                result = self.prompt(action['message'], steering=self.state == 'RUNNING', expected=action.get('expected_remote_run_id'))
            elif kind == 'cancel':
                self.stop('CANCELLED')
                result = self.snapshot()
            else:
                raise ValueError('Unsupported agent control action')
        except BaseException:
            self.operations[operation_id]['state'] = 'UNKNOWN'
            raise
        self.operations[operation_id].update(state='COMPLETED', result=result)
        return result


def serve():
    os.umask(0o077)
    deadline = float(sys.argv[1])
    runtime = AgentProcess('/task/checkout', '/control', deadline, user=65534)
    class Handler(socketserver.StreamRequestHandler):
        def handle(self):
            try:
                raw = self.rfile.readline(256 * 1024 + 1)
                if len(raw) > 256 * 1024:
                    raise ValueError('Agent control frame too large')
                result = runtime.dispatch(json.loads(raw))
            except Exception:
                result = {'error_code': 'AGENT_CONTROL_REJECTED', 'state': runtime.state}
            self.wfile.write((canonical(result) + '\n').encode())
    with socketserver.ThreadingUnixStreamServer('/control/agent.sock', Handler) as server:
        server.timeout = .25
        while time.time() < deadline:
            server.handle_request()
        runtime.stop('TIMED_OUT')


if __name__ == '__main__':
    serve()
