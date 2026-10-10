"""Separate process liveness, chat readiness and local diagnostics."""
import sqlite3

from v14.journal import SCHEMA_VERSION, schema_version


def readiness(runtime, state):
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
    try:
        backend = 'ready' if runtime.http_get(runtime.LLAMA_BASE + '/health', timeout=(2, 3)).status_code == 200 else 'unavailable'
    except runtime.requests.RequestException:
        backend = 'unreachable'
    ready = not service['draining'] and journal in {'ready', 'disabled'} and backend == 'ready'
    return {'ready': ready, 'version': runtime.GATEWAY_VERSION, 'checks': {'journal': journal, 'backend': backend},
            'service': service, 'durableJobs': runtime.GATEWAY_DURABLE_JOBS}


def diagnostics(runtime, state):
    result = readiness(runtime, state)
    result['sqliteVersion'] = sqlite3.sqlite_version
    result['journalSchemaVersion'] = SCHEMA_VERSION
    result['journalSynchronous'] = 'FULL'
    with runtime.mcp_sessions_lock:
        result['mcp'] = {name: {'alive': session.is_alive(), 'protocol': session.protocol_version,
                               'circuit': session.circuit.snapshot()} for name, session in runtime.mcp_sessions.items()}
    return result
