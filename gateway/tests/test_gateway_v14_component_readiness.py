import unittest
from types import SimpleNamespace
from v14.readiness import observations
from v14.catalog import CatalogCache


class IndependentReadinessTest(unittest.TestCase):
    def runtime(self):
        return SimpleNamespace(LLAMA_BASE='http://selected', gateway_engineering=SimpleNamespace(readiness=lambda: {
            'checks': {'host_github_connection': 'missing'}, 'graph_ready': False,
            'github_reachable': None, 'repository_access': None}),
            gateway_debug=SimpleNamespace(snapshot=lambda: {'debugEnabled': True, 'storageReady': True}))

    def test_healthy_process_does_not_claim_optional_services_or_inference_execution(self):
        result = observations(self.runtime(), {'draining': False, 'maxWorkers': 1, 'activeWorkers': 1},
                              {'generationReady': True, 'inferenceVerified': None}, 'ready')
        self.assertTrue(result['process']['ready'])
        self.assertTrue(result['selectedModel']['ready'])
        self.assertIsNone(result['selectedModel']['inferenceVerified'])
        self.assertEqual('HOST_GITHUB_CONNECTION_REQUIRED', result['repositoryGraph']['reasonCode'])
        self.assertFalse(result['githubAuthorization']['configured'])
        self.assertIsNone(result['githubAuthorization']['ready'])
        self.assertEqual('WAITING_CAPACITY', result['workerCapacity']['state'])
        self.assertTrue(result['diagnostics']['ready'])

    def test_model_missing_never_substitutes_or_disables_diagnostics(self):
        result = observations(self.runtime(), {'draining': False, 'maxWorkers': 1, 'activeWorkers': 0},
                              {'generationReady': True, 'modelAvailable': False}, 'ready')
        self.assertFalse(result['selectedModel']['ready'])
        self.assertFalse(result['selectedModel']['automaticSubstitution'])
        self.assertTrue(result['diagnostics']['ready'])

    def test_catalogue_success_does_not_establish_execution_success(self):
        config = {'server': {'command': 'fixture'}}
        runtime = SimpleNamespace(load_mcp_config=lambda: config, mcp_tool_metadata={},
                                  mcp_call=lambda *args, **kwargs: {'tools': []})
        cache = CatalogCache(runtime, interactive=1)
        self.assertEqual([], cache.get())
        self.assertIsNone(cache.readiness()['toolsReady'])
        self.assertTrue(cache.readiness()['schemaReady'])
        self.assertIsNone(cache.readiness()['executionVerified'])
        cache.observe_execution('server', config['server'], 'actual_read', True)
        self.assertTrue(cache.readiness()['toolsReady'])
        self.assertTrue(cache.readiness()['executionVerified'])
        config['server'] = {'command': 'new-connection'}
        self.assertIsNone(cache.readiness()['toolsReady'])
        self.assertIsNone(cache.readiness()['executionVerified'])

    def test_discovery_failure_does_not_fabricate_an_executed_tool_failure(self):
        config = {'server': {'command': 'fixture'}}
        def failed(*args, **kwargs):
            raise TimeoutError('discovery fixture')
        runtime = SimpleNamespace(load_mcp_config=lambda: config, mcp_tool_metadata={}, mcp_call=failed)
        cache = CatalogCache(runtime, interactive=1)
        self.assertEqual([], cache.get())
        self.assertFalse(cache.readiness()['toolsReady'])
        self.assertIsNone(cache.readiness()['executionVerified'])
