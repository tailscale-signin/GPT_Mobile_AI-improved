import copy
import json
import sqlite3
import tempfile
import threading
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import Mock, patch

from v14.engineering import EngineeringService, EngineeringStore
from v14.execution_binding import fingerprint
from v14.workspace_board import snapshot


class WorkspaceCommandTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.clock = 1000.
        runtime = SimpleNamespace(LLAMA_BASE='http://localhost:8080', GATEWAY_DURABLE_JOBS=True,
                                  durable_job_initialized=True, durable_job_lock=threading.RLock(),
                                  _durable_connect=lambda: sqlite3.connect(Path(self.temp.name) / 'jobs.db'))
        self.store = EngineeringStore(runtime, lambda: self.clock)
        self.backend = SimpleNamespace()
        self.service = EngineeringService(self.store, self.backend)
        self.service.capabilities._probe = lambda route, model: {
            'requested_model_id': model, 'reported_model_id': model, 'limits_verified': True,
            'context_tokens': 32768, 'max_output_tokens': 32768, 'model_revision': None,
            'template_digest': None, 'capability_revision': fingerprint([route, model]),
            'supported': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']}
        binding = self.service.prepare_binding('owner', {
            'profile_uid': 'profile', 'profile_revision': 'revision', 'provider_connection_id': 'connection',
            'provider_connection_revision': 'revision', 'provider_kind': 'LLAMA',
            'configured_endpoint': runtime.LLAMA_BASE, 'requested_model_id': 'local',
            'generation_parameters': {}, 'privacy_scope': 'private', 'tool_owner': 'GATEWAY',
            'required_capability_policy': ['SELECTED_ONLY', 'MODEL_IDENTITY', 'CONTEXT_BUDGET']})
        self.request = {'repository': 'owner/repo', 'branch': 'feature', 'base': 'main', 'objective': 'Repair source',
                        'model': 'local', 'profile_id': 'profile', 'request_id': 'initial', 'chat_id': 12,
                        'execution_binding_id': binding['binding']['binding_id'],
                        'acceptance': [{'id': 'repair', 'paths': ['a.py'], 'checks': ['tests']}]}
        created = self.store.create('owner', self.request)
        job, lease, _ = self.store.claim(created['id'])
        job['data'].update(head_sha='a' * 40, tree={'a.py': {'sha': 'b' * 40, 'mode': '100644'}}, files={})
        self.store.complete(job, lease, 'IMPLEMENT', {})
        self.job = self.store.get(created['id'])
        self.commands = self.service.workspace_commands

        def access(principal, job_id):
            value = self.store.get(job_id, principal)
            if value is None:
                raise PermissionError('Unknown job owner')
            return value
        self.service.evidence_access = access

    def tearDown(self):
        self.temp.cleanup()

    def payload(self, identifier='cmd-1', **updates):
        return dict({'command_id': identifier, 'revision': self.job['revision'], 'head_sha': 'a' * 40,
                     'action': 'worker_status', 'arguments': {}}, **updates)

    def test_exact_identity_is_idempotent_but_changed_payload_is_rejected(self):
        first = self.commands.enqueue('owner', self.job['id'], self.payload())
        self.assertEqual('QUEUED', first['status'])
        self.assertEqual(first, self.commands.enqueue('owner', self.job['id'], self.payload()))
        with self.assertRaises(ValueError):
            self.commands.enqueue('owner', self.job['id'], self.payload(arguments={'worker_id': 'other'}))
        self.assertEqual(1, len(self.commands.list(self.job)))

    def test_delegated_agent_wait_uses_parent_checkpoint_without_a_second_model_step(self):
        self.backend.step = Mock()
        with patch.object(self.service.agent_runs, 'advance', return_value={
                'pending': True, 'holding': True, 'next_at': self.clock + 15, 'observed': ['retained-run']}):
            self.service.tick(self.job['id'])
        saved = self.store.get(self.job['id'])
        self.assertEqual('IMPLEMENT', saved['state'])
        self.assertEqual(self.job['revision'] + 1, saved['revision'])
        self.assertEqual(self.job['steps'], saved['steps'])
        self.backend.step.assert_not_called()

    def test_parent_cancellation_queues_cleanup_without_blocking_http_or_startup(self):
        with patch.object(self.service.agent_runs, 'stop_parent') as stop:
            result = self.store.control(self.job['id'], 'owner', 'cancel', self.job['revision'])
            self.assertEqual('CANCELLED', result['state'])
            self.assertIn(self.job['id'], self.service.agent_cleanup_pending)
            stop.assert_not_called()
            self.service.stop_agents(self.job['id'])
        self.assertEqual('CANCELLED', stop.call_args.args[0]['state'])

    def test_completed_maintenance_does_not_disqualify_the_existing_merge_ready_checkpoint(self):
        job, lease, _ = self.store.claim(self.job['id'])
        self.store.complete(job, lease, 'MERGE_READY', {})
        self.job = self.store.get(job['id'])
        payload = self.payload(action='agent_status', arguments={'run_id': 'test-run'})
        self.commands.enqueue('owner', job['id'], payload)
        with patch.object(self.service.agent_runs, 'act', return_value={'run_id': 'test-run', 'state': 'COMPLETED'}), \
                patch.object(self.service, 'validate_execution') as model_binding:
            self.service.tick(job['id'])
        saved = self.store.get(job['id'])
        self.assertEqual('MERGE_READY', saved['state'])
        self.assertEqual('COMPLETED', self.commands.read('owner', job['id'], payload['command_id'])['status'])
        model_binding.assert_not_called()

    def test_foreign_stale_and_overridden_identities_cannot_enqueue(self):
        with self.assertRaises(PermissionError):
            self.commands.enqueue('foreign', self.job['id'], self.payload())
        for value in (self.payload(head_sha='c' * 40), self.payload(revision=999),
                      self.payload(arguments={'head_sha': 'c' * 40}),
                      self.payload(arguments={'request_id': 'another-command'})):
            with self.assertRaises(ValueError):
                self.commands.enqueue('owner', self.job['id'], value)
        self.assertEqual([], self.commands.list(self.job))

    def test_result_and_mutated_job_commit_together_under_parent_lease(self):
        self.commands.enqueue('owner', self.job['id'], self.payload())
        job, lease, _ = self.store.claim(self.job['id'])

        def act(backend, owned, action):
            self.assertEqual('cmd-1', action['request_id'])
            self.assertEqual('cmd-1', owned['_workspace_user_command_id'])
            self.assertEqual('a' * 40, action['head_sha'])
            owned['data']['worker_marker'] = 'retained'
            return {'action': action['action'], 'head_sha': action['head_sha'], 'receipt_sha256': 'd' * 64}
        with patch('v14.engineering_workspace.act', side_effect=act):
            result = self.commands.process(job)
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual('RUNNING', self.commands.read('owner', job['id'], 'cmd-1')['status'])
        self.assertNotIn('worker_marker', self.store.get(job['id'])['data'])
        self.assertTrue(self.store.complete(job, lease, 'IMPLEMENT', result))
        self.assertEqual('COMPLETED', self.commands.read('owner', job['id'], 'cmd-1')['status'])
        self.assertEqual('retained', self.store.get(job['id'])['data']['worker_marker'])

    def test_interrupted_running_command_is_never_replayed(self):
        self.commands.enqueue('owner', self.job['id'], self.payload())
        job, _, _ = self.store.claim(self.job['id'])
        with patch('v14.engineering_workspace.act', return_value={'receipt_sha256': 'd' * 64}) as invoke:
            self.commands.process(job)
            self.store.recover()
            recovered, lease, _ = self.store.claim(job['id'])
            result = self.commands.process(recovered)
        self.assertEqual(1, invoke.call_count)
        self.assertEqual('UNKNOWN', result['status'])
        self.assertTrue(result['requires_attention'])
        self.store.complete(recovered, lease, 'NEEDS_INPUT', result)
        self.assertEqual('UNKNOWN', self.commands.read('owner', job['id'], 'cmd-1')['status'])

    def test_changed_source_rejects_queued_work_before_execution(self):
        self.commands.enqueue('owner', self.job['id'], self.payload())
        job, lease, _ = self.store.claim(self.job['id'])
        job['data']['head_sha'] = 'c' * 40
        with patch('v14.engineering_workspace.act') as invoke:
            result = self.commands.process(job)
        self.assertEqual('STALE_SOURCE', result['reason'])
        invoke.assert_not_called()
        self.store.complete(job, lease, 'IMPLEMENT', result)
        self.assertEqual('REJECTED', self.commands.read('owner', job['id'], 'cmd-1')['status'])

    def test_late_command_holds_publication_boundary(self):
        job, lease, _ = self.store.claim(self.job['id'])
        self.commands.enqueue('owner', self.job['id'], self.payload())
        self.store.complete(job, lease, 'COMMIT', {'review': {'approved': True}})
        retained = self.store.get(job['id'])
        self.assertEqual('IMPLEMENT', retained['state'])
        self.assertEqual('QUEUED', self.commands.read('owner', job['id'], 'cmd-1')['status'])

    def test_review_draft_is_private_and_does_not_resume_paused_task(self):
        job, lease, _ = self.store.claim(self.job['id'])
        self.store.complete(job, lease, 'NEEDS_INPUT', {})
        self.job = self.store.get(job['id'])
        payload = self.payload(action='review_draft', arguments={'draft_id': 'note', 'body': 'Cover the empty input.', 'path': 'a.py', 'line': 1})
        result = self.commands.enqueue('owner', job['id'], payload)
        self.assertEqual('COMPLETED', result['status'])
        saved = self.commands.drafts(self.job)
        self.assertEqual('Cover the empty input.', saved[0]['body'])
        self.assertFalse(saved[0]['posted'])
        self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
        self.assertNotIn('Cover the empty input.', json.dumps(self.commands.list(self.job)))

    def retain_tree_artifact(self):
        job = self.store.get(self.job['id'])
        tree = self.service.artifacts.put_json(job, job['data']['tree'], 'retained-source-tree')
        job['data']['tree'] = tree
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(job), job['id'])))
        return job

    def test_review_draft_hydrates_checkpointed_tree_before_writer_transaction(self):
        self.retain_tree_artifact()
        payload = self.payload(action='review_draft', arguments={'draft_id': 'artifact-note', 'body': 'Check the source.', 'path': 'a.py', 'line': 1})
        result = self.commands.enqueue('owner', self.job['id'], payload)
        self.assertEqual('COMPLETED', result['status'])
        self.assertEqual('a.py', self.commands.drafts(self.job)[0]['path'])
        self.assertEqual('IMPLEMENT', self.store.get(self.job['id'])['state'])

    def test_review_draft_rejects_tree_replacement_during_hydration(self):
        job = self.retain_tree_artifact()
        original = self.service.artifacts.json_value
        def changed_tree(owner, reference):
            value = original(owner, reference)
            job['data']['tree'] = {'a.py': {'sha': 'c' * 40, 'mode': '100644'}}
            # A leased worker may update checkpoint data without advancing the
            # revision. The tree binding must detect this independently.
            self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(job), job['id'])))
            return value
        payload = self.payload(action='review_draft', arguments={'draft_id': 'changed-tree', 'body': 'Check source.', 'path': 'a.py'})
        with patch.object(self.service.artifacts, 'json_value', side_effect=changed_tree):
            with self.assertRaisesRegex(ValueError, 'source tree changed'):
                self.commands.enqueue('owner', job['id'], payload)
        self.assertEqual([], self.commands.list(job))
        self.assertEqual([], self.commands.drafts(job))

    def test_cancellation_during_review_tree_hydration_still_wins_admission(self):
        job = self.retain_tree_artifact()
        original = self.service.artifacts.json_value
        def cancelled_parent(owner, reference):
            value = original(owner, reference)
            self.store.control(job['id'], 'owner', 'cancel', job['revision'])
            return value
        payload = self.payload(action='review_draft', arguments={'draft_id': 'cancelled-draft', 'body': 'Check source.', 'path': 'a.py'})
        with patch.object(self.service.artifacts, 'json_value', side_effect=cancelled_parent):
            with self.assertRaisesRegex(ValueError, 'revision or source changed'):
                self.commands.enqueue('owner', job['id'], payload)
        self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])
        self.assertEqual([], self.commands.list(job))

    def test_environment_observation_uses_parent_owner_without_resuming_paused_task(self):
        job, lease, _ = self.store.claim(self.job['id'])
        self.store.complete(job, lease, 'NEEDS_INPUT', {})
        self.job = self.store.get(job['id'])
        payload = self.payload(action='environment_qualify')
        self.commands.enqueue('owner', job['id'], payload)
        self.assertIn(job['id'], self.store.due())
        with patch('v14.engineering_workspace.act', return_value={'action': 'environment_qualify', 'ready': True, 'receipt_sha256': 'd' * 64}), patch.object(self.service, 'validate_execution') as invoke_model:
            self.service.tick(job['id'])
        invoke_model.assert_not_called()
        self.assertEqual('NEEDS_INPUT', self.store.get(job['id'])['state'])
        self.assertEqual('COMPLETED', self.commands.read('owner', job['id'], 'cmd-1')['status'])
        self.assertNotIn(job['id'], self.store.due())

    def test_queue_bound_and_confirmed_absent_readback(self):
        for index in range(8):
            self.commands.enqueue('owner', self.job['id'], self.payload('command-' + str(index)))
        with self.assertRaises(ValueError):
            self.commands.enqueue('owner', self.job['id'], self.payload('overflow'))
        absent = self.commands.read('owner', self.job['id'], 'missing')
        self.assertEqual('NOT_FOUND', absent['status'])
        self.assertTrue(absent['confirmed_absent'])

    def test_cancel_wins_without_promoting_uncommitted_result(self):
        self.commands.enqueue('owner', self.job['id'], self.payload())
        job, lease, _ = self.store.claim(self.job['id'])
        with patch('v14.engineering_workspace.act', return_value={'receipt_sha256': 'd' * 64}):
            result = self.commands.process(job)
        self.store.control(job['id'], 'owner', 'cancel', job['revision'])
        self.assertFalse(self.store.complete(job, lease, 'IMPLEMENT', result))
        receipt = self.commands.read('owner', job['id'], 'cmd-1')
        self.assertEqual('UNKNOWN', receipt['status'])
        self.assertIsNone(receipt['result_reference'])

    def test_board_rejects_concurrent_revision_movement(self):
        original = self.service.evidence_access
        count = 0

        def moving(principal, job_id):
            nonlocal count
            value = original(principal, job_id)
            count += 1
            if count > 1:
                value['revision'] += 1
            return value
        self.service.evidence_access = moving
        with patch('v14.workspace_board._environment', return_value={}):
            with self.assertRaises(ValueError):
                snapshot(self.service, 'owner', self.job['id'])


if __name__ == '__main__':
    unittest.main()
