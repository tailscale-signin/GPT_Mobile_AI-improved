import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from v14.catalog import CatalogCache
from v14.health import BackendReadiness, readiness


class CatalogReadinessTest(unittest.TestCase):
    def setUp(self):
        self.config = {'local': {'command': ['server']}}
        self.runtime = SimpleNamespace(load_mcp_config=lambda: self.config)
        self.cache = CatalogCache(self.runtime, ttl=300)
        self.key = self.cache.key('local', self.config['local'])

    def test_inspection_does_not_start_discovery_or_claim_unknown_tools_ready(self):
        self.assertEqual('NOT_DISCOVERED', self.cache.readiness()['toolsState'])
        self.assertIsNone(self.cache.readiness()['toolsReady'])
        self.assertEqual({}, self.cache.flights)

    def test_readiness_requires_current_authorized_fresh_catalog_evidence(self):
        self.cache.cache[self.key] = (time.monotonic(), [{}], {})
        self.cache.health[self.key] = {'executionAvailable': True}
        self.assertIs(True, self.cache.readiness()['toolsReady'])
        self.cache.health[self.key] = {'executionAvailable': False}
        self.assertIs(False, self.cache.readiness()['toolsReady'])
        self.cache.health[self.key] = {'executionAvailable': True}
        self.cache.cache[self.key] = (time.monotonic() - 301, [{}], {})
        self.assertEqual('STALE', self.cache.readiness()['toolsState'])
        self.config['local'] = {'command': ['changed-server']}
        self.assertIsNone(self.cache.readiness()['toolsReady'])

    def test_no_configured_tool_is_explicit_and_not_a_failure(self):
        self.config.clear()
        self.assertEqual({'toolsReady': None, 'toolsState': 'NOT_CONFIGURED',
                          'toolConnectionCount': 0, 'toolCount': 0}, self.cache.readiness())

    def test_gateway_health_never_pretends_a_generation_was_measured(self):
        response = SimpleNamespace(status_code=200, close=lambda: None)
        self.runtime.LLAMA_BASE = 'http://127.0.0.1:8080'
        self.runtime.http_get = lambda *args, **kwargs: response
        self.runtime.requests = SimpleNamespace(RequestException=OSError)
        self.runtime.GATEWAY_DURABLE_JOBS = False
        self.runtime.GATEWAY_VERSION = '14'
        self.runtime.gateway_catalogue = self.cache
        state = SimpleNamespace(snapshot=lambda: {'draining': False}, instance_id='a' * 32)
        with patch('v14.health._backend_readiness', BackendReadiness()):
            result = readiness(self.runtime, state)
        self.assertTrue(result['ready'])
        self.assertTrue(result['capabilities']['generationReady'])
        self.assertIsNone(result['capabilities']['inferenceVerified'])
        self.assertIsNone(result['capabilities']['toolsReady'])
        self.assertEqual('NOT_DISCOVERED', result['capabilities']['toolsState'])
        self.assertEqual({}, self.cache.flights)


if __name__ == '__main__':
    unittest.main()
