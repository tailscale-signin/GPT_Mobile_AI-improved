"""Durable benchmark coordinator owned by the existing gateway lifecycle.

This coordinator schedules isolated measurements and delegates explicitly
requested disposable GitHub qualification to its scoped execution adapter.
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
from benchmarks.runner import BenchmarkRunner, benchmark_error_receipt
from benchmarks import sandbox
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
        self.cleanup_after = 0.0
        self.external_cleanup_after = 0.0
        self.reconciling = set()
        from benchmarks.qualification_service import QualificationService
        self.qualifications = QualificationService(self)

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
                'settingsEvidence': 'REQUESTED_MODEL_HTTP_RECEIPTS', 'nativeBatching': 'NOT_VERIFIED',
                'provenanceCoverage': {'modelArtifact': 'NOT_MEASURED', 'runtimeBuild': 'NOT_MEASURED',
                                       'gpu': 'NOT_MEASURED', 'quantization': 'NOT_MEASURED'}}

    def snapshot(self):
        with self.lock:
            queued = len(self.store.pending()) if getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False) else 0
            claimed = {row['run_id'] for row in self.sandbox_claims()}
            external = {row['run_id'] for row in self.external_cleanups()}
            return {'queuedBenchmarks': queued,
                    'unconfirmedSandboxCleanups': len(claimed - self.active.keys()),
                    'unconfirmedGitHubCleanups': len(external - self.active.keys()),
                    'benchmarkDraining': self.draining, 'activeBenchmarks': len(set(self.active) | claimed | external)}

    def external_cleanups(self):
        return self.store.pending_external_cleanups() if self.store.path.exists() else []

    def reconcile_external(self, ident=None, owner=None, *, force=False):
        pending = [row for row in self.external_cleanups() if (ident is None or row['run_id'] == ident) and (owner is None or row['principal'] == owner)]
        if not pending:
            return True
        if not force and time.monotonic() < self.external_cleanup_after:
            return False
        self.external_cleanup_after = time.monotonic() + 5
        target = pending[0]
        with self.lock:
            if target['run_id'] in self.active or target['run_id'] in self.reconciling:
                return False
            self.reconciling.add(target['run_id'])
        try:
            from benchmarks.live_github import reconcile_interrupted
            run = self.store.get(target['run_id'], target['principal'], cases=False)
            reconcile_interrupted(self, target['principal'], run)
        except (OSError, ValueError, PermissionError):
            # A failed read does not clear retained physical ownership.
            pass
        finally:
            with self.lock:
                self.reconciling.discard(target['run_id'])
        return not self.external_cleanups()

    def sandbox_claims(self):
        # Disabling durable admission cannot erase existing physical ownership,
        # but an installation with no journal needs no new database side effect.
        return self.store.pending_sandboxes() if self.store.path.exists() else []

    def unconfirmed_sandboxes(self):
        with self.lock:
            return [row for row in self.sandbox_claims() if row['run_id'] not in self.active]

    def reconcile_sandboxes(self):
        pending = self.unconfirmed_sandboxes()
        if not pending:
            return True
        if time.monotonic() < self.cleanup_after:
            return False
        self.cleanup_after = time.monotonic() + 5
        owned = pending[0]
        receipt = sandbox.reconcile_container(owned['container'])
        if (receipt.get('kind') == 'sandbox' and receipt.get('container') == owned['container'] and
                receipt.get('outcome') == 'confirmed'):
            self.store.release_sandbox(owned['run_id'], owned['container'])
        return not self.unconfirmed_sandboxes()

    def create(self, principal, request, *, submission=None):
        request = validate_request(request)
        if request['suite'] in {'coding', 'coding_submission'} and not principal.engineering_allowed:
            raise PermissionError('Engineering scope is required for isolated coding execution')
        provenance = self.provenance()
        if request['suite'] in {'coding', 'coding_submission'}:
            provenance['benchmarkSettingsDigest'] = digest(load_settings(self.root))
        if request.get('execution_binding_id'):
            binding, _, _ = self.engineering.bindings.validate(principal.id, request['execution_binding_id'], force=True)
            if binding['requested_model_id'] != request['model']:
                raise ValueError('Benchmark selected model differs from its execution binding')
            context = request.get('comparison_context')
            if context and any(context.get(key) != binding[field] for key, field in (
                    ('profile_uid', 'profile_uid'), ('profile_revision', 'profile_revision'),
                    ('settings_digest', 'generation_parameters_digest'), ('route_identity', 'backend_endpoint_identity'))):
                raise ValueError('Benchmark comparison context differs from its immutable binding')
            provenance.update(selectedBinding=binding, modelIdentityScope='VERIFIED_SELECTED_LOCAL_BINDING')
        elif request['suite'] == 'coding_submission':
            provenance.update(modelIdentityScope='AUTHENTICATED_CLIENT_OBSERVATION', graderDigest=request['grader_digest'])
        else:
            provenance.update(modelIdentityScope='CONFIGURED_GATEWAY_ROUTE')
        if 'SEMANTIC' in request.get('context_variants', []):
            from benchmarks.context_semantic import describe
            provenance['contextEncoderIdentity'] = describe(request, provenance, self.engineering)
        # The maintenance prepare path holds this same admission fence.
        with self.runtime.gateway_service.lock:
            if self.draining or self.stop.is_set() or self.runtime.gateway_service.draining or getattr(self.runtime, 'gateway_draining', False):
                raise BenchmarkConflict('Gateway is stopping or updating; start the benchmark after it is ready')
            if not getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False):
                raise BenchmarkConflict('Durable storage is required for saved benchmark runs')
            if self.unconfirmed_sandboxes():
                raise BenchmarkConflict('A previous sandbox cleanup is unconfirmed. Restore the local container runtime before starting more work.')
            if self.external_cleanups():
                raise BenchmarkConflict('A previous disposable GitHub workflow has unconfirmed shutdown. Reconcile its saved request before starting more work.')
            run, created = self.store.create(principal.id, request, provenance, submission=submission)
            if created:
                self.wake.set()
            return run

    def evaluate(self, principal, value):
        from benchmarks.external import environment_receipt, evaluate_request
        request, proposal = evaluate_request(value)
        environment = environment_receipt(load_settings(self.root), request['task_pack'], request['grader_digest'])
        if request.get('environment_digest', environment['environmentDigest']) != environment['environmentDigest']:
            raise ValueError('BENCHMARK_ENVIRONMENT_CHANGED')
        request['environment_digest'] = environment['environmentDigest']
        return self.create(principal, request, submission=proposal)

    def fixture_catalog(self, *, preset='quick', seed=17, task_id=None, task_pack='python'):
        from benchmarks import fixtures
        from benchmarks.calibration import self_check
        from benchmarks.external import environment_receipt, public_task, select_task
        settings = load_settings(self.root)
        if preset not in {'quick', 'standard', 'deep'} or type(seed) is not int or not 0 <= seed <= 2 ** 31 - 1:
            raise ValueError('Invalid frozen fixture plan')
        if task_pack not in {'python', 'repository'}:
            raise ValueError('Unknown task pack')
        active_settings = dict(settings, sandbox_image=settings.get('repository_image', '')) if task_pack == 'repository' else settings
        ready, reason = False, None
        try:
            sandbox.preflight(active_settings)
            ready = True
        except SandboxUnavailable as error:
            reason = error.reason
        if task_id:
            tasks = [select_task(task_id, seed, task_pack)]
        elif task_pack == 'repository':
            from benchmarks.repository_tasks import task_manifest
            tasks = task_manifest(preset, seed)
        else:
            tasks = fixtures.suite(preset, seed)
        shared = {'graderDigest': tasks[0]['graderDigest'],
                  **environment_receipt(settings, task_pack, tasks[0]['graderDigest']),
                  'sandboxReady': ready, 'readinessReason': reason,
                  'calibration': self_check() if task_pack == 'python' else {'status': 'NOT_RUN', 'scope': 'PER_TASK_NATIVE_RUNTIME'},
                  'scope': tasks[0].get('executionScope', 'ISOLATED_PYTHON_CONTRACT'), 'taskPack': task_pack}
        if task_id:
            return dict(public_task(tasks[0], seed), **shared)
        return dict(schema='gateway.coding-task-catalog.v2', preset=preset, seed=seed,
                    tasks=[public_task(task, seed) for task in tasks],
                    manifestDigest=digest([task['taskDigest'] for task in tasks]), **shared)

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

    def cancel_request(self, principal, value):
        if not isinstance(value, dict) or set(value) != {'request_id'}:
            raise ValueError('Use the original benchmark request identity')
        with self.runtime.gateway_service.lock:
            run = self.store.retire_request(principal, value['request_id'])
            if run is not None and run['state'] not in TERMINAL:
                self.cancel(run['id'], principal)
            if run is not None and run['id'] not in self.active:
                self.reconcile_external(run['id'], principal, force=True)
            return self.store.submission(principal, value['request_id'])

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
            if self.store.path != data / 'benchmarks.sqlite3':
                if self.store.path.exists():
                    # Classify the old durable dispatches before checking their
                    # ownership; the new journal cannot settle an old workflow.
                    self.store.recover()
                if self.sandbox_claims() or self.external_cleanups():
                    raise RuntimeError('The original benchmark journal still owns unsettled execution')
                self.store = BenchmarkStore(data / 'benchmarks.sqlite3')
                self.qualifications.store = self.store
            self.root = Path(os.environ.get('GATEWAY_DESKTOP_ROOT', str(data.parent if data.name == 'data' else data)))
        if not getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False) and not self.store.path.exists():
            return
        self.store.recover()
        self.stop.clear()
        self.draining = False
        self.cleanup_after = 0.0
        self.external_cleanup_after = 0.0
        self.thread = threading.Thread(target=self.work, name='gateway-benchmark-coordinator', daemon=True)
        self.thread.start()

    def work(self):
        while not self.stop.is_set():
            try:
                local_settled = self.reconcile_sandboxes()
                external_settled = self.reconcile_external()
                settled = local_settled and external_settled
                ids = self.store.pending() if getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False) else []
                if settled and ids and not getattr(self.runtime, 'gateway_draining', False):
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
            if self.unconfirmed_sandboxes() or self.external_cleanups():
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
            if run['request']['suite'] in {'coding', 'coding_submission'} and not self.engineering.authorized({'principal': owner}):
                raise PermissionError('Engineering authority was revoked')
            if run['request'].get('execution_binding_id'):
                binding, _, _ = self.engineering.bindings.validate(owner, run['request']['execution_binding_id'])
                if binding != run['provenance'].get('selectedBinding'):
                    raise ValueError('SELECTED_EXECUTION_CHANGED')
            return self.identity()
        runner = None
        try:
            def qualify_live(run_value, task, candidate, grade, cancellation):
                from benchmarks.live_github import qualify
                return qualify(self, owner, run_value, task, candidate, grade, cancellation)
            context_ranker = None
            if 'SEMANTIC' in run['request'].get('context_variants', []):
                from benchmarks.context_semantic import ContextSemanticRanker
                def guard_context():
                    if identity() != run['provenance']['executionIdentity']:
                        raise ValueError('SELECTED_EXECUTION_CHANGED')
                    if self.stop.is_set() or self.draining or self.runtime.gateway_service.draining or getattr(self.runtime, 'gateway_draining', False):
                        raise InterruptedError('Benchmark stopped before local context ranking')
                context_ranker = ContextSemanticRanker(self.engineering, run, owner, cancel, guard_context)
            runner = BenchmarkRunner(self.store, self.origin(), cancel, identity, lambda: load_settings(self.root),
                                     external_qualifier=qualify_live, context_ranker=context_ranker)
            summary = runner.execute(run)
            self.store.update(ident, state='COMPLETED', summary=summary, endedAt=time.time(), progress='Results saved')
        except InterruptedError as error:
            receipts = getattr(runner, 'cancellation_receipts', [])
            # The runner's richer case receipt and the raised exception can
            # describe the same cancelled HTTP job. Keep the case/stage while
            # avoiding duplicate shutdown requests in the final evidence.
            raised = getattr(error, 'cancellation', None)
            if raised:
                key = 'jobId' if raised.get('jobId') else 'container' if raised.get('container') else None
                seen = any(row.get('outcome') == raised.get('outcome') and
                           (row.get(key) == raised.get(key) if key else row == raised)
                           for row in receipts)
                if not seen:
                    receipts = [*receipts, raised]
            unknown = any(row.get('outcome') not in {'confirmed', 'terminal', 'not_canceled'} for row in receipts)
            state = 'INTERRUPTED' if self.stop.is_set() or unknown else 'CANCELLED'
            self.store.update(ident, state=state, endedAt=time.time(),
                              progress='Benchmark stopped; execution shutdown is unconfirmed' if unknown else 'Stopped; completed measurements remain saved',
                              failure={'code': 'CANCELLATION_UNCONFIRMED' if unknown else 'BENCHMARK_STOPPED', 'cancellationReceipts': receipts})
        except (SandboxUnavailable, PermissionError) as error:
            self.store.update(ident, state='BLOCKED', endedAt=time.time(), progress=str(error)[:240],
                              failure={'code': 'SANDBOX_SETUP_REQUIRED' if isinstance(error, SandboxUnavailable) else 'AUTHORITY_REVOKED',
                                        **({'reason': error.reason} if isinstance(error, SandboxUnavailable) else {})})
        except Exception as error:
            # Never persist exception text, server bodies or generated source.
            receipt = benchmark_error_receipt(error, 'RUN_EXECUTION')
            self.store.update(ident, state='FAILED', endedAt=time.time(), progress='The run stopped. Open Evidence for the retained outcome.',
                              failure=dict(receipt, code=receipt['errorCode']))
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
        except (ValueError, TypeError) as error:
            specific = {'TASK_REVISION_CHANGED', 'GRADER_REVISION_CHANGED', 'PROPOSAL_IDENTITY_CHANGED', 'BENCHMARK_ENVIRONMENT_CHANGED'}
            return JSONResponse({'error': {'code': str(error) if str(error) in specific else 'invalid_benchmark_request'}}, status_code=400)
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
            value = json.loads(raw)
            if value.get('suite') == 'coding_submission':
                raise ValueError('Use the one-task evaluation endpoint')
            return service.create(principal, value)
        return await invoke(submit)

    @app.get('/gateway/benchmarks/fixtures')
    async def fixtures(request: Request):
        def read():
            owner(request)
            return service.fixture_catalog(preset=request.query_params.get('preset', 'quick'),
                                           seed=int(request.query_params.get('seed', '17')), task_pack=request.query_params.get('task_pack', 'python'))
        return await invoke(read)

    @app.get('/gateway/benchmarks/fixtures/{task_id}')
    async def fixture(task_id: str, request: Request):
        def read():
            owner(request)
            return service.fixture_catalog(task_id=task_id, seed=int(request.query_params.get('seed', '17')), task_pack=request.query_params.get('task_pack', 'python'))
        return await invoke(read)

    @app.get('/gateway/benchmarks/capabilities')
    async def capabilities(request: Request):
        def read():
            owner(request)
            catalog = service.fixture_catalog()
            return {key: value for key, value in catalog.items() if key != 'tasks'} | {
                'oneTaskEvaluation': True, 'clientModelGeneration': True, 'nativeRemoteProfileGeneration': False,
                'evidenceLanes': ['EXECUTABLE', 'LIVE_GITHUB'], 'liveRepositoryRequired': True}
        return await invoke(read)

    @app.post('/gateway/benchmarks/cancel-request')
    async def cancel_request(request: Request):
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 4096:
                return JSONResponse({'error': {'code': 'benchmark_request_too_large'}}, status_code=413)
        return await invoke(lambda: service.cancel_request(owner(request).id, json.loads(raw)))

    @app.post('/gateway/benchmarks/evaluate')
    async def evaluate(request: Request):
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 262144:
                return JSONResponse({'error': {'code': 'benchmark_request_too_large'}}, status_code=413)
        return await invoke(lambda: service.evaluate(owner(request), json.loads(raw)))

    @app.get('/gateway/benchmarks/qualifications')
    async def qualifications(request: Request):
        return await invoke(lambda: service.qualifications.history(owner(request).id, request.query_params.get('request_id')))

    @app.post('/gateway/benchmarks/qualifications')
    async def create_qualification(request: Request):
        raw = bytearray()
        async for chunk in request.stream():
            raw.extend(chunk)
            if len(raw) > 262144:
                return JSONResponse({'error': {'code': 'benchmark_request_too_large'}}, status_code=413)
        return await invoke(lambda: service.qualifications.create(owner(request), json.loads(raw)))

    @app.get('/gateway/benchmarks/qualifications/{ident}')
    async def read_qualification(ident: str, request: Request):
        return await invoke(lambda: service.qualifications.refresh(owner(request).id, ident))

    @app.post('/gateway/benchmarks/qualifications/{ident}/resume')
    async def resume_qualification(ident: str, request: Request):
        return await invoke(lambda: service.qualifications.refresh(owner(request).id, ident, execute=True))

    @app.get('/gateway/benchmarks/{ident}')
    async def snapshot(ident: str, request: Request):
        def read():
            run = service.store.get(ident, owner(request).id)
            if run is None:
                raise KeyError(ident)
            return run
        return await invoke(read)

    @app.get('/gateway/benchmarks/{ident}/cases')
    async def case_page(ident: str, request: Request):
        def read():
            through = request.query_params.get('through')
            return service.store.case_page(ident, owner(request).id,
                                           after=int(request.query_params.get('after', '-1')),
                                           limit=int(request.query_params.get('limit', '25')),
                                           through=int(through) if through is not None else None)
        return await invoke(read)

    @app.post('/gateway/benchmarks/{ident}/cancel')
    async def cancel(ident: str, request: Request):
        return await invoke(lambda: service.cancel(ident, owner(request).id))

    @app.delete('/gateway/benchmarks/{ident}')
    async def remove(ident: str, request: Request):
        return await invoke(lambda: {'removed': service.store.remove(ident, owner(request).id)})
