"""Bounded admission and worker accounting, independent of model/tool policy."""
import asyncio
import json
import os
import sqlite3
import threading
import time
import uuid
from contextlib import asynccontextmanager

from fastapi.responses import JSONResponse
from v14.deadlines import DeadlineExceeded, request_deadline
from v14.state_lock import StateDirectoryLock


class ServiceState:
    def __init__(self, max_workers=2, max_queued=64):
        self.max_workers = max_workers
        self.max_queued = max_queued
        self.instance_id = uuid.uuid4().hex
        self.lock = threading.RLock()
        self.capacity = threading.Condition(self.lock)
        self.pending = {}
        self.recovery_tasks = set()
        self.draining = False
        self.workers = {}
        self.active_requests = 0
        self.maintenance_generation = 0
        self.rejected = 0
        self.runtime = None
        self.authorized = lambda principal: True

    def snapshot(self):
        with self.lock:
            result = {'draining': self.draining, 'activeWorkers': len(self.workers),
                      'queuedWorkers': len(self.pending), 'activeRequests': self.active_requests,
                      'maxWorkers': self.max_workers, 'maximumQueuedWorkers': self.max_queued, 'rejectedWorkers': self.rejected,
                      'queueAvailable': False}
        runtime = self.runtime
        if runtime is not None and getattr(runtime, 'GATEWAY_DURABLE_JOBS', False) and getattr(runtime, 'durable_job_initialized', False):
            journal = getattr(runtime, 'gateway_conversations', None)
            if journal is not None:
                try:
                    pending = journal.read(lambda db: db.execute("SELECT COUNT(*) FROM gateway_generation_admissions WHERE dispatch_state='queued'").fetchone()[0])
                    result.update(queuedWorkers=max(result['queuedWorkers'], pending),
                                  queueAvailable=not result['draining'] and pending < self.max_queued and getattr(runtime, 'durable_write_healthy', True))
                except (OSError, ValueError, RuntimeError, sqlite3.Error):
                    pass
        return result

    def install(self, runtime):
        self.runtime = runtime
        runtime.gateway_draining = False
        runtime.gateway_durable_queue_installed = True
        runtime.gateway_max_queued = self.max_queued
        original = runtime.process_chat_payload
        def bounded(payload, cancel_event=None, progress_callback=None, hard_cancel_event=None):
            key = uuid.uuid4().hex
            job_id = payload.get('_gateway_job_id') if isinstance(payload, dict) else None
            journal = getattr(runtime, 'gateway_conversations', None)
            durable = journal.pending_generation(job_id) if journal and job_id and getattr(runtime, "GATEWAY_DURABLE_JOBS", False) else None
            try:
                deadline = request_deadline(payload)
                if durable:
                    # Include the persisted queue wait, including a process restart.
                    remaining = max(0.0, durable['deadline'] - time.time())
                else:
                    remaining = deadline.require_time()
            except (ValueError, DeadlineExceeded):
                return 400, {'error': {'code': 'invalid_request', 'message': 'Invalid gateway run deadline'}}
            if isinstance(payload, dict):
                payload.pop('gateway', None)
            expires = time.monotonic() + remaining
            with self.capacity:
                if durable and durable['dispatch_state'] == 'queued':
                    self.pending[key] = (durable['accepted_at'], job_id)
                    try:
                        while True:
                            if hard_cancel_event is not None and hard_cancel_event.is_set():
                                return 499, {'error': {'code': 'cancelled', 'message': 'Cancelled before dispatch'}}
                            if time.monotonic() >= expires:
                                return 504, {'error': {'code': 'queue_deadline_exceeded', 'message': 'The admitted run deadline expired before dispatch'}}
                            if self.draining:
                                # Persisted, never-dispatched work is replayable on
                                # startup. No terminal result is fabricated here.
                                return 503, {'_gateway_deferred': True, 'error': {'code': 'gateway_restarting', 'message': 'Accepted job is retained for the next gateway process'}}
                            first = min(self.pending, key=lambda value: self.pending[value])
                            if len(self.workers) < self.max_workers and first == key:
                                self.workers[key] = (cancel_event, hard_cancel_event)
                                break
                            self.capacity.wait(.1)
                    finally:
                        self.pending.pop(key, None)
                        self.capacity.notify_all()
                elif self.draining or len(self.workers) >= self.max_workers:
                    self.rejected += 1
                    return 503, {'error': {'code': 'gateway_busy', 'message': 'Gateway capacity is occupied; retry later'}}
                else:
                    self.workers[key] = (cancel_event, hard_cancel_event)
            expired = threading.Event()
            def expire_run():
                expired.set()
                for signal in (cancel_event, hard_cancel_event):
                    if signal is not None:
                        signal.set()
            timer = threading.Timer(max(.001, expires - time.monotonic()), expire_run)
            timer.daemon = True
            try:
                if durable:
                    from v14.conversations import fingerprint
                    if durable.get('binding_fingerprint') != fingerprint([getattr(runtime, 'LLAMA_BASE', None), payload.get('model')]):
                        return 409, {'error': {'code': 'queued_execution_binding_changed', 'message': 'The accepted model route changed before dispatch; start a new reviewed request'}}
                    if not self.authorized(durable['principal']):
                        return 403, {'error': {'code': 'principal_revoked', 'message': 'The queued job lost execution authority before dispatch'}}
                    with runtime.job_registry_lock:
                        job = runtime.job_registry.get(job_id)
                        if job is None:
                            raise ValueError('The admitted worker lost its durable job')
                        prior = dict(job)
                        job.update(status='running', stage='starting', queue_reason=None,
                                   started_at=job.get('started_at') or time.time(),
                                   _claim_dispatch=True, run_deadline=durable['deadline'])
                        try:
                            runtime.durable_persist_job(job_id)
                        except Exception:
                            job.clear()
                            job.update(prior)
                            raise
                timer.start()
                return original(payload, cancel_event, progress_callback, hard_cancel_event)
            finally:
                timer.cancel()
                if expired.is_set():
                    runtime.logger.warning('Gateway run deadline expired; cancellation signals were sent')
                with self.capacity:
                    self.workers.pop(key, None)
                    self.capacity.notify_all()
        runtime.process_chat_payload = bounded

    async def recover_queued(self, runtime):
        """Resume only committed admissions with proof no worker dispatched."""
        journal = getattr(runtime, 'gateway_conversations', None)
        if not journal or not runtime.GATEWAY_DURABLE_JOBS:
            return
        rows = await asyncio.to_thread(journal.pending_generation)
        from gateway_security import current_device
        from v14.conversations import admission_context
        for row in rows:
            job_id = row['job_id']
            if not runtime.device_store.owns(job_id, row['principal']):
                continue
            payload = json.loads(row['payload_json'])
            payload['_gateway_job_id'] = job_id
            cancel, hard = threading.Event(), threading.Event()
            with runtime.job_registry_lock:
                job = runtime.job_registry.get(job_id)
                if job is None or job.get('status') != 'queued':
                    continue
                job.update(_cancel_event=cancel, _hard_cancel_event=hard, _durable_background_admitted=True)
            def run(job_id=job_id, payload=payload, cancel=cancel, hard=hard, principal=row['principal'], generation=job['generation']):
                token = current_device.set(principal)
                context_token = admission_context.set(None)
                try:
                    try:
                        code, result = runtime.process_chat_payload(payload, cancel, lambda event: runtime.update_gateway_job(job_id, event), hard)
                    except Exception as error:
                        runtime.finish_gateway_job(job_id, 500, None, error=error, expected_generation=generation)
                        return
                    runtime.finish_gateway_job(job_id, code, result, expected_generation=generation)
                    return code, result
                finally:
                    admission_context.reset(context_token)
                    current_device.reset(token)
            task = asyncio.create_task(asyncio.to_thread(run))
            self.recovery_tasks.add(task)
            def completed(task, job_id=job_id):
                self.recovery_tasks.discard(task)
                runtime.mark_singleflight_worker_finished(job_id, task)
                if not task.cancelled() and task.exception() is not None:
                    runtime.logger.error('Retained queued job finalization failed: %s', job_id)
            task.add_done_callback(completed)
            with runtime.singleflight_lock:
                runtime.singleflight_workers[job_id] = {'task': task, 'cancel_event': cancel, 'hard_cancel_event': hard,
                    'clients': 0, 'created_at': row['accepted_at'], 'finished_at': None, 'job_mode': 'interactive'}

    def begin_drain(self):
        with self.lock:
            self.draining = True
            if self.runtime is not None:
                self.runtime.gateway_draining = True
            self.capacity.notify_all()
            for signals in self.workers.values():
                for signal in signals:
                    if signal is not None:
                        signal.set()

    def lifespan(self, runtime, grace):
        @asynccontextmanager
        async def lifecycle(app):
            state_lock = None
            with self.lock:
                self.draining = os.getenv("GATEWAY_START_QUIESCED") == "1"
                runtime.gateway_draining = self.draining
            # Database validation and recovery must finish before HTTP admission.
            try:
                database_path = getattr(runtime, "GATEWAY_JOB_DB_PATH", None)
                if database_path:
                    state_lock = StateDirectoryLock(database_path)
                    await asyncio.to_thread(state_lock.acquire)
                await asyncio.to_thread(runtime.start_gateway_background_services)
                await self.recover_queued(runtime)
            except BaseException:
                runtime.startup_services_started = False
                self.begin_drain()
                try:
                    await asyncio.to_thread(runtime.close_all_mcp_sessions)
                finally:
                    if state_lock is not None:
                        await asyncio.to_thread(state_lock.release)
                raise
            try:
                yield
            finally:
                self.begin_drain()
                deadline = time.monotonic() + grace
                while self.snapshot()['activeWorkers'] and time.monotonic() < deadline:
                    await asyncio.sleep(0.05)
                try:
                    await asyncio.to_thread(runtime.close_all_mcp_sessions)
                finally:
                    runtime.startup_services_started = False
                    if state_lock is not None:
                        await asyncio.to_thread(state_lock.release)
        return lifecycle


