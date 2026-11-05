"""Opt-in transport diagnostics: bounded, metadata-only and written off the request path."""
import asyncio
import hashlib
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid
from collections import deque
from contextlib import asynccontextmanager

from fastapi import Request
from fastapi.responses import JSONResponse, Response

MAX_LINES = 5000
MAX_LINE_BYTES = 2048
CLIENT_HEADER = 'X-Gateway-Client-Diagnostics'
_DIGEST = re.compile(r'[a-f0-9]{64}')
_EVENTS = {'client_request', 'gateway_response', 'transport_failure', 'debug_enabled', 'debug_disabled', 'gateway_started'}
_ROUTES = {'chat', 'models', 'readiness', 'liveness', 'capabilities', 'job', 'engineering', 'other'}


def digest(value):
    return hashlib.sha256(str(value or '').encode('utf-8')).hexdigest()


def client_metadata(raw):
    """Accept an explicit tiny schema, never arbitrary client text or log messages."""
    if not isinstance(raw, str) or len(raw.encode('utf-8')) > MAX_LINE_BYTES:
        return {}
    try:
        value = json.loads(raw)
    except (ValueError, TypeError, RecursionError):
        return {}
    if not isinstance(value, dict) or type(value.get('schemaVersion')) is not int or value['schemaVersion'] != 1:
        return {}
    result = {'schemaVersion': 1}
    for key in ('androidSdk', 'appVersionCode', 'networkEpoch'):
        item = value.get(key)
        if type(item) is int and 0 <= item <= 2 ** 53:
            result[key] = item
    for key in ('profileDigest', 'modelDigest'):
        item = value.get(key)
        if isinstance(item, str) and _DIGEST.fullmatch(item):
            result[key] = item
    role = value.get('requestRole')
    if isinstance(role, str) and role in {'primary', 'github', 'review', 'synthesis', 'diagnostic', 'other'}:
        result['requestRole'] = role
    return result


def route_kind(path):
    path = str(path).removeprefix('/v1')
    return ('chat' if path == '/chat/completions' else 'models' if path == '/models' else
            'readiness' if path == '/gateway/ready' else 'liveness' if path == '/gateway/live' else
            'capabilities' if path in {'/gateway/capabilities', '/gateway/v14', '/gateway/v15'} else
            'job' if path.startswith('/gateway/jobs') else 'engineering' if path.startswith('/gateway/engineering/') else 'other')


