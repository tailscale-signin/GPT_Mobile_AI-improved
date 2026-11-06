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
from concurrent.futures import Future
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
from queue import Queue, Empty, Full

from fastapi import Request
from fastapi.responses import JSONResponse, Response
from .diagnostic_events import (DiagnosticSessionJournal, DiagnosticProtocolError, MAX_BATCH_BYTES, MAX_CAPTURE_SECONDS,
                                event_identity, sanitize_event, trace_context, HEX32)

MAX_LINES = 5000
MAX_LINE_BYTES = 2048
CLIENT_HEADER = 'X-Gateway-Client-Diagnostics'
_DIGEST = re.compile(r'[a-f0-9]{64}')
_EVENTS = {'client_request', 'gateway_response', 'transport_failure', 'debug_enabled', 'debug_disabled', 'gateway_started'}
_ROUTES = {'chat', 'models', 'readiness', 'liveness', 'capabilities', 'dashboard', 'job', 'engineering', 'other'}
current_diagnostic_trace = ContextVar('gateway_diagnostic_trace', default=None)
current_diagnostic_capture = ContextVar('gateway_diagnostic_capture', default=None)


def diagnostic_trace_headers():
    """Propagate causal context without baggage or arbitrary inbound headers."""
    context = current_diagnostic_trace.get()
    return {'traceparent': f"00-{context['trace_id']}-{context['span_id']}-01"} if context else {}


def capture_context_snapshot(principal, runtime):
    """Retain only the admitted capture's opaque causal identifiers for a durable job."""
    store = getattr(runtime, 'gateway_debug', None)
    capture, trace = current_diagnostic_capture.get(), current_diagnostic_trace.get()
    if store is None or not store.enabled or not isinstance(capture, dict) or not isinstance(trace, dict):
        return None
    admitted = capture.get('request_state', {}).get('principal')
    session, started = capture.get('session'), capture.get('started')
    if (getattr(admitted, 'id', None) != principal or not isinstance(session, str) or not HEX32.fullmatch(session)
            or type(started) is not int or not -300000 <= store.now() * 1000 - started <= MAX_CAPTURE_SECONDS * 1000
            or trace_context(f"00-{trace.get('trace_id', '')}-{trace.get('span_id', '')}-01") is None):
        return None
    return {'schema_version': 2, 'session_id': session, 'capture_started_at_utc': started,
            'principal_digest': digest(principal), 'trace_id': trace['trace_id'], 'parent_span_id': trace['span_id']}


@contextmanager
def execution_diagnostic_context(snapshot, runtime, principal, authorized):
    """Resume a submitted capture on a worker under current authorization and expiry.

    A missing, disabled, expired or revoked capture never creates a replacement
    association. Each stage owns a fresh span and restores its caller's context.
    """
    store = getattr(runtime, 'gateway_debug', None)
    capture, trace = None, None
    if isinstance(snapshot, dict) and store is not None and callable(authorized):
        session, started = snapshot.get('session_id'), snapshot.get('capture_started_at_utc')
        trace = trace_context(f"00-{snapshot.get('trace_id', '')}-{snapshot.get('parent_span_id', '')}-01")
        try:
            permitted = store.enabled and authorized() is True
        except Exception:
            permitted = False
        if (permitted and type(snapshot.get('schema_version')) is int and snapshot['schema_version'] == 2
                and isinstance(session, str) and HEX32.fullmatch(session) and type(started) is int
                and -300000 <= store.now() * 1000 - started <= MAX_CAPTURE_SECONDS * 1000
                and snapshot.get('principal_digest') == digest(principal) and trace is not None):
            capture = {'session': session, 'started': started, 'principal': principal, 'authorized': authorized}
    capture_marker = current_diagnostic_capture.set(capture)
    trace_marker = current_diagnostic_trace.set(trace if capture is not None else None)
    try:
        yield
    finally:
        current_diagnostic_trace.reset(trace_marker)
        current_diagnostic_capture.reset(capture_marker)


