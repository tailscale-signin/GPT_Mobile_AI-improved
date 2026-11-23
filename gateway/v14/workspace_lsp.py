"""Bounded compiler/language-server sessions in the isolated live checkout."""
import json
import hashlib
import os
from pathlib import Path
import selectors
import signal
import subprocess
import sys
import threading
import time

PUSH_DIAGNOSTICS_TIMEOUT = 12
SERVERS = {'kotlin': ['kotlin-lsp', '--stdio'], 'python': ['pylsp'], 'typescript': ['typescript-language-server', '--stdio']}
KINDS = {'definition': ('definitionProvider', 'textDocument/definition'),
         'references': ('referencesProvider', 'textDocument/references'),
         'diagnostics': ('diagnosticProvider', 'textDocument/diagnostic'),
         'rename_preview': ('renameProvider', 'textDocument/rename'),
         'callers': ('callHierarchyProvider', 'textDocument/prepareCallHierarchy'),
         'supertypes': ('typeHierarchyProvider', 'textDocument/prepareTypeHierarchy')}


class Client:
    def __init__(self, process):
        self.process = process
        self.next_id = 0
        self.diagnostics = {}
        self.document_versions = {}
        self.interrupted = None
        self.fd_io = False
        self.pending = bytearray()

    def enable_cancellable_io(self):
        # Pooled servers use raw pipes. Only the query owner reads/writes them;
        # a helper retaining a pipe must not make cancellation wait for EOF.
        os.set_blocking(self.process.stdin.fileno(), False)
        os.set_blocking(self.process.stdout.fileno(), False)
        self.fd_io = True

    def _wait(self, stream, event):
        with selectors.DefaultSelector() as selector:
            selector.register(stream, event)
            while True:
                if self.interrupted is not None and self.interrupted():
                    raise InterruptedError('Language-server IO cancelled or expired')
                if selector.select(.05):
                    return
                if self.process.poll() is not None:
                    raise ValueError('Language server closed its connection')

    def _read(self, size):
        while True:
            self._wait(self.process.stdout, selectors.EVENT_READ)
            try:
                raw = os.read(self.process.stdout.fileno(), size)
            except BlockingIOError:
                continue
            if not raw:
                raise ValueError('Language server closed its connection')
            return raw

    def _take(self, size):
        raw = bytes(self.pending[:size])
        del self.pending[:size]
        return raw

    def _line(self, limit):
        if not self.fd_io:
            return self.process.stdout.readline(limit)
        while True:
            newline = self.pending.find(b'\n', 0, limit)
            if newline >= 0:
                return self._take(newline + 1)
            if len(self.pending) >= limit:
                return self._take(limit)
            self.pending.extend(self._read(4096))

    def _body(self, size):
        if not self.fd_io:
            return self.process.stdout.read(size)
        while len(self.pending) < size:
            self.pending.extend(self._read(min(65536, size - len(self.pending))))
        return self._take(size)

    def send(self, message):
        if message.get('method') in {'textDocument/didOpen', 'textDocument/didChange'}:
            doc = message.get('params', {}).get('textDocument', {})
            if isinstance(doc.get('uri'), str) and type(doc.get('version')) is int:
                self.document_versions[doc['uri']] = doc['version']
        raw = json.dumps(dict(jsonrpc='2.0', **message), separators=(',', ':')).encode()
        raw = b'Content-Length: ' + str(len(raw)).encode() + b'\r\n\r\n' + raw
        if not self.fd_io:
            self.process.stdin.write(raw)
            self.process.stdin.flush()
            return
        pending = memoryview(raw)
        while pending:
            self._wait(self.process.stdin, selectors.EVENT_WRITE)
            try:
                written = os.write(self.process.stdin.fileno(), pending[:65536])
            except BlockingIOError:
                continue
            if not written:
                raise ValueError('Language server closed its connection')
            pending = pending[written:]

    def receive(self):
        size = None
        for _ in range(16):
            header = self._line(1024)
            if not header:
                raise ValueError('Language server closed its connection')
            if header in (b'\r\n', b'\n'):
                break
            key, _, value = header.partition(b':')
            if key.strip().lower() == b'content-length':
                size = int(value)
        if size is None or not 1 <= size <= 4 * 1024 * 1024:
            raise ValueError('Invalid language-server frame')
        message = json.loads(self._body(size))
        if 'method' in message:
            if 'id' in message:
                if message['method'] == 'workspace/configuration':
                    self.send(dict(id=message['id'], result=[None] * len(message.get('params', {}).get('items', []))))
                elif message['method'] in {'client/registerCapability', 'client/unregisterCapability', 'window/workDoneProgress/create'}:
                    self.send(dict(id=message['id'], result=None))
                else:
                    self.send(dict(id=message['id'], error={'code': -32601, 'message': 'Unsupported client request'}))
            elif message['method'] == 'textDocument/publishDiagnostics':
                p = message['params']
                expected, reported = self.document_versions.get(p['uri']), p.get('version')
                # Warm sessions can receive delayed notifications from an older
                # document. Unversioned diagnostics are usable for the initial
                # snapshot only; after changes their freshness is unproven.
                if (expected is None or reported == expected or reported is None and expected == 1):
                    self.diagnostics[p['uri']] = p.get('diagnostics', [])
        return message

    def request(self, method, params):
        self.next_id += 1
        ident = self.next_id
        self.send(dict(id=ident, method=method, params=params))
        while True:
            message = self.receive()
            if 'method' not in message and message.get('id') == ident:
                if 'error' in message:
                    raise ValueError('Language server rejected the query: ' + str(message['error'].get('code')))
                return message.get('result')

    def pushed_diagnostics(self, uri):
        # A push-only server may publish after didOpen and documentSymbol have returned.
        # A server that never publishes is killed; a warm pool discards it.
        expired = threading.Event()

        def stop_waiting():
            expired.set()
            self.process.kill()

        deadline = threading.Timer(PUSH_DIAGNOSTICS_TIMEOUT, stop_waiting)
        deadline.start()
        try:
            while uri not in self.diagnostics:
                self.receive()
        except (OSError, ValueError):
            if not expired.is_set():
                raise
        finally:
            deadline.cancel()
            deadline.join()
        return self.diagnostics.get(uri)


