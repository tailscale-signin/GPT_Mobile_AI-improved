import json
import hashlib
import hmac
import sys
import unittest
from unittest.mock import patch
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from fastapi import FastAPI
from fastapi.testclient import TestClient
from v14.engineering_routes import install_engineering_routes
from v14.identity import Admission
from v14.config import Config
import test_gateway_v14_engineering as fixtures


class EngineeringHTTPTests(unittest.TestCase):
    def setUp(self):
        fixtures.DurableEngineeringTests.setUp(self)
        self.backend.available = lambda: True
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

    def test_existing_read_scope_cannot_start_engineering_and_no_credential_is_returned(self):
        response = self.client.post('/gateway/engineering/jobs', json=self.request, headers={'Tailscale-User-Login': 'reader@example.test'})
        self.assertEqual(403, response.status_code)
        capability = self.client.get('/gateway/engineering/capabilities', headers=self.headers)
        self.assertTrue(capability.json()['explicit_merge_approval'])
        self.assertEqual('android_background_sync', capability.json()['notifications'])

    def test_disabled_durable_storage_does_not_break_gateway_startup(self):
        self.runtime.GATEWAY_DURABLE_JOBS = False
        self.service.start()
        self.assertIsNone(self.service.thread)
        self.assertFalse(self.client.get('/gateway/engineering/capabilities', headers=self.headers).json()['enabled'])
        self.assertEqual(503, self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers).status_code)

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
        self.assertEqual([job['id']], self.store.due())
        self.assertEqual('CI', self.store.get(job['id'])['state'])
        self.assertNotIn('merge', self.backend.calls)
