"""Observed discovery, exact task requirements, and recovery after listener failure."""
import json
import copy
import threading
import time
import unittest
from types import SimpleNamespace
from unittest.mock import patch

from v14.catalog import CatalogCache
from v14.readiness import observations


class TaskToolReadinessTest(unittest.TestCase):
    def setUp(self):
        self.config = {'working': {'command': 'private-command', 'env': {'TOKEN': 'secret-value'}},
                       'broken': {'url': 'http://private-address:9000'}}
        self.calls = []
        self.broken = True
        self.runtime = SimpleNamespace(load_mcp_config=lambda: self.config, mcp_tool_metadata={})
        self.runtime.mcp_call = self.discover
        self.cache = CatalogCache(self.runtime, interactive=1)

    def discover(self, name, *args, **kwargs):
        self.calls.append(name)
        if name == 'broken' and self.broken:
            raise ConnectionRefusedError(111, 'private-address secret-value connection refused')
        return {'tools': [{'name': 'read', 'inputSchema': {'type': 'object'}}]}

    def test_task_uses_only_exact_required_tools_without_a_discovery_side_effect(self):
        self.cache.get()
        before = list(self.calls)
        result = self.cache.task_readiness(['working_read'])
        self.assertTrue(result['ready'])
        self.assertIsNone(result['requiredTools'][0]['executionVerified'])
        self.assertFalse(self.cache.task_readiness(['broken_read'])['ready'])
        self.assertFalse(self.cache.task_readiness(['read'])['ready'])
        self.assertTrue(self.cache.task_readiness([])['ready'])
        self.assertEqual(before, self.calls)

    def test_refused_listener_has_safe_action_and_bounded_retry_without_secret_text(self):
        self.cache.get()
        row = next(row for row in self.cache.connection_observations() if row['serviceId'] == 'broken')
        self.assertEqual('MCP_CONNECTION_REFUSED', row['reasonCode'])
        self.assertEqual('CHECK_LISTENER_AND_RETRY_DISCOVERY', row['recoveryAction'])
        self.assertGreater(row['retryAfterMs'], 0)
        self.assertLessEqual(row['retryAfterMs'], 30_000)
        for secret in ('secret-value', 'private-address', 'private-command', 'TOKEN'):
            self.assertNotIn(secret, json.dumps(row))
        self.cache.get()
        self.assertEqual(1, self.calls.count('broken'))

    def test_restored_listener_recovers_same_configuration_without_false_execution_proof(self):
        self.cache.get()
        key = self.cache.key('broken', self.config['broken'])
        self.broken = False
        self.cache.health[key]['retryAt'] = time.monotonic() - 1
        self.assertEqual(2, len(self.cache.get()))
        result = self.cache.task_readiness(['broken_read'])
        self.assertTrue(result['ready'])
        self.assertIsNone(result['requiredTools'][0]['executionVerified'])
        self.assertEqual(0, self.cache.health[key]['consecutiveFailures'])

    def test_stale_or_changed_catalog_cannot_admit_an_old_tool(self):
        self.cache.get()
        key = self.cache.key('working', self.config['working'])
        _, definitions, metadata = self.cache.cache[key]
        self.cache.cache[key] = (time.monotonic() - 301, definitions, metadata)
        self.assertFalse(self.cache.task_readiness(['working_read'])['ready'])
        self.config['working'] = {'command': 'replacement'}
        self.assertEqual('MCP_REQUIRED_TOOL_NOT_ADVERTISED', self.cache.task_readiness(['working_read'])['requiredTools'][0]['reasonCode'])

    def test_execution_proof_belongs_only_to_the_observed_tool_and_current_contract(self):
        self.cache.get()
        cfg = self.config['working']
        self.cache.observe_execution('working', cfg, 'read', True, contract=self.cache.execution_contract('working', cfg, 'read'))
        self.assertTrue(self.cache.task_readiness(['working_read'])['requiredTools'][0]['executionVerified'])
        self.assertIsNone(self.cache.task_readiness(['broken_read'])['requiredTools'][0]['executionVerified'])

    def test_connection_removed_during_observation_returns_changed_configuration_without_exception(self):
        self.cache.get()
        before = list(self.calls)
        snapshots = iter((copy.deepcopy(self.config), {}))
        self.runtime.load_mcp_config = lambda: next(snapshots, {})
        result = self.cache.task_readiness(['working_read'])
        self.assertFalse(result['ready'])
        self.assertEqual('MCP_CONFIGURATION_CHANGED', result['requiredTools'][0]['reasonCode'])
        self.assertIsNone(result['requiredTools'][0]['executionVerified'])
        self.assertEqual(before, self.calls)

    def test_cached_replacement_revision_cannot_lend_readiness_to_an_old_tool_during_observation(self):
        self.cache.get()
        original = copy.deepcopy(self.config)
        cfg = original['working']
        self.cache.observe_execution('working', cfg, 'read', True, contract=self.cache.execution_contract('working', cfg, 'read'))
        replacement = {'working': {'command': 'replacement'}}
        replacement_key = self.cache.key('working', replacement['working'])
        self.cache.cache[replacement_key] = (
            time.monotonic(), [{'function': {'name': 'working_replacement'}}],
            {'working_replacement': {'server': 'working', 'tool': 'replacement', 'inputSchema': {'type': 'object'}}})
        self.cache.health[replacement_key] = {'connectionHealthy': True, 'authorized': True}
        snapshots = iter((original, replacement))
        self.runtime.load_mcp_config = lambda: next(snapshots, replacement)
        result = self.cache.task_readiness(['working_read'])
        self.assertFalse(result['ready'])
        self.assertEqual('MCP_CONFIGURATION_CHANGED', result['requiredTools'][0]['reasonCode'])
        self.assertIsNone(result['requiredTools'][0]['executionVerified'])
        self.assertTrue(self.cache.task_readiness(['working_replacement'])['ready'])

    def test_late_discovery_timeout_cannot_restore_tools_after_authorization_rejection(self):
        self.config = {'working': self.config['working']}
        self.assertEqual(1, len(self.cache.get()))
        cfg = self.config['working']
        key = self.cache.key('working', cfg)
        self.assertIsNotNone(self.cache.execution_contract('working', cfg, 'read'))

        class LateDeadline:
            def __init__(self, interval, callback):
                self.callback = callback
            def start(self):
                pass
            def cancel(self):
                # The callback already began while the exception handler held
                # the cache lock and runs before the flight is finally removed.
                self.callback()

        def denied(*args, **kwargs):
            raise PermissionError('private authorization detail')
        self.runtime.mcp_call = denied
        completed, cancelled = threading.Event(), threading.Event()
        self.cache.flights[key] = (completed, cancelled)
        with patch('v14.catalog.threading.Timer', LateDeadline):
            self.cache._load('working', key, cfg, completed, cancelled)
        observed = self.cache.connection_observations()[0]
        self.assertEqual('AUTH_REQUIRED', observed['state'])
        self.assertEqual('MCP_AUTH_REQUIRED', observed['reasonCode'])
        self.assertTrue(observed['retryRequiresConfigurationChange'])
        self.assertFalse(self.cache.health[key]['authorized'])
        self.assertEqual([], self.cache.get())
        self.assertIsNone(self.cache.execution_contract('working', cfg, 'read'))
        self.assertFalse(self.cache.task_readiness(['working_read'])['ready'])
        self.assertNotIn('private authorization', json.dumps(observed))

    def test_component_readiness_exposes_connection_causes_without_blocking_process(self):
        self.cache.get()
        self.runtime.gateway_catalogue = self.cache
        result = observations(self.runtime, {'draining': False, 'maxWorkers': 1, 'activeWorkers': 0}, {}, 'disabled')
        self.assertTrue(result['process']['ready'])
        self.assertFalse(result['mcpDiscovery']['ready'])
        self.assertEqual(2, len(result['mcpDiscovery']['connections']))

    def test_invalid_tool_selectors_are_rejected_without_discovery(self):
        for names in ([''] , ['https://private'], ['x'] * 65):
            with self.assertRaises(ValueError):
                self.cache.task_readiness(names)
        self.assertEqual([], self.calls)


if __name__ == '__main__':
    unittest.main()