def diagnostic_execution_event(runtime, principal, stage, binding_id=None, job_id=None, state=None,
                               error_code=None, dispatched=None, local_duration_ms=None, bounded_attributes=None,
                               operation_id=None, request_id=None):
    """Best-effort metadata from actual model/effect receipt transitions, never execution authority.

    Durable workers re-enter their admitted capture explicitly. Jobs without a
    saved capture have no fabricated association with the worker's other tasks.
    """
    store = getattr(runtime, 'gateway_debug', None)
    capture = current_diagnostic_capture.get()
    trace = current_diagnostic_trace.get() or {}
    if store is None or capture is None:
        return False
    admitted = capture.get('request_state', {}).get('principal')
    owner = getattr(admitted, 'id', None) or capture.get('principal')
    if (owner != principal or not store.enabled or type(capture.get('started')) is not int
            or not -300000 <= store.now() * 1000 - capture['started'] <= MAX_CAPTURE_SECONDS * 1000):
        return False
    try:
        if callable(capture.get('authorized')) and capture['authorized']() is not True:
            return False
    except Exception:
        return False
    dispatch = ('not_dispatched' if dispatched is False or state == 'PREPARED' else
                'unknown' if state == 'UNKNOWN_OUTCOME' else 'response_received' if state == 'COMPLETED' else
                'dispatched' if dispatched is True or state == 'DISPATCHED' else 'unknown')
    failed = state in {'FAILED', 'UNKNOWN_OUTCOME', 'REJECTED', 'BLOCKED', 'PRECHECK_REJECTED', 'IDENTITY_MISMATCH'}
    code = ('DISPATCH_UNCERTAIN' if state == 'UNKNOWN_OUTCOME' else
            'PRECHECK_REJECTED' if state == 'PRECHECK_REJECTED' or failed and dispatch == 'not_dispatched' else
            error_code or ('UNKNOWN_CAUSE' if failed else 'NONE'))
    category = 'model' if stage == 'MODEL_EXECUTION' or stage.startswith('model') or stage in {'prefill', 'decode'} else 'tool'
    stage = 'operation_dispatch' if stage in {'MODEL_EXECUTION', 'GITHUB_OPERATION'} else stage
    try:
        store.record_event(principal, capture['session'], capture['started'], stage=stage,
                           binding_id=binding_id, job_id=job_id, operation_id=operation_id, request_id=request_id,
                           severity='error' if failed else 'info', error_code=code, category=category,
                           dispatch_state=dispatch, retry_class='reconcile' if state == 'UNKNOWN_OUTCOME' else 'unknown',
                           local_duration_ms=local_duration_ms, bounded_attributes=bounded_attributes or {}, **trace)
        return True
    except Exception:
        # A diagnostics-only resource failure must not change execution/effect receipts.
        store.event_error = 'DIAGNOSTIC_EVENT_CAPTURE_FAILED'
        return False


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
            'dashboard' if path == '/gateway/dashboard' else
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
        self.export_lock = threading.Lock()
        self.records = deque(maxlen=MAX_LINES)
        self.sequence = 0
        self.pending = threading.Event()
        self.stopped = threading.Event()
        self.thread = None
        self.write_error = None
        self.journal = DiagnosticSessionJournal(self.path.with_suffix('.events.sqlite3'), now)
        self.event_queue = Queue(maxsize=256)
        self.queue_dropped = 0
        self.boot_id = uuid.uuid4().hex
        self.origin_id = digest('gateway:' + str(self.path.absolute()))[:32]
        self.origin_sequences = {}
        self.local_gaps = {}
        self.event_error = None
        # Read a bounded tail; never hydrate an oversized or malformed historical log.
        try:
            with self.path.open('rb') as stream:
                stream.seek(0, 2)
                size = stream.tell()
                stream.seek(max(0, size - MAX_LINES * MAX_LINE_BYTES))
                if stream.tell():
                    stream.readline(MAX_LINE_BYTES)
                while True:
                    raw = stream.readline(MAX_LINE_BYTES + 1)
                    if not raw:
                        break
                    if len(raw) > MAX_LINE_BYTES:
                        while raw and not raw.endswith(b'\n'):
                            raw = stream.readline(MAX_LINE_BYTES + 1)
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
        if self._ensure_writer():
            self.record('gateway_started')

    def _ensure_writer(self):
        with self.lock:
            if self.thread is not None and self.thread.is_alive():
                return False
            self.stopped.clear()
            self.thread = threading.Thread(target=self._write_loop, name='gateway-debug-writer', daemon=True)
            self.thread.start()
            return True

    def stop(self):
        self.stopped.set()
        if self.thread is not None:
            self.thread.join(timeout=3)
        self.flush()
        self.journal.close()

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
                    'lineLimit': MAX_LINES, 'lastSequence': self.sequence,
                    'content': 'sanitized_transport_metadata', 'protocolVersion': 2, 'clientMetadata': True,
                    'bidirectionalEvents': True, 'recordByteLimit': MAX_LINE_BYTES,
                    'retainedLineLimit': MAX_LINES, 'debugEnabled': self.enabled,
                    'storageChecked': self.journal.storage_checked,
                    'storageReady': (self.write_error is None and self.journal.storage_error is None and self.event_error is None)
                                    if self.journal.storage_checked else None,
                    'storageError': self.write_error or self.journal.storage_error or self.event_error, 'queueDropped': self.queue_dropped,
                    'sessionDurationLimitSeconds': MAX_CAPTURE_SECONDS}

    def record_event(self, principal, session, capture_started, **fields):
        """Non-blocking producer; the existing background writer commits all v2 events."""
        with self.lock:
            if (not self.enabled or not isinstance(session, str) or not HEX32.fullmatch(session) or type(capture_started) is not int
                    or not -300000 <= self.now() * 1000 - capture_started <= MAX_CAPTURE_SECONDS * 1000):
                return
            key = principal, session, capture_started
            self.origin_sequences = {item: value for item, value in self.origin_sequences.items()
                                     if self.now() * 1000 - item[2] <= MAX_CAPTURE_SECONDS * 1000}
            if key not in self.origin_sequences and len(self.origin_sequences) >= 16:
                self.queue_dropped += 1
                return
            sequence = self.origin_sequences.get(key, 0) + 1
            self.origin_sequences[key] = sequence
            event = sanitize_event(dict(fields, schema_version=2, origin_device_pseudonym=self.origin_id, origin_boot_id=self.boot_id,
                                        origin_sequence=sequence, event_id=event_identity(self.origin_id, self.boot_id, sequence),
                                        occurred_at_utc=int(self.now() * 1000), owner='gateway'), imported=False)
            if event is None:
                self.queue_dropped += 1
                return
            gaps = self.local_gaps.pop(key, [])
            batch = {'schema_version': 2, 'session_id': session, 'capture_started_at_utc': capture_started, 'events': [event], 'gaps': gaps}
            try:
                self.event_queue.put_nowait((principal, batch, None))
            except Full:
                self.queue_dropped += 1
                if gaps and gaps[-1]['last'] + 1 == sequence:
                    gaps[-1]['last'] = sequence
                else:
                    gaps.append({'origin_device_pseudonym': self.origin_id, 'origin_boot_id': self.boot_id,
                                 'first': sequence, 'last': sequence, 'reason': 'QUEUE_FULL'})
                self.local_gaps[key] = gaps
            self.pending.set()
        self._ensure_writer()

    def ingest(self, principal, batch):
        future = Future()
        with self.lock:
            if not self.enabled:
                raise DiagnosticProtocolError('DIAGNOSTICS_DISABLED', 409)
            try:
                self.event_queue.put_nowait((principal, batch, future))
            except Full:
                raise DiagnosticProtocolError('DIAGNOSTIC_BACKPRESSURE', 429) from None
            self.pending.set()
        self._ensure_writer()
        return future

    def _drain_events(self):
        for _ in range(256):
            try:
                principal, batch, future = self.event_queue.get_nowait()
            except Empty:
                break
            try:
                if not self.enabled:
                    raise DiagnosticProtocolError('DIAGNOSTICS_DISABLED', 409)
                result = self.journal.ingest(principal, batch, local=future is None)
                if future is not None and not future.done():
                    future.set_result(result)
            except DiagnosticProtocolError as failure:
                if future is not None and not future.done():
                    future.set_exception(failure)
                elif future is None:
                    with self.lock:
                        self.queue_dropped += len(batch.get('events', []))
                    self.event_error = failure.code
                    key = principal, batch['session_id'], batch['capture_started_at_utc']
                    with self.lock:
                        losses = batch.get('gaps', []) + self.local_gaps.pop(key, [])
                        for item in batch.get('events', []):
                            losses.append({'origin_device_pseudonym': item['origin_device_pseudonym'], 'origin_boot_id': item['origin_boot_id'],
                                           'first': item['origin_sequence'], 'last': item['origin_sequence'], 'reason': 'STORAGE_UNAVAILABLE'})
                        compact = []
                        for loss in sorted(losses, key=lambda x: x['first']):
                            if compact and compact[-1]['reason'] == loss['reason'] and compact[-1]['last'] + 1 >= loss['first']:
                                compact[-1]['last'] = max(compact[-1]['last'], loss['last'])
                            elif len(compact) < 64:
                                compact.append(loss)
                            else:
                                self.event_error = 'DIAGNOSTIC_GAP_LIMIT'
                        self.local_gaps[key] = compact
            finally:
                self.event_queue.task_done()

    def export(self):
        with self.lock:
            records = list(self.records)
        return encode_records(records)

    def export_session(self, principal, session):
        # A retained capture is bounded, but concurrent full exports multiply its
        # peak memory. Reject excess exports without blocking the event writer.
        if not self.export_lock.acquire(blocking=False):
            raise DiagnosticProtocolError('DIAGNOSTIC_EXPORT_BUSY', 429)
        try:
            return self.journal.export(principal, session)
        finally:
            self.export_lock.release()

    def flush(self):
        # Snapshot while holding the writer lock prevents an older flush overwriting a newer one.
        with self.flush_lock:
            self._drain_events()
            with self.lock:
                if not self.pending.is_set():
                    return
                self.pending.clear()
                if not self.event_queue.empty():
                    self.pending.set()
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
        observed = observed and not scope.get('path', '').removeprefix('/v1').startswith('/gateway/debug/')
        trace = trace_context(headers.get(b'traceparent', b'').decode('ascii', errors='ignore')) or {'trace_id': uuid.uuid4().hex, 'span_id': uuid.uuid4().hex[:16]}
        scope.setdefault('state', {})['diagnostic_trace'] = trace
        trace_marker = current_diagnostic_trace.set(trace)
        session = headers.get(b'x-gateway-diagnostic-session', b'').decode('ascii', errors='ignore')
        try:
            capture_started = int(headers.get(b'x-gateway-diagnostic-started', b''))
        except (ValueError, TypeError):
            capture_started = None
        capture_marker = current_diagnostic_capture.set({'session': session, 'started': capture_started, 'request_state': scope['state']}
                                                         if HEX32.fullmatch(session) and capture_started is not None else None)
        started = time.monotonic()
        received = sent = 0
        status = None
        response_started = response_completed = False
        request_logged = False

        def attributes():
            return {'route': context['route'], 'method': context['method'],
                    'response_headers_sent': response_started, 'response_body_completed': response_completed}

        def event(stage, severity='info', error_code='NONE', **fields):
            principal = scope.get('state', {}).get('principal')
            if observed and principal is not None and HEX32.fullmatch(session) and capture_started is not None:
                self.store.record_event(principal.id, session, capture_started, stage=stage, severity=severity,
                                        error_code=error_code, request_id=context['requestDigest'], **trace, **fields)

        def record_request():
            nonlocal request_logged
            if not observed or request_logged:
                return
            principal = scope.get('state', {}).get('principal')
            context['principalDigest'] = digest(getattr(principal, 'id', 'unavailable'))
            self.store.record('client_request', client=client if principal else {}, **context)
            event('request', direction='client_to_gateway', dispatch_state='dispatched', category='transport',
                  bounded_attributes=attributes())
            request_logged = True

        async def observed_receive():
            nonlocal received
            message = await receive()
            received += len(message.get('body', b''))
            return message

        async def observed_send(message):
            nonlocal status, sent, response_started, response_completed
            if message['type'] == 'http.response.start':
                status = message['status']
                additions = {b'x-gateway-instance-id': self.instance_id.encode(), b'x-gateway-debug-protocol': b'2',
                             b'x-gateway-debug-enabled': b'true' if self.store.enabled else b'false',
                             b'traceparent': f"00-{trace['trace_id']}-{trace['span_id']}-01".encode('ascii')}
                if trace.get('parent_span_id'):
                    additions[b'x-gateway-client-span-id'] = trace['parent_span_id'].encode('ascii')
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
            await send(message)
            if message['type'] == 'http.response.start':
                response_started = True
            elif message['type'] == 'http.response.body':
                sent += len(message.get('body', b''))
                response_completed = not message.get('more_body', False)

        try:
            await self.app(scope, observed_receive, observed_send)
        except BaseException as error:
            if observed:
                record_request()
                failure = 'cancelled' if isinstance(error, asyncio.CancelledError) else 'connection_closed' if isinstance(error, (ConnectionError, BrokenPipeError)) else 'request_failed'
                self.store.record('transport_failure', failure=failure, **context)
                event('transport', severity='warning' if failure == 'cancelled' else 'error',
                      error_code='CANCELLED' if failure == 'cancelled' else 'TRANSPORT_FAILURE',
                      category='cancellation' if failure == 'cancelled' else 'transport', dispatch_state='unknown',
                      retry_class='none' if failure == 'cancelled' else 'unknown',
                      local_duration_ms=max(0, int((time.monotonic() - started) * 1000)), bounded_attributes=attributes())
            raise
        finally:
            if observed:
                record_request()
                # A broken connection before response.start is not an observed HTTP
                # 500. A sent header also does not prove a complete response body.
                if response_started:
                    self.store.record('gateway_response', status=status, receivedBytes=received, sentBytes=sent,
                                      durationMs=max(0, int((time.monotonic() - started) * 1000)), **context)
                    event('response', severity='error' if status >= 400 else 'info', error_code='HTTP_ERROR' if status >= 400 else 'NONE',
                          direction='gateway_to_client', category='transport', dispatch_state='response_received',
                          local_duration_ms=max(0, int((time.monotonic() - started) * 1000)),
                          bounded_attributes={**attributes(), 'status': status, 'received_bytes': received, 'sent_bytes': sent})
            current_diagnostic_trace.reset(trace_marker)
            current_diagnostic_capture.reset(capture_marker)


