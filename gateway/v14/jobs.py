"""Terminal-state guard and explicit locally approved legacy ownership migration."""
import json
import re
from collections import deque
import time
from v14.journal import preflight, record_schema, schema_version
from gateway_v13_runtime import GatewayContractError

TERMINAL = {"completed", "failed", "cancelled", "canceled", "interrupted"}


def install_terminal_guard(runtime):
    from v14.conversations import Conversations
    runtime.gateway_conversations = Conversations(runtime)
    install_strict_journal(runtime)
    finish = runtime.finish_gateway_job
    update = runtime.update_gateway_job
    register = runtime.register_gateway_job
    cancel = runtime.cancel_gateway_job
    disconnect = runtime.mark_gateway_job_disconnected
    def guarded_register(job_id, *args, **kwargs):
        with runtime.job_registry_lock:
            previous = runtime.job_registry.get(job_id)
            model = args[0] if args else kwargs.get('model')
            if previous is not None and previous.get('model') and model and previous['model'] != model:
                raise GatewayContractError('Continue this job using its original admitted model.', 'job_binding_changed', 409,
                    details={'job_id': job_id, 'failed_stage': 'job_admission', 'dispatch_state': 'not_dispatched'})
            if previous is not None and previous.get('status') in TERMINAL:
                raise GatewayContractError('This gateway job is already terminal. Retrieve its saved result or start a new request without the old job ID.', 'terminal_job_not_resumable', 409,
                    details={'job_id': job_id, 'terminal_status': previous.get('status'),
                             'terminal_status_code': previous.get('status_code'),
                             'result_available': previous.get('_result') is not None,
                             'failed_stage': 'tool_continuation', 'dispatch_state': 'not_dispatched'})
            from v14.conversations import admission_context, ConversationConflict
            from v14.conversation_policy import retained_policy
            context = admission_context.get()
            if previous is not None and context and retained_policy(previous) != context.get('retention', 'full'):
                raise GatewayContractError('Continue this job with its originally admitted conversation retention policy.', 'job_retention_changed', 409,
                    details={'job_id': job_id, 'failed_stage': 'job_admission', 'dispatch_state': 'not_dispatched'})
            admitted_profile = ((context['payload'].get('gateway') or {}).get('profile_id') or
                                (context.get('profile') or {}).get('profile_id')) if context else None
            if previous is not None and previous.get('profile_id') and admitted_profile and previous['profile_id'] != admitted_profile:
                raise GatewayContractError('Continue this job using its original admitted profile.', 'job_binding_changed', 409,
                    details={'job_id': job_id, 'failed_stage': 'job_admission', 'dispatch_state': 'not_dispatched'})
            if previous is not None and context and bool(previous.get('_ephemeral')) != ((context['payload'].get('gateway') or {}).get('store_conversation') is False):
                raise ConversationConflict('An active job cannot change its transcript privacy mode')
            snapshot = dict(previous) if previous is not None else None
            if previous is not None:
                previous['_admission_pending'] = True
            try:
                result = register(job_id, *args, **kwargs)
                if previous is None and isinstance(result, dict):
                    # Volatile birth marker: an older persisted job with unknown
                    # history must never acquire an entire-job isolation proof.
                    result['_server_isolation_new'] = True
                if isinstance(result, dict) and not result.get('_ephemeral') and runtime.GATEWAY_DURABLE_JOBS and runtime.durable_job_initialized:
                    # register() has returned only after the strict SQLite commit.
                    # This volatile admission proof belongs to the current worker;
                    # restart recovery still reconciles stored outcomes, never replays.
                    result['_durable_background_admitted'] = True
                return result
            except Exception:
                if previous is None:
                    runtime.job_registry.pop(job_id, None)
                else:
                    previous.clear()
                    previous.update(snapshot)
                raise
    def guarded_finish(job_id, status_code, result, error=None, *, expected_generation=None):
        # Hold the same reentrant registry lock through persistence so a racing
        # late disconnect cannot overwrite a committed terminal result.
        with runtime.job_registry_lock:
            if isinstance(result, dict) and result.get('_gateway_deferred') is True:
                return
            job = runtime.job_registry.get(job_id)
            if job is None:
                job = restore_completion_identity(runtime, job_id, expected_generation)
                runtime.job_registry[job_id] = job
            if expected_generation is not None and job.get('generation') != expected_generation:
                raise DurableCommitError('The completing worker no longer owns this job generation')
            if job and job.get("status") in TERMINAL:
                return
            snapshot = dict(job) if job else None
            if snapshot and isinstance(snapshot.get("_segment_results"), deque):
                snapshot["_segment_results"] = deque(snapshot["_segment_results"], maxlen=snapshot["_segment_results"].maxlen)
            try:
                return finish(job_id, status_code, result, error, expected_generation=expected_generation)
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
    def guarded_disconnect(job_id):
        # A failed commit must leave the transition available for a later
        # disconnect to retry. Keep the lock through commit/rollback so a
        # reconnect or completion cannot be replaced by the old snapshot.
        with runtime.job_registry_lock:
            job = runtime.job_registry.get(job_id)
            snapshot = dict(job) if job is not None else None
            try:
                return disconnect(job_id)
            except Exception:
                if job is not None and snapshot is not None:
                    job.clear()
                    job.update(snapshot)
                raise
    runtime.finish_gateway_job = guarded_finish
    runtime.update_gateway_job = guarded_update
    runtime.register_gateway_job = guarded_register
    runtime.mark_gateway_job_disconnected = guarded_disconnect

    def guarded_cancel(job_id):
        runtime.ensure_gateway_job_loaded(job_id)
        with runtime.job_registry_lock:
            if cancel_undispatched_job(runtime, job_id):
                return {'job_id': job_id, 'canceled': True, 'cancellationRequested': False,
                        'cancellationOutcome': 'confirmed', 'status': 'cancelled',
                        'message': 'Job cancelled before dispatch'}
            job = runtime.job_registry.get(job_id)
            snapshot = dict(job) if job is not None else None
            if job is not None:
                job['cancellation_requested'] = True
            try:
                return cancel(job_id)
            except Exception:
                if job is not None and snapshot is not None:
                    job.clear()
                    job.update(snapshot)
                raise
    runtime.cancel_gateway_job = guarded_cancel

    def continue_disconnected(job_id):
        if job_id is None:
            from v14.actions import current_gateway_job_id
            job_id = current_gateway_job_id.get()
        with runtime.job_registry_lock:
            job = runtime.job_registry.get(job_id)
            return bool(runtime.GATEWAY_DURABLE_JOBS and job is not None
                        and job.get('_durable_background_admitted') is True
                        and job.get('status') not in TERMINAL
                        and not (job.get('_hard_cancel_event') and job['_hard_cancel_event'].is_set()))
    runtime.gateway_continue_disconnected = continue_disconnected


