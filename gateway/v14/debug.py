"""Opt-in transport diagnostics: bounded, metadata-only and written off the request path."""
import asyncio
import hashlib
import io
import json
import os
from pathlib import Path
import re
import threading
import time
import uuid
import zipfile
from collections import deque
from concurrent.futures import Future
from contextlib import asynccontextmanager, contextmanager
from contextvars import ContextVar
from queue import Queue, Empty, Full

from fastapi import Request
from fastapi.responses import JSONResponse, Response
from .diagnostic_events import (DiagnosticSessionJournal, DiagnosticProtocolError, MAX_BATCH_BYTES, MAX_CAPTURE_SECONDS,
                                event_identity, sanitize_event, trace_context, HEX32)
from .debug_projection import (ROUTES, ADMISSION_CODES, route_kind, provenance, failure_fields, response_summary, choice)

MAX_LINES = 5000
MAX_LINE_BYTES = 2048
CLIENT_HEADER = 'X-Gateway-Client-Diagnostics'
_DIGEST = re.compile(r'[a-f0-9]{64}')
_EVENTS = {'client_request', 'gateway_response', 'transport_failure', 'debug_enabled', 'debug_disabled', 'gateway_started', 'gateway_stopped'}
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
    if choice(value.get('clientType'), {'android', 'desktop', 'other'}):
        result['clientType'] = value['clientType']
    return result