def install_routes(app, store):
    def principal(request):
        identity = getattr(request.state, 'principal', None)
        if identity is None:
            raise DiagnosticProtocolError('DIAGNOSTIC_IDENTITY_REQUIRED', 401)
        return identity.id

    def error(failure):
        return JSONResponse({'error': {'code': failure.code}, 'schema_version': 2}, status_code=failure.status,
                            headers={'Cache-Control': 'no-store'})

    @app.post('/gateway/debug/events')
    async def ingest_events(request: Request):
        try:
            owner = principal(request)
            raw = bytearray()
            async for chunk in request.stream():
                if len(raw) + len(chunk) > MAX_BATCH_BYTES:
                    raise DiagnosticProtocolError('DIAGNOSTIC_BATCH_LIMIT', 413)
                raw.extend(chunk)
            try:
                value = json.loads(raw)
            except (ValueError, UnicodeDecodeError, RecursionError):
                raise DiagnosticProtocolError('INVALID_DIAGNOSTICS_SCHEMA') from None
            receipt = await asyncio.wrap_future(store.ingest(owner, value))
            return JSONResponse(receipt, headers={'Cache-Control': 'no-store'})
        except DiagnosticProtocolError as failure:
            return error(failure)

    @app.get('/gateway/debug/events')
    async def read_events(request: Request, session_id: str, after: int = 0, limit: int = 64, exclude_origin: str | None = None):
        try:
            result = await asyncio.to_thread(store.journal.read, principal(request), session_id, after, limit, exclude_origin)
            return JSONResponse(result, headers={'Cache-Control': 'no-store'})
        except DiagnosticProtocolError as failure:
            return error(failure)

    @app.get('/gateway/debug/export')
    async def export_session(request: Request, session_id: str):
        try:
            result = await asyncio.to_thread(store.export_session, principal(request), session_id)
            return Response(result, media_type='application/zip', headers={
                'Content-Disposition': 'attachment; filename="gateway-debug-session.zip"', 'Cache-Control': 'no-store'})
        except DiagnosticProtocolError as failure:
            return error(failure)

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
