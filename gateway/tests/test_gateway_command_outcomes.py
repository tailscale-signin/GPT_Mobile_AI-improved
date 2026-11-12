"""Admission outcomes remain recoverable without replaying a lost command POST."""
import json
import sqlite3
import unittest
from types import SimpleNamespace
from unittest.mock import patch

import requests
import test_gateway_v14_engineering_http as fixtures
from v14.conversations import ConversationConflict, Conversations
from v14.execution_binding import ExecutionBindingError
from v14.job_catalog import install_routes


class EngineeringCommandOutcomeTests(unittest.TestCase):
    def setUp(self):
        fixtures.EngineeringHTTPTests.setUp(self)
        install_routes(self.client.app, self.store.conversations, self.service, SimpleNamespace(instance_id='instance'), {})

    tearDown = fixtures.EngineeringHTTPTests.tearDown

    def receipt(self, command='request', headers=None, include=True):
        return self.client.get('/gateway/commands/' + command + ('?include_outcome=true' if include else ''), headers=headers or self.headers)

    def test_lost_rejection_response_is_recovered_after_reopening_journal_and_fences_late_post(self):
        with patch.object(self.service, 'admit', side_effect=ExecutionBindingError('EXECUTION_MODEL_LOADING', 'Private backend detail', 409)):
            rejected = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(409, rejected.status_code)
        # The Android response may be lost; only this later authenticated GET is used.
        self.store.conversations = Conversations(self.runtime, lambda: self.time)
        recovered = self.receipt()
        self.assertEqual(200, recovered.status_code, recovered.text)
        outcome = recovered.json()
        self.assertEqual('rejected', outcome['admission_state'])
        self.assertEqual('EXECUTION_MODEL_LOADING', outcome['error_code'])
        self.assertEqual('provider_admission', outcome['failed_stage'])
        self.assertEqual('not_dispatched', outcome['dispatch_state'])
        self.assertFalse(outcome['inference_attempted'])
        self.assertNotIn('Private backend detail', recovered.text)
        self.assertNotIn(self.request['objective'], recovered.text)
        self.assertEqual(404, self.receipt(include=False).status_code)
        self.assertEqual(404, self.receipt(headers={'Tailscale-User-Login': 'other@example.test'}).status_code)
        self.assertEqual(404, self.receipt(headers={'Tailscale-User-Login': 'reader@example.test'}).status_code)
        with self.assertRaises(ConversationConflict):
            self.store.create('tailnet:owner', self.request)
        self.assertEqual([], self.store.list('tailnet:owner'))
        self.assertEqual([], self.backend.calls)

    def test_lost_acceptance_response_is_readable_and_late_rejection_cannot_overwrite_it(self):
        accepted = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, accepted.status_code, accepted.text)
        job = accepted.json()
        outcome = self.store.conversations.reject_command('tailnet:owner', 'request', self.request, 'queue_full', 429)
        self.assertEqual(job['id'], outcome['job_id'])
        receipt = self.receipt().json()
        self.assertEqual(job['id'], receipt['job_id'])
        self.assertEqual(job['conversation_id'], receipt['conversation_id'])
        self.assertNotIn('admission_state', receipt)
        self.assertEqual(1, len(self.store.list('tailnet:owner')))
        self.assertEqual([], self.backend.calls)

    def test_acceptance_committing_during_failed_preflight_wins_http_response_too(self):
        def competing_admission(owner, payload):
            self.store.create(owner, payload)
            raise ExecutionBindingError('EXECUTION_MODEL_LOADING', 'Later preflight result', 409)
        with patch.object(self.service, 'admit', side_effect=competing_admission):
            response = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(200, response.status_code, response.text)
        self.assertEqual(response.json()['id'], self.receipt().json()['job_id'])
        self.assertEqual(1, len(self.store.list('tailnet:owner')))

    def test_unknown_network_result_never_becomes_a_negative_receipt(self):
        with patch.object(self.service, 'admit', side_effect=requests.ConnectionError('Lost upstream response')):
            response = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(503, response.status_code)
        self.assertEqual(404, self.receipt().status_code)
        for _ in range(3):
            self.assertEqual(404, self.receipt('never-admitted').status_code)
        self.assertEqual([], self.store.list('tailnet:owner'))

    def test_known_unready_host_has_durable_cause_without_payload_or_credentials(self):
        self.backend.available = lambda: False
        response = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(503, response.status_code)
        receipt = self.receipt().json()
        self.assertEqual('engineering_connection_required', receipt['error_code'])
        self.assertEqual('rejected', receipt['admission_state'])
        self.assertNotIn('repository', receipt)
        self.assertNotIn('objective', receipt)
        self.assertNotIn('token', json.dumps(receipt))

    def test_changed_payload_cannot_claim_an_accepted_or_rejected_command_identity(self):
        accepted = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, accepted.status_code)
        changed = dict(self.request, objective='A different task')
        conflict = self.client.post('/gateway/engineering/jobs', json=changed, headers=self.headers)
        self.assertEqual(409, conflict.status_code)
        self.assertTrue(conflict.json()['error']['command_outcome_unknown'])
        self.assertEqual(accepted.json()['id'], self.receipt().json()['job_id'])
        # A different request ID with the already accepted command ID reaches
        # the command fence rather than existing_request's request-ID check.
        reused_command = self.client.post('/gateway/engineering/jobs', json=dict(changed, request_id='different-request', command_id='request'), headers=self.headers)
        self.assertEqual(409, reused_command.status_code)
        self.assertTrue(reused_command.json()['error']['command_outcome_unknown'])
        with self.assertRaises(ConversationConflict):
            self.store.conversations.reject_command('tailnet:owner', 'request', changed, 'queue_full', 429)
        rejected_request = dict(self.request, request_id='rejected')
        self.store.conversations.reject_command('tailnet:owner', 'rejected', rejected_request, 'queue_full', 429)
        rejected_conflict = self.client.post('/gateway/engineering/jobs', json=dict(rejected_request, objective='Another payload'), headers=self.headers)
        self.assertEqual(409, rejected_conflict.status_code)
        self.assertTrue(rejected_conflict.json()['error']['command_outcome_unknown'])
        self.assertEqual('queue_full', self.receipt('rejected').json()['error_code'])
        self.assertEqual(1, len(self.store.list('tailnet:owner')))

    def test_unreadable_journal_cannot_negate_an_earlier_acceptance(self):
        accepted = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, accepted.status_code)
        unavailable = dict(self.service.readiness(), ready=False, saved_results=False)
        with patch.object(self.service, 'readiness', return_value=unavailable), \
                patch.object(self.store, 'existing_request') as existing, \
                patch.object(self.store.conversations, 'reject_command') as reject:
            response = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(503, response.status_code)
        error = response.json()['error']
        self.assertEqual('unknown', error['admission_state'])
        self.assertEqual('unknown', error['dispatch_state'])
        self.assertTrue(error['command_outcome_unknown'])
        self.assertNotIn('inference_attempted', error)
        existing.assert_not_called()
        reject.assert_not_called()
        self.assertEqual(accepted.json()['id'], self.receipt().json()['job_id'])
        self.assertEqual(1, len(self.store.list('tailnet:owner')))

    def test_full_rejection_ledger_preserves_unknown_outcome_without_a_fence(self):
        self.store.tx(lambda db: db.executemany('INSERT INTO gateway_command_rejections VALUES(?,?,?,?,?)',
            (('tailnet:owner', 'prior-' + str(index), 'fingerprint', '{}', self.time) for index in range(16384))))
        with patch.object(self.service, 'admit', side_effect=ExecutionBindingError('EXECUTION_MODEL_LOADING', 'Original readiness failure', 409)):
            response = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(503, response.status_code, response.text)
        error = response.json()['error']
        self.assertEqual('engineering_connection_unavailable', error['code'])
        self.assertEqual('unknown', error['admission_state'])
        self.assertEqual('unknown', error['dispatch_state'])
        self.assertTrue(error['command_outcome_unknown'])
        self.assertNotIn('inference_attempted', error)
        self.assertEqual(404, self.receipt().status_code)
        self.assertEqual([], self.store.list('tailnet:owner'))

    def test_failed_rejection_write_cannot_turn_preflight_error_into_terminal_rejection(self):
        for storage_failure in (sqlite3.OperationalError('Private journal details'), OSError('Private filesystem details')):
            with self.subTest(failure=type(storage_failure).__name__), \
                    patch.object(self.service, 'admit', side_effect=ExecutionBindingError('EXECUTION_MODEL_LOADING', 'Original readiness failure', 409)), \
                    patch.object(self.store.conversations, 'reject_command', side_effect=storage_failure):
                response = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
            self.assertEqual(503, response.status_code, response.text)
            error = response.json()['error']
            self.assertEqual('engineering_connection_unavailable', error['code'])
            self.assertEqual('unknown', error['admission_state'])
            self.assertEqual('unknown', error['dispatch_state'])
            self.assertTrue(error['command_outcome_unknown'])
            self.assertNotIn('inference_attempted', error)
            self.assertNotIn('Private', response.text)
            self.assertEqual(404, self.receipt().status_code)
        # Without a committed rejection fence a later copy really can accept;
        # the earlier 503 must therefore keep the client in reconciliation.
        accepted = self.client.post('/gateway/engineering/jobs', json=self.request, headers=self.headers)
        self.assertEqual(202, accepted.status_code, accepted.text)
        self.assertEqual(accepted.json()['id'], self.receipt().json()['job_id'])

    def test_missing_or_malformed_identity_returns_client_error_without_outcome(self):
        for payload in ({key: value for key, value in self.request.items() if key != 'request_id'}, dict(self.request, command_id='invalid\nidentity')):
            response = self.client.post('/gateway/engineering/jobs', json=payload, headers=self.headers)
            self.assertIn(response.status_code, (400, 409, 422))
        self.assertEqual(404, self.receipt().status_code)
