"""Separate process liveness, model availability, generation readiness and metadata access."""
import hashlib
import sqlite3
import threading
import time
from collections import OrderedDict

from v14.journal import SCHEMA_VERSION, schema_version


class BackendReadiness:
    """Short per-model singleflight cache; changing profiles does not evict another model's probe."""
    def __init__(self, now=time.monotonic):
        self.now = now
        self.lock = threading.RLock()
        self.entries = OrderedDict()
        self.refreshes = {}

    def inspect(self, runtime, model=''):
        # Include the read function identity so injected transports/tests cannot reuse old evidence.
        key = (runtime.LLAMA_BASE, model, id(runtime.http_get))
        with self.lock:
            entry = self.entries.get(key)
            if entry and entry[0] > self.now():
                return dict(entry[1])
            gate, subscribers = self.refreshes.get(key, (threading.Lock(), 0))
            self.refreshes[key] = gate, subscribers + 1
        try:
            with gate:
                with self.lock:
                    entry = self.entries.get(key)
                    if entry and entry[0] > self.now():
                        return dict(entry[1])
                result = self._probe(runtime, model)
                with self.lock:
                    self.entries[key] = self.now() + 3, result
                    self.entries.move_to_end(key)
                    while len(self.entries) > 64:
                        self.entries.popitem(last=False)
                return dict(result)
        finally:
            with self.lock:
                current, count = self.refreshes[key]
                if count == 1:
                    self.refreshes.pop(key)
                else:
                    self.refreshes[key] = current, count - 1

    def _probe(self, runtime, model):
        result = {'networkReachable': False, 'modelAvailable': None, 'generationReady': None,
                  'metadataAccess': 'unknown', 'healthHttpStatus': None, 'modelsHttpStatus': None,
                  'backend': 'unreachable', 'inferenceVerified': None,
                  'readinessEvidence': 'health_and_model_catalog'}
        params = {'autoload': 'false'}
        if model:
            params['model'] = model
            result['selectedModelDigest'] = hashlib.sha256(model.encode()).hexdigest()
        try:
            response = runtime.http_get(runtime.LLAMA_BASE + '/health', params=params, timeout=(0.5, 1))
            status = response.status_code
            result.update(networkReachable=True, healthHttpStatus=status)
            if status == 200:
                result.update(generationReady=True, metadataAccess='available', backend='ready')
            elif status in {401, 403}:
                # A restricted administrative surface is reachable; do not call generation broken.
                result.update(metadataAccess='restricted', backend='restricted')
            else:
                result.update(generationReady=False, metadataAccess='available', backend='unavailable')
            response.close()
        except runtime.requests.RequestException:
            # Drop a stale socket for the next bounded read, never replay a chat POST here.
            reset = getattr(runtime, 'reset_http_session', None)
            if reset:
                reset()
        if model or result['metadataAccess'] == 'restricted':
            try:
                response = runtime.http_get(runtime.LLAMA_BASE + '/v1/models', params=params, timeout=(0.5, 1))
                result.update(networkReachable=True, modelsHttpStatus=response.status_code)
                if response.status_code == 200:
                    try:
                        body = response.json()
                        values = body.get('data') if isinstance(body, dict) else None
                        if isinstance(values, list) and all(isinstance(item, dict) and isinstance(item.get('id'), str) for item in values):
                            result['modelAvailable'] = any(item['id'] == model for item in values) if model else bool(values)
                    except (ValueError, TypeError):
                        pass
                response.close()
            except runtime.requests.RequestException:
                pass
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
    return {'schemaVersion': 1, 'ready': ready, 'version': runtime.GATEWAY_VERSION,
            'instanceId': state.instance_id, 'checks': {'journal': journal, 'backend': backend['backend']},
            'capabilities': backend, 'service': service, 'durableJobs': runtime.GATEWAY_DURABLE_JOBS}


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
