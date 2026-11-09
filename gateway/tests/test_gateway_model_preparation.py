"""Advertised models, loaded allocations and accepted load commands stay distinct."""
import json
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock
import unittest

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.backend_observation import response_failure
from v14.debug_projection import response_summary
from v14.execution_binding import configured_route
from v14.health import BackendReadiness
from v14.inference_capabilities import InferenceCapabilities
from v14.model_preparation import prepare_model


class Response:
    def __init__(self, body=None, status=200, raw=None, broken=False):
        self.status_code, self.closed, self.broken = status, False, broken
        self.raw = json.dumps(body or {}).encode() if raw is None else raw

    def iter_content(self, size):
        if self.broken:
            raise requests.exceptions.ChunkedEncodingError('private address and response')
        for offset in range(0, len(self.raw), size):
            yield self.raw[offset:offset + size]

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError('private response', response=self)

    def close(self):
        self.closed = True


def runtime(get, post=None):
    return SimpleNamespace(LLAMA_BASE='http://127.0.0.1:8080/v1', GATEWAY_INFERENCE_PROVIDER='LLAMA',
                           http_get=get, get_http_session=lambda: SimpleNamespace(post=post), requests=requests)


class ModelQualificationTests(unittest.TestCase):
    def test_properties_rejection_retains_allowlisted_error_and_never_loads(self):
        error = Response({'error': {'code': 400, 'type': 'invalid_request_error',
                                   'message': 'Model "selected" is not loaded'}}, status=400)
        get = Mock(side_effect=[Response({'data': [{'id': 'selected'}]}), error])
        post = Mock(side_effect=AssertionError('A passive check must not load or infer'))
        result = InferenceCapabilities(runtime(get, post)).selected_model('selected')
        self.assertFalse(result['limitsVerified'])
        self.assertEqual('EXECUTION_MODEL_NOT_LOADED', result['failureCode'])
        self.assertEqual('SELECTED_MODEL_NOT_LOADED', result['reasonCode'])
        self.assertEqual(400, result['upstreamStatus'])
        self.assertEqual(400, result['upstreamCode'])
        self.assertEqual('invalid_request_error', result['upstreamErrorType'])
        self.assertEqual('The selected model is not loaded.', result['upstreamMessage'])
        self.assertTrue(result['modelAdvertised'])
        self.assertFalse(result['modelLoaded'])
        self.assertEqual('UNLOADED', result['modelState'])
        self.assertEqual({'model': 'selected', 'autoload': 'false'}, get.call_args.kwargs['params'])
        self.assertTrue(error.closed)
        post.assert_not_called()
        projected = response_summary({'selectedModel': result})
        self.assertEqual(result['upstreamMessage'], projected['upstreamMessage'])
        self.assertNotIn('"selected"', json.dumps(projected))

    def test_unknown_http_400_cannot_prove_an_unloaded_model_or_leak_its_body(self):
        error = Response({'error': {'code': 'private_token', 'type': 'private route',
                                   'message': 'private model selected at /private/path with bearer secret'}}, status=400)
        get = Mock(side_effect=[Response({'data': [{'id': 'selected'}]}), error])
        result = InferenceCapabilities(runtime(get)).selected_model('selected')
        self.assertEqual('BACKEND_HTTP_ERROR', result['reasonCode'])
        self.assertEqual(400, result['upstreamStatus'])
        self.assertEqual('UNKNOWN', result['modelState'])
        self.assertIsNone(result['modelLoaded'])
        self.assertNotIn('private', json.dumps(result))
        self.assertNotIn('secret', json.dumps(result))
        self.assertEqual('PARSED', result['upstreamErrorBodyState'])

    def test_invalid_oversize_and_interrupted_error_bodies_keep_the_http_rejection(self):
        for response, body_state in [(Response(status=400, raw=b'not-json'), 'INVALID'),
                                     (Response(status=400, raw=b'x' * 20000), 'TOO_LARGE'),
                                     (Response(status=400, broken=True), 'UNAVAILABLE')]:
            with self.subTest(body_state=body_state):
                get = Mock(side_effect=[Response({'data': [{'id': 'selected'}]}), response])
                result = InferenceCapabilities(runtime(get)).selected_model('selected')
                self.assertEqual('BACKEND_HTTP_ERROR', result['reasonCode'])
                self.assertEqual(400, result['upstreamStatus'])
                self.assertEqual(body_state, result['upstreamErrorBodyState'])
                self.assertFalse(result['retryable'])
                self.assertTrue(response.closed)

    def test_authentication_rejection_cannot_be_overridden_by_model_message(self):
        observed = response_failure(Response({'error': {'code': 'model_not_loaded', 'message': 'Model not loaded'}}, 403), 'backend_properties')
        self.assertEqual('BACKEND_METADATA_DENIED', observed['reasonCode'])
        self.assertFalse(observed['retryable'])

    def test_explicit_catalogue_load_states_block_qualification_without_properties_reads(self):
        for state in ['unloaded', 'loading', 'sleeping']:
            with self.subTest(state=state):
                get = Mock(return_value=Response({'data': [{'id': 'selected', 'status': {'value': state}}]}))
                result = InferenceCapabilities(runtime(get)).selected_model('selected')
                self.assertFalse(result['limitsVerified'])
                self.assertTrue(result['modelAdvertised'])
                self.assertFalse(result['modelLoaded'])
                self.assertEqual(state.upper(), result['modelState'])
                self.assertEqual(1, get.call_count)

    def test_healthy_router_cannot_make_an_explicitly_unloaded_model_ready(self):
        get = Mock(side_effect=[Response(), Response({'data': [{'id': 'selected', 'status': {'value': 'unloaded'}}]})])
        result = BackendReadiness().inspect(runtime(get), 'selected')
        self.assertTrue(result['networkReachable'])
        self.assertTrue(result['modelAvailable'])
        self.assertFalse(result['modelLoaded'])
        self.assertFalse(result['generationReady'])
        self.assertEqual('SELECTED_MODEL_NOT_LOADED', result['reasonCode'])

    def test_sleeping_properties_cannot_attest_a_loaded_allocation(self):
        get = Mock(side_effect=[Response({'data': [{'id': 'selected'}]}),
                               Response({'model_alias': 'selected', 'is_sleeping': True, 'default_generation_settings': {'n_ctx': 8192}})])
        result = InferenceCapabilities(runtime(get)).selected_model('selected')
        self.assertFalse(result['limitsVerified'])
        self.assertEqual('SLEEPING', result['modelState'])
        self.assertEqual('UNVERIFIED', result['reasoningControlState'])

    def test_one_models_template_probe_does_not_hold_other_models_cache_lock(self):
        entered, release = threading.Event(), threading.Event()
        capabilities = InferenceCapabilities(runtime(Mock()))
        def probe(route, model, **kwargs):
            return {'reported_model_id': model, 'limits_verified': True, 'context_tokens': 8192,
                    'max_output_tokens': 8192, 'output_limit_source': 'context_capacity', 'observed_at_ms': 1,
                    'supported': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET'],
                    'capability_revision': model, 'template_digest': model, 'reasoning_parameters': ['thinking']}
        def template(method, url, **kwargs):
            if kwargs['body']['model'] == 'slow':
                entered.set()
                if not release.wait(3):
                    raise AssertionError('template probe was not released')
            enabled = kwargs['body']['chat_template_kwargs']['thinking']
            return {'prompt': 'prefix<think>' + ('' if enabled else '</think>')}
        capabilities._probe, capabilities._json = probe, template
        with ThreadPoolExecutor(max_workers=2) as pool:
            pending = pool.submit(capabilities.selected_model, 'slow')
            self.assertTrue(entered.wait(1))
            try:
                fast = pool.submit(capabilities.selected_model, 'fast').result(timeout=1)
                self.assertEqual('VERIFIED', fast['reasoningControlState'])
            finally:
                release.set()
            self.assertEqual('VERIFIED', pending.result(timeout=1)['reasoningControlState'])
        self.assertEqual({}, capabilities.reasoning_refreshes)