def cancel_undispatched_job(runtime, job_id):
    """Confirm Stop only while the same durable generation proves no dispatch."""
    if not runtime.GATEWAY_DURABLE_JOBS:
        return False
    with runtime.job_registry_lock, runtime.durable_job_lock:
        job = runtime.job_registry.get(job_id)
        if not job or job.get('status') != 'queued' or not job.get('generation'):
            return False
        row = runtime.gateway_conversations.read(lambda db: db.execute(
            'SELECT a.dispatch_state,a.payload_json,j.public_json FROM gateway_generation_admissions a '
            'JOIN gateway_jobs j ON j.job_id=a.job_id WHERE a.job_id=?', (job_id,)).fetchone())
        if not row or row[0] != 'queued' or row[1] is None:
            return False
        saved = json.loads(row[2])
        if saved.get('status') != 'queued' or saved.get('generation') != job['generation']:
            return False
        snapshot = dict(job)
        job['cancellation_requested'] = False
        try:
            runtime.finish_gateway_job(job_id, 499, None, error='Cancelled before worker dispatch',
                                       expected_generation=job['generation'])
        except Exception:
            job.clear()
            job.update(snapshot)
            raise
        signal = job.get('_hard_cancel_event')
        if signal is not None:
            signal.set()
        return True


def record_execution_isolation(runtime, job_id, client_owned):
    """Persist a monotonic proof from the actual execution branch, never request/model metadata."""
    if not job_id:
        return
    with runtime.job_registry_lock:
        job = runtime.job_registry.get(job_id)
        if job is None or job.get('status') in TERMINAL:
            return
        snapshot = dict(job)
        prior_proof = (job.get('execution_isolation_schema') == 1
                       and job.get('execution_isolation_scope') == 'entire_job'
                       and job.get('execution_isolation') == 'github_client_owned'
                       and job.get('server_tools_allowed') is False)
        first_dispatch = job.pop('_server_isolation_new', False) is True
        proved = client_owned is True and (first_dispatch or prior_proof)
        job.update(execution_isolation_schema=1, execution_isolation_scope='entire_job',
                   execution_isolation='github_client_owned' if proved else 'unverified',
                   server_tools_allowed=False if proved else None)
        try:
            runtime.durable_persist_job(job_id)
        except Exception:
            job.clear()
            job.update(snapshot)
            raise


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


