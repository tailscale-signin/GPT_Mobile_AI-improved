"""Durable benchmark coordinator owned by the existing gateway lifecycle.

This coordinator schedules local benchmark measurements. It does not execute
GitHub mutations, replace the engineering supervisor, or resume uncertain work.
"""
import asyncio
from contextlib import asynccontextmanager
import hashlib
import json
import logging
import os
from pathlib import Path
import sqlite3
import threading
import time

from fastapi import Request
from fastapi.responses import JSONResponse

from benchmarks.contracts import TERMINAL, digest, validate_request
from benchmarks.runner import BenchmarkRunner
from benchmarks.sandbox import SandboxUnavailable
from benchmarks.settings import load_settings
from benchmarks.store import BenchmarkConflict, BenchmarkStore
from desktop.configuration import authority, control_host, load
from v14 import VERSION
from v14.conversation_policy import verify_mutation_identity
from v14.execution_binding import ExecutionBindingError


class BenchmarkService:
    def __init__(self, runtime, engineering):
        self.runtime, self.engineering = runtime, engineering
        data = Path(runtime.GATEWAY_JOB_DB_PATH).parent
        self.root = Path(os.environ.get('GATEWAY_DESKTOP_ROOT', str(data.parent if data.name == 'data' else data)))
        self.store = BenchmarkStore(data / 'benchmarks.sqlite3')
        self.lock = threading.RLock()
        self.stop = threading.Event()
        self.wake = threading.Event()
        self.active = {}
        self.thread = None
        self.draining = False

    def origin(self):
        if (self.root / 'settings.json').exists():
            cfg = load(self.root)
            return 'http://' + authority(control_host(cfg), cfg['port'])
        host = os.environ.get('GATEWAY_HOST', '127.0.0.1')
        host = {'0.0.0.0': '127.0.0.1', '::': '::1'}.get(host, host)
        return 'http://' + authority(host, int(os.environ.get('GATEWAY_PORT', '8090')))

    def identity(self):
        return digest({'route': getattr(self.runtime, 'LLAMA_BASE', None), 'origin': self.origin(),
                       'instance': self.runtime.gateway_service.instance_id, 'gatewayVersion': VERSION})

    def provenance(self):
        manifest_path = Path(__file__).resolve().parents[1] / 'manifest-v14.json'
        try:
            raw = manifest_path.read_bytes()
            manifest = json.loads(raw)
        except (OSError, ValueError):
            raw, manifest = b'', {}
        observed = getattr(getattr(self.runtime, 'gateway_debug', None), 'provenance', None)
        observed = observed if isinstance(observed, dict) else {}
        return {'gatewayVersion': VERSION, 'sourceCommit': observed.get('sourceCommit') or manifest.get('sourceCommit'),
                'buildStatus': observed.get('buildStatus') or manifest.get('buildStatus') or 'UNKNOWN',
                'manifestDigest': hashlib.sha256(raw).hexdigest() if raw else None,
                'executionIdentity': self.identity(), 'instanceId': self.runtime.gateway_service.instance_id,
                'modelArtifact': None, 'runtimeBuild': None, 'gpu': None,
                'settingsEvidence': 'REQUESTED_MODEL_HTTP_RECEIPTS', 'nativeBatching': 'NOT_VERIFIED'}

    def snapshot(self):
        with self.lock:
            queued = len(self.store.pending()) if getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False) else 0
            return {'activeBenchmarks': len(self.active), 'queuedBenchmarks': queued,
                    'benchmarkDraining': self.draining}

    def create(self, principal, request):
        request = validate_request(request)
        if request['suite'] == 'coding' and not principal.engineering_allowed:
            raise PermissionError('Engineering scope is required for isolated coding execution')
        # The maintenance prepare path holds this same admission fence.
        with self.runtime.gateway_service.lock:
            if self.draining or self.stop.is_set() or self.runtime.gateway_service.draining or getattr(self.runtime, 'gateway_draining', False):
                raise BenchmarkConflict('Gateway is stopping or updating; start the benchmark after it is ready')
            if not getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False):
                raise BenchmarkConflict('Durable storage is required for saved benchmark runs')
            run, created = self.store.create(principal.id, request, self.provenance())
            if created:
                self.wake.set()
            return run

    def cancel(self, ident, principal):
        with self.runtime.gateway_service.lock:
            run = self.store.get(ident, principal, cases=False)
            if run is None:
                raise KeyError('Benchmark is unavailable')
            if run['state'] in TERMINAL:
                return run
            with self.lock:
                event = self.active.get(ident)
                if event is not None:
                    self.store.update(ident, state='CANCELLING', cancellationRequested=True, progress='Stopping after the admitted operation closes')
                    event.set()
                else:
                    self.store.update(ident, state='CANCELLED', cancellationRequested=True, endedAt=time.time(), progress='Cancelled before dispatch')
            self.wake.set()
            return self.store.get(ident, principal)

    def begin_drain(self):
        with self.lock:
            self.draining = True
            self.stop.set()
            for event in self.active.values():
                event.set()
            self.wake.set()

    def resume_admission(self):
        with self.lock:
            if self.thread is not None and self.thread.is_alive() and not self.stop.is_set():
                self.draining = False

    def start(self):
        with self.lock:
            if self.active or self.thread is not None and self.thread.is_alive():
                raise RuntimeError('Benchmark lifecycle is already running')
            # The composition root may bind a new database for an installation
            # or a TestClient lifespan after this service was constructed.
            data = Path(self.runtime.GATEWAY_JOB_DB_PATH).parent
            self.root = Path(os.environ.get('GATEWAY_DESKTOP_ROOT', str(data.parent if data.name == 'data' else data)))
            if self.store.path != data / 'benchmarks.sqlite3':
                self.store = BenchmarkStore(data / 'benchmarks.sqlite3')
        if not getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False):
            return
        self.store.recover()
        self.stop.clear()
        self.draining = False
        self.thread = threading.Thread(target=self.work, name='gateway-benchmark-coordinator', daemon=True)
        self.thread.start()

    def work(self):
        while not self.stop.is_set():
            try:
                ids = self.store.pending()
                if ids and not getattr(self.runtime, 'gateway_draining', False):
                    self.run_one(ids[0])
                    continue
            except (OSError, ValueError, sqlite3.Error, RuntimeError) as error:
                logging.getLogger(__name__).warning('Benchmark journal unavailable: %s', type(error).__name__)
            self.wake.wait(1)
            self.wake.clear()

    def run_one(self, ident):
        with self.runtime.gateway_service.lock:
            if self.stop.is_set() or self.runtime.gateway_service.draining:
                return
            run = self.store.get(ident, cases=False)
            if run is None or run['state'] != 'QUEUED':
                return
            owner = self.store.owner(ident)
            with self.lock:
                cancel = self.active.setdefault(ident, threading.Event())
            run = self.store.update(ident, state='RUNNING', startedAt=time.time(), progress='Checking the selected model')
        def identity():
            if not self.runtime.gateway_service.authorized(owner):
                raise PermissionError('Benchmark authority was revoked')
            if run['request']['suite'] == 'coding' and not self.engineering.authorized({'principal': owner}):
                raise PermissionError('Engineering authority was revoked')
            return self.identity()
        runner = None
        try:
            runner = BenchmarkRunner(self.store, self.origin(), cancel, identity, lambda: load_settings(self.root))
            summary = runner.execute(run)
            self.store.update(ident, state='COMPLETED', summary=summary, endedAt=time.time(), progress='Results saved')
        except InterruptedError as error:
            receipts = getattr(runner, 'cancellation_receipts', [])
            if getattr(error, 'cancellation', None) and error.cancellation not in receipts:
                receipts = [*receipts, error.cancellation]
            unknown = any(row.get('outcome') not in {'confirmed', 'terminal', 'not_canceled'} for row in receipts)
            state = 'INTERRUPTED' if self.stop.is_set() or unknown else 'CANCELLED'
            self.store.update(ident, state=state, endedAt=time.time(),
                              progress='Benchmark stopped; model cancellation remains unconfirmed' if unknown else 'Stopped; completed measurements remain saved',
                              failure={'code': 'CANCELLATION_UNCONFIRMED' if unknown else 'BENCHMARK_STOPPED', 'cancellationReceipts': receipts})
        except (SandboxUnavailable, PermissionError) as error:
            self.store.update(ident, state='BLOCKED', endedAt=time.time(), progress=str(error)[:240],
                              failure={'code': 'SANDBOX_SETUP_REQUIRED' if isinstance(error, SandboxUnavailable) else 'AUTHORITY_REVOKED'})
        except Exception as error:
            # Never persist exception text, server bodies or generated source.
            self.store.update(ident, state='FAILED', endedAt=time.time(), progress='The run stopped. Open Evidence for the retained outcome.',
                              failure={'code': str(error) if str(error) in {'SELECTED_EXECUTION_CHANGED', 'GATEWAY_NOT_READY', 'SELECTED_MODEL_NOT_ADVERTISED', 'GATEWAY_INSTANCE_CHANGED'} else type(error).__name__})
        finally:
            with self.lock:
                self.active.pop(ident, None)

    def lifecycle(self, existing):
        @asynccontextmanager
        async def lifecycle(app):
            async with existing(app):
                await asyncio.to_thread(self.start)
                try:
                    yield
                finally:
                    self.begin_drain()
                    if self.thread is not None:
                        # Keep the runtime alive until bounded HTTP/container
                        # operations and their cancellation receipts have settled.
                        await asyncio.to_thread(self.thread.join)
        return lifecycle


