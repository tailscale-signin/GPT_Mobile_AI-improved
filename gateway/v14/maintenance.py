"""Local manager protocol. Quiesce refuses active or resumable work; never cancels it."""
import asyncio
from fastapi import Request
from fastapi.responses import JSONResponse
from v14.health import readiness
from v14.usage import dashboard_snapshot
from v14.debug_projection import provenance


def pending_index_jobs(db):
    # Older installations have no graph-job table; an idle check must not
    # initialize a second executor or create a journal as a side effect.
    exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='code_graph_index_jobs'").fetchone()
    if not exists:
        return 0
    return db.execute("SELECT COUNT(*) FROM code_graph_index_jobs WHERE state IN ('QUEUED','RUNNING','INTERRUPTED','PAUSED')").fetchone()[0]


def indexing_snapshot(engineering):
    active = 0
    for name in ('graph', 'public_graph'):
        graph = getattr(engineering, name, None)
        worker = getattr(graph, '_index_jobs', None)
        if worker is not None:
            active += worker.snapshot()['activeIndexBatches']
    return {'activeIndexBatches': active}


def pending_work(runtime, engineering):
    if not runtime.GATEWAY_DURABLE_JOBS:
        return 0
    def count(db):
        chats = db.execute("SELECT COUNT(*) FROM gateway_jobs WHERE status IS NULL OR status NOT IN ('completed','failed','cancelled','canceled','interrupted')").fetchone()[0]
        jobs = db.execute("SELECT COUNT(*) FROM engineering_jobs WHERE state NOT IN ('COMPLETED','CANCELLED')").fetchone()[0]
        return chats + jobs + pending_index_jobs(db)
    return engineering.store.tx(count)


def prepare(runtime, state, engineering, generation=None):
    # Same lock used to admit HTTP requests and acquire worker slots. The
    # generation fence prevents a timed-out prepare from draining after resume.
    with state.lock:
        if generation is not None and state.maintenance_generation != generation:
            return False
        benchmarks = getattr(runtime, 'gateway_benchmarks', None)
        benchmark_state = benchmarks.snapshot() if benchmarks is not None else {}
        indexing = indexing_snapshot(engineering)
        if state.active_requests or state.workers or pending_work(runtime, engineering) or benchmark_state.get('activeBenchmarks', 0) or benchmark_state.get('queuedBenchmarks', 0) or indexing['activeIndexBatches']:
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
        health['build'] = provenance(getattr(getattr(runtime, 'gateway_debug', None), 'provenance', {}))
        health['dashboard'] = await asyncio.to_thread(dashboard_snapshot, runtime)
        benchmarks = getattr(runtime, 'gateway_benchmarks', None)
        if benchmarks is not None:
            health['benchmarks'] = await asyncio.to_thread(benchmarks.snapshot)
        health['repositoryIndexing'] = await asyncio.to_thread(indexing_snapshot, engineering)
        health['repositoryIndexing']['pendingIndexJobs'] = await asyncio.to_thread(engineering.store.tx, pending_index_jobs) if runtime.GATEWAY_DURABLE_JOBS else 0
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
            with state.lock:
                generation = state.maintenance_generation
            if not await asyncio.to_thread(prepare, runtime, state, engineering, generation):
                return JSONResponse({'error': 'work_in_progress'}, status_code=409)
        elif command == 'resume':
            with state.lock:
                state.maintenance_generation += 1
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
