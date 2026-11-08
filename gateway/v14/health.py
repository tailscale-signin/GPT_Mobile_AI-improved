"""Separate process liveness, model availability, generation readiness and metadata access."""
import hashlib
import json
import os
import sqlite3
import threading
import time
from collections import OrderedDict
from contextlib import closing

from v14.journal import SCHEMA_VERSION, schema_version
from v14.readiness import observations
from v14.backend_observation import (backend_root, exception_failure, failure, http_failure,
                                     read_metadata, MetadataFailure, record_backend_result)
from v14.execution_binding import ExecutionBindingError


def bounded_metadata(response, limit=1024 * 1024):
    raw = bytearray()
    deadline = time.monotonic() + 2
    for chunk in response.iter_content(8192):
        if len(raw) + len(chunk) > limit or time.monotonic() > deadline:
            raise ValueError('Runtime metadata exceeds its observation budget')
        raw.extend(chunk)
    return json.loads(raw)


class BackendReadiness:
    """Short per-model singleflight cache; changing profiles does not evict another model's probe."""
    def __init__(self, now=time.monotonic):
        self.now = now
        self.lock = threading.RLock()
        self.entries = OrderedDict()
        self.refreshes = {}

    def inspect(self, runtime, model=''):
        # Include the read function identity so injected transports/tests cannot reuse old evidence.
        transport = runtime.http_get
        key = (runtime.LLAMA_BASE, model, os.getenv('GATEWAY_INFERENCE_CONFIG_REVISION'),
               getattr(runtime, 'GATEWAY_INFERENCE_PROVIDER', None), os.getenv('GATEWAY_INFERENCE_PROVIDER'),
               getattr(runtime, 'gateway_backend_recovery_epoch', 0), id(transport))
        with self.lock:
            entry = self.entries.get(key)
            if entry and entry[0] > self.now():
                return self._observation(entry)
            gate, subscribers = self.refreshes.get(key, (threading.Lock(), 0))
            self.refreshes[key] = gate, subscribers + 1
        try:
            with gate:
                with self.lock:
                    entry = self.entries.get(key)
                    if entry and entry[0] > self.now():
                        return self._observation(entry)
                observed = self.now()
                observed_at = int(time.time() * 1000)
                result = self._probe(runtime, model)
                result['observedAtMs'] = observed_at
                with self.lock:
                    # Keep the transport alive for this bounded entry. Otherwise
                    # Python can reuse its id and lend a new transport old evidence.
                    entry = self.now() + 3, result, observed, transport
                    self.entries[key] = entry
                    self.entries.move_to_end(key)
                    while len(self.entries) > 64:
                        self.entries.popitem(last=False)
                return self._observation(entry)
        finally:
            with self.lock:
                current, count = self.refreshes[key]
                if count == 1:
                    self.refreshes.pop(key)
                else:
                    self.refreshes[key] = current, count - 1

    def _observation(self, entry):
        result = dict(entry[1])
        result['observationAgeMs'] = max(0, int((self.now() - entry[2]) * 1000))
        return result

    def _probe(self, runtime, model):
        result = {'networkReachable': False, 'modelAvailable': None, 'generationReady': None,
                  'metadataAccess': 'unknown', 'healthHttpStatus': None, 'modelsHttpStatus': None,
                  'backend': 'unreachable', 'inferenceVerified': None,
                  'readinessEvidence': 'health_and_model_catalog'}
        try:
            root = backend_root(runtime)
        except ExecutionBindingError as error:
            result.update(exception_failure(error))
            return result
        params = {'autoload': 'false'}
        if model:
            params['model'] = model
            result['selectedModelDigest'] = hashlib.sha256(model.encode()).hexdigest()
        try:
            with closing(runtime.http_get(root + '/health', params=params, timeout=(0.5, 1), stream=True, allow_redirects=False)) as response:
                status = response.status_code
                result.update(networkReachable=True, healthHttpStatus=status)
                if status == 200:
                    result.update(generationReady=True, metadataAccess='available', backend='ready')
                elif status in {401, 403}:
                    # A restricted administrative surface is reachable; do not call generation broken.
                    result.update(metadataAccess='restricted', backend='restricted')
                    result['healthFailure'] = http_failure(status, 'backend_health')
                else:
                    result.update(generationReady=False, metadataAccess='available', backend='unavailable')
                    result['healthFailure'] = http_failure(status, 'backend_health')
                    if status == 503:
                        try:
                            body = bounded_metadata(response, 65536)
                            error = body.get('error', {}) if isinstance(body, dict) else {}
                            phase = body.get('status') if isinstance(body, dict) else None
                            message = error.get('message') if isinstance(error, dict) else None
                            if phase in {'loading', 'loading model'} or message == 'Loading model':
                                result.update(backend='loading', modelLoading=True)
                        except (ValueError, TypeError):
                            pass
        except runtime.requests.RequestException as error:
            result['healthFailure'] = exception_failure(error, 'backend_health')
            # Drop a stale socket for the next bounded read, never replay a chat POST here.
            reset = getattr(runtime, 'reset_http_session', None)
            if reset:
                reset()
        if model or result['metadataAccess'] == 'restricted':
            try:
                with closing(runtime.http_get(root + '/v1/models', params=params, timeout=(0.5, 1), stream=True, allow_redirects=False)) as response:
                    result.update(networkReachable=True, modelsHttpStatus=response.status_code)
                    if response.status_code == 200:
                        try:
                            body = read_metadata(response, 'backend_models')
                            values = body.get('data') if isinstance(body, dict) else None
                            if isinstance(values, list) and len(values) <= 1024 and all(isinstance(item, dict) and isinstance(item.get('id'), str) for item in values):
                                result['modelAvailable'] = any(item['id'] == model for item in values) if model else bool(values)
                            else:
                                result['modelsFailure'] = failure('invalid_response', 'backend_models')
                        except MetadataFailure as error:
                            result['modelsFailure'] = error.observation
                        except (ValueError, TypeError):
                            result['modelsFailure'] = failure('invalid_response', 'backend_models')
                    else:
                        result['modelsFailure'] = http_failure(response.status_code, 'backend_models')
            except runtime.requests.RequestException as error:
                result['modelsFailure'] = exception_failure(error, 'backend_models')
        if result.get('modelLoading'):
            result.update(failure('model_loading', 'backend_health', result['healthHttpStatus']))
        elif result['modelAvailable'] is False:
            result.update(failure('model_unavailable', 'selected_model'))
        elif result.get('healthFailure'):
            result.update(result['healthFailure'])
        elif result.get('modelsFailure'):
            result.update(result['modelsFailure'])
        elif result['generationReady'] is not True:
            result.update(failure('unverified', 'backend_health'))
        elif model and result['modelAvailable'] is not True:
            result.update(failure('unverified', 'backend_models'), reasonCode='SELECTED_MODEL_UNVERIFIED')
        else:
            result.update(reasonCode=None, retryable=False)
        # Recovery is transport evidence. A missing/loading selected model must
        # not flip this global epoch and invalidate another profile's cache.
        record_backend_result(runtime, result['networkReachable'])
        return result