def _location(root, action, servers):
    language, kind = action['language'], action['operation']
    if language not in servers or kind not in KINDS:
        raise ValueError('Choose a supported language and code-intelligence operation')
    file = (root / action['path']).resolve(strict=True)
    if not file.is_relative_to(root) or not file.is_file() or file.stat().st_size > 2 * 1024 * 1024:
        raise ValueError('Select a bounded file in the live checkout')
    line, character = action.get('line', 1), action.get('column', 1)
    if type(line) is not int or type(character) is not int or min(line, character) < 1:
        raise ValueError('Positions begin at line 1, UTF-16 column 1')
    text = file.read_text(encoding='utf-8')
    uri = file.as_uri()
    return language, kind, text, uri, line, character


def _operation(client, capabilities, action, uri, line, character):
    kind = action['operation']
    provider, method = KINDS[kind]
    supported = capabilities.get(provider)
    if supported is None or supported is False:
        if kind == 'diagnostics' and capabilities.get('documentSymbolProvider'):
            client.request('textDocument/documentSymbol', {'textDocument': {'uri': uri}})
        if kind == 'diagnostics':
            diagnostics = client.pushed_diagnostics(uri)
            if diagnostics is not None:
                return {'resolution': 'language_server', 'operation': kind, 'result': diagnostics}
        return {'resolution': 'unavailable', 'operation': kind, 'next_action': 'Use lexical exploration or run the project compiler'}
    params = {'textDocument': {'uri': uri}}
    if kind != 'diagnostics':
        params['position'] = {'line': line - 1, 'character': character - 1}
    if kind == 'references':
        params['context'] = {'includeDeclaration': True}
    if kind == 'rename_preview':
        name = action.get('new_name')
        if not isinstance(name, str) or not 1 <= len(name) <= 200:
            raise ValueError('Supply a bounded proposed name')
        params['newName'] = name
    result = client.request(method, params)
    if kind in {'callers', 'supertypes'}:
        method = 'callHierarchy/incomingCalls' if kind == 'callers' else 'typeHierarchy/supertypes'
        result = [{'item': item, 'relationships': client.request(method, {'item': item})} for item in (result or [])[:8]]
    return {'resolution': 'language_server', 'operation': kind, 'result': result, 'applied': False}


def _initialize(client, root):
    capabilities = client.request('initialize', {
        'processId': None, 'rootUri': root.as_uri(), 'workspaceFolders': [{'name': root.name, 'uri': root.as_uri()}],
        'capabilities': {'workspace': {'configuration': True}, 'general': {'positionEncodings': ['utf-16']},
                         'textDocument': {'diagnostic': {}, 'publishDiagnostics': {}, 'callHierarchy': {}, 'typeHierarchy': {}}}})['capabilities']
    client.send({'method': 'initialized', 'params': {}})
    return capabilities


