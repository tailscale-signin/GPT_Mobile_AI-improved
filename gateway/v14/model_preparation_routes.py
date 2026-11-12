"""Admitted, durable exact-model load actions; no generation or selector fallback."""
import asyncio
import copy
import json
import os
import re
import sqlite3
import threading
import time
import uuid
from contextlib import asynccontextmanager
from types import SimpleNamespace

from fastapi import Request
from fastapi.responses import JSONResponse

from v14.conversation_policy import verify_mutation_identity
from v14.execution_binding import ExecutionBindingError, configured_route, fingerprint
from v14.inference_capabilities import InferenceCapabilities
from v14.model_preparation import prepare_model, preparation_receipt

MAX_RECORDS = 16384
MAX_RECEIPT_BYTES = 16384
RETENTION_SECONDS = 30 * 86400
PREPARATION_SECONDS = 20
PENDING = {'PREPARING', 'WAITING_FOR_MODEL', 'LOAD_OUTCOME_UNKNOWN'}


class PreparationUnavailable(ExecutionBindingError):
    def __init__(self, code='MODEL_PREPARATION_STORAGE_UNAVAILABLE'):
        super().__init__(code, 'Model preparation is unavailable. Retain the operation identity and recheck the exact model.', 503)
        self.retryable = True


class PreparationChanged(Exception):
    pass


class PreparationCancelled(Exception):
    pass