class LoadGuard:
    """Bound request bytes, upload time and concurrent chat streams before routing."""
    def __init__(self, app, config, state):
        self.app, self.config, self.state = app, config, state
        self.active = 0  # ASGI event-loop owned; no await between check and increment.

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope.get('method') in {'GET', 'HEAD', 'OPTIONS'} or scope.get('path') == '/gateway/admin/maintenance':
            return await self.app(scope, receive, send)
        trace = uuid.uuid4().hex
        async def reject(code, status):
            headers = {'X-Gateway-Trace-ID': trace}
            if status == 503:
                headers['Retry-After'] = '2'
            await JSONResponse({'error': {'code': code}, 'traceId': trace}, status_code=status,
                               headers=headers)(scope, receive, send)
        with self.state.lock:
            accepted = not self.state.draining and self.active < self.config.max_active_chats
            if accepted:
                self.active += 1
                self.state.active_requests += 1
        if not accepted:
            return await reject('gateway_busy', 503)
        try:
            chunks, size = [], 0
            deadline = time.monotonic() + self.config.body_timeout
            while True:
                if time.monotonic() >= deadline:
                    return await reject('request_body_timeout', 408)
                try:
                    message = await asyncio.wait_for(receive(), max(0.001, deadline - time.monotonic()))
                except asyncio.TimeoutError:
                    return await reject('request_body_timeout', 408)
                if message['type'] == 'http.disconnect':
                    return
                data = message.get('body', b'')
                size += len(data)
                if size > self.config.max_body_bytes:
                    return await reject('request_body_too_large', 413)
                chunks.append(data)
                if not message.get('more_body', False):
                    break
            body = b''.join(chunks)
            delivered = False
            async def buffered_receive():
                nonlocal delivered
                if not delivered:
                    delivered = True
                    return {'type': 'http.request', 'body': body, 'more_body': False}
                return await receive()
            async def traced_send(message):
                if message['type'] == 'http.response.start':
                    message['headers'] = list(message.get('headers', [])) + [(b'x-gateway-trace-id', trace.encode())]
                await send(message)
            await self.app(scope, buffered_receive, traced_send)
        finally:
            with self.state.lock:
                self.active -= 1
                self.state.active_requests -= 1
