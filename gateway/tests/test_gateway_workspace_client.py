import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock

from desktop import workspace_client
from desktop.conversation_client import ClientError


class WorkspaceClientTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.identity = {'gateway_id': 'installed-gateway', 'principal_id': 'owner'}
        self.job = dict(id='job', repository='owner/repo', branch='feature')
        self.client = Mock(expected_identity=self.identity)
        self.payload = {'command_id': 'request-1', 'revision': 2, 'head_sha': 'a' * 40,
                        'action': 'review_draft', 'arguments': {'draft_id': 'note', 'body': 'private review source'}}
        self.accepted = {'schema_version': 1, 'job_id': 'job', 'command_id': 'request-1',
                         'head_sha': 'a' * 40, 'revision': 2, 'status': 'QUEUED'}

    def tearDown(self):
        self.temp.cleanup()

    def test_uncertain_send_preserves_encrypted_original_before_network(self):
        def request(method, *args, **kwargs):
            stored = workspace_client._original(self.root, self.identity, 'job', 'request-1')
            self.assertEqual(self.payload, stored)
            raise ClientError('Connection interrupted')
        self.client.request.side_effect = request
        with self.assertRaises(ClientError):
            workspace_client.submit(self.client, self.root, self.job, self.payload)
        raw = (self.root / 'data' / 'desktop-workspace-commands.json').read_text()
        self.assertNotIn('private review source', raw)
        self.assertEqual(1, self.client.request.call_count)

    def test_mismatched_response_keeps_original_command(self):
        self.client.request.return_value = dict(self.accepted, command_id='foreign')
        with self.assertRaises(ClientError):
            workspace_client.submit(self.client, self.root, self.job, self.payload)
        self.assertEqual(self.payload, workspace_client._original(self.root, self.identity, 'job', 'request-1'))

    def test_retry_requires_absence_and_reuses_exact_original_envelope(self):
        self.client.request.return_value = self.accepted
        workspace_client.submit(self.client, self.root, self.job, self.payload)
        board = dict(self.identity, schema_version=1, job_id='job', repository='owner/repo', branch='feature',
                     head_sha='a' * 40, revision=2)
        absent = dict(self.accepted, status='NOT_FOUND', confirmed_absent=True)
        self.client.request.reset_mock()
        self.client.request.side_effect = [absent, board, self.accepted]
        result = workspace_client.retry_original(self.client, self.root, self.job, 'request-1')
        self.assertEqual(self.accepted, result)
        call = self.client.request.call_args_list[-1]
        self.assertEqual(self.payload, call.kwargs['payload'])
        self.assertEqual('workspace-command', call.kwargs['user_action'])

    def test_existing_or_changed_source_never_replays(self):
        self.client.request.return_value = self.accepted
        workspace_client.submit(self.client, self.root, self.job, self.payload)
        for response in (self.accepted, dict(self.accepted, status='NOT_FOUND', confirmed_absent=True, head_sha='b' * 40)):
            self.client.request.reset_mock()
            self.client.request.return_value = response
            with self.assertRaises(ClientError):
                workspace_client.retry_original(self.client, self.root, self.job, 'request-1')
            self.assertEqual(1, self.client.request.call_count)
            self.assertEqual('GET', self.client.request.call_args.args[0])

    def test_foreign_identity_cannot_recover_original_arguments(self):
        self.client.request.return_value = self.accepted
        workspace_client.submit(self.client, self.root, self.job, self.payload)
        with self.assertRaises(ClientError):
            workspace_client._original(self.root, dict(self.identity, principal_id='another'), 'job', 'request-1')

    def test_terminal_receipt_retires_only_matching_local_pending(self):
        self.client.request.return_value = self.accepted
        workspace_client.submit(self.client, self.root, self.job, self.payload)
        self.client.request.return_value = dict(self.accepted, status='COMPLETED')
        workspace_client.receipt(self.client, self.root, self.job, 'request-1')
        self.assertEqual({}, workspace_client._read(self.root))

    def test_restart_board_exposes_pending_identity_without_arguments(self):
        self.client.request.return_value = self.accepted
        workspace_client.submit(self.client, self.root, self.job, self.payload)
        self.client.request.return_value = dict(self.identity, schema_version=1, job_id='job', repository='owner/repo',
                                               branch='feature', head_sha='a' * 40, revision=2)
        value = workspace_client.board(self.client, self.job, self.root)
        self.assertEqual('request-1', value['local_commands'][0]['command_id'])
        self.assertNotIn('private review source', json.dumps(value))

    def test_stale_absent_command_can_be_dismissed_without_replay_or_success(self):
        self.client.request.return_value = self.accepted
        workspace_client.submit(self.client, self.root, self.job, self.payload)
        absent = dict(self.accepted, status='NOT_FOUND', confirmed_absent=True, revision=3)
        current = dict(self.identity, schema_version=1, job_id='job', repository='owner/repo',
                       branch='feature', head_sha='a' * 40, revision=3)
        self.client.request.reset_mock()
        self.client.request.side_effect = [absent, current]
        value = workspace_client.dismiss_absent(self.client, self.root, self.job, 'request-1')
        self.assertFalse(value['completed'])
        self.assertFalse(value['replayed'])
        self.assertEqual({}, workspace_client._read(self.root))
        self.assertTrue(all(call.args[0] == 'GET' for call in self.client.request.call_args_list))

    def test_unknown_or_current_commands_cannot_be_dismissed_as_absent(self):
        self.client.request.return_value = self.accepted
        workspace_client.submit(self.client, self.root, self.job, self.payload)
        current = dict(self.identity, schema_version=1, job_id='job', repository='owner/repo',
                       branch='feature', head_sha='a' * 40, revision=2)
        for response in (dict(self.accepted, status='UNKNOWN'), dict(self.accepted, status='NOT_FOUND', confirmed_absent=True)):
            self.client.request.side_effect = [response, current]
            with self.assertRaises(ClientError):
                workspace_client.dismiss_absent(self.client, self.root, self.job, 'request-1')
            self.assertEqual(self.payload, workspace_client._original(self.root, self.identity, 'job', 'request-1'))


if __name__ == '__main__':
    unittest.main()