def query(root, action, servers=None):
    """Compatibility one-shot API; production terminal uses the scoped pool."""
    root = root.resolve(strict=True)
    servers = SERVERS if servers is None else servers
    language, kind, text, uri, line, character = _location(root, action, servers)
    proc = subprocess.Popen(servers[language], cwd=root, stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
    client = Client(proc)
    try:
        capabilities = _initialize(client, root)
        client.send({'method': 'textDocument/didOpen', 'params': {'textDocument': {
            'uri': uri, 'version': 1, 'languageId': language, 'text': text}}})
        return _operation(client, capabilities, action, uri, line, character)
    finally:
        proc.kill()
        proc.wait(timeout=5)
        proc.stdin.close()
        proc.stdout.close()


class SessionPool:
    """At most two warm servers inside one authorized isolated checkout.

    PID 1 owns this pool. Every terminal command invalidates it, and the host
    rechecks the session/policy/source before each request. Cancellation kills
    the affected process group; expiry/close reaps the owned server. The workspace
    container bounds descendant lifetime, including helpers in other groups.
    Servers never run as the root control user or access its private control socket.
    """
    def __init__(self, root, identity, source_sha, deadline, *, servers=None, user=None, idle_seconds=60, maximum=2, query_seconds=30):
        self.root = root.resolve(strict=True)
        self.identity, self.source_sha, self.deadline = identity, source_sha, deadline
        self.root_identity = (self.root.stat().st_dev, self.root.stat().st_ino)
        self.servers = {key: tuple(value) for key, value in (SERVERS if servers is None else servers).items()}
        self.user, self.idle_seconds, self.maximum, self.query_seconds = user, min(120, max(.05, idle_seconds)), min(3, max(1, maximum)), min(60, max(.1, query_seconds))
        self.lock, self.rows, self.closed = threading.RLock(), {}, threading.Event()
        self.reaper = threading.Thread(target=self._reap, daemon=True)
        self.reaper.start()

    def _close_streams(self, row):
        for stream in (row['proc'].stdin, row['proc'].stdout):
            try:
                stream.close()
            except (OSError, ValueError):
                pass

    def _terminate(self, row):
        proc = row['proc']
        try:
            os.killpg(proc.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            pass
        # The query owns active descriptors until its cancellable IO has
        # stopped. Do not close or reuse a descriptor under a pending read.
        if not row['busy']:
            self._close_streams(row)

    def _remove(self, key):
        row = self.rows.get(key)
        if row:
            self._terminate(row)
            self.rows.pop(key, None)
        return row

    def _reap(self):
        while not self.closed.wait(.1):
            with self.lock:
                now = time.monotonic()
                for key, row in list(self.rows.items()):
                    if time.time() >= self.deadline or not row['busy'] and now - row['last_used'] >= self.idle_seconds:
                        self._remove(key)

    def invalidate(self):
        with self.lock:
            for key in list(self.rows):
                self._remove(key)

    def close(self):
        self.closed.set()
        self.invalidate()
        self.reaper.join(timeout=2)

    def query(self, action, cancel=None):
        cancel = cancel or threading.Event()
        if cancel.is_set() or self.closed.is_set() or time.time() >= self.deadline:
            raise InterruptedError('Language-server request stopped before source access')
        if (action.get('workspace_identity') != self.identity or action.get('source_sha') != self.source_sha or
                self.root_identity != (self.root.stat().st_dev, self.root.stat().st_ino)):
            raise PermissionError('Language-server workspace identity changed')
        language, kind, text, uri, line, character = _location(self.root, action, self.servers)
        key = hashlib.sha256(json.dumps([self.identity, self.source_sha, self.root_identity, language, self.servers[language]]).encode()).hexdigest()
        finished, stopped = threading.Event(), threading.Event()
        query_deadline = min(self.deadline, time.time() + self.query_seconds)
        watcher = None
        with self.lock:
            if cancel.is_set() or self.closed.is_set() or time.time() >= query_deadline:
                raise InterruptedError('Language-server request stopped before dispatch')
            row = self.rows.get(key)
            if row and (row['proc'].poll() is not None or row['queries'] >= 64 or time.monotonic() - row['last_used'] >= self.idle_seconds):
                self._remove(key)
                row = None
            if row and row['busy']:
                raise ValueError('A language-server query already owns this checkout session')
            reused = row is not None
            if row is None:
                if len(self.rows) >= self.maximum:
                    idle = sorted((row['last_used'], key) for key, row in self.rows.items() if not row['busy'])
                    if not idle:
                        raise ValueError('Language-server session allowance reached')
                    self._remove(idle[0][1])
                environment = {k: v for k, v in os.environ.items() if k in {'PATH', 'JAVA_HOME', 'ANDROID_HOME', 'ANDROID_SDK_ROOT', 'LANG'}}
                environment['HOME'] = str(self.root.parent / 'home')
                privilege = {'user': self.user, 'group': self.user, 'extra_groups': []} if self.user is not None else {}
                proc = subprocess.Popen(self.servers[language], cwd=self.root, env=environment, stdin=subprocess.PIPE,
                                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, bufsize=0, start_new_session=True, **privilege)
                row = {'proc': proc, 'client': None, 'capabilities': None, 'documents': {},
                       'queries': 0, 'last_used': time.monotonic(), 'busy': False}
                self.rows[key] = row
                try:
                    row['client'] = Client(proc)
                    row['client'].enable_cancellable_io()
                except BaseException:
                    self._remove(key)
                    raise
            row['busy'] = True
        def watch():
            while not finished.wait(.05):
                if cancel.is_set() or self.closed.is_set() or time.time() >= min(self.deadline, query_deadline):
                    stopped.set()
                    self._terminate(row)
                    return
        try:
            client = row['client']
            client.interrupted = lambda: (cancel.is_set() or stopped.is_set() or self.closed.is_set() or
                                          time.time() >= min(self.deadline, query_deadline))
            watcher = threading.Thread(target=watch, daemon=True)
            watcher.start()
            with self.lock:
                if (cancel.is_set() or stopped.is_set() or self.closed.is_set() or
                        time.time() >= min(self.deadline, query_deadline) or self.rows.get(key) is not row):
                    raise InterruptedError('Language-server request stopped before initialization')
            if row['capabilities'] is None:
                row['capabilities'] = _initialize(client, self.root)
            previous = row['documents'].get(uri)
            digest = hashlib.sha256(text.encode()).hexdigest()
            if previous is None:
                client.send({'method': 'textDocument/didOpen', 'params': {'textDocument': {
                    'uri': uri, 'version': 1, 'languageId': language, 'text': text}}})
                row['documents'][uri] = (digest, 1)
            elif previous[0] != digest:
                version = previous[1] + 1
                client.diagnostics.pop(uri, None)
                client.send({'method': 'textDocument/didChange', 'params': {'textDocument': {'uri': uri, 'version': version},
                                                                         'contentChanges': [{'text': text}]}})
                row['documents'][uri] = (digest, version)
            result = _operation(client, row['capabilities'], action, uri, line, character)
            # Watcher scheduling is not an admission witness. Serialize result
            # publication with pool invalidation and recheck the owned row.
            with self.lock:
                if (cancel.is_set() or stopped.is_set() or self.closed.is_set() or
                        time.time() >= min(self.deadline, query_deadline) or self.rows.get(key) is not row):
                    raise InterruptedError('Language-server query cancelled, expired or invalidated')
                row['queries'] += 1
                return dict(result, cache_hit=reused, session_queries=row['queries'], source_sha=self.source_sha,
                            workspace_identity=self.identity, input_sha256=digest, runtime_verified=False)
        except BaseException as failure:
            with self.lock:
                invalidated = self.rows.get(key) is not row
                if not invalidated:
                    self._remove(key)
            if (cancel.is_set() or stopped.is_set() or self.closed.is_set() or invalidated or
                    time.time() >= min(self.deadline, query_deadline)):
                raise InterruptedError('Language-server query cancelled or expired') from failure
            raise
        finally:
            finished.set()
            row['client'].interrupted = None
            if watcher is not None and watcher.ident is not None:
                watcher.join(timeout=2)
            with self.lock:
                row['last_used'], row['busy'] = time.monotonic(), False
                if row['proc'].poll() is not None:
                    if self.rows.get(key) is row:
                        self._remove(key)
                    else:
                        self._close_streams(row)


if __name__ == '__main__':
    try:
        print(json.dumps(query(Path.cwd().resolve(), json.loads(sys.argv[1]))))
    except (OSError, ValueError, KeyError) as error:
        print(json.dumps({'resolution': 'unavailable', 'error': str(error)[:300], 'next_action': 'Inspect lexical coverage or run the compiler'}))
        sys.exit(1)
