"""Bounded admission and worker accounting, independent of model/tool policy."""
import asyncio
import os
import threading
import time
import uuid
from contextlib import asynccontextmanager

from fastapi.responses import JSONResponse
from v14.deadlines import DeadlineExceeded, request_deadline
from v14.state_lock import StateDirectoryLock
from v14.errors import GatewayFailure


class ServiceState:
    def __init__(self, max_workers=2, max_queued=64):
        self.max_workers = max_workers
        self.max_queued = max_queued
        self.instance_id = uuid.uuid4().hex
        self.lock = threading.RLock()
        self.condition = threading.Condition(self.lock)
        self.draining = False
        self.workers = {}
        self.queued = set()
        self.admitted = set()
        self.active_requests = 0
        self.maintenance_generation = 0
        self.rejected = 0
        self.runtime = None

    def snapshot(self):
        with self.lock:
            return {'draining': self.draining, 'activeWorkers': len(self.workers),
                    'activeRequests': self.active_requests, 'maxWorkers': self.max_workers, 'rejectedWorkers': self.rejected,
                    'queuedWorkers': len(self.queued), 'maximumQueuedWorkers': self.max_queued,
                    'queueAvailable': not self.draining and len(self.admitted) < self.max_workers + self.max_queued}

    def install(self, runtime):
        self.runtime = runtime
        runtime.gateway_draining = False
        runtime.gateway_durable_queue_installed = True
        if hasattr(runtime, 'register_gateway_job'):
            register = runtime.register_gateway_job
            finish = runtime.finish_gateway_job
            def admitted_register(job_id, *args, **kwargs):
                with self.condition:
                    reserved = job_id in self.admitted
                    if self.draining or not reserved and len(self.admitted) >= self.max_workers + self.max_queued:
                        self.rejected += 1
                        raise GatewayFailure('gateway_busy', 'Gateway admission capacity is occupied; no job was accepted.', status_code=503, retryable=True)
                    self.admitted.add(job_id)
                try:
                    return register(job_id, *args, **kwargs)
                except BaseException:
                    if not reserved:
                        with self.condition:
                            self.admitted.discard(job_id)
                            self.condition.notify_all()
                    raise
            def worker_finished(job_id, *args, **kwargs):
                result = finish(job_id, *args, **kwargs)
                with self.condition:
                    self.admitted.discard(job_id)
                    self.queued.discard(job_id)
                    self.condition.notify_all()
                return result
            runtime.register_gateway_job = admitted_register
            runtime.finish_gateway_job = worker_finished
        original = runtime.process_chat_payload
        def bounded(payload, cancel_event=None, progress_callback=None, hard_cancel_event=None):
            key = uuid.uuid4().hex
            job_id = payload.get('_gateway_job_id') if isinstance(payload, dict) else None
            try:
                deadline = request_deadline(payload)
                remaining = deadline.require_time()
            except (ValueError, DeadlineExceeded):
                return 400, {'error': {'code': 'invalid_request', 'message': 'Invalid gateway run deadline'}}
            if isinstance(payload, dict):
                # Gateway control metadata is consumed at admission and must not
                # be forwarded as a model-provider parameter.
                payload.pop('gateway', None)
            with self.condition:
                durable_admission = job_id in self.admitted and bool(getattr(runtime, 'GATEWAY_DURABLE_JOBS', False))
                if self.draining or len(self.workers) >= self.max_workers and not durable_admission:
                    self.rejected += 1
                    return 503, {'error': {'code': 'gateway_busy', 'message': 'Gateway capacity is occupied; retry later'}}
            expired = threading.Event()
            def expire_run():
                expired.set()
                for signal in (cancel_event, hard_cancel_event):
                    if signal is not None:
                        signal.set()
            timer = threading.Timer(remaining, expire_run)
            timer.daemon = True
            timer.start()
            try:
                waiting_reported = False
                while True:
                    with self.condition:
                        if expired.is_set() or deadline.expired():
                            return 408, {'error': {'code': 'gateway_queue_deadline', 'dispatch_state': 'not_dispatched'}}
                        if self.draining or any(signal is not None and signal.is_set() for signal in (cancel_event, hard_cancel_event)):
                            return 499, {'error': {'code': 'gateway_cancelled_before_dispatch', 'dispatch_state': 'not_dispatched'}}
                        if len(self.workers) < self.max_workers:
                            self.queued.discard(job_id)
                            self.workers[key] = (cancel_event, hard_cancel_event)
                            break
                        self.queued.add(job_id)
                    if not waiting_reported and callable(progress_callback):
                        progress_callback({'status': 'queued', 'scheduling_state': 'queued',
                                           'message': 'Accepted; waiting for gateway worker capacity'})
                        waiting_reported = True
                    with self.condition:
                        self.condition.wait(min(0.1, max(0.001, remaining)))
                if job_id and callable(progress_callback):
                    progress_callback({'status': 'running', 'scheduling_state': 'running',
                                       'started_at': time.time(), 'message': 'Gateway worker started the accepted job'})
                return original(payload, cancel_event, progress_callback, hard_cancel_event)
            finally:
                timer.cancel()
                if expired.is_set():
                    runtime.logger.warning('Gateway run deadline expired; cancellation signals were sent')
                with self.condition:
                    self.workers.pop(key, None)
                    self.queued.discard(job_id)
                    self.condition.notify_all()
        runtime.process_chat_payload = bounded

    def begin_drain(self):
        with self.condition:
            self.draining = True
            if self.runtime is not None:
                self.runtime.gateway_draining = True
            for signals in self.workers.values():
                for signal in signals:
                    if signal is not None:
                        signal.set()
            self.condition.notify_all()

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
