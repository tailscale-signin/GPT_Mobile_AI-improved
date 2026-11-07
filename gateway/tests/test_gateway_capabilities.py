"""Boolean capability flags and complete real-route fixtures shared with Android."""
import json
import unittest

from gateway_capability_fixture import (FULL_FIXTURE, LEGACY_FIXTURE,
                                        capture_capabilities, running_capability_gateway)


class GatewayCapabilitiesContractTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = running_capability_gateway()
        cls.base, cls.backend = cls.gateway.__enter__()

    @classmethod
    def tearDownClass(cls):
        cls.gateway.__exit__(None, None, None)

    def test_all_capability_routes_publish_only_boolean_feature_flags(self):
        for path in ('/gateway/capabilities', '/v1/gateway/capabilities',
                     '/gateway/v14', '/v1/gateway/v14', '/gateway/v15', '/v1/gateway/v15'):
            with self.subTest(route=path):
                payload = capture_capabilities(self.base, path)
                self.assertTrue(payload['features'])
                invalid = {key: type(value).__name__ for key, value in payload['features'].items()
                           if type(value) is not bool}
                self.assertEqual({}, invalid, 'Feature flags are strict JSON booleans; metadata belongs outside features')
                self.assertNotIn('gateway_server_icon', payload['features'])
                self.assertNotIn('disconnect_grace', payload['features'])
                self.assertEqual('gateway_server', payload['ui_contract']['gateway_tool_icon'])
                self.assertIs(type(payload['ui_contract']['gateway_tool_icon']), str)
                self.assertIn(type(payload['singleflight']['disconnect_grace_seconds']), (int, float))
                self.assertEqual(20, payload['singleflight']['disconnect_grace_seconds'])
                self.assertFalse(payload['features']['structuredEvidence'])
                self.assertFalse(payload['features']['mcpFacade'])
        self.assertEqual(0, self.backend.model_requests)

    def test_full_shared_fixture_matches_the_actual_http_response(self):
        expected = json.loads(FULL_FIXTURE.read_text(encoding='utf-8'))
        actual = capture_capabilities(self.base)
        self.assertEqual(expected, actual)
        self.assertEqual(0, self.backend.model_requests)

    def test_legacy_fixture_preserves_the_full_pre_fix_mixed_feature_response(self):
        legacy = json.loads(LEGACY_FIXTURE.read_text(encoding='utf-8'))
        invalid = {key: type(value).__name__ for key, value in legacy['features'].items()
                   if type(value) is not bool}
        self.assertEqual({'disconnect_grace': 'int', 'gateway_server_icon': 'str'}, invalid)
        self.assertEqual('gateway_server', legacy['features']['gateway_server_icon'])
        self.assertEqual(20, legacy['features']['disconnect_grace'])
        for field in ('accepted_request_headers', 'build', 'contracts', 'diagnostics', 'endpoints',
                      'eventVersions', 'headers', 'mcpTransportAdapters', 'protocols', 'ui_contract'):
            self.assertIn(field, legacy)
        self.assertEqual('15.0.0', legacy['gatewayVersion'])
        self.assertFalse(legacy['features']['structuredEvidence'])
        self.assertFalse(legacy['features']['mcpFacade'])


if __name__ == '__main__':
    unittest.main()
