"""Real admission/routes/SQLite around one exact external router load boundary."""
import asyncio
import json
import sqlite3
import sys
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from contextlib import asynccontextmanager, closing
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

import httpx
import requests
from fastapi import FastAPI
from fastapi.testclient import TestClient

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from gateway_security import DeviceStore
from v14.config import Config
from v14.identity import Admission
from v14.inference_capabilities import InferenceCapabilities
from v14.model_preparation_routes import ModelPreparationService, PreparationUnavailable, install_model_preparation_routes
from test_gateway_model_preparation import Response


class ModelPreparationHttpTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.path = Path(self.directory.name) / 'jobs.db'
        self.states, self.calls = {'selected': 'unloaded', 'other': 'unloaded'}, []
        self.get_hook = self.post_hook = None
        self.devices = DeviceStore(Path(self.directory.name) / 'auth.db')
        self.runtime = SimpleNamespace(LLAMA_BASE='http://127.0.0.1:8080/v1', GATEWAY_INFERENCE_PROVIDER='LLAMA',
            GATEWAY_DURABLE_JOBS=True, durable_job_initialized=True, durable_job_lock=threading.RLock(),
            _durable_connect=self.connect, http_get=self.get, get_http_session=lambda: SimpleNamespace(post=self.post))
        self.capabilities = InferenceCapabilities(self.runtime)
        self.service = ModelPreparationService(self.capabilities)

    def connect(self):
        db = sqlite3.connect(self.path, timeout=2)
        db.execute('PRAGMA synchronous=FULL')
        return db

    def get(self, url, **kwargs):
        self.calls.append(('GET', url, kwargs))
        if self.get_hook:
            self.get_hook(url, kwargs)
        self.assertEqual('false', kwargs.get('params', {}).get('autoload'))
        if url.endswith('/models'):
            return Response({'data': [{'id': model, 'status': {'value': state}} for model, state in self.states.items()]})
        if url.endswith('/props'):
            model = kwargs['params']['model']
            return Response({'model_alias': model, 'default_generation_settings': {'n_ctx': 8192}})
        raise AssertionError('Unexpected metadata route: ' + url)

    def post(self, url, **kwargs):
        self.calls.append(('POST', url, kwargs))
        self.assertIn(url, ('http://127.0.0.1:8080/models/load', 'http://127.0.0.1:8080/tokenize'))
        model = kwargs['json']['model']
        waking = url.endswith('/tokenize')
        if waking:
            self.assertEqual({'model': model, 'content': '', 'add_special': False}, kwargs['json'])
            self.assertEqual({'autoload': 'false'}, kwargs['params'])
        # Check the real committed SQLite fence at the external dispatch boundary.
        # sqlite3's context manager commits/rolls back but does not close the
        # handle. Windows keeps jobs.db locked until it is explicitly closed.
        with closing(self.connect()) as db:
            rows = db.execute('SELECT receipt FROM gateway_model_preparations WHERE model=? AND unresolved=1', (model,)).fetchall()
        self.assertEqual(1, len(rows))
        self.assertEqual(1, json.loads(rows[0][0])['loadRequests'])
        if self.post_hook:
            return self.post_hook(url, kwargs)
        self.states[model] = 'loaded'
        return Response({'tokens': []} if waking else {'success': True})

    def app(self, config=None, service=None, lifecycle=False):
        active = service or self.service
        @asynccontextmanager
        async def parent(app):
            yield
        app = FastAPI(lifespan=active.lifecycle(parent) if lifecycle else None)
        install_model_preparation_routes(app, active, lambda: 'installation-fixture')
        app.add_middleware(Admission, config=(config or Config()).validate(), store=self.devices)
        return app

    def client(self, config=None, service=None, peer='127.0.0.1', lifecycle=False):
        client = TestClient(self.app(config, service, lifecycle), base_url='http://127.0.0.1:8090', client=(peer, 1234))
        self.addCleanup(client.close)
        return client

    def request(self, client=None, *, model='selected', operation='selection-1', path='/gateway/models/prepare', headers=None):
        return (client or self.client()).post(path, json={'model': model, 'operationId': operation}, headers=headers)

    def mutations(self):
        return [call for call in self.calls if call[0] == 'POST']

    def test_http_aliases_load_exact_model_once_and_replay_saved_receipt(self):
        first = self.request()
        self.assertEqual(200, first.status_code, first.text)
        body = first.json()
        self.assertEqual('METADATA_QUALIFIED', body['state'])
        self.assertEqual('selected', body['requestedModel'])
        self.assertEqual('selected', body['selectedModel']['reportedModel'])
        self.assertEqual('selection-1', body['operationId'])
        self.assertEqual('DISPATCHED', body['dispatchState'])
        self.assertTrue(body['loadAccepted'])
        self.assertEqual(1, body['loadRequests'])
        self.assertFalse(body['inferenceTested'])
        self.assertFalse(body['automaticSubstitution'])
        self.assertFalse(body['reasoningSettingsChanged'])
        self.assertEqual(body, self.request(path='/v1/gateway/models/prepare').json())
        self.assertEqual([{'model': 'selected'}], [call[2]['json'] for call in self.mutations()])
        self.assertEqual('no-store', first.headers['cache-control'])

    def test_existing_loaded_and_loading_models_receive_no_mutation(self):
        for state, expected_status, expected_state in [('loaded', 200, 'METADATA_QUALIFIED'), ('loading', 202, 'WAITING_FOR_MODEL')]:
            with self.subTest(state=state):
                self.states['selected'] = state
                response = self.request(operation=state)
                self.assertEqual(expected_status, response.status_code)
                self.assertEqual(expected_state, response.json()['state'])
                self.assertEqual('NOT_DISPATCHED', response.json()['dispatchState'])
                self.assertEqual(0, response.json()['loadRequests'])
        self.assertEqual([], self.mutations())

    def test_sleep_wake_is_durable_and_duplicate_operation_keeps_its_exact_model(self):
        self.states['selected'] = 'sleeping'
        first = self.request()
        self.assertEqual(200, first.status_code)
        self.assertEqual('METADATA_QUALIFIED', first.json()['state'])
        self.assertEqual('WAKE', first.json()['preparationAction'])
        self.assertEqual(first.json(), self.request().json())
        self.assertEqual(409, self.request(model='other').status_code)
        self.assertEqual('unloaded', self.states['other'])
        self.assertEqual(['http://127.0.0.1:8080/tokenize'], [call[1] for call in self.mutations()])

    def test_uncertain_wake_survives_restart_and_new_selection_without_replay(self):
        self.states['selected'] = 'sleeping'
        def lost(*args):
            raise requests.ReadTimeout('private wake response')
        self.post_hook = lost
        first = self.request()
        self.assertEqual('LOAD_OUTCOME_UNKNOWN', first.json()['state'])
        self.assertIsNone(first.json()['loadAccepted'])
        restarted = self.client(service=ModelPreparationService(InferenceCapabilities(self.runtime)))
        for operation in ('selection-1', 'selection-2'):
            replay = self.request(restarted, operation=operation)
            self.assertEqual('SLEEPING', replay.json()['modelState'])
            self.assertEqual('WAKE', replay.json()['preparationAction'])
            self.assertIsNone(replay.json()['loadAccepted'])
        self.assertEqual(1, len(self.mutations()))
        self.states['selected'] = 'loaded'
        self.assertEqual('METADATA_QUALIFIED', self.request(restarted, operation='selection-2').json()['state'])
        self.assertEqual(1, len(self.mutations()))

    def test_wake_auth_rejection_keeps_safe_evidence_and_never_unloads(self):
        self.states['selected'] = 'sleeping'
        self.post_hook = lambda *args: Response({'error': {'message': 'private token', 'code': 'private-code'}}, status=403)
        response = self.request()
        self.assertEqual('LOAD_REJECTED', response.json()['state'])
        self.assertEqual('WAKE', response.json()['preparationAction'])
        self.assertFalse(response.json()['loadAccepted'])
        self.assertEqual(403, response.json()['failure']['upstreamStatus'])
        self.assertNotIn('private', response.text)
        self.assertEqual(response.json(), self.request().json())
        self.assertEqual(1, len(self.mutations()))

    def test_cancelling_dispatched_wake_cannot_repeat_it(self):
        self.states['selected'] = 'sleeping'
        entered, release = threading.Event(), threading.Event()
        def blocked(url, kwargs):
            entered.set()
            self.assertTrue(release.wait(3))
            self.states['selected'] = 'loaded'
            return Response({'tokens': []})
        self.post_hook = blocked
        asyncio.run(self.cancel_request(entered, release))
        receipt = self.request().json()
        self.assertEqual('METADATA_QUALIFIED', receipt['state'])
        self.assertEqual('WAKE', receipt['preparationAction'])
        self.assertEqual(1, len(self.mutations()))

    def test_invalid_wake_receipt_retains_unknown_acceptance_without_replay(self):
        self.states['selected'] = 'sleeping'
        self.post_hook = lambda *args: Response({'tokens': 'private-invalid-reply'})
        first = self.request()
        self.assertEqual('LOAD_OUTCOME_UNKNOWN', first.json()['state'])
        self.assertIsNone(first.json()['loadAccepted'])
        self.assertEqual('invalid_response', first.json()['failure']['failureCategory'])
        self.assertNotIn('private', first.text)
        self.assertEqual('LOAD_OUTCOME_UNKNOWN', self.request(operation='new-selection').json()['state'])
        self.assertEqual(1, len(self.mutations()))

    def test_legacy_loaded_catalogue_cannot_retire_uncertain_wake_after_failed_readiness(self):
        for failure_kind, accepted in ((kind, accepted) for kind in ('sleeping', 'denied', 'unavailable') for accepted in (False, True)):
            with self.subTest(failure_kind=failure_kind, accepted=accepted):
                model = 'legacy-' + failure_kind + str(accepted)
                self.states[model] = 'loaded'
                dispatches = []
                def legacy_get(url, **kwargs):
                    self.calls.append(('GET', url, kwargs))
                    self.assertEqual('false', kwargs['params']['autoload'])
                    if url.endswith('/models'):
                        return Response({'data': [{'id': name, 'status': {'value': state}} for name, state in self.states.items()]})
                    if dispatches and failure_kind != 'sleeping':
                        return Response({'error': {'code': 'model_not_found' if failure_kind == 'unavailable' else 'private', 'message': 'private'}}, 404 if failure_kind == 'unavailable' else 403)
                    return Response({'model_alias': model, 'is_sleeping': True, 'default_generation_settings': {'n_ctx': 8192}})
                def lost(url, kwargs):
                    dispatches.append(url)
                    if accepted:
                        return Response({'tokens': []})
                    raise requests.ReadTimeout('private wake response')
                self.runtime.http_get, self.post_hook = legacy_get, lost
                first = self.request(model=model, operation=model).json()
                self.assertEqual('LOAD_OUTCOME_UNKNOWN', first['state'])
                self.assertEqual(True if accepted else None, first['loadAccepted'])
                self.assertIn('failureCode', first['reconciliationFailure'])
                restarted = self.client(service=ModelPreparationService(InferenceCapabilities(self.runtime)))
                second = self.request(restarted, model=model, operation=model + '-new').json()
                self.assertEqual('LOAD_OUTCOME_UNKNOWN', second['state'])
                self.assertEqual(True if accepted else None, second['loadAccepted'])
                self.assertEqual(1, len(dispatches))
                self.assertNotIn('private', json.dumps(second))

    def test_unadvertised_unknown_and_unsupported_backends_never_load(self):
        for model in ('absent', 'selected'):
            with self.subTest(model=model):
                self.states['selected'] = 'unknown'
                response = self.request(model=model, operation=model)
                self.assertEqual('PRECHECK_REJECTED', response.json()['state'])
                self.assertEqual(0, response.json()['loadRequests'])
        self.runtime.GATEWAY_INFERENCE_PROVIDER = 'OLLAMA'
        response = self.request(operation='unsupported')
        self.assertEqual('MODEL_PREPARATION_UNSUPPORTED', response.json()['reasonCode'])
        self.assertEqual([], self.mutations())

    def test_remote_identity_and_chat_plus_tools_scopes_are_required(self):
        config = Config(mode='trusted_proxy', trusted_users={
            'owner': {'principalId': 'owner', 'scopes': ['chat', 'tools']},
            'reader': {'principalId': 'reader', 'scopes': ['jobs']},
            'chat-only': {'principalId': 'chat-only', 'scopes': ['chat']}})
        client = self.client(config)
        self.assertEqual(401, self.request(client).status_code)
        for user in ('reader', 'chat-only'):
            self.assertEqual(403, self.request(client, headers={'Tailscale-User-Login': user}).status_code)
        self.assertEqual([], self.calls)
        self.assertEqual(200, self.request(client, headers={'Tailscale-User-Login': 'owner'}).status_code)
        self.assertEqual(401, self.request(self.client(peer='100.64.0.5')).status_code)

    def test_open_network_uses_its_admitted_shared_identity_without_backend_admin_access(self):
        client = self.client(Config(mode='open_network', host='0.0.0.0'), peer='100.64.0.5')
        self.assertEqual(200, self.request(client).status_code)
        self.assertEqual(403, client.post('/models/load', json={'model': 'other'}).status_code)
        self.assertEqual(1, len(self.mutations()))

    def test_browser_mutation_requires_the_existing_origin_and_csrf_contract(self):
        client = self.client()
        headers = {'Origin': 'http://127.0.0.1:8090'}
        self.assertEqual(403, self.request(client, headers=headers).status_code)
        self.assertEqual(403, self.request(client, headers={'Origin': 'https://untrusted.invalid'}).status_code)
        self.assertEqual([], self.calls)
        headers['X-Gateway-CSRF'] = client.get('/gateway/session', headers=headers).json()['csrfToken']
        self.assertEqual(200, self.request(client, headers=headers).status_code)

    def test_installation_and_principal_preconditions_reject_before_load(self):
        for headers in ({'X-Gateway-Installation-ID': 'installation-fixture'},
                        {'X-Gateway-Installation-ID': 'other', 'X-Gateway-Principal-ID': 'local:single-user'},
                        {'X-Gateway-Installation-ID': 'installation-fixture', 'X-Gateway-Principal-ID': 'another'}):
            with self.subTest(headers=headers):
                self.assertIn(self.request(headers=headers).status_code, (400, 409))
        self.assertEqual([], self.calls)
        self.assertEqual(200, self.request(headers={'X-Gateway-Installation-ID': 'installation-fixture',
                                                   'X-Gateway-Principal-ID': 'local:single-user'}).status_code)

    def test_operation_identity_is_principal_scoped_and_conflicting_reuse_is_rejected(self):
        self.assertEqual(200, self.request().status_code)
        self.assertEqual(409, self.request(model='other').status_code)
        self.assertEqual(409, self.request(operation='new', headers={'Idempotency-Key': 'different'}).status_code)
        config = Config(mode='trusted_proxy', trusted_users={'other': {'principalId': 'other', 'scopes': ['chat', 'tools']}})
        self.assertEqual(200, self.request(self.client(config), model='other', headers={'Tailscale-User-Login': 'other'}).status_code)
        self.runtime.LLAMA_BASE = 'http://127.0.0.1:8081/v1'
        self.assertEqual(409, self.request().status_code)
        self.assertEqual(2, len(self.mutations()))

    def test_route_change_between_precheck_and_dispatch_never_loads_another_backend(self):
        def change_route(url, kwargs):
            self.runtime.LLAMA_BASE = 'http://127.0.0.1:8081/v1'
        self.get_hook = change_route
        response = self.request()
        self.assertEqual(409, response.status_code)
        self.assertEqual('MODEL_PREPARATION_CONFIGURATION_CONFLICT', response.json()['error']['code'])
        self.assertEqual('not_dispatched', response.json()['error']['dispatch_state'])
        self.assertEqual([], self.mutations())
        self.assertEqual(['http://127.0.0.1:8080/models'], [call[1] for call in self.calls])

    def test_configuration_revision_change_before_dispatch_cannot_reuse_its_old_authority(self):
        import os
        with patch.dict(os.environ, {'GATEWAY_INFERENCE_CONFIG_REVISION': 'original'}):
            self.get_hook = lambda *args: os.environ.update(GATEWAY_INFERENCE_CONFIG_REVISION='changed-credentials')
            response = self.request()
        self.assertEqual(409, response.status_code)
        self.assertEqual('MODEL_PREPARATION_CONFIGURATION_CONFLICT', response.json()['error']['code'])
        self.assertEqual([], self.mutations())
        self.get_hook = None
        self.assertEqual(200, self.request(operation='changed-configuration').status_code)
        self.assertEqual(1, len(self.mutations()))

    def test_bounded_exact_input_and_duplicate_idempotency_headers_are_rejected(self):
        for model, operation in [(' selected ', 'valid'), ('x' * 513, 'valid'), ('', 'valid'),
                                 ('selected', ''), ('selected', 'x' * 129), ('selected', 'bad id')]:
            with self.subTest(model=model[:20], operation=operation[:20]):
                self.assertEqual(400, self.request(model=model, operation=operation).status_code)
        client = self.client()
        for body in ([], {'model': 'selected'}, {'model': 'selected', 'operationId': 'valid', 'fallback': 'other'}):
            self.assertEqual(400, client.post('/gateway/models/prepare', json=body).status_code)
        self.assertEqual(413, client.post('/gateway/models/prepare', content=' ' * 4097).status_code)
        self.assertEqual(400, self.request(headers=[('Idempotency-Key', 'selection-1'), ('Idempotency-Key', 'selection-1')]).status_code)
        self.assertEqual([], self.calls)

    def test_lost_response_and_restart_coalesce_new_operations_without_reposting(self):
        def lost(url, kwargs):
            raise requests.exceptions.ChunkedEncodingError('private backend response')
        self.post_hook = lost
        first = self.request()
        self.assertEqual(202, first.status_code)
        self.assertEqual('LOAD_OUTCOME_UNKNOWN', first.json()['state'])
        restarted = ModelPreparationService(InferenceCapabilities(self.runtime))
        client = self.client(service=restarted)
        for operation in ('selection-1', 'another-selection'):
            response = self.request(client, operation=operation)
            self.assertEqual(202, response.status_code)
            self.assertEqual('UNLOADED', response.json()['modelState'])
            self.assertEqual(1, response.json()['loadRequests'])
            self.assertNotIn('private', response.text)
        self.assertEqual(1, len(self.mutations()))
        self.states['selected'] = 'loaded'
        qualified = self.request(client, operation='another-selection')
        self.assertEqual('METADATA_QUALIFIED', qualified.json()['state'])
        self.assertIsNone(qualified.json()['loadAccepted'])
        self.assertEqual(1, len(self.mutations()))

    def test_accepted_load_with_failed_readback_remains_unresolved_across_restart(self):
        def unavailable_after_dispatch(url, kwargs):
            if self.mutations():
                raise requests.ReadTimeout('private backend')
        self.get_hook = unavailable_after_dispatch
        response = self.request()
        self.assertEqual(202, response.status_code)
        self.assertTrue(response.json()['loadAccepted'])
        self.assertEqual('LOAD_OUTCOME_UNKNOWN', response.json()['state'])
        self.assertEqual('UNKNOWN', response.json()['modelState'])
        client = self.client(service=ModelPreparationService(self.capabilities))
        self.assertEqual(202, self.request(client, operation='reselected').status_code)
        self.assertEqual(1, len(self.mutations()))

    def test_rejected_load_keeps_definite_nonacceptance_when_readback_fails(self):
        self.post_hook = lambda *args: Response({'error': {}}, status=403)
        def unavailable_after_dispatch(url, kwargs):
            if self.mutations():
                raise requests.ReadTimeout('private backend')
        self.get_hook = unavailable_after_dispatch
        response = self.request()
        self.assertEqual(200, response.status_code)
        self.assertFalse(response.json()['loadAccepted'])
        self.assertEqual('LOAD_REJECTED', response.json()['state'])
        self.assertEqual(1, len(self.mutations()))

    def test_concurrent_same_model_selections_share_the_single_pending_load(self):
        entered, release = threading.Event(), threading.Event()
        def slow(url, kwargs):
            entered.set()
            self.assertTrue(release.wait(3))
            self.states['selected'] = 'loaded'
            return Response({'success': True})
        self.post_hook = slow
        with ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.request)
            self.assertTrue(entered.wait(2))
            try:
                second = self.request(operation='second-selection')
                self.assertEqual(202, second.status_code)
                self.assertEqual('DISPATCHED', second.json()['dispatchState'])
                self.assertEqual('second-selection', second.json()['operationId'])
                self.assertEqual(1, len(self.mutations()))
            finally:
                release.set()
            self.assertEqual('METADATA_QUALIFIED', first.result(timeout=2).json()['state'])

    def test_storage_and_capacity_failure_are_bounded_and_never_authorize_dispatch(self):
        self.runtime.GATEWAY_DURABLE_JOBS = False
        response = self.request()
        self.assertEqual(503, response.status_code)
        self.assertEqual([], self.calls)
        self.runtime.GATEWAY_DURABLE_JOBS = True
        client = self.client(service=ModelPreparationService(self.capabilities, max_records=1))
        self.assertEqual(200, self.request(client).status_code)
        self.assertEqual(503, self.request(client, operation='overflow').status_code)
        self.assertEqual(1, len(self.mutations()))

    def test_failed_final_receipt_commit_retains_durable_dispatch_for_restart_readback(self):
        save = self.service._save
        def fail_final(record, receipt, **kwargs):
            if receipt['state'] == 'METADATA_QUALIFIED':
                raise PreparationUnavailable()
            return save(record, receipt, **kwargs)
        with patch.object(self.service, '_save', side_effect=fail_final):
            response = self.request()
        self.assertEqual(503, response.status_code)
        self.assertEqual('unknown', response.json()['error']['dispatch_state'])
        client = self.client(service=ModelPreparationService(self.capabilities))
        self.assertEqual('METADATA_QUALIFIED', self.request(client).json()['state'])
        self.assertEqual(1, len(self.mutations()))

    def test_shared_deadline_expiry_sends_no_backend_request(self):
        client = self.client(service=ModelPreparationService(self.capabilities, deadline_seconds=0))
        response = self.request(client)
        self.assertEqual('PRECHECK_REJECTED', response.json()['state'])
        self.assertEqual('EXECUTION_METADATA_DEADLINE', response.json()['failure']['failureCode'])
        self.assertEqual([], self.calls)

    def test_verified_passive_readback_retires_unknown_load_before_later_eviction(self):
        self.post_hook = lambda *args: Response({'success': True})
        first = self.request()
        self.assertEqual(202, first.status_code)
        self.states['selected'] = 'loaded'
        observed = self.capabilities.selected_model('selected', force=True)
        self.service.observe_loaded('selected', observed)
        self.assertEqual(1, len(self.mutations()))
        self.assertEqual('METADATA_QUALIFIED', self.request().json()['state'])
        self.states['selected'] = 'unloaded'
        self.post_hook = None
        second = self.request(operation='after-eviction')
        self.assertEqual('METADATA_QUALIFIED', second.json()['state'])
        self.assertEqual(2, len(self.mutations()))

    def test_restart_observer_retires_completed_load_while_phone_detached_then_allows_one_new_wake(self):
        self.post_hook = lambda *args: Response({'success': True})
        self.assertEqual(202, self.request().status_code)
        self.assertEqual(1, len(self.mutations()))
        restarted = ModelPreparationService(InferenceCapabilities(self.runtime), observation_interval=0.01)
        retired = threading.Event()
        original = restarted.observe_loaded
        def observed(model, selected, **kwargs):
            original(model, selected, **kwargs)
            if selected.get('limitsVerified') is True:
                retired.set()
        self.states['selected'] = 'loaded'
        with patch.object(restarted, 'observe_loaded', side_effect=observed):
            with self.client(service=restarted, lifecycle=True) as client:
                self.assertTrue(retired.wait(2))
                self.assertEqual(1, len(self.mutations()))
                self.states['selected'] = 'sleeping'
                self.assertEqual('METADATA_QUALIFIED', self.request(client).json()['state'])
                self.post_hook = None
                self.assertEqual('METADATA_QUALIFIED', self.request(client, operation='after-sleep').json()['state'])
                self.assertEqual(2, len(self.mutations()))
                self.assertEqual('METADATA_QUALIFIED', self.request(client, operation='after-sleep').json()['state'])
                self.assertEqual(2, len(self.mutations()))
        self.assertFalse(restarted.observation_thread.is_alive())
        self.assertTrue(restarted.observation_stop.is_set())

    def test_detached_observer_keeps_unknown_outcome_and_requires_current_exact_route(self):
        self.post_hook = lambda *args: Response({'success': True})
        self.assertEqual(202, self.request().status_code)
        for state in ('unloaded', 'sleeping'):
            self.states['selected'] = state
            self.service.reconcile_pending()
            self.assertEqual('LOAD_OUTCOME_UNKNOWN', self.request().json()['state'])
        self.states['selected'] = 'loaded'
        self.runtime.LLAMA_BASE = 'http://127.0.0.1:8081/v1'
        before = len(self.calls)
        self.service.reconcile_pending()
        self.assertEqual(before, len(self.calls))
        self.runtime.LLAMA_BASE = 'http://127.0.0.1:8080/v1'
        stopped = threading.Event()
        stopped.set()
        self.service.reconcile_pending(stopped)
        self.assertEqual(before, len(self.calls))
        self.states['selected'] = 'sleeping'
        self.assertEqual('LOAD_OUTCOME_UNKNOWN', self.request(operation='still-same-load').json()['state'])
        self.assertEqual(1, len(self.mutations()))

    def test_passive_observer_without_pending_load_creates_no_schema_or_backend_traffic(self):
        self.service.reconcile_pending()
        with closing(self.connect()) as db:
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE name LIKE 'gateway_model_preparations'").fetchone())
        self.assertEqual([], self.calls)

    def test_unverified_or_different_model_observation_cannot_retire_unknown_load(self):
        self.post_hook = lambda *args: Response({'success': True})
        self.request()
        self.states['selected'] = 'loaded'
        observed = self.capabilities.selected_model('selected', force=True)
        for invalid in (dict(observed, reportedModel='other'), dict(observed, requestedModel='other'),
                        dict(observed, limitsVerified=False), dict(observed, modelLoaded=False),
                        dict(observed, observedAtMillis=0), dict(observed, observedAtMillis=True),
                        dict(observed, observationAgeMs=3001), dict(observed, observationAgeMs=-1)):
            self.service.observe_loaded('selected', invalid)
        self.states['selected'] = 'unloaded'
        self.assertEqual('LOAD_OUTCOME_UNKNOWN', self.request(operation='still-pending').json()['state'])
        self.assertEqual(1, len(self.mutations()))

    def test_cached_loaded_proof_before_current_dispatch_cannot_clear_its_fence(self):
        self.states['selected'] = 'loaded'
        stale = self.capabilities.selected_model('selected', force=True)
        self.states['selected'] = 'unloaded'
        self.post_hook = lambda *args: Response({'success': True})
        with patch('v14.model_preparation_routes.time.time', return_value=stale['observedAtMillis'] / 1000 + 1):
            self.assertEqual(202, self.request().status_code)
            self.service.observe_loaded('selected', stale)
            self.assertEqual('LOAD_OUTCOME_UNKNOWN', self.request(operation='still-unknown').json()['state'])
            self.assertEqual(1, len(self.mutations()))
            self.states['selected'] = 'loaded'
            fresh = self.capabilities.selected_model('selected', force=True)
            self.service.observe_loaded('selected', fresh)
            self.assertEqual('METADATA_QUALIFIED', self.request().json()['state'])
        self.assertEqual(1, len(self.mutations()))

    def test_passive_loaded_observation_creates_no_preparation_schema_or_mutation(self):
        self.states['selected'] = 'loaded'
        self.service.observe_loaded('selected', self.capabilities.selected_model('selected', force=True))
        with closing(self.connect()) as db:
            self.assertIsNone(db.execute("SELECT name FROM sqlite_master WHERE name LIKE 'gateway_model_preparations'").fetchone())
        self.assertEqual([], self.mutations())

    def test_confirmed_backend_rejection_is_a_receipt_and_never_http_predispatch_error(self):
        self.post_hook = lambda *args: Response({'error': {'message': 'private denied'}}, status=403)
        response = self.request()
        self.assertEqual(200, response.status_code)
        self.assertEqual('LOAD_REJECTED', response.json()['state'])
        self.assertFalse(response.json()['loadAccepted'])
        self.assertEqual('DISPATCHED', response.json()['dispatchState'])
        self.assertEqual(403, response.json()['failure']['upstreamStatus'])
        self.assertEqual(response.json(), self.request().json())
        self.assertEqual(1, len(self.mutations()))

    def test_worker_capacity_rejects_new_model_without_expanding_thread_or_load_count(self):
        entered, release = threading.Event(), threading.Event()
        def slow(url, kwargs):
            entered.set()
            self.assertTrue(release.wait(3))
            self.states['selected'] = 'loaded'
            return Response({'success': True})
        self.post_hook = slow
        self.service.max_active = 1
        with ThreadPoolExecutor(max_workers=1) as pool:
            first = pool.submit(self.request)
            self.assertTrue(entered.wait(2))
            try:
                response = self.request(model='other', operation='second-model')
                self.assertEqual(503, response.status_code)
                self.assertEqual('MODEL_PREPARATION_BUSY', response.json()['error']['code'])
                self.assertEqual(1, len(self.mutations()))
                self.assertEqual(1, len(self.service.running))
            finally:
                release.set()
            self.assertEqual(200, first.result(timeout=2).status_code)
        self.post_hook = None
        self.assertEqual(200, self.request(model='other', operation='second-model').status_code)
        self.assertEqual(2, len(self.mutations()))

    def test_cancellation_before_load_retains_non_dispatch_receipt(self):
        entered, release = threading.Event(), threading.Event()
        def blocked(url, kwargs):
            entered.set()
            self.assertTrue(release.wait(3))
        self.get_hook = blocked
        asyncio.run(self.cancel_request(entered, release))
        response = self.request()
        self.assertEqual('MODEL_PREPARATION_CANCELLED', response.json()['reasonCode'])
        self.assertEqual('NOT_DISPATCHED', response.json()['dispatchState'])
        self.assertEqual([], self.mutations())

    def test_cancellation_after_dispatch_cannot_undo_or_repeat_the_exact_load(self):
        entered, release = threading.Event(), threading.Event()
        def blocked(url, kwargs):
            entered.set()
            self.assertTrue(release.wait(3))
            self.states['selected'] = 'loaded'
            return Response({'success': True})
        self.post_hook = blocked
        asyncio.run(self.cancel_request(entered, release))
        response = self.request()
        self.assertEqual('METADATA_QUALIFIED', response.json()['state'])
        self.assertEqual(1, len(self.mutations()))

    async def cancel_request(self, entered, release):
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=self.app(), client=('127.0.0.1', 2345)),
                                     base_url='http://127.0.0.1:8090') as client:
            task = asyncio.create_task(client.post('/gateway/models/prepare', json={'model': 'selected', 'operationId': 'selection-1'}))
            self.assertTrue(await asyncio.to_thread(entered.wait, 2))
            task.cancel()
            try:
                with self.assertRaises(asyncio.CancelledError):
                    await task
            finally:
                release.set()
            for _ in range(200):
                with self.service.lock:
                    running = bool(self.service.running)
                if not running:
                    break
                await asyncio.sleep(.01)
            self.assertFalse(running)


if __name__ == '__main__':
    unittest.main()
