"""Terminal-state guard and explicit locally approved legacy ownership migration."""
import re
from collections import deque
import time

TERMINAL = {"completed", "failed", "cancelled", "canceled", "interrupted"}


def install_terminal_guard(runtime):
    install_strict_journal(runtime)
    finish = runtime.finish_gateway_job
    update = runtime.update_gateway_job
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
            return update(job_id, *args, **kwargs)
    runtime.finish_gateway_job = guarded_finish
    runtime.update_gateway_job = guarded_update


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


def install_strict_journal(runtime):
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
                finally:
                    conn.close()
        except Exception as exc:
            runtime._durable_metric("failures")
            raise DurableCommitError("Durable job commit failed; completion was not acknowledged") from exc
    runtime.durable_persist_job = persist
