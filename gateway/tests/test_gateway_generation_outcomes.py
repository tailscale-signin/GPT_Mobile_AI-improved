"""Real generation route, durable rejection fence and read-only reconciliation."""
import importlib
import sqlite3
import unittest
from unittest.mock import patch

import test_gateway_conversations as conversation_fixtures
import test_gateway_v14_jobs as journal_fixtures
from v14.conversations import ConversationConflict, Conversations


class GenerationCommandOutcomeTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.gateway = importlib.import_module('gateway_v14')

    setUp = journal_fixtures.JournalTests.setUp
    client = journal_fixtures.JournalTests.client
    completion = journal_fixtures.JournalTests.completion
    payload = staticmethod(conversation_fixtures.ConversationHTTPTests.payload)

    def test_lost_generation_conflict_is_readable_after_restart_and_fences_late_post(self):
        payload = self.payload()
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            with patch.object(self.runtime, 'register_gateway_job', side_effect=ConversationConflict('Existing job is active')):
                failed = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(409, failed.status_code, failed.text)
            self.assertEqual('rejected', failed.json()['error']['admission_state'])
            self.assertFalse(failed.json()['error']['inference_attempted'])
            self.stack.enter_context(patch.object(self.runtime, 'gateway_conversations', Conversations(self.runtime)))
            receipt = client.get('/gateway/commands/turn-one?include_outcome=true')
            self.assertEqual(200, receipt.status_code, receipt.text)
            outcome = receipt.json()
            self.assertEqual('generation', outcome['job_kind'])
            self.assertEqual('rejected', outcome['admission_state'])
            self.assertEqual('conversation_conflict', outcome['error_code'])
            self.assertEqual('not_dispatched', outcome['dispatch_state'])
            self.assertNotIn('Inspect the repository', receipt.text)
            self.assertEqual(404, client.get('/gateway/commands/turn-one').status_code)
            late = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(409, late.status_code, late.text)
            model.assert_not_called()

    def test_changed_payload_cannot_reuse_negative_command_and_accepted_outcome_wins(self):
        payload = self.payload()
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload', side_effect=self.completion) as model:
            accepted = client.post('/v1/chat/completions', json=payload)
            self.assertEqual(200, accepted.status_code, accepted.text)
            receipt = self.runtime.gateway_conversations.reject_command(
                'local:single-user', 'turn-one', payload, 'conversation_conflict', 409, kind='generation')
            self.assertEqual(accepted.headers['x-gateway-job-id'], receipt['job_id'])
            self.assertNotIn('admission_state', receipt)
            rejected = self.payload(command='rejected')
            with patch.object(self.runtime, 'register_gateway_job', side_effect=ConversationConflict('Active job')):
                self.assertEqual(409, client.post('/v1/chat/completions', json=rejected).status_code)
            changed = self.payload(command='rejected', text='A different request')
            response = client.post('/v1/chat/completions', json=changed)
            self.assertEqual(409, response.status_code)
            self.assertTrue(response.json()['error'].get('command_outcome_unknown', True))
            self.assertEqual(1, model.call_count)

    def test_failed_journal_write_remains_unknown_and_does_not_claim_rejection(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            with patch.object(self.runtime, 'register_gateway_job', side_effect=ConversationConflict('Active job')), \
                    patch.object(self.runtime.gateway_conversations, 'reject_command', side_effect=sqlite3.OperationalError('Private details')):
                response = client.post('/v1/chat/completions', json=self.payload())
            self.assertEqual(503, response.status_code, response.text)
            error = response.json()['error']
            self.assertEqual('unknown', error['admission_state'])
            self.assertEqual('unknown', error['dispatch_state'])
            self.assertTrue(error['command_outcome_unknown'])
            self.assertNotIn('inference_attempted', error)
            self.assertNotIn('Private details', response.text)
            self.assertEqual(404, client.get('/gateway/commands/turn-one?include_outcome=true').status_code)
            model.assert_not_called()

    def test_private_generation_and_transport_uncertainty_never_create_rejection_receipts(self):
        with self.client() as client, patch.object(self.runtime, 'process_chat_payload') as model:
            with patch.object(self.runtime, 'register_gateway_job', side_effect=ConversationConflict('Active job')):
                self.assertEqual(409, client.post('/v1/chat/completions', json=self.payload(persistent=False)).status_code)
            self.assertEqual(404, client.get('/gateway/commands/turn-one?include_outcome=true').status_code)
            with patch.object(self.runtime, 'register_gateway_job', side_effect=self.gateway.DurableCommitError('Unknown commit')):
                self.assertEqual(503, client.post('/v1/chat/completions', json=self.payload()).status_code)
            self.assertEqual(404, client.get('/gateway/commands/turn-one?include_outcome=true').status_code)
            model.assert_not_called()


if __name__ == '__main__':
    unittest.main()