def sanitized_record(value):
    """Also used on reload/export, so edited local files cannot turn export into raw-log upload."""
    if not isinstance(value, dict) or not isinstance(value.get('event'), str) or value['event'] not in _EVENTS:
        return None
    result = {'schemaVersion': 1, 'event': value['event']}
    for key in ('sequence', 'timestampMs', 'recordedAtMs', 'arrivalAtMs', 'responseStartedAtMs', 'completedAtMs',
                'status', 'receivedBytes', 'sentBytes', 'durationMs', 'retryAttempt'):
        item = value.get(key)
        if type(item) is int and 0 <= item <= 2 ** 53:
            result[key] = item
    for key in ('requestDigest', 'operationDigest', 'principalDigest', 'jobDigest'):
        item = value.get(key)
        if isinstance(item, str) and _DIGEST.fullmatch(item):
            result[key] = item
    for key in ('httpRequestId', 'traceId', 'bootId', 'instanceId'):
        item = value.get(key)
        if isinstance(item, str) and HEX32.fullmatch(item):
            result[key] = item
    for key in ('spanId', 'parentSpanId'):
        item = value.get(key)
        if isinstance(item, str) and re.fullmatch('[a-f0-9]{16}', item):
            result[key] = item
    for key in ('responseHeadersSent', 'responseBodyCompleted', 'cleanShutdown', 'diagnosticTruncated'):
        if type(value.get(key)) is bool:
            result[key] = value[key]
    if choice(value.get('clientType'), {'android', 'desktop', 'desktop_manager', 'other', 'unknown'}):
        result['clientType'] = value['clientType']
    if isinstance(value.get('route'), str) and value['route'] in ROUTES:
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
    safe_identity = provenance(value.get('provenance'))
    if safe_identity:
        result['provenance'] = safe_identity
    summary = failure_fields(value.get('observation'))
    if summary:
        result['observation'] = summary
    if choice(value.get('admissionCode'), ADMISSION_CODES):
        result['admissionCode'] = value['admissionCode']
    # Leave room for sequence and write time assigned by the store. Optional
    # repeated context is shed first; never emit an oversized record silently.
    for key in ('client', 'provenance', 'parentSpanId', 'spanId', 'traceId', 'jobDigest', 'observation'):
        if len(json.dumps(result, separators=(',', ':'), ensure_ascii=True).encode()) <= MAX_LINE_BYTES - 128:
            break
        result.pop(key, None)
        result['diagnosticTruncated'] = True
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
        self.provenance = {}
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
            self.record('gateway_started', provenance=dict(self.provenance, bootId=self.boot_id, processId=os.getpid()))

    def configure_identity(self, **fields):
        with self.lock:
            self.provenance = provenance(dict(fields, bootId=self.boot_id, processId=os.getpid(), diagnosticProtocolVersion=2))

    def _ensure_writer(self):
        with self.lock:
            if self.thread is not None and self.thread.is_alive():
                return False
            self.stopped.clear()
            self.thread = threading.Thread(target=self._write_loop, name='gateway-debug-writer', daemon=True)
            self.thread.start()
            return True

    def stop(self, clean=True):
        if self.thread is not None and self.thread.is_alive() and not self.stopped.is_set():
            self.record('gateway_stopped', cleanShutdown=clean, provenance=self.provenance)
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
                self.record('debug_enabled', provenance=self.provenance)

    def record(self, event, **fields):
        with self.lock:
            if not self.enabled:
                return
            item = sanitized_record(dict(fields, event=event, bootId=self.boot_id,
                                         instanceId=self.provenance.get('instanceId')))
            if item is None:
                return
            self.sequence += 1
            written = int(self.now() * 1000)
            item.update(sequence=self.sequence, recordedAtMs=written)
            item.setdefault('timestampMs', written)
            self.records.append(item)
            self.pending.set()

    def snapshot(self):
        with self.lock:
            return {'schemaVersion': 1, 'enabled': self.enabled, 'retainedLines': len(self.records),
                    'lineLimit': MAX_LINES, 'lastSequence': self.sequence,
                    'content': 'sanitized_transport_and_observation_metadata', 'protocolVersion': 2, 'clientMetadata': True,
                    'physicalRequestIds': True, 'structuredFailures': True, 'fullBundleExport': True,
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
            occurred = fields.pop('occurred_at_utc', int(self.now() * 1000))
            event = sanitize_event(dict(fields, schema_version=2, origin_device_pseudonym=self.origin_id, origin_boot_id=self.boot_id,
                                        origin_sequence=sequence, event_id=event_identity(self.origin_id, self.boot_id, sequence),
                                        occurred_at_utc=occurred, owner='gateway'), imported=False)
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
            records = [clean for row in self.records if (clean := sanitized_record(row)) is not None]
        return encode_records(records)

    def export_bundle(self):
        """Local-admin bundle: transport, retained causal events and honest coverage/provenance."""
        if not self.export_lock.acquire(blocking=False):
            raise DiagnosticProtocolError('DIAGNOSTIC_EXPORT_BUSY', 429)
        try:
            self.flush()
            with self.lock:
                records = [clean for row in self.records if (clean := sanitized_record(row)) is not None]
                current = dict(self.provenance)
                queued, dropped = self.event_queue.qsize(), self.queue_dropped
            events, coverage = self.journal.local_snapshot()
            versions = []
            for candidate in [row.get('provenance') for row in records] + [current]:
                safe = provenance(candidate)
                if safe and safe not in versions:
                    versions.append(safe)
            builds = sorted({row['client']['appVersionCode'] for row in records
                             if isinstance(row.get('client'), dict) and type(row['client'].get('appVersionCode')) is int})
            transport = encode_records(records).encode('utf-8')
            journal = encode_records(events).encode('utf-8')
            files = {'gateway-debug.jsonl': transport, 'diagnostic-events.jsonl': journal}
            manifest = {'schemaVersion': 1, 'diagnosticProtocolVersion': 2, 'exportedAtMs': int(self.now() * 1000),
                        'scope': 'local_admin_retained_diagnostics', 'redaction': 'allowlisted_metadata_only',
                        'versions': versions[-32:], 'versionsTruncated': len(versions) > 32, 'androidBuilds': builds[-64:],
                        'transport': {'retainedCount': len(records), 'recordLimit': MAX_LINES, 'recordByteLimit': MAX_LINE_BYTES,
                                      'firstSequence': min((r.get('sequence', 0) for r in records), default=None),
                                      'lastSequence': max((r.get('sequence', 0) for r in records), default=None),
                                      'captureStartMs': min((r.get('timestampMs', 0) for r in records), default=None),
                                      'captureEndMs': max((r.get('timestampMs', 0) for r in records), default=None),
                                      'timing': 'timestampMs is occurrence time; recordedAtMs is write time; durationMs is monotonic',
                                      'httpIdentity': 'httpRequestId identifies one call; operationDigest identifies its caller operation',
                                      'historicalTiming': 'Rows without arrivalAtMs retain legacy timing semantics.'},
                        'journal': dict(coverage, queuedAtSnapshot=queued, queueDropped=dropped),
                        'executionCoverage': 'Captured receipt transitions only. Absence does not prove no inference or lost jobs.',
                        'files': [{'path': name, 'bytes': len(data), 'sha256': hashlib.sha256(data).hexdigest()}
                                  for name, data in files.items()]}
            output = io.BytesIO()
            with zipfile.ZipFile(output, 'w', compression=zipfile.ZIP_DEFLATED) as archive:
                for name, data in files.items():
                    archive.writestr(name, data)
                archive.writestr('manifest.json', json.dumps(manifest, sort_keys=True, separators=(',', ':')).encode())
            return output.getvalue()
        finally:
            self.export_lock.release()

    def export_session(self, principal, session):
        # A retained capture is bounded, but concurrent full exports multiply its
        # peak memory. Reject excess exports without blocking the event writer.
        if not self.export_lock.acquire(blocking=False):
            raise DiagnosticProtocolError('DIAGNOSTIC_EXPORT_BUSY', 429)
        try:
            return self.journal.export(principal, session, versions=self.provenance)
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
        clean = False
        try:
            async with parent(app):
                yield
            clean = True
        finally:
            await asyncio.to_thread(store.stop, clean=clean)
    return run


class DebugMiddleware:
    """Observe even admission errors; only admitted clients receive a metadata acknowledgement."""
    def __init__(self, app, store, instance_id):
        self.app, self.store, self.instance_id = app, store, instance_id
        self.now = getattr(store, 'now', time.time)

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http':
            return await self.app(scope, receive, send)
        started, arrived_at = time.monotonic(), int(self.now() * 1000)
        headers = dict(scope.get('headers', []))
        request_id = headers.get(b'x-request-id', b'').decode('latin1')
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,128}', request_id):
            request_id = uuid.uuid4().hex
        client = client_metadata(headers.get(CLIENT_HEADER.lower().encode(), b'').decode('latin1'))
        context = {'requestDigest': digest(request_id), 'operationDigest': digest(request_id),
                   'httpRequestId': uuid.uuid4().hex, 'principalDigest': digest('unavailable'),
                   'route': route_kind(scope.get('path', '')), 'method': scope.get('method', ''),
                   'arrivalAtMs': arrived_at, 'clientType': 'unknown'}
        retry = headers.get(b'x-gateway-retry-attempt', b'')
        if re.fullmatch(b'[0-9]', retry):
            context['retryAttempt'] = int(retry)
        job = headers.get(b'x-gateway-job-id', b'')
        if job:
            context['jobDigest'] = digest(job.decode('latin1'))
        observed = not scope.get('path', '').removeprefix('/v1').startswith('/gateway/admin/debug')
        observed = observed and not scope.get('path', '').removeprefix('/v1').startswith('/gateway/debug/')
        trace = trace_context(headers.get(b'traceparent', b'').decode('ascii', errors='ignore')) or {'trace_id': uuid.uuid4().hex, 'span_id': uuid.uuid4().hex[:16]}
        context.update(traceId=trace['trace_id'], spanId=trace['span_id'])
        if trace.get('parent_span_id'):
            context['parentSpanId'] = trace['parent_span_id']
        scope.setdefault('state', {})['diagnostic_trace'] = trace
        trace_marker = current_diagnostic_trace.set(trace)
        session = headers.get(b'x-gateway-diagnostic-session', b'').decode('ascii', errors='ignore')
        try:
            capture_started = int(headers.get(b'x-gateway-diagnostic-started', b''))
        except (ValueError, TypeError):
            capture_started = None
        capture_marker = current_diagnostic_capture.set({'session': session, 'started': capture_started, 'request_state': scope['state']}
                                                         if HEX32.fullmatch(session) and capture_started is not None else None)
        received = sent = 0
        status = None
        response_started = response_completed = False
        request_logged = False
        response_at = completed_at = completed_duration = None
        admission, response_body, body_oversize = None, bytearray(), False

        def attributes():
            return {'route': context['route'], 'method': context['method'],
                    'response_headers_sent': response_started, 'response_body_completed': response_completed}

        def event(stage, severity='info', error_code='NONE', **fields):
            principal = scope.get('state', {}).get('principal')
            if observed and principal is not None and HEX32.fullmatch(session) and capture_started is not None:
                self.store.record_event(principal.id, session, capture_started, stage=stage, severity=severity,
                                        error_code=error_code, request_id=context['httpRequestId'],
                                        operation_id=context['operationDigest'], **trace, **fields)

        def record_request():
            nonlocal request_logged
            if not observed or request_logged:
                return
            principal = scope.get('state', {}).get('principal')
            context['principalDigest'] = digest(getattr(principal, 'id', 'unavailable'))
            if principal:
                context['clientType'] = ('desktop_manager' if principal.id == 'local:manager' else
                                         'android' if type(client.get('androidSdk')) is int else client.get('clientType', 'unknown'))
            self.store.record('client_request', timestampMs=arrived_at, client=client if principal else {}, **context)
            event('request', direction='client_to_gateway', dispatch_state='dispatched', category='transport',
                  occurred_at_utc=arrived_at, bounded_attributes=attributes())
            request_logged = True

        async def observed_receive():
            nonlocal received
            message = await receive()
            received += len(message.get('body', b''))
            return message

        async def observed_send(message):
            nonlocal status, sent, response_started, response_completed, response_at, completed_at, completed_duration, admission, body_oversize
            if message['type'] == 'http.response.start':
                status = message['status']
                additions = {b'x-gateway-instance-id': self.instance_id.encode(), b'x-gateway-debug-protocol': b'2',
                             b'x-gateway-http-request-id': context['httpRequestId'].encode('ascii'),
                             b'x-gateway-debug-enabled': b'true' if self.store.enabled else b'false',
                             b'traceparent': f"00-{trace['trace_id']}-{trace['span_id']}-01".encode('ascii')}
                if trace.get('parent_span_id'):
                    additions[b'x-gateway-client-span-id'] = trace['parent_span_id'].encode('ascii')
                if client and self.store.enabled and scope.get('state', {}).get('principal'):
                    additions[b'x-gateway-client-diagnostics-accepted'] = b'1'
                prior = list(message.get('headers', []))
                admission = next((v.decode('ascii', errors='ignore') for k, v in prior if k.lower() == b'x-gateway-admission-error'), None)
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
                response_at = int(self.now() * 1000)
            elif message['type'] == 'http.response.body':
                body = message.get('body', b'')
                sent += len(body)
                metadata = context['route'] in {'models', 'readiness', 'capabilities', 'backend_health', 'backend_properties', 'maintenance'}
                metadata = metadata or context['route'] == 'engineering' and (status >= 400 or scope.get('path', '').endswith('/capabilities'))
                if observed and self.store.enabled and metadata and not body_oversize:
                    if len(response_body) + len(body) <= 65536:
                        response_body.extend(body)
                    else:
                        response_body.clear()
                        body_oversize = True
                response_completed = not message.get('more_body', False)
                if response_completed:
                    completed_at = int(self.now() * 1000)
                    completed_duration = max(0, int((time.monotonic() - started) * 1000))

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
                    body = None
                    if response_completed and response_body and not body_oversize:
                        try:
                            body = json.loads(response_body)
                        except (ValueError, UnicodeDecodeError, RecursionError):
                            pass
                    summary = response_summary(body, admission)
                    duration = completed_duration if completed_duration is not None else max(0, int((time.monotonic() - started) * 1000))
                    self.store.record('gateway_response', status=status, receivedBytes=received, sentBytes=sent,
                                      timestampMs=completed_at or int(self.now() * 1000), responseStartedAtMs=response_at,
                                      completedAtMs=completed_at, responseHeadersSent=response_started, responseBodyCompleted=response_completed,
                                      durationMs=duration, observation=summary,
                                      admissionCode=admission, diagnosticTruncated=body_oversize, **context)
                    receipt = {**attributes(), 'status': status, 'received_bytes': received, 'sent_bytes': sent}
                    receipt.update({key: value for key, value in {
                        'gateway_admission_code': admission, 'failure_code': summary.get('failureCode'),
                        'reason_code': summary.get('reasonCode'), 'dependency': summary.get('dependency'),
                        'upstream_status': summary.get('upstreamStatus')}.items() if value is not None})
                    restricted = admission == 'remote_surface_not_admitted'
                    severity = 'info' if restricted else 'error' if status >= 500 else 'warning' if status >= 400 or summary.get('failureCode') else 'info'
                    code = 'AUTHORIZATION_FAILED' if status in {401, 403} else 'HTTP_ERROR' if status >= 400 else 'SOURCE_UNAVAILABLE' if summary.get('failureCode') else 'NONE'
                    event('response', severity=severity, error_code=code,
                          direction='gateway_to_client', category='transport', dispatch_state='response_received',
                          occurred_at_utc=completed_at if completed_at is not None else int(self.now() * 1000),
                          local_duration_ms=duration,
                          bounded_attributes=receipt)
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
    def export(format: str = 'jsonl'):
        if format == 'jsonl':
            return Response(store.export(), media_type='application/x-ndjson', headers={
                'Content-Disposition': 'attachment; filename="gateway-debug.jsonl"', 'Cache-Control': 'no-store'})
        if format != 'zip':
            return JSONResponse({'error': {'code': 'invalid_debug_export_format'}}, status_code=400)
        try:
            return Response(store.export_bundle(), media_type='application/zip', headers={
                'Content-Disposition': 'attachment; filename="gateway-debug.zip"', 'Cache-Control': 'no-store'})
        except DiagnosticProtocolError as failure:
            return error(failure)