def install_benchmark_routes(app, service):
    def owner(request):
        principal = request.state.principal
        if not principal.local_admin and not {'jobs', 'chat', 'tools'} <= principal.scopes:
            raise PermissionError('Benchmark scope is unavailable')
        if request.method not in {'GET', 'HEAD'}:
            verify_mutation_identity(request.headers, principal.id, service.engineering.store.conversations.gateway_id)
        return principal

    async def invoke(action):
        try:
            return await asyncio.to_thread(action)
        except PermissionError:
            return JSONResponse({'error': {'code': 'benchmark_scope_required'}}, status_code=403)
        except ExecutionBindingError as error:
            return JSONResponse({'error': error.error_payload()}, status_code=error.status)
        except KeyError:
            return JSONResponse({'error': {'code': 'benchmark_not_found'}}, status_code=404)
        except BenchmarkConflict as error:
            return JSONResponse({'error': {'code': 'benchmark_conflict', 'message': str(error)}}, status_code=409)
        except (ValueError, TypeError):
            return JSONResponse({'error': {'code': 'invalid_benchmark_request'}}, status_code=400)
        except (OSError, sqlite3.Error):
            return JSONResponse({'error': {'code': 'benchmark_storage_unavailable'}}, status_code=503)

    @app.get('/gateway/benchmarks')
    async def history(request: Request):
        def read():
            principal = owner(request)
            request_id = request.query_params.get('request_id')
            if request_id is not None:
                return service.store.submission(principal.id, request_id)
            before = request.query_params.get('before')
            return service.store.history(principal.id, before=int(before) if before else None)
        return await invoke(read)

    @app.post('/gateway/benchmarks')
    async def create(request: Request):
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 4096:
                return JSONResponse({'error': {'code': 'benchmark_request_too_large'}}, status_code=413)
        def submit():
            principal = owner(request)
            return service.create(principal, json.loads(raw))
        return await invoke(submit)

    @app.get('/gateway/benchmarks/{ident}')
    async def snapshot(ident: str, request: Request):
        def read():
            run = service.store.get(ident, owner(request).id)
            if run is None:
                raise KeyError(ident)
            return run
        return await invoke(read)

    @app.post('/gateway/benchmarks/{ident}/cancel')
    async def cancel(ident: str, request: Request):
        return await invoke(lambda: service.cancel(ident, owner(request).id))

    @app.delete('/gateway/benchmarks/{ident}')
    async def remove(ident: str, request: Request):
        return await invoke(lambda: {'removed': service.store.remove(ident, owner(request).id)})