class ModelPreparationService:
    def __init__(self, capabilities, *, max_active=2, max_records=MAX_RECORDS, deadline_seconds=PREPARATION_SECONDS,
                 observation_interval=5):
        self.capabilities, self.runtime = capabilities, capabilities.runtime
        self.max_active, self.max_records, self.deadline_seconds = max_active, max_records, deadline_seconds
        self.lock, self.running = threading.RLock(), set()
        self.observation_interval = observation_interval
        self.observation_stop, self.observation_thread = threading.Event(), None
        self.observation_cursor = ''

    def lifecycle(self, existing):
        @asynccontextmanager
        async def lifecycle(app):
            async with existing(app):
                self.observation_stop.clear()

                def observe():
                    while not self.observation_stop.is_set():
                        self.reconcile_pending(self.observation_stop)
                        self.observation_stop.wait(self.observation_interval)

                self.observation_thread = threading.Thread(target=observe, name='model-preparation-observer', daemon=True)
                self.observation_thread.start()
                try:
                    yield
                finally:
                    self.observation_stop.set()
                    # Each pass has one shared metadata deadline, including all
                    # records. Shutdown never leaves an unbounded load worker.
                    await asyncio.to_thread(self.observation_thread.join, min(self.deadline_seconds, 5) + 1)
        return lifecycle

    def _pending_records(self, route):
        if not getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False) or not self.runtime.durable_job_initialized:
            return []
        with self.runtime.durable_job_lock:
            db = self.runtime._durable_connect()
            try:
                db.row_factory = sqlite3.Row
                exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_model_preparations'").fetchone()
                if not exists:
                    return []
                def read(cursor):
                    return db.execute('''SELECT * FROM gateway_model_preparations
                        WHERE unresolved=1 AND route=? AND id>? ORDER BY id LIMIT ?''',
                        (route['runtime_config_revision'], cursor, min(self.max_active, 8))).fetchall()
                rows = read(self.observation_cursor) or read('')
                self.observation_cursor = rows[-1]['id'] if rows else ''
                return [self._record(row) for row in rows]
            finally:
                db.close()

    def _observer(self, route, provider, deadline_seconds):
        frozen_runtime = SimpleNamespace(LLAMA_BASE=route['endpoint'], GATEWAY_INFERENCE_PROVIDER=provider,
                                         GATEWAY_INFERENCE_CONNECTION_ID=route['backend_connection_id'])
        for name in ('http_get', 'get_http_session'):
            if hasattr(self.runtime, name):
                setattr(frozen_runtime, name, getattr(self.runtime, name))
        return InferenceCapabilities(frozen_runtime, deadline_at=time.monotonic() + deadline_seconds)

    def reconcile_pending(self, stopped=None):
        """Observe only previously admitted loads while a phone is detached.

        A fresh exact loaded observation retires the old fence before a later
        sleep. Unknown or absent models retain it indefinitely; this path never
        invokes prepare_model or sends a load/inference request.
        """
        try:
            provider = str(getattr(self.runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA').upper()
            if provider != 'LLAMA':
                return
            route = configured_route(self.runtime, provider)
            records = self._pending_records(route)
            bounded = self._observer(route, provider, min(self.deadline_seconds, 5))
            for record in records:
                if stopped is not None and stopped.is_set():
                    return
                with self.lock:
                    running = record['id'] in self.running
                if running or record['receipt']['state'] == 'PREPARING':
                    continue
                if configured_route(self.runtime, provider)['runtime_config_revision'] != route['runtime_config_revision']:
                    return
                selected = bounded.selected_model(record['model'], force=True)
                self.observe_loaded(record['model'], selected, expected_route=route)
        except (ExecutionBindingError, sqlite3.Error, OSError, ValueError, TypeError):
            # An observation outage cannot erase a dispatch fence or authorize
            # another mutation. A later bounded pass retries only metadata.
            return

    def _tx(self, action):
        runtime = self.runtime
        if not getattr(runtime, 'GATEWAY_DURABLE_JOBS', False):
            raise PreparationUnavailable()
        try:
            with runtime.durable_job_lock:
                if not runtime.durable_job_initialized and not runtime.init_durable_job_store():
                    raise PreparationUnavailable()
                db = runtime._durable_connect()
                try:
                    db.row_factory = sqlite3.Row
                    with db:
                        db.execute('BEGIN IMMEDIATE')
                        db.execute('''CREATE TABLE IF NOT EXISTS gateway_model_preparations (
                            id TEXT PRIMARY KEY, target TEXT NOT NULL, route TEXT NOT NULL, model TEXT NOT NULL,
                            receipt TEXT NOT NULL, unresolved INTEGER NOT NULL, revision INTEGER NOT NULL,
                            created_at REAL NOT NULL, updated_at REAL NOT NULL)''')
                        db.execute('''CREATE UNIQUE INDEX IF NOT EXISTS gateway_model_preparations_pending
                            ON gateway_model_preparations(target) WHERE unresolved=1''')
                        db.execute('''CREATE TABLE IF NOT EXISTS gateway_model_preparation_requests (
                            principal TEXT NOT NULL, operation TEXT NOT NULL, route TEXT NOT NULL, model TEXT NOT NULL,
                            preparation TEXT NOT NULL, created_at REAL NOT NULL, PRIMARY KEY(principal, operation))''')
                        return action(db)
                finally:
                    db.close()
        except (sqlite3.Error, OSError, ValueError, TypeError) as error:
            if isinstance(error, ExecutionBindingError):
                raise
            raise PreparationUnavailable() from error

    @staticmethod
    def _record(row):
        return dict(row, receipt=json.loads(row['receipt']))

    def _admit(self, principal, operation, model, route):
        revision = route['runtime_config_revision']
        target = fingerprint({'endpoint': route['endpoint_identity'], 'provider': route['provider_kind'], 'model': model})

        def admit(db):
            now = time.time()
            # An unresolved dispatch never expires into permission to load again.
            expired = now - RETENTION_SECONDS
            db.execute('''DELETE FROM gateway_model_preparation_requests WHERE preparation IN
                (SELECT id FROM gateway_model_preparations WHERE unresolved=0 AND updated_at<?)''', (expired,))
            db.execute('DELETE FROM gateway_model_preparations WHERE unresolved=0 AND updated_at<?', (expired,))
            old = db.execute('SELECT * FROM gateway_model_preparation_requests WHERE principal=? AND operation=?',
                             (principal, operation)).fetchone()
            if old:
                if old['route'] != revision or old['model'] != model:
                    raise ExecutionBindingError('MODEL_PREPARATION_OPERATION_CONFLICT',
                        'The operation identity already belongs to a different exact model or backend configuration.', 409)
                return self._record(db.execute('SELECT * FROM gateway_model_preparations WHERE id=?',
                                               (old['preparation'],)).fetchone())
            if db.execute('SELECT COUNT(*) FROM gateway_model_preparation_requests').fetchone()[0] >= self.max_records:
                raise PreparationUnavailable('MODEL_PREPARATION_CAPACITY')
            active = db.execute('SELECT * FROM gateway_model_preparations WHERE target=? AND unresolved=1', (target,)).fetchone()
            if active and active['route'] != revision:
                raise ExecutionBindingError('MODEL_PREPARATION_CONFIGURATION_CONFLICT',
                    'The previous load on this exact backend is unresolved. Reconcile it before changing its configuration.', 409)
            if active is None:
                if db.execute('SELECT COUNT(*) FROM gateway_model_preparations').fetchone()[0] >= self.max_records:
                    raise PreparationUnavailable('MODEL_PREPARATION_CAPACITY')
                receipt = dict(preparation_receipt(model), state='PREPARING', observedAtMillis=int(now * 1000))
                identifier = uuid.uuid4().hex
                db.execute('INSERT INTO gateway_model_preparations VALUES(?,?,?,?,?,1,0,?,?)',
                           (identifier, target, revision, model, json.dumps(receipt), now, now))
                active = db.execute('SELECT * FROM gateway_model_preparations WHERE id=?', (identifier,)).fetchone()
            db.execute('INSERT INTO gateway_model_preparation_requests VALUES(?,?,?,?,?,?)',
                       (principal, operation, revision, model, active['id'], now))
            return self._record(active)
        return self._tx(admit)

    def _read(self, identifier):
        return self._tx(lambda db: self._record(db.execute('SELECT * FROM gateway_model_preparations WHERE id=?',
                                                         (identifier,)).fetchone()))

    def _pending(self, target, route):
        # Normal metadata polls neither create this schema nor start a write
        # transaction. Only an existing explicit preparation can be reconciled.
        with self.runtime.durable_job_lock:
            db = self.runtime._durable_connect()
            try:
                db.row_factory = sqlite3.Row
                exists = db.execute("SELECT 1 FROM sqlite_master WHERE type='table' AND name='gateway_model_preparations'").fetchone()
                row = db.execute('SELECT * FROM gateway_model_preparations WHERE target=? AND route=? AND unresolved=1',
                                 (target, route)).fetchone() if exists else None
                return self._record(row) if row else None
            finally:
                db.close()

    def observe_loaded(self, model, selected, *, expected_route=None):
        """Retire unresolved intent on exact passive readback, never dispatch a load.

        This matters when the app has detached after a load and only polls its
        normal metadata route. A later backend eviction can then be prepared by
        a new operation without confusing it with the earlier resolved load.
        """
        if (not getattr(self.runtime, 'GATEWAY_DURABLE_JOBS', False) or
                selected.get('requestedModel') != model or selected.get('reportedModel') != model or
                selected.get('limitsVerified') is not True or selected.get('modelLoaded') is not True or
                selected.get('modelState') != 'LOADED'):
            return
        measured, age = selected.get('observedAtMillis'), selected.get('observationAgeMs')
        if type(measured) is not int or not 0 <= measured <= int(time.time() * 1000) or type(age) is not int or not 0 <= age <= 3000:
            return
        try:
            provider = str(getattr(self.runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA').upper()
            route = configured_route(self.runtime, provider)
            if expected_route is not None and route['runtime_config_revision'] != expected_route['runtime_config_revision']:
                return
            target = fingerprint({'endpoint': route['endpoint_identity'], 'provider': route['provider_kind'], 'model': model})
            record = self._pending(target, route['runtime_config_revision'])
            if record and measured >= int(record['updated_at'] * 1000):
                self._save(record, dict(record['receipt'], state='METADATA_QUALIFIED', modelState='LOADED',
                                        selectedModel=copy.deepcopy(selected)))
        except (ExecutionBindingError, sqlite3.Error, OSError, ValueError, TypeError):
            # A journal outage does not invalidate a successful passive metadata
            # observation or authorize any external mutation.
            return

    def _save(self, record, receipt, *, dispatch=False):
        receipt = dict(receipt, observedAtMillis=int(time.time() * 1000))
        encoded = json.dumps(receipt, ensure_ascii=False, allow_nan=False)
        if len(encoded.encode('utf-8')) > MAX_RECEIPT_BYTES:
            raise PreparationUnavailable('MODEL_PREPARATION_RECEIPT_OVERSIZE')
        unresolved = receipt['state'] in PENDING

        def save(db):
            current = self._record(db.execute('SELECT * FROM gateway_model_preparations WHERE id=?', (record['id'],)).fetchone())
            if current['revision'] != record['revision']:
                if dispatch:
                    raise PreparationChanged()
                return current
            if dispatch and (current['receipt']['loadRequests'] or not current['unresolved']):
                raise PreparationChanged()
            db.execute('UPDATE gateway_model_preparations SET receipt=?,unresolved=?,revision=revision+1,updated_at=? WHERE id=?',
                       (encoded, int(unresolved), time.time(), record['id']))
            return self._record(db.execute('SELECT * FROM gateway_model_preparations WHERE id=?', (record['id'],)).fetchone())
        return self._tx(save)

    @staticmethod
    def validate(model, operation):
        if not isinstance(model, str) or not 1 <= len(model) <= 512 or model != model.strip() or any(ord(c) < 32 for c in model):
            raise ExecutionBindingError('EXECUTION_MODEL_IDENTITY_INVALID', 'Select a bounded exact model identifier.', 400)
        if not isinstance(operation, str) or re.fullmatch(r'[A-Za-z0-9._~-]{1,128}', operation) is None:
            raise ExecutionBindingError('MODEL_PREPARATION_OPERATION_INVALID', 'Supply a bounded operation identity.', 400)

    def prepare(self, principal, model, operation, cancelled=None):
        self.validate(model, operation)
        provider = str(getattr(self.runtime, 'GATEWAY_INFERENCE_PROVIDER', None) or os.getenv('GATEWAY_INFERENCE_PROVIDER') or 'LLAMA').upper()
        route = configured_route(self.runtime, provider)
        with self.lock:
            record = self._admit(principal, operation, model, route)
            if not record['unresolved'] or record['id'] in self.running:
                return dict(copy.deepcopy(record['receipt']), operationId=operation)
            if len(self.running) >= self.max_active:
                raise PreparationUnavailable('MODEL_PREPARATION_BUSY')
            self.running.add(record['id'])
        try:
            self.capabilities.invalidate_model(route, model)
            # A fresh bounded observer avoids holding another model's metadata
            # cache gate and shares one deadline across every HTTP exchange.
            bounded = self._observer(route, provider, self.deadline_seconds)

            def before_dispatch(receipt):
                nonlocal record
                if cancelled is not None and cancelled.is_set():
                    raise PreparationCancelled()
                current = configured_route(self.runtime, provider)
                if current['runtime_config_revision'] != route['runtime_config_revision']:
                    raise ExecutionBindingError('MODEL_PREPARATION_CONFIGURATION_CONFLICT',
                        'The configured backend changed before load dispatch. Recheck the original exact selection.', 409)
                record = self._save(record, receipt, dispatch=True)
                self.capabilities.invalidate_model(route, model)

            prior = record['receipt'] if record['receipt']['state'] != 'PREPARING' else None
            receipt = prepare_model(bounded, model, before_dispatch=before_dispatch, prior=prior, expected_route=route)
            record = self._save(record, receipt)
        except PreparationCancelled:
            record = self._save(record, dict(preparation_receipt(model), reasonCode='MODEL_PREPARATION_CANCELLED'))
        except PreparationChanged:
            record = self._read(record['id'])
        except ExecutionBindingError as error:
            if not record['receipt']['loadRequests']:
                # Configuration rejection before the fence is a known absence
                # of dispatch; do not strand an unstarted claim forever.
                self._save(record, dict(preparation_receipt(model), reasonCode=error.code))
            raise
        finally:
            self.capabilities.invalidate_model(route, model)
            with self.lock:
                self.running.discard(record['id'])
        return dict(copy.deepcopy(record['receipt']), operationId=operation)


def install_model_preparation_routes(app, service, installation):
    @app.post('/gateway/models/prepare')
    @app.post('/v1/gateway/models/prepare')
    async def prepare(request: Request):
        try:
            principal = request.state.principal
            if not {'chat', 'tools'} <= principal.scopes:
                return JSONResponse({'error': {'code': 'model_preparation_scope_required'}}, status_code=403)
            verify_mutation_identity(request.headers, principal.id, installation)
            raw = await request.body()
            if len(raw) > 4096:
                return JSONResponse({'error': {'code': 'model_preparation_body_too_large'}}, status_code=413)
            try:
                body = json.loads(raw)
            except (ValueError, TypeError, UnicodeError, RecursionError) as error:
                raise ExecutionBindingError('MODEL_PREPARATION_REQUEST_INVALID', 'Supply a valid bounded JSON object.', 400) from error
            if not isinstance(body, dict) or set(body) != {'model', 'operationId'}:
                raise ExecutionBindingError('MODEL_PREPARATION_REQUEST_INVALID', 'Supply only an exact model and operationId.', 400)
            operation = body['operationId']
            supplied = request.headers.get('idempotency-key')
            if supplied is not None and supplied != operation:
                raise ExecutionBindingError('MODEL_PREPARATION_OPERATION_CONFLICT', 'The idempotency header must match operationId.', 409)
            # Cancelling this await does not cancel its bounded worker or erase
            # the durable dispatch fence. A later request reconciles the same load.
            cancelled = threading.Event()
            try:
                receipt = await asyncio.to_thread(service.prepare, principal.id, body['model'], operation, cancelled)
            except asyncio.CancelledError:
                cancelled.set()
                raise
            pending = receipt['state'] in PENDING
            return JSONResponse(receipt, status_code=202 if pending else 200,
                                headers={'Retry-After': '1'} if pending else None)
        except PreparationUnavailable as error:
            return JSONResponse({'error': dict(error.error_payload(), inference_attempted=False, dispatch_state='unknown')},
                                status_code=error.status, headers={'Retry-After': '2'})
        except ExecutionBindingError as error:
            return JSONResponse({'error': error.error_payload(stage='provider_admission')}, status_code=error.status)
        except (ValueError, TypeError, UnicodeError):
            # An unexpected observation/encoding failure may follow dispatch.
            # It is never a predispatch 4xx or permission to try a new operation.
            return JSONResponse({'error': {'code': 'MODEL_PREPARATION_OBSERVATION_UNAVAILABLE', 'retryable': True,
                                           'inference_attempted': False, 'dispatch_state': 'unknown'}}, status_code=503)
