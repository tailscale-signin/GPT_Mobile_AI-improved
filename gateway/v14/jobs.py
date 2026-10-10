"""Terminal-state guard and explicit locally approved legacy ownership migration."""
import json
import re
from collections import deque
import time
from v14.journal import preflight, record_schema, schema_version

TERMINAL = {"completed", "failed", "cancelled", "canceled", "interrupted"}


def install_terminal_guard(runtime):
    install_strict_journal(runtime)
    finish = runtime.finish_gateway_job
    update = runtime.update_gateway_job
    register = runtime.register_gateway_job
    def guarded_register(job_id, *args, **kwargs):
        with runtime.job_registry_lock:
            previous = runtime.job_registry.get(job_id)
            if previous is not None and previous.get('status') in TERMINAL:
                raise ValueError('Terminal jobs cannot be resumed; submit a new request without its old job ID')
            snapshot = dict(previous) if previous is not None else None
            try:
                return register(job_id, *args, **kwargs)
            except Exception:
                if previous is None:
                    runtime.job_registry.pop(job_id, None)
                else:
                    previous.clear()
                    previous.update(snapshot)
                raise
    def guarded_finish(job_id, status_code, result, error=None):
        # Hold the same reentrant registry lock through persistence so a racing
        # late disconnect cannot overwrite a committed terminal result.
        with runtime.job_registry_lock:
            job = runtime.job_registry.get(job_id)
            if job and job.get("status") in TERMINAL:
                return
            snapshot = dict(job) if job else None
            if snapshot and isinstance(snapshot.get("_segment_results"), deque):
                snapshot["_segment_results"] = deque(snapshot["_segment_results"], maxlen=snapshot["_segment_results"].maxlen)
            try:
                return finish(job_id, status_code, result, error)
            except Exception:
                if job is not None and snapshot is not None:
                    job.clear()
                    job.update(snapshot)
                raise
    def guarded_update(job_id, *args, **kwargs):
        with runtime.job_registry_lock:
            job = runtime.job_registry.get(job_id)
            if job and job.get("status") in TERMINAL:
                return
            snapshot = dict(job) if job else None
            if snapshot and isinstance(snapshot.get('_events'), deque):
                snapshot['_events'] = deque(snapshot['_events'], maxlen=snapshot['_events'].maxlen)
            try:
                return update(job_id, *args, **kwargs)
            except Exception:
                if job is not None and snapshot is not None:
                    job.clear()
                    job.update(snapshot)
                raise
    runtime.finish_gateway_job = guarded_finish
    runtime.update_gateway_job = guarded_update
    runtime.register_gateway_job = guarded_register


def migrate_legacy_owner(store, device_id, principal_id):
    if not re.fullmatch(r"[a-f0-9]{32}", device_id) or not (principal_id == "local:single-user" or principal_id.startswith("tailnet:")):
        raise ValueError("Supply a legacy device ID and an explicit admitted principal")
    with store.connect() as db:
        db.execute("BEGIN IMMEDIATE")
        if not db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone():
            raise ValueError("Unknown legacy device")
        return db.execute("UPDATE ownership SET device_id=? WHERE device_id=?", (principal_id, device_id)).rowcount


class DurableCommitError(RuntimeError):
    pass


def upgrade_legacy_journal(conn):
    """Add missing timestamp columns without rewriting saved JSON or events.

    CREATE TABLE IF NOT EXISTS in the retained initializer cannot upgrade an
    existing journal. Run before its cleanup/recovery, in one rollbackable
    transaction. Unknown core schemas fail closed rather than discarding data.
    """
    schemas = {
        "gateway_jobs": ({"job_id", "public_json", "result_json", "status", "updated_at"},
                         ("created_at", "finished_at"), "public_json"),
        "gateway_events": ({"job_id", "sequence", "event_json"}, ("created_at",), "event_json"),
    }
    with conn:
        conn.execute("BEGIN IMMEDIATE")
        version = schema_version(conn)
        for table, (required, optional, payload_column) in schemas.items():
            columns = {row[1] for row in conn.execute(f"PRAGMA table_info({table})")}
            if not columns:
                continue  # The retained initializer creates new tables.
            if not required.issubset(columns):
                raise ValueError(f"Unsupported {table} schema: missing {', '.join(sorted(required - columns))}")
            missing = [column for column in optional if column not in columns]
            if version and missing:
                raise ValueError('Versioned journal schema does not match its declared version')
            for column in missing:
                conn.execute(f"ALTER TABLE {table} ADD COLUMN {column} REAL")
            if not missing:
                continue
            keys = ("job_id", "sequence") if table == "gateway_events" else ("job_id",)
            rows = conn.execute(f"SELECT {', '.join(keys)}, {payload_column} FROM {table}").fetchall()
            for row in rows:
                try:
                    payload = json.loads(row[len(keys)] or "{}")
                except (ValueError, TypeError):
                    continue
                if not isinstance(payload, dict):
                    continue
                for column in missing:
                    value = payload.get(column)
                    if isinstance(value, (int, float)) and not isinstance(value, bool):
                        where = " AND ".join(f"{key}=?" for key in keys)
                        conn.execute(f"UPDATE {table} SET {column}=? WHERE {where}",
                                     (value, *[row[i] for i in range(len(keys))]))
        record_schema(conn)