class ExplicitModelPreparationTests(unittest.TestCase):
    def fixture(self, *, lost=False, after='loaded', status='unloaded', rejection=None):
        state = [status]
        calls = []
        def get(url, **kwargs):
            calls.append(('GET', url, kwargs))
            if url.endswith('/models'):
                return Response({'data': [{'id': 'selected', 'status': {'value': state[0]}}]})
            if url.endswith('/props'):
                return Response({'model_alias': 'selected', 'default_generation_settings': {'n_ctx': 8192}})
            raise AssertionError('Unexpected route: ' + url)
        def post(url, **kwargs):
            calls.append(('POST', url, kwargs))
            self.assertTrue(url.endswith('/models/load'))
            self.assertEqual({'model': 'selected'}, kwargs['json'])
            state[0] = after
            if lost:
                raise requests.exceptions.ChunkedEncodingError('load accepted, but response lost at private address')
            if rejection:
                return Response({'error': {'code': rejection, 'message': 'private token'}}, rejection)
            return Response({'success': True})
        return InferenceCapabilities(runtime(get, post)), calls

    def test_host_command_loads_exact_model_once_then_requalifies_metadata_without_inference(self):
        capability, calls = self.fixture()
        # A negative passive observation must not survive an explicit successful repair.
        self.assertFalse(capability.selected_model('selected')['limitsVerified'])
        receipt = prepare_model(capability, 'selected')
        self.assertEqual('METADATA_QUALIFIED', receipt['state'])
        self.assertTrue(receipt['loadAccepted'])
        self.assertEqual(1, receipt['loadRequests'])
        self.assertEqual('selected', receipt['selectedModel']['reportedModel'])
        self.assertEqual('UNVERIFIED', receipt['selectedModel']['reasoningControlState'])
        self.assertFalse(receipt['inferenceTested'])
        self.assertFalse(receipt['automaticSubstitution'])
        self.assertFalse(receipt['reasoningSettingsChanged'])
        self.assertEqual(1, sum(method == 'POST' for method, _, _ in calls))
        self.assertTrue(all(details['params'].get('autoload') == 'false' for method, _, details in calls if method == 'GET'))

    def test_lost_load_response_is_reconciled_by_readback_and_never_posted_twice(self):
        for state in ('loaded', 'loading'):
            with self.subTest(state=state):
                capability, calls = self.fixture(lost=True, after=state)
                receipt = prepare_model(capability, 'selected')
                self.assertIsNone(receipt['loadAccepted'])
                self.assertEqual(1, receipt['loadRequests'])
                self.assertEqual('METADATA_QUALIFIED' if state == 'loaded' else 'WAITING_FOR_MODEL', receipt['state'])
                self.assertEqual('BACKEND_CONNECTION_CLOSED', receipt['failure']['reasonCode'])
                self.assertEqual(1, sum(method == 'POST' for method, _, _ in calls))
                self.assertNotIn('private', json.dumps(receipt))

    def test_existing_loading_and_loaded_models_do_not_receive_a_load_command(self):
        for state in ('loading', 'loaded'):
            capability, calls = self.fixture(status=state)
            receipt = prepare_model(capability, 'selected')
            self.assertEqual(0, receipt['loadRequests'])
            self.assertEqual('NOT_DISPATCHED', receipt['dispatchState'])
            self.assertFalse(any(method == 'POST' for method, _, _ in calls))

    def test_invalid_or_unsupported_router_does_not_receive_a_mutation(self):
        for rows in ([], [{'id': 'other', 'status': {'value': 'unloaded'}}], [{'id': 'selected'}]):
            post = Mock(side_effect=AssertionError('unsupported router must not receive load'))
            capability = InferenceCapabilities(runtime(Mock(return_value=Response({'data': rows})), post))
            receipt = prepare_model(capability, 'selected')
            self.assertEqual('PRECHECK_REJECTED', receipt['state'])
            self.assertEqual(0, receipt['loadRequests'])
            post.assert_not_called()

    def test_confirmed_load_rejection_keeps_http_evidence_and_does_not_retry(self):
        capability, calls = self.fixture(rejection=403, after='unloaded')
        receipt = prepare_model(capability, 'selected')
        self.assertEqual('LOAD_REJECTED', receipt['state'])
        self.assertFalse(receipt['loadAccepted'])
        self.assertEqual(403, receipt['failure']['upstreamStatus'])
        self.assertEqual(1, sum(method == 'POST' for method, _, _ in calls))

    def test_repair_invalidates_only_the_requested_models_observations(self):
        capability, _ = self.fixture()
        route = configured_route(capability.runtime, 'LLAMA')
        key = (route['runtime_config_revision'], 'other', 'revision')
        capability.reasoning_cache[key] = 0, {'parameter': 'thinking'}
        capability.reasoning_states[key] = 'VERIFIED'
        prepare_model(capability, 'selected')
        self.assertIn(key, capability.reasoning_cache)
        self.assertIn(key, capability.reasoning_states)


if __name__ == '__main__':
    unittest.main()
