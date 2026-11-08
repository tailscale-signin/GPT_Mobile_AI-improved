"""Real admission middleware and saved-work routes; no inference or credential creation."""
import importlib
import sqlite3
import unittest
from contextlib import closing
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

import test_gateway_v14_jobs as journal_fixtures
from v14.config import Config
from v14.identity import Admission


class SavedWorkAdmissionTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = importlib.import_module('gateway_v14')

    setUp = journal_fixtures.JournalTests.setUp

    def client(self, mode='open_network', scopes=('chat', 'jobs', 'tools'), peer=None):
        config = Config(mode=mode, host='0.0.0.0' if mode == 'open_network' else '127.0.0.1',
                        allowed_hosts=('127.0.0.1:8090',), allowed_origins=(),
                        trusted_users={'reader@example.test': {'principalId': 'reader', 'scopes': list(scopes)}} if mode == 'trusted_proxy' else {})
        config.validate()
        self.stack.enter_context(patch.object(self.gateway, 'config', config))
        app = FastAPI()
        app.add_middleware(Admission, config=config, store=self.store)
        # Actual application handlers and journal projection. Only process lifecycle
        # and external backends are omitted from these bounded metadata reads.
        app.router.routes.extend(self.gateway.app.router.routes)
        return TestClient(app, base_url='http://127.0.0.1:8090', client=(peer or ('192.168.1.20' if mode == 'open_network' else '127.0.0.1'), 1234))

    def test_keyfree_remote_reads_admit_identity_catalog_conversations_and_command_reconciliation(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            for prefix in ('', '/v1'):
                capabilities = client.get(prefix + '/gateway/capabilities')
                self.assertEqual(200, capabilities.status_code)
                source = capabilities.json()['build']['sourceCommit']
                self.assertRegex(source, r'^[a-f0-9]{40}$')
                self.assertFalse(capabilities.json()['auth']['apiKeyRequired'])
                self.assertEqual('open_network', capabilities.json()['auth']['mode'])
                self.assertTrue(capabilities.json()['features']['conversation_sync_v1'])
                self.assertTrue(capabilities.json()['features']['job_catalog_v1'])
                identity = client.get(prefix + '/gateway/identity')
                self.assertEqual(200, identity.status_code, identity.text)
                scope = identity.json()
                self.assertEqual(1, scope['schema_version'])
                self.assertEqual(1, scope['protocol_version'])
                self.assertEqual('network:shared', scope['principal_id'])
                self.assertEqual(capabilities.json()['gateway_id'], scope['gateway_id'])
                for route in ('conversations?limit=1', 'job-catalog?limit=1'):
                    response = client.get(prefix + '/gateway/' + route)
                    self.assertEqual(200, response.status_code, response.text)
                    self.assertEqual(scope['gateway_id'], response.json()['gateway_id'])
                    self.assertEqual(scope['principal_id'], response.json()['principal_id'])
                    self.assertEqual([], response.json()['items'])
                    self.assertEqual('no-store', response.headers['cache-control'])
                missing = client.get(prefix + '/gateway/commands/original-command')
                self.assertEqual(404, missing.status_code)
                self.assertEqual('not_found', missing.json()['error']['code'])
                self.assertNotIn('x-gateway-admission-error', missing.headers)
            model.assert_not_called()
            self.assertEqual({}, self.runtime.job_registry)
            with closing(sqlite3.connect(self.db)) as db:
                self.assertEqual(0, db.execute('SELECT COUNT(*) FROM gateway_jobs').fetchone()[0])
                self.assertEqual(0, db.execute('SELECT COUNT(*) FROM gateway_conversations').fetchone()[0])

    def test_proxy_identity_and_scope_failures_keep_exact_bounded_header_and_body_codes(self):
        with self.client('trusted_proxy', scopes=('chat', 'tools')) as client, patch.object(self.runtime, 'process_chat_payload') as model:
            missing_identity = client.get('/gateway/identity')
            self.assertEqual(401, missing_identity.status_code)
            self.assertEqual('transport_identity_required', missing_identity.headers['x-gateway-admission-error'])
            self.assertEqual({'error': {'code': 'transport_identity_required'}}, missing_identity.json())
            headers = {'Tailscale-User-Login': 'reader@example.test'}
            self.assertEqual(200, client.get('/gateway/identity', headers=headers).status_code)
            for path in ('/gateway/conversations', '/gateway/job-catalog', '/gateway/commands/original-command'):
                response = client.get(path, headers=headers)
                self.assertEqual(403, response.status_code)
                self.assertEqual('scope_not_granted', response.headers['x-gateway-admission-error'])
                self.assertEqual({'error': {'code': 'scope_not_granted'}}, response.json())
            model.assert_not_called()

    def test_saved_work_allowlist_does_not_open_admin_or_unknown_remote_surfaces(self):
        with self.client() as client:
            for route in ('/props', '/gateway/health', '/gateway/unregistered'):
                response = client.get(route)
                self.assertEqual(403, response.status_code)
                self.assertEqual('remote_surface_not_admitted', response.headers['x-gateway-admission-error'])
                self.assertEqual({'error': {'code': 'remote_surface_not_admitted'}}, response.json())
            self.assertEqual(403, client.post('/gateway/tools/refresh').status_code)
            self.assertEqual(401, client.get('/gateway/admin/dashboard').status_code)


if __name__ == '__main__':
    unittest.main()
