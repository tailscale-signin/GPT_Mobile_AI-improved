"""Backend outages stay actionable, bounded, model-specific and free of inference retries."""
import errno
import json
import os
from pathlib import Path
import socket
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from types import SimpleNamespace
import unittest
from unittest.mock import Mock, patch

import requests

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.backend_observation import exception_failure, model_response, record_backend_result
from v14.execution_binding import configured_route, ExecutionBindingError
from v14.health import BackendReadiness, readiness
from v14.inference_capabilities import InferenceCapabilities
from v14.service import ServiceState


class Response:
    def __init__(self, status=200, body=None, raw=None):
        self.status_code, self.closed = status, False
        self.raw = json.dumps(body or {}).encode() if raw is None else raw

    def iter_content(self, size):
        for start in range(0, len(self.raw), size):
            yield self.raw[start:start + size]

    def raise_for_status(self):
        if self.status_code >= 400:
            raise requests.HTTPError('private upstream body and address', response=self)

    def close(self):
        self.closed = True


def backend(get):
    return SimpleNamespace(LLAMA_BASE='http://127.0.0.1:8080/v1', GATEWAY_INFERENCE_PROVIDER='LLAMA',
                           http_get=get, requests=requests, reset_http_session=Mock(),
                           GATEWAY_DURABLE_JOBS=False, GATEWAY_VERSION='15.0.0')


