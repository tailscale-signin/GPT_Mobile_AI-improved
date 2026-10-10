"""Bounded admission and worker accounting, independent of model/tool policy."""
import asyncio
import threading
import time
import uuid
from contextlib import asynccontextmanager

from fastapi.responses import JSONResponse


class ServiceState:
    def __init__(self, max_workers=2):
        self.max_workers = max_workers
        self.lock = threading.RLock()
        self.draining = False
        self.workers = {}
        self.rejected = 0
        self.runtime = None

    def snapshot(self):
        with self.lock:
            return {'draining': self.draining, 'activeWorkers': len(self.workers),
                    'maxWorkers': self.max_workers, 'rejectedWorkers': self.rejected}

    def install(self, runtime):
        self.runtime = runtime
        runtime.gateway_draining = False
        original = runtime.process_chat_payload
        def bounded(payload, cancel_event=None, progress_callback=None, hard_cancel_event=None):
            key = uuid.uuid4().hex
            with self.lock:
                if self.draining or len(self.workers) >= self.max_workers:
                    self.rejected += 1
                    return 503, {'error': {'code': 'gateway_busy', 'message': 'Gateway capacity is occupied; retry later'}}
                self.workers[key] = (cancel_event, hard_cancel_event)
            try:
                return original(payload, cancel_event, progress_callback, hard_cancel_event)
            finally:
                with self.lock:
                    self.workers.pop(key, None)
        runtime.process_chat_payload = bounded

    def begin_drain(self):
        with self.lock:
            self.draining = True
            if self.runtime is not None:
                self.runtime.gateway_draining = True
            for signals in self.workers.values():
                for signal in signals:
                    if signal is not None:
                        signal.set()

    def lifespan(self, runtime, grace):
        @asynccontextmanager
        async def lifecycle(app):
            with self.lock:
                self.draining = False
                runtime.gateway_draining = False
            # Database validation and recovery must finish before HTTP admission.
            try:
                await asyncio.to_thread(runtime.start_gateway_background_services)
            except BaseException:
                runtime.startup_services_started = False
                self.begin_drain()
                await asyncio.to_thread(runtime.close_all_mcp_sessions)
                raise
            try:
                yield
            finally:
                self.begin_drain()
                deadline = time.monotonic() + grace
                while self.snapshot()['activeWorkers'] and time.monotonic() < deadline:
                    await asyncio.sleep(0.05)
                await asyncio.to_thread(runtime.close_all_mcp_sessions)
                runtime.startup_services_started = False
        return lifecycle


class LoadGuard:
    """Bound request bytes, upload time and concurrent chat streams before routing."""
    def __init__(self, app, config, state):
        self.app, self.config, self.state = app, config, state
        self.active = 0  # ASGI event-loop owned; no await between check and increment.

    async def __call__(self, scope, receive, send):
        if scope['type'] != 'http' or scope.get('method') != 'POST' or scope.get('path') not in {'/v1/chat/completions', '/chat/completions'}:
            return await self.app(scope, receive, send)
        trace = uuid.uuid4().hex
        async def reject(code, status):
            headers = {'X-Gateway-Trace-ID': trace}
            if status == 503:
                headers['Retry-After'] = '2'
            await JSONResponse({'error': {'code': code}, 'traceId': trace}, status_code=status,
                               headers=headers)(scope, receive, send)
        if self.state.snapshot()['draining'] or self.active >= self.config.max_active_chats:
            return await reject('gateway_busy', 503)
        self.active += 1
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
            self.active -= 1