def restore_completion_identity(runtime, job_id, expected_generation):
    """Rehydrate the exact admitted worker without model or side-effect replay."""
    if not expected_generation or not runtime.GATEWAY_DURABLE_JOBS or not runtime.durable_job_initialized:
        raise DurableCommitError('The admitted worker identity is unavailable; completion was not acknowledged')
    with runtime.durable_job_lock:
        conn = runtime._durable_connect()
        try:
            row = conn.execute('SELECT public_json,result_json FROM gateway_jobs WHERE job_id=?', (job_id,)).fetchone()
            if row is None:
                raise DurableCommitError('The admitted worker identity is not retained')
            public = json.loads(row[0])
            if public.get('job_id') != job_id or public.get('generation') != expected_generation:
                raise DurableCommitError('The retained job belongs to a different worker generation')
            result = json.loads(row[1]) if row[1] is not None else None
            rows = conn.execute('SELECT event_json FROM gateway_events WHERE job_id=? ORDER BY sequence DESC LIMIT ?',
                                (job_id, runtime.DURABLE_JOB_EVENT_MAX)).fetchall()
            return runtime._runtime_job_from_persisted(public, result, [json.loads(row[0]) for row in reversed(rows)])
        finally:
            conn.close()


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
        from v14.conversations import admission_context
        context = admission_context.get()
        with runtime.job_registry_lock:
            job = runtime.job_registry.get(job_id)
            if job is not None and context and 'conversation_retention' not in job:
                job['conversation_retention'] = context.get('retention', 'full')
                presentation = context.get('profile') or {}
                job.update(profile_id=(context['payload'].get('gateway') or {}).get('profile_id') or presentation.get('profile_id'),
                           profile_name=presentation.get('profile_name'), principal_id=context['principal'])
                if job['conversation_retention'] == 'none':
                    job['title'] = 'Gateway conversation'
            if job is not None and context and (context['payload'].get('gateway') or {}).get('store_conversation') is False:
                job['_ephemeral'] = True
            if job is not None and job.get('_ephemeral'):
                return
        if not runtime.GATEWAY_DURABLE_JOBS:
            return
        if not runtime.durable_job_initialized and not runtime.init_durable_job_store():
            raise DurableCommitError("Durable job store unavailable")
        try:
            with runtime.job_registry_lock, runtime.durable_job_lock:
                job = runtime.job_registry.get(job_id)
                if job is None:
                    return
                public, result = runtime._persisted_job_payload(job)
                conn = runtime._durable_connect()
                try:
                    with conn:
                        projection = getattr(runtime, 'gateway_conversations', None)
                        if projection:
                            projection.generation_projection(conn, job, public, result)
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
            from v14.conversations import ConversationConflict, QueueFull
            if isinstance(exc, (ConversationConflict, QueueFull)):
                raise
            runtime.durable_write_healthy = False
            runtime._durable_metric("failures")
            raise DurableCommitError("Durable job commit failed; completion was not acknowledged") from exc
    runtime.durable_persist_job = persist
