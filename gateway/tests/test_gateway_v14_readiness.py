"""Readiness evidence is authenticated-route/model specific and never an inference replay."""
import hashlib
from pathlib import Path
import sys
import threading
from types import SimpleNamespace
import unittest
from unittest.mock import Mock
from concurrent.futures import ThreadPoolExecutor

import requests
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.health import BackendReadiness, readiness
from v14.service import ServiceState


def response(status, body=None):
    return Mock(status_code=status, json=Mock(return_value=body or {}))


def runtime(get):
    return SimpleNamespace(LLAMA_BASE='http://127.0.0.1:8080', http_get=get, requests=requests,
                           reset_http_session=Mock(), GATEWAY_DURABLE_JOBS=False, GATEWAY_VERSION='15.0.0')


class BackendReadinessTests(unittest.TestCase):
    def test_restricted_health_and_live_catalog_are_not_generation_readiness(self):
        get = Mock(side_effect=[response(403), response(200, {'data': [{'id': 'selected'}]})])
        state = ServiceState(2)
        result = readiness(runtime(get), state, 'selected')
        self.assertFalse(result['ready'])
        self.assertEqual('restricted', result['checks']['backend'])
        self.assertTrue(result['capabilities']['networkReachable'])
        self.assertTrue(result['capabilities']['modelAvailable'])
        self.assertIsNone(result['capabilities']['generationReady'])
        self.assertEqual('restricted', result['capabilities']['metadataAccess'])
        self.assertEqual(hashlib.sha256(b'selected').hexdigest(), result['capabilities']['selectedModelDigest'])
        self.assertEqual(state.instance_id, result['instanceId'])
        for call in get.call_args_list:
            self.assertEqual({'model': 'selected', 'autoload': 'false'}, call.kwargs['params'])
            self.assertEqual((0.5, 1), call.kwargs['timeout'])

    def test_a_different_listed_model_cannot_substitute_for_selected_profile(self):
        get = Mock(side_effect=[response(200), response(200, {'data': [{'id': 'other'}]})])
        result = readiness(runtime(get), ServiceState(2), 'selected')
        self.assertFalse(result['ready'])
        self.assertTrue(result['capabilities']['generationReady'])
        self.assertFalse(result['capabilities']['modelAvailable'])

    def test_switching_profiles_reuses_each_models_short_cache_and_refreshes_after_expiry(self):
        clock = [0]
        get = Mock(side_effect=lambda url, **kwargs: response(200, {'data': [{'id': kwargs['params']['model']}]}))
        probe = BackendReadiness(now=lambda: clock[0])
        backend = runtime(get)
        self.assertTrue(probe.inspect(backend, 'first')['modelAvailable'])
        self.assertTrue(probe.inspect(backend, 'second')['modelAvailable'])
        self.assertTrue(probe.inspect(backend, 'first')['modelAvailable'])
        self.assertEqual(4, get.call_count)
        clock[0] = 4
        probe.inspect(backend, 'first')
        self.assertEqual(6, get.call_count)

    def test_parallel_profile_checks_share_one_bounded_probe(self):
        started, release = threading.Event(), threading.Event()
        def get(url, **kwargs):
            if url.endswith('/health'):
                started.set()
                release.wait(2)
            return response(200, {'data': [{'id': 'selected'}]})
        transport = Mock(side_effect=get)
        probe, backend = BackendReadiness(), runtime(transport)
        with ThreadPoolExecutor(max_workers=5) as workers:
            pending = [workers.submit(probe.inspect, backend, 'selected') for _ in range(5)]
            self.assertTrue(started.wait(1))
            release.set()
            self.assertTrue(all(future.result(timeout=3)['modelAvailable'] for future in pending))
        self.assertEqual(2, transport.call_count)

    def test_disconnected_health_resets_socket_but_only_performs_read_only_checks(self):
        get = Mock(side_effect=[requests.ConnectionError('socket closed'), response(200, {'data': [{'id': 'selected'}]})])
        backend = runtime(get)
        result = BackendReadiness().inspect(backend, 'selected')
        self.assertIsNone(result['generationReady'])
        self.assertTrue(result['modelAvailable'])
        backend.reset_http_session.assert_called_once_with()
        self.assertEqual(2, get.call_count)

    def test_each_gateway_restart_has_a_new_runtime_identity(self):
        first, second = ServiceState(2), ServiceState(2)
        self.assertNotEqual(first.instance_id, second.instance_id)
        self.assertRegex(first.instance_id, '^[a-f0-9]{32}$')


if __name__ == '__main__':
    unittest.main()