def sanitized_record(value):
    """Also used on reload/export, so edited local files cannot turn export into raw-log upload."""
    if not isinstance(value, dict) or not isinstance(value.get('event'), str) or value['event'] not in _EVENTS:
        return None
    result = {'schemaVersion': 1, 'event': value['event']}
    for key in ('sequence', 'timestampMs', 'status', 'receivedBytes', 'sentBytes', 'durationMs'):
        item = value.get(key)
        if type(item) is int and 0 <= item <= 2 ** 53:
            result[key] = item
    for key in ('requestDigest', 'principalDigest', 'jobDigest'):
        item = value.get(key)
        if isinstance(item, str) and _DIGEST.fullmatch(item):
            result[key] = item
    if isinstance(value.get('route'), str) and value['route'] in _ROUTES:
        result['route'] = value['route']
    if isinstance(value.get('method'), str) and value['method'] in {'GET', 'HEAD', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS'}:
        result['method'] = value['method']
    if isinstance(value.get('failure'), str) and value['failure'] in {'cancelled', 'connection_closed', 'request_failed'}:
        result['failure'] = value['failure']
    try:
        client = client_metadata(json.dumps(value.get('client', {})))
    except (ValueError, TypeError, RecursionError):
        client = {}
    if client:
        result['client'] = client
    return result


def encode_records(records):
    return ''.join(json.dumps(item, separators=(',', ':'), sort_keys=True) + '\n' for item in records)


class DebugLogStore:
    """A single writer coalesces records; both memory and the atomic disk snapshot cap at 5,000."""
    def __init__(self, path, enabled=False, now=time.time):
        self.path = Path(path)
        self.enabled = bool(enabled)
        self.now = now
        self.lock = threading.RLock()
        self.flush_lock = threading.Lock()
        self.records = deque(maxlen=MAX_LINES)
        self.sequence = 0
        self.pending = threading.Event()
        self.stopped = threading.Event()
        self.thread = None
        self.write_error = None
        # Read a bounded tail; never hydrate an oversized or malformed historical log.
        try:
            with self.path.open('rb') as stream:
                stream.seek(0, 2)
                size = stream.tell()
                stream.seek(max(0, size - MAX_LINES * MAX_LINE_BYTES))
                if stream.tell():
                    stream.readline(MAX_LINE_BYTES)
                for raw in stream:
                    if len(raw) > MAX_LINE_BYTES:
                        continue
                    try:
                        item = sanitized_record(json.loads(raw))
                    except (ValueError, UnicodeDecodeError, RecursionError):
                        continue
                    if item:
                        self.records.append(item)
                        self.sequence = max(self.sequence, item.get('sequence', 0))
        except OSError:
            pass

    def start(self):
        with self.lock:
            if self.thread is not None and self.thread.is_alive():
                return
            self.stopped.clear()
            self.thread = threading.Thread(target=self._write_loop, name='gateway-debug-writer', daemon=True)
            self.thread.start()
        self.record('gateway_started')

    def stop(self):
        self.stopped.set()
        if self.thread is not None:
            self.thread.join(timeout=3)
        self.flush()

    def set_enabled(self, enabled):
        if type(enabled) is not bool:
            raise ValueError('enabled must be boolean')
        with self.lock:
            if enabled == self.enabled:
                return
            if not enabled:
                self.record('debug_disabled')
            self.enabled = enabled
            if enabled:
                self.record('debug_enabled')

    def record(self, event, **fields):
        with self.lock:
            if not self.enabled:
                return
            item = sanitized_record(dict(fields, event=event))
            if item is None:
                return
            self.sequence += 1
            item.update(sequence=self.sequence, timestampMs=int(self.now() * 1000))
            self.records.append(item)
            self.pending.set()

    def snapshot(self):
        with self.lock:
            return {'schemaVersion': 1, 'enabled': self.enabled, 'retainedLines': len(self.records),
                    'lineLimit': MAX_LINES, 'lastSequence': self.sequence, 'storageError': self.write_error,
                    'content': 'sanitized_transport_metadata'}

    def export(self):
        with self.lock:
            records = list(self.records)
        return encode_records(records)

    def flush(self):
        # Snapshot while holding the writer lock prevents an older flush overwriting a newer one.
        with self.flush_lock:
            with self.lock:
                if not self.pending.is_set():
                    return
                self.pending.clear()
                records = list(self.records)
            data = encode_records(records)
            temporary = self.path.with_name(self.path.name + '.tmp-' + uuid.uuid4().hex)
            try:
                self.path.parent.mkdir(parents=True, exist_ok=True)
                with temporary.open('w', encoding='utf-8', newline='\n') as stream:
                    stream.write(data)
                    stream.flush()
                    os.fsync(stream.fileno())
                os.replace(temporary, self.path)
                self.write_error = None
            except OSError:
                self.write_error = 'DEBUG_LOG_WRITE_FAILED'
                self.pending.set()
            finally:
                try:
                    temporary.unlink(missing_ok=True)
                except OSError:
                    pass

    def _write_loop(self):
        while not self.stopped.is_set():
            if self.pending.wait(1):
                # Coalesce chat chunks/requests instead of synchronously rewriting on every event.
                self.stopped.wait(0.5)
                self.flush()
                if self.write_error:
                    self.stopped.wait(1)


def lifecycle(store, parent):
    @asynccontextmanager
    async def run(app):
        store.start()
        try:
            async with parent(app):
                yield
        finally:
            await asyncio.to_thread(store.stop)
    return run


class DebugMiddleware:
    """Observe even admission errors; only admitted clients receive a metadata acknowledgement."""
    def __init__(self, app, store, instance_id):
        self.app, self.store, self.instance_id = app, store, instance_id

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        headers = dict(scope.get('headers', []))
        request_id = headers.get(b'x-request-id', b'').decode('latin1')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', request_id):
            request_id = uuid.uuid4().hex
        client = client_metadata(headers.get(CLIENT_HEADER.lower().encode(), b'').decode('latin1'))
        context = {'requestDigest': digest(request_id), 'principalDigest': digest('unavailable'),
                   'route': route_kind(scope.get('path', '')), 'method': scope.get('method', '')}
        job = headers.get(b'x-gateway-job-id', b'')
        if job:
            context['jobDigest'] = digest(job.decode('latin1'))
        observed = not scope.get('path', '').removeprefix('/v1').startswith('/gateway/admin/debug')
        started = time.monotonic()
        received = sent = 0
        status = 500
        request_logged = False

        def record_request():
            nonlocal request_logged
            if not observed or request_logged:
                return
            principal = scope.get('state', {}).get('principal')
            context['principalDigest'] = digest(getattr(principal, 'id', 'unavailable'))
            self.store.record('client_request', client=client if principal else {}, **context)
            request_logged = True

        async def observed_receive():
            nonlocal received
            message = await receive()
            received += len(message.get('body', b''))
            return message

        async def observed_send(message):
            nonlocal status, sent
            if message['type'] == 'http.response.start':
                status = message['status']
                additions = {b'x-gateway-instance-id': self.instance_id.encode(), b'x-gateway-debug-protocol': b'1',
                             b'x-gateway-debug-enabled': b'true' if self.store.enabled else b'false'}
                if client and self.store.enabled and scope.get('state', {}).get('principal'):
                    additions[b'x-gateway-client-diagnostics-accepted'] = b'1'
                prior = list(message.get('headers', []))
                returned_id = next((v.decode('latin1') for k, v in prior if k.lower() == b'x-gateway-request-id'), None)
                if returned_id is None:
                    additions[b'x-gateway-request-id'] = request_id.encode()
                else:
                    # Correlate Android's returned job/request receipt with these two local records.
                    context['requestDigest'] = digest(returned_id)
                record_request()
                message['headers'] = [(k, v) for k, v in prior if k.lower() not in additions] + list(additions.items())
            sent += len(message.get('body', b''))
            await send(message)

        try:
            await self.app(scope, observed_receive, observed_send)
        except BaseException as error:
            if observed:
                record_request()
                failure = 'cancelled' if isinstance(error, asyncio.CancelledError) else 'connection_closed' if isinstance(error, (ConnectionError, BrokenPipeError)) else 'request_failed'
                self.store.record('transport_failure', failure=failure, **context)
            raise
        finally:
            if observed:
                record_request()
                self.store.record('gateway_response', status=status, receivedBytes=received, sentBytes=sent,
                                  durationMs=max(0, int((time.monotonic() - started) * 1000)), **context)


def install_routes(app, store):
    @app.get('/gateway/admin/debug')
    def status():
        return store.snapshot()

    @app.post('/gateway/admin/debug')
    async def configure(request: Request):
        try:
            # The body is deliberately tiny and never logged.
            raw = bytearray()
            async for chunk in request.stream():
                if len(raw) + len(chunk) > 128:
                    raise ValueError()
                raw.extend(chunk)
            value = json.loads(raw)
            if not isinstance(value, dict) or set(value) != {'enabled'} or type(value['enabled']) is not bool:
                raise ValueError()
            store.set_enabled(value['enabled'])
            return store.snapshot()
        except (ValueError, TypeError):
            return JSONResponse({'error': {'code': 'invalid_debug_settings'}}, status_code=400)

    @app.get('/gateway/admin/debug/export')
    def export():
        return Response(store.export(), media_type='application/x-ndjson', headers={
            'Content-Disposition': 'attachment; filename="gateway-debug.jsonl"', 'Cache-Control': 'no-store'})