class BackendDiagnosticTests(unittest.TestCase):
    def test_model_route_normalizes_api_suffix_closes_response_and_retains_exact_ids(self):
        response = Response(body={'data': [{'id': 'selected'}, {'id': 'other', 'created': 123, 'owned_by': 'local'}]})
        get = Mock(return_value=response)
        status, body = model_response(backend(get))
        self.assertEqual(200, status)
        self.assertEqual(['selected', 'other'], [row['id'] for row in body['data']])
        self.assertEqual(123, body['data'][1]['created'])
        self.assertTrue(response.closed)
        self.assertEqual('http://127.0.0.1:8080/v1/models', get.call_args.args[0])
        self.assertEqual({'autoload': 'false'}, get.call_args.kwargs['params'])
        self.assertFalse(get.call_args.kwargs['allow_redirects'])

    def test_failure_types_are_reported_without_exception_strings(self):
        cases = [
            (requests.ConnectionError(ConnectionRefusedError(errno.ECONNREFUSED, 'private address')), 'BACKEND_CONNECTION_REFUSED'),
            (requests.ConnectTimeout('private address'), 'BACKEND_CONNECT_TIMEOUT'),
            (requests.ReadTimeout('private address'), 'BACKEND_READ_TIMEOUT'),
            (requests.ConnectionError(socket.gaierror(-2, 'private address')), 'BACKEND_DNS_FAILED'),
            (requests.exceptions.SSLError('private certificate'), 'BACKEND_TLS_FAILED'),
            (requests.ConnectionError(ConnectionResetError('private address')), 'BACKEND_CONNECTION_CLOSED')]
        for error, code in cases:
            with self.subTest(code=code):
                status, body = model_response(backend(Mock(side_effect=error)))
                self.assertEqual(502, status)
                self.assertEqual(code, body['error']['failureCode'])
                self.assertEqual('backend_models', body['error']['dependency'])
                self.assertNotIn('private', json.dumps(body))
                self.assertFalse(body['error']['inferenceTested'])

    def test_upstream_failure_status_and_invalid_metadata_are_distinct(self):
        for status, code, retryable in [(403, 'BACKEND_METADATA_DENIED', False),
                                       (404, 'BACKEND_ROUTE_UNSUPPORTED', False),
                                       (429, 'BACKEND_HTTP_ERROR', True), (503, 'BACKEND_HTTP_ERROR', True)]:
            with self.subTest(status=status):
                response = Response(status, {'secret': 'private response with password'})
                observed, body = model_response(backend(Mock(return_value=response)))
                self.assertEqual(502, observed)
                self.assertEqual(code, body['error']['failureCode'])
                self.assertEqual(status, body['error']['upstreamStatus'])
                self.assertEqual(retryable, body['error']['retryable'])
                self.assertTrue(response.closed)
                self.assertNotIn('password', json.dumps(body))
        for raw, expected in [(b'not json', 'BACKEND_INVALID_RESPONSE'),
                              (b'{"data":[{}]}', 'BACKEND_INVALID_RESPONSE'),
                              (b'x' * (1024 * 1024 + 1), 'BACKEND_RESPONSE_TOO_LARGE')]:
            response = Response(raw=raw)
            _, body = model_response(backend(Mock(return_value=response)))
            self.assertEqual(expected, body['error']['failureCode'])
            self.assertTrue(response.closed)

    def test_selected_readiness_explains_unavailable_model_without_substitution(self):
        calls = []
        def get(url, **kwargs):
            calls.append(url)
            return Response(body={'data': [{'id': 'another'}]})
        result = readiness(backend(get), ServiceState(2), 'selected')
        self.assertFalse(result['ready'])
        self.assertEqual('SELECTED_MODEL_NOT_AVAILABLE', result['reasonCode'])
        self.assertTrue(result['capabilities']['networkReachable'])
        self.assertTrue(result['capabilities']['generationReady'])
        self.assertFalse(result['capabilities']['modelAvailable'])
        self.assertEqual(['http://127.0.0.1:8080/health', 'http://127.0.0.1:8080/v1/models'], calls)

    def test_malformed_catalog_cannot_claim_selected_model_component_ready(self):
        get = Mock(side_effect=[Response(), Response(body={'data': 'invalid'})])
        result = readiness(backend(get), ServiceState(2), 'selected')
        self.assertFalse(result['ready'])
        self.assertEqual('BACKEND_INVALID_RESPONSE', result['reasonCode'])
        self.assertIsNone(result['components']['selectedModel']['ready'])
        self.assertEqual('UNKNOWN', result['components']['selectedModel']['state'])

    def test_readiness_preserves_each_failed_dependency_and_cached_observation_age(self):
        clock = [0.0]
        get = Mock(side_effect=lambda *args, **kwargs: (_ for _ in ()).throw(requests.ConnectTimeout('private')))
        runtime, probe = backend(get), BackendReadiness(lambda: clock[0])
        first = probe.inspect(runtime, 'selected')
        clock[0] = 1.25
        second = probe.inspect(runtime, 'selected')
        self.assertEqual(2, get.call_count)
        self.assertEqual(first['observedAtMs'], second['observedAtMs'])
        self.assertEqual(1250, second['observationAgeMs'])
        self.assertEqual('backend_health', second['healthFailure']['dependency'])
        self.assertEqual('backend_models', second['modelsFailure']['dependency'])
        self.assertEqual('BACKEND_CONNECT_TIMEOUT', second['reasonCode'])

    def test_missing_model_does_not_invalidate_healthy_generic_or_other_model_caches(self):
        get = Mock(side_effect=lambda *args, **kwargs: Response(body={'data': [{'id': 'available'}]}))
        runtime, probe = backend(get), BackendReadiness(lambda: 0)
        for model in ['missing', '', 'missing', '', 'missing', '']:
            result = probe.inspect(runtime, model)
            if model:
                self.assertFalse(result['modelAvailable'])
        self.assertEqual(3, get.call_count)
        self.assertEqual(0, getattr(runtime, 'gateway_backend_recovery_epoch', 0))


