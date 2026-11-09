import hashlib
import shutil
import sys
import unittest
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from fastapi import FastAPI
from fastapi.testclient import TestClient
from v14.config import Config
from v14.engineering_routes import install_engineering_routes
from v14.identity import Admission
import test_gateway_repository_lifecycle as fixtures


@unittest.skipUnless(shutil.which('git'), 'Git CLI required for local operation fixture')
class RepositoryLifecycleHTTPTests(unittest.TestCase):
    def setUp(self):
        fixtures.RepositoryLifecycleTests.setUp(self)
        self.service.authorized = lambda job: job['principal'] in {'tailnet:owner', 'tailnet:other'}
        self.service.readiness = lambda: {'ready': True, 'graph_ready': False, 'checks': {}}
        self.service.repository_lifecycle = self.lifecycle
        self.runtime.gateway_service = SimpleNamespace(instance_id='a' * 32)
        self.starts = []
        self.lifecycle.start = self.starts.append
        app = FastAPI()
        app.add_middleware(Admission, config=Config(mode='trusted_proxy', allowed_hosts=('testserver',), allowed_origins=(),
                           trusted_users={'owner@example.test': {'principalId': 'owner', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                                          'other@example.test': {'principalId': 'other', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                                          'reader@example.test': {'principalId': 'reader', 'scopes': ['chat', 'jobs', 'tools']}}))
        install_engineering_routes(app, self.service)
        self.client = TestClient(app, client=('127.0.0.1', 12000))
        self.headers = {'Tailscale-User-Login': 'owner@example.test',
                        'X-Gateway-Installation-ID': self.store.conversations.gateway_id(), 'X-Gateway-Principal-ID': 'tailnet:owner'}
        self.url = '/gateway/engineering/repository-operations'

    def tearDown(self):
        self.client.close()
        fixtures.RepositoryLifecycleTests.tearDown(self)

    def test_capability_only_advertises_configured_host_clone_and_history_import(self):
        response = self.client.get('/gateway/engineering/capabilities', headers=self.headers)
        self.assertEqual(200, response.status_code, response.text)
        value = response.json()
        self.assertTrue(value['features']['repository_lifecycle_v1'])
        self.assertEqual(['clone_repository', 'import_repository'], value['repository_operations']['operations'])
        self.assertEqual(['history'], value['repository_operations']['copy_modes'])
        self.assertFalse(value['repository_operations']['remote_authorization_verified'])
        self.backend.available = lambda: False
        value = self.client.get('/gateway/engineering/capabilities', headers=self.headers).json()
        self.assertFalse(value['features']['repository_lifecycle_v1'])
        self.assertEqual([], value['repository_operations']['operations'])

    def test_repository_mutation_requires_scope_and_exact_installation_and_principal(self):
        missing = self.client.post(self.url, json=self.payload, headers={'Tailscale-User-Login': 'owner@example.test'})
        self.assertEqual(400, missing.status_code, missing.text)
        self.assertEqual('CONTINUITY_IDENTITY_REQUIRED', missing.json()['error']['code'])
        wrong = self.client.post(self.url, json=self.payload, headers=dict(self.headers, **{'X-Gateway-Installation-ID': 'gw_other'}))
        self.assertEqual(409, wrong.status_code, wrong.text)
        reader = self.client.post(self.url, json=self.payload, headers={'Tailscale-User-Login': 'reader@example.test'})
        self.assertEqual(403, reader.status_code)
        self.assertEqual([], self.starts)
        self.assertEqual([], self.lifecycle.http_calls)

    def test_accepted_status_is_scoped_and_repeated_post_retains_one_owner(self):
        first = self.client.post(self.url, json=self.payload, headers=self.headers)
        self.assertEqual(202, first.status_code, first.text)
        self.assertEqual('PENDING', first.json()['status'])
        self.assertFalse(first.json()['remote_state_verified'])
        operation_id = first.json()['operation_id']
        digest = hashlib.sha256(fixtures.canonical(['tailnet:owner', self.payload]).encode()).hexdigest()
        self.assertEqual(digest, first.json()['request_digest'])
        self.assertEqual('repoop_' + digest[:32], operation_id)
        second = self.client.post(self.url, json=self.payload, headers=self.headers)
        self.assertEqual(operation_id, second.json()['operation_id'])
        self.assertEqual(1, len(self.starts))
        hidden = self.client.get(self.url + '/' + operation_id, headers={'Tailscale-User-Login': 'other@example.test'})
        self.assertEqual(404, hidden.status_code)
        self.starts.pop()()
        verified = self.client.get('/v1' + self.url + '/' + operation_id, headers=self.headers)
        self.assertEqual(200, verified.status_code, verified.text)
        self.assertEqual('VERIFIED', verified.json()['status'])
        self.assertTrue(verified.json()['history_preserved'])
        self.assertTrue(verified.json()['remote_state_verified'])
        self.assertEqual(digest, verified.json()['request_digest'])
        self.assertNotIn('host-fixture-token', verified.text)
        self.assertNotIn(self.temp.name, verified.text)


if __name__ == '__main__':
    unittest.main()
