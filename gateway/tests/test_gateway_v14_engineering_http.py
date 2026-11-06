import json
import hashlib
import hmac
import sys
import unittest
from unittest.mock import patch
from pathlib import Path
from types import SimpleNamespace

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI
from fastapi.testclient import TestClient
from v14.engineering_routes import install_engineering_routes
from v14.identity import Admission
from v14.config import Config
import test_gateway_v14_engineering as fixtures


class EngineeringHTTPTests(unittest.TestCase):
    def test_evidence_pagination_preview_and_account_isolation(self):
        job = self.store.create('tailnet:owner', self.request)
        for index in range(21):
            last = self.service.evidence.append(job, 'github_api', {'index': index, 'source': 'x' * 17000})
        url = '/gateway/engineering/jobs/' + job['id'] + '/evidence'
        first = self.client.get(url, headers=self.headers).json()
        self.assertEqual(20, len(first['receipts']))
        self.assertTrue(first['has_more'])
        second = self.client.get(url + '?after=' + str(first['next_cursor']), headers=self.headers).json()
        self.assertEqual(last['receipt_id'], second['receipts'][0]['receipt_id'])
        receipt = url + '/' + last['receipt_id']
        preview = self.client.get(receipt + '?preview=true', headers=self.headers).json()
        self.assertTrue(preview['truncated'])
        self.assertEqual(16000, len(preview['preview']))
        self.assertIsNone(preview['payload'])
        self.assertEqual(20, self.client.get(receipt, headers=self.headers).json()['payload']['index'])
        for path in (url, receipt):
            self.assertEqual(404, self.client.get(path, headers={'Tailscale-User-Login': 'other@example.test'}).status_code)
            self.assertEqual(403, self.client.get(path, headers={'Tailscale-User-Login': 'reader@example.test'}).status_code)
        self.assertEqual(409, self.client.get(url + '?after=-2', headers=self.headers).status_code)

    def test_diagnostic_job_resumes_without_duplicate_execution_or_repository_writes(self):
        url = '/gateway/engineering/diagnostics/jobs'
        request = {'kind': 'transport_probe', 'request_id': 'a' * 64}
        created = self.client.post(url, json=request, headers=self.headers)
        self.assertEqual(200, created.status_code, created.text)
        job = created.json()
        first = self.client.get(url + '/' + job['id'] + '?after=0&limit=1', headers=self.headers).json()
        self.assertEqual([1], [event['sequence'] for event in first['events']])
        resumed = self.client.get(url + '/' + job['id'] + '?after=1', headers=self.headers).json()
        self.assertEqual([2, 3], [event['sequence'] for event in resumed['events']])
        self.assertEqual('COMPLETED', resumed['status'])
        self.assertEqual(job['id'], self.client.post(url, json=request, headers=self.headers).json()['id'])
        self.assertEqual([], self.client.get(url + '/' + job['id'] + '?after=3', headers=self.headers).json()['events'])
        stream = self.client.get(url + '/' + job['id'] + '/events?after=1', headers=self.headers)
        events = [json.loads(line[6:]) for line in stream.text.splitlines() if line.startswith('data: {')]
        self.assertEqual([2, 3], [event['sequence'] for event in events])
        self.assertTrue(all(event['job_id'] == job['id'] for event in events))
        self.assertIn('data: [DONE]', stream.text)
        self.assertEqual([], self.backend.calls)
        self.assertEqual(404, self.client.get(url + '/' + job['id'], headers={'Tailscale-User-Login': 'other@example.test'}).status_code)
        self.assertEqual(403, self.client.post(url, json=request, headers={'Tailscale-User-Login': 'reader@example.test'}).status_code)
        self.assertEqual(409, self.client.get(url + '/' + job['id'] + '?after=4', headers=self.headers).status_code)

    def setUp(self):
        fixtures.DurableEngineeringTests.setUp(self)
        fixtures.DurableEngineeringTests.admit(self, 'tailnet:owner')
        self.backend.available = lambda: True
        self.store.tx(lambda db: db.execute('SELECT 1'))
        self.service.thread = SimpleNamespace(is_alive=lambda: True)
        app = FastAPI()
        config = Config(mode='trusted_proxy', allowed_hosts=('testserver',), allowed_origins=(),
                        trusted_users={'owner@example.test': {'principalId': 'owner', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                                       'other@example.test': {'principalId': 'other', 'scopes': ['chat', 'jobs', 'tools', 'engineering']},
                                       'reader@example.test': {'principalId': 'reader', 'scopes': ['chat', 'jobs', 'tools']}})
        app.add_middleware(Admission, config=config)
        install_engineering_routes(app, self.service)
        self.client = TestClient(app, client=('127.0.0.1', 12000))
        self.headers = {'Tailscale-User-Login': 'owner@example.test'}

    def tearDown(self):
        self.client.close()
        fixtures.DurableEngineeringTests.tearDown(self)

    def test_app_create_close_resume_updates_and_explicit_merge_endpoint(self):
        response = self.client.post('/v1/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, response.status_code, response.text)
        job_id = response.json()['id']
        # No connected client participates in any of these workflow steps.
        for _ in range(7):
            self.service.tick(job_id)
        response = self.client.get('/v1/gateway/engineering/jobs/' + job_id + '?after_revision=2', headers=self.headers)
        self.assertEqual(200, response.status_code)
        snapshot = response.json()
        self.assertTrue(snapshot['merge_ready'])
        self.assertTrue(all(update['revision'] > 2 for update in snapshot['updates']))
        self.assertEqual(404, self.client.get('/gateway/engineering/jobs/' + job_id, headers={'Tailscale-User-Login': 'other@example.test'}).status_code)
        approval = {'revision': snapshot['revision'], 'pr': 7, 'head_sha': 'a' * 40}
        url = '/gateway/engineering/jobs/' + job_id + '/approve'
        self.assertEqual(403, self.client.post(url, json=approval, headers=self.headers).status_code)
        approved = self.client.post(url, json=approval, headers=dict(self.headers, **{'X-Gateway-User-Action': 'approve-and-merge'}))
        self.assertEqual(200, approved.status_code, approved.text)
        self.service.tick(job_id)
        self.service.tick(job_id)
        self.assertEqual('COMPLETED', self.store.get(job_id)['state'])

    def test_graph_route_requires_engineering_scope_and_keeps_principal_server_owned(self):
        payload = {'action': 'status', 'repository': 'owner/repo', 'branch': 'feature', 'commit_sha': 'a' * 40}
        with patch.object(self.service.graph, 'execute', return_value={'status': 'NOT_INDEXED'}) as graph:
            denied = self.client.post('/gateway/engineering/graph', json=payload, headers={'Tailscale-User-Login': 'reader@example.test'})
            self.assertEqual(403, denied.status_code)
            graph.assert_not_called()
            response = self.client.post('/v1/gateway/engineering/graph', json=payload, headers=self.headers)
            self.assertEqual(200, response.status_code)
            graph.assert_called_once_with('tailnet:owner', payload)

    def test_graph_capacity_does_not_fail_as_a_successful_result(self):
        with patch.object(self.service.graph, 'execute', side_effect=TimeoutError('capacity')):
            response = self.client.post('/gateway/engineering/graph', json={'action': 'query'}, headers=self.headers)
            self.assertEqual(503, response.status_code)

    def test_existing_read_scope_cannot_start_engineering_and_no_credential_is_returned(self):
        response = self.client.post('/gateway/engineering/jobs', json=self.request, headers={'Tailscale-User-Login': 'reader@example.test'})
        self.assertEqual(403, response.status_code)
        capability = self.client.get('/gateway/engineering/capabilities', headers=self.headers)
        self.assertTrue(capability.json()['explicit_merge_approval'])
        self.assertEqual('android_background_sync', capability.json()['notifications'])

    def test_disabled_durable_storage_does_not_break_gateway_startup(self):
        self.runtime.GATEWAY_DURABLE_JOBS = False
        self.service.thread = None
        self.service.start()
        self.assertIsNone(self.service.thread)
        self.assertFalse(self.client.get('/gateway/engineering/capabilities', headers=self.headers).json()['enabled'])
        self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers).status_code)

    def test_readiness_separates_host_configuration_storage_worker_and_chat(self):
        url = '/gateway/engineering/capabilities'
        ready = self.client.get(url, headers=self.headers).json()
        self.assertTrue(ready['enabled'])
        self.assertTrue(ready['code_graph']['enabled'])
        self.assertIsNone(ready['readiness']['inference_ready'])
        self.assertIsNone(ready['readiness']['github_reachable'])
        self.assertIsNone(ready['readiness']['repository_access'])
        self.backend.available = lambda: False
        missing = self.client.get(url, headers=self.headers).json()
        self.assertFalse(missing['enabled'])
        self.assertFalse(missing['code_graph']['enabled'])
        self.assertEqual('missing', missing['readiness']['checks']['host_github_connection'])
        self.assertTrue(missing['readiness']['saved_results'])
        with patch.object(self.service.graph, 'execute') as graph:
            response = self.client.post('/gateway/engineering/graph', json={'action': 'status'}, headers=self.headers)
            self.assertEqual(412, response.status_code)
            self.assertFalse(response.json()['error']['retryable'])
            self.assertEqual('HOST_GITHUB_CONNECTION_REQUIRED', response.json()['error']['reason'])
            graph.assert_not_called()
        self.backend.available = lambda: True
        self.service.thread = None
        stopped = self.client.get(url, headers=self.headers).json()
        self.assertFalse(stopped['enabled'])
        self.assertTrue(stopped['code_graph']['enabled'])
        self.assertEqual('unavailable', stopped['readiness']['checks']['worker'])
        self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers).status_code)
        self.runtime.durable_write_healthy = False
        blocked = self.client.get(url, headers=self.headers).json()
        self.assertFalse(blocked['code_graph']['enabled'])
        self.assertEqual('write_failed', blocked['readiness']['checks']['journal'])
        self.assertTrue(blocked['readiness']['saved_results'])

    def test_lost_create_receipt_recovers_same_terminal_job_when_new_work_is_unavailable(self):
        created = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, created.status_code)
        job = created.json()
        self.store.control(job['id'], 'tailnet:owner', 'cancel', job['revision'])
        self.backend.available = lambda: False
        self.service.thread = None
        recovered = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(200, recovered.status_code, recovered.text)
        self.assertEqual(job['id'], recovered.json()['id'])
        self.assertEqual('CANCELLED', recovered.json()['state'])
        self.assertEqual(200, self.client.get('/gateway/engineering/jobs/' + job['id'], headers=self.headers).status_code)
        self.assertEqual(409, self.client.post('/gateway/engineering/jobs', json=dict(self.request, objective='different task'), headers=self.headers).status_code)
        self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=dict(self.request, request_id='new-work'), headers=self.headers).status_code)
        self.assertEqual(1, len(self.store.list('tailnet:owner')))
        self.assertEqual([], self.backend.calls)

    def test_missed_updates_paginate_without_replaying_execution(self):
        job = self.store.create('tailnet:owner', self.request)
        def insert(db):
            for revision in range(1, 260):
                self.store.event(db, dict(job, message='Saved update ' + str(revision)), 'CI', revision, self.time)
            db.execute('UPDATE engineering_jobs SET revision=259 WHERE id=?', (job['id'],))
        self.store.tx(insert)
        url = '/gateway/engineering/jobs/' + job['id']
        first = self.client.get(url + '?after_revision=-1', headers=self.headers).json()
        self.assertTrue(first['has_more_updates'])
        second = self.client.get(url + '?after_revision=' + str(first['next_revision']), headers=self.headers).json()
        self.assertFalse(second['has_more_updates'])
        self.assertEqual(list(range(260)), [event['revision'] for event in first['updates'] + second['updates']])
        self.assertEqual([], self.backend.calls)

    def test_signed_webhook_advances_reconciliation_without_granting_merge(self):
        job = self.store.create('tailnet:owner', self.request)
        for _ in range(6):
            self.service.tick(job['id'])
        self.backend.evidence['checks_passed'] = False
        self.service.tick(job['id'])
        self.assertEqual([], self.store.due())
        body = b'{"action":"completed"}'
        signature = 'sha256=' + hmac.new(b'test-secret', body, hashlib.sha256).hexdigest()
        with patch.dict('os.environ', {'GATEWAY_ENGINEERING_WEBHOOK_SECRET': 'test-secret'}):
            bad = self.client.post('/gateway/engineering/webhook', content=body, headers=dict(self.headers, **{'X-Hub-Signature-256': 'wrong'}))
            self.assertEqual(403, bad.status_code)
            self.assertEqual([], self.store.due())
            good = self.client.post('/gateway/engineering/webhook', content=body, headers=dict(self.headers, **{'X-Hub-Signature-256': signature}))
            self.assertEqual(200, good.status_code)
            duplicate = self.client.post('/gateway/engineering/webhook', content=body, headers=dict(self.headers, **{'X-Hub-Signature-256': signature}))
            self.assertTrue(duplicate.json()['duplicate'])
        self.assertEqual([job['id']], self.store.due())
        self.assertEqual('CI', self.store.get(job['id'])['state'])
        self.assertNotIn('merge', self.backend.calls)