def install_journal_upgrade(runtime):
    initialize = runtime.init_durable_job_store
    connect = runtime._durable_connect
    def durable_connect():
        conn = connect()
        try:
            conn.execute('PRAGMA synchronous=FULL')
            return conn
        except Exception:
            conn.close()
            raise
    runtime._durable_connect = durable_connect
    def upgraded_initialize():
        if not runtime.GATEWAY_DURABLE_JOBS:
            return False
        with runtime.durable_job_lock:
            if runtime.durable_job_initialized:
                return True
            try:
                conn = runtime._durable_connect()
                try:
                    backup = preflight(conn, runtime.GATEWAY_JOB_DB_PATH)
                    if backup:
                        runtime.logger.info('Pre-upgrade journal snapshot saved: %s', backup)
                    upgrade_legacy_journal(conn)
                finally:
                    conn.close()
            except Exception as exc:
                runtime._durable_metric("failures")
                raise DurableCommitError("Durable journal schema upgrade failed; existing data was retained") from exc
            if not initialize():
                raise DurableCommitError("Durable job store unavailable")
            runtime.durable_write_healthy = True
            return True
    runtime.init_durable_job_store = upgraded_initialize


def install_strict_journal(runtime):
    install_journal_upgrade(runtime)
    def persist(job_id, event=None):
        if not runtime.GATEWAY_DURABLE_JOBS:
            return
        if not runtime.durable_job_initialized and not runtime.init_durable_job_store():
            raise DurableCommitError("Durable job store unavailable")
        with runtime.job_registry_lock:
            job = runtime.job_registry.get(job_id)
            if job is None:
                return
            public, result = runtime._persisted_job_payload(job)
        try:
            with runtime.durable_job_lock:
                conn = runtime._durable_connect()
                try:
                    with conn:
                        conn.execute("INSERT INTO gateway_jobs(job_id,public_json,result_json,status,created_at,updated_at,finished_at) VALUES(?,?,?,?,?,?,?) ON CONFLICT(job_id) DO UPDATE SET public_json=excluded.public_json,result_json=excluded.result_json,status=excluded.status,updated_at=excluded.updated_at,finished_at=excluded.finished_at",
                            (job_id, runtime._json_safe_dump(public), runtime._json_safe_dump(result) if result is not None else None,
                             public.get("status"), public.get("created_at"), public.get("updated_at"), public.get("finished_at")))
                        if isinstance(event, dict) and isinstance(event.get("sequence"), int) and event["sequence"] > 0:
                            conn.execute("INSERT OR REPLACE INTO gateway_events(job_id,sequence,event_json,created_at) VALUES(?,?,?,?)",
                                (job_id,event["sequence"],runtime._json_safe_dump(event),time.time()))
                            conn.execute("DELETE FROM gateway_events WHERE job_id=? AND sequence NOT IN (SELECT sequence FROM gateway_events WHERE job_id=? ORDER BY sequence DESC LIMIT ?)",
                                (job_id,job_id,runtime.DURABLE_JOB_EVENT_MAX))
                    runtime.durable_job_metrics["writes"] += 1
                    runtime.durable_write_healthy = True
                    if isinstance(event, dict) and isinstance(event.get("sequence"), int) and event["sequence"] > 0:
                        runtime.durable_job_metrics["event_writes"] += 1
                finally:
                    conn.close()
        except Exception as exc:
            runtime.durable_write_healthy = False
            runtime._durable_metric("failures")
            raise DurableCommitError("Durable job commit failed; completion was not acknowledged") from exc
    runtime.durable_persist_job = persist