_backend_readiness = BackendReadiness()


def readiness(runtime, state, model=''):
    service = state.snapshot()
    journal = 'disabled' if not runtime.GATEWAY_DURABLE_JOBS else 'unavailable'
    if runtime.GATEWAY_DURABLE_JOBS and runtime.durable_job_initialized:
        try:
            with runtime.durable_job_lock:
                conn = runtime._durable_connect()
                try:
                    valid = schema_version(conn) == SCHEMA_VERSION
                    conn.execute('SELECT job_id FROM gateway_jobs LIMIT 1').fetchone()
                    journal = 'ready' if valid and getattr(runtime, 'durable_write_healthy', True) else 'write_failed'
                finally:
                    conn.close()
        except (sqlite3.Error, ValueError):
            journal = 'unavailable'
    backend = _backend_readiness.inspect(runtime, model)
    catalogue = getattr(runtime, 'gateway_catalogue', None)
    backend.update(catalogue.readiness() if catalogue and hasattr(catalogue, 'readiness') else {
        'toolsReady': None, 'toolsState': 'NOT_MEASURED', 'toolCount': None,
        'toolConnectionCount': None})
    ready = (not service['draining'] and journal in {'ready', 'disabled'} and backend['generationReady'] is True
             and (not model or backend['modelAvailable'] is True))
    can_queue = bool(ready and journal == 'ready' and getattr(runtime, 'gateway_durable_queue_installed', False)
                     and service.get('queueAvailable') is True)
    backend.update(canQueue=can_queue, canAccept=bool(ready and (
        service.get('activeWorkers', 0) < service.get('maxWorkers', 0) or can_queue)))
    reason = ('GATEWAY_DRAINING' if service['draining'] else
              'DURABLE_STORAGE_WRITE_FAILED' if journal == 'write_failed' else
              'DURABLE_STORAGE_UNAVAILABLE' if journal not in {'ready', 'disabled'} else backend.get('reasonCode'))
    return {'schemaVersion': 1, 'ready': ready, 'version': runtime.GATEWAY_VERSION,
            'reasonCode': None if ready else reason, 'retryable': False if ready else
                bool(service['draining'] or journal not in {'ready', 'disabled'} or backend.get('retryable')),
            'serverTimeMs': int(time.time() * 1000),
            'instanceId': state.instance_id, 'gateway_id': runtime.gateway_conversations.gateway_id() if getattr(runtime, 'gateway_conversations', None) and runtime.GATEWAY_DURABLE_JOBS and journal == 'ready' else None, 'checks': {'journal': journal, 'backend': backend['backend']},
            'capabilities': backend, 'service': service, 'durableJobs': runtime.GATEWAY_DURABLE_JOBS,
            'components': observations(runtime, service, backend, journal)}


def diagnostics(runtime, state):
    result = readiness(runtime, state)
    result['catalogue'] = dict(getattr(getattr(runtime, 'gateway_catalogue', None), 'health', {}))
    result['schemaRejections'] = dict(getattr(runtime, 'gateway_schema_rejections', {}))
    performance = getattr(runtime, 'gateway_github_performance', None)
    reads = getattr(runtime, 'gateway_github_reads', None)
    result['github'] = {'modelRounds': performance.snapshot() if performance else {},
                        'immutableReads': reads.snapshot() if reads else {}}
    result['schemaMetrics'] = dict(getattr(runtime, 'gateway_schema_metrics', {}))
    result['sqliteVersion'] = sqlite3.sqlite_version
    result['journalSchemaVersion'] = SCHEMA_VERSION
    result['journalSynchronous'] = 'FULL'
    with runtime.mcp_sessions_lock:
        result['mcp'] = {name: {'alive': session.is_alive(), 'protocol': session.protocol_version,
                               'circuit': session.circuit.snapshot()} for name, session in runtime.mcp_sessions.items()}
    return result
