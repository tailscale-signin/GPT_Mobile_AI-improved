"""Local manager protocol. Quiesce refuses active or resumable work; never cancels it."""
import asyncio
from fastapi import Request
from fastapi.responses import JSONResponse
from v14.health import readiness


def pending_work(runtime, engineering):
    if not runtime.GATEWAY_DURABLE_JOBS:
        return 0
    def count(db):
        chats = db.execute("SELECT COUNT(*) FROM gateway_jobs WHERE status IS NULL OR status NOT IN ('completed','failed','cancelled','canceled','interrupted')").fetchone()[0]
        jobs = db.execute("SELECT COUNT(*) FROM engineering_jobs WHERE state NOT IN ('COMPLETED','CANCELLED')").fetchone()[0]
        return chats + jobs
    return engineering.store.tx(count)


def prepare(runtime, state, engineering):
    # Same lock used to admit HTTP requests and acquire worker slots.
    with state.lock:
        if state.active_requests or state.workers or pending_work(runtime, engineering):
            return False
        state.draining = True
        runtime.gateway_draining = True
        return True


def install_maintenance(app, runtime, state, engineering):
    @app.get('/gateway/admin/maintenance')
    async def status():
        health = await asyncio.to_thread(readiness, runtime, state)
        # Backend availability is reported, but cannot invalidate the installed runtime.
        health['runtimeHealthy'] = health['checks']['journal'] == 'ready'
        health['protocol'] = 1
        return health

    @app.post('/gateway/admin/maintenance')
    async def action(request: Request):
        # This surface accepts tiny JSON commands only, behind the per-process secret.
        body = bytearray()
        async for chunk in request.stream():
            body.extend(chunk)
            if len(body) > 256:
                return JSONResponse({'error': 'command_too_large'}, status_code=413)
        import json
        try:
            command = json.loads(body).get('action')
        except (ValueError, AttributeError):
            return JSONResponse({'error': 'invalid_command'}, status_code=400)
        if command == 'prepare':
            if not await asyncio.to_thread(prepare, runtime, state, engineering):
                return JSONResponse({'error': 'work_in_progress'}, status_code=409)
        elif command == 'resume':
            with state.lock:
                state.draining = False
                runtime.gateway_draining = False
        elif command == 'shutdown':
            if not state.snapshot()['draining']:
                return JSONResponse({'error': 'prepare_required'}, status_code=409)
            server = getattr(app.state, 'server', None)
            if server is None:
                return JSONResponse({'error': 'server_unavailable'}, status_code=503)
            server.should_exit = True
        else:
            return JSONResponse({'error': 'unknown_action'}, status_code=400)
        return {'accepted': True, 'service': state.snapshot()}