class CapabilityObservationTests(unittest.TestCase):
    def test_repeated_failure_has_short_ttl_original_age_and_fresh_force_boundary(self):
        clock = [0.0]
        runtime = backend(Mock(side_effect=requests.ConnectTimeout('private backend')))
        capabilities = InferenceCapabilities(runtime, clock=lambda: clock[0])
        first = capabilities.selected_model('selected')
        clock[0] = 1.25
        cached = capabilities.selected_model('selected')
        self.assertEqual(1, runtime.http_get.call_count)
        self.assertEqual(first['observedAtMillis'], cached['observedAtMillis'])
        self.assertEqual(1250, cached['observationAgeMs'])
        self.assertEqual('UNVERIFIED', cached['reasoningControlState'])
        self.assertEqual('EXECUTION_BACKEND_UNAVAILABLE', cached['failureCode'])
        self.assertEqual('BACKEND_CONNECT_TIMEOUT', cached['reasonCode'])
        self.assertEqual('selected', cached['requestedModel'])
        with self.assertRaises(ExecutionBindingError):
            capabilities.inspect(configured_route(runtime, 'LLAMA'), 'selected', force=True)
        self.assertEqual(2, runtime.http_get.call_count)
        clock[0] = 5
        capabilities.selected_model('selected')
        self.assertEqual(3, runtime.http_get.call_count)

    def test_concurrent_failed_checks_share_a_single_observation(self):
        started, release = threading.Event(), threading.Event()
        def get(*args, **kwargs):
            started.set()
            if not release.wait(3):
                raise AssertionError('probe not released')
            raise requests.ConnectTimeout('private address')
        runtime = backend(Mock(side_effect=get))
        capabilities = InferenceCapabilities(runtime)
        with ThreadPoolExecutor(max_workers=6) as workers:
            pending = [workers.submit(capabilities.selected_model, 'selected') for _ in range(6)]
            self.assertTrue(started.wait(1))
            release.set()
            results = [future.result(timeout=3) for future in pending]
        self.assertEqual(1, runtime.http_get.call_count)
        self.assertTrue(all(row['reasonCode'] == 'BACKEND_CONNECT_TIMEOUT' for row in results))
        self.assertEqual({}, capabilities.refreshes)

    def test_unrelated_models_do_not_wait_for_each_others_metadata_gate(self):
        started, release = threading.Event(), threading.Event()
        runtime = backend(Mock())
        capabilities = InferenceCapabilities(runtime)
        def probe(route, model):
            if model == 'slow':
                started.set()
                if not release.wait(3):
                    raise AssertionError('probe not released')
            return {'reported_model_id': model}
        capabilities._probe = probe
        route = configured_route(runtime, 'LLAMA')
        with ThreadPoolExecutor(max_workers=2) as workers:
            slow = workers.submit(capabilities.inspect, route, 'slow')
            self.assertTrue(started.wait(1))
            try:
                fast = workers.submit(capabilities.inspect, route, 'fast').result(timeout=1)
                self.assertEqual('fast', fast['reported_model_id'])
            finally:
                release.set()
            self.assertEqual('slow', slow.result(timeout=1)['reported_model_id'])

    def test_configuration_change_and_measured_recovery_invalidate_failed_evidence(self):
        runtime = backend(Mock(side_effect=requests.ConnectTimeout('private')))
        capabilities = InferenceCapabilities(runtime)
        capabilities.selected_model('selected')
        capabilities.selected_model('selected')
        self.assertEqual(1, runtime.http_get.call_count)
        with patch.dict(os.environ, {'GATEWAY_INFERENCE_CONFIG_REVISION': 'new-revision'}):
            capabilities.selected_model('selected')
        self.assertEqual(2, runtime.http_get.call_count)
        record_backend_result(runtime, True)
        capabilities.selected_model('selected')
        self.assertEqual(3, runtime.http_get.call_count)

    def test_failed_cache_is_bounded_and_never_turns_metadata_denial_into_model_support(self):
        runtime = backend(Mock(side_effect=lambda *args, **kwargs: Response(403)))
        capabilities = InferenceCapabilities(runtime)
        for number in range(70):
            result = capabilities.selected_model('model-' + str(number))
            self.assertFalse(result['limitsVerified'])
            self.assertEqual('UNVERIFIED', result['reasoningControlState'])
            self.assertEqual(403, result['upstreamStatus'])
        self.assertEqual(64, len(capabilities.cache))
        self.assertEqual({}, capabilities.refreshes)


if __name__ == '__main__':
    unittest.main()
