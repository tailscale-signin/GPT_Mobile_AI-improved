from copy import deepcopy
from pathlib import Path
import sys
import threading
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_gateway_v14_engineering as fixtures
from v14.engineering_post_merge_recovery import enqueue, inspect
from v14.execution_binding import ExecutionBindingError


class PostMergeRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.DurableEngineeringTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.tearDown)
        self.service, self.store, self.backend = self.fixture.service, self.fixture.store, self.fixture.backend
        created = self.store.create('owner', self.fixture.request)
        job, lease, _ = self.store.claim(created['id'])
        self.failed = {'status': 'FAILED', 'reason': 'MERGE_WORKFLOW_FAILED', 'merge_sha': 'c' * 40,
                       'branch_head': 'd' * 40, 'workflows': [{'id': 7, 'conclusion': 'failure'}]}
        job['data'].update(merge_sha='c' * 40, post_merge=self.failed)
        self.store.complete(job, lease, 'VERIFY', {'merge_sha': 'c' * 40, 'post_merge': self.failed})
        self.job, self.lease, _ = self.store.claim(created['id'])
        self.backend.root = lambda job: '/repos/owner/repo'
        self.backend.api = Mock(return_value={'tree': {'sha': 'e' * 40}})
        self.backend.token = Mock(return_value='fixture')
        self.backend.cancellation = lambda job: threading.Event()
        self.service.validate_execution = Mock()
        self.service.preflight_submission = Mock(return_value={'ready': True, 'acceptance': self.fixture.request['acceptance']})

    def test_recovery_is_idempotent_retains_deadline_and_never_reuses_merge_approval(self):
        original = deepcopy(self.job['request'])
        with patch('v14.engineering_source.prepare_source'):
            child = enqueue(self.service, self.job, self.failed)
            same = enqueue(self.service, self.job, self.failed)
        self.assertEqual(child['id'], same['id'])
        self.assertEqual(self.job['id'], child['recovery_of'])
        self.assertEqual(self.job['deadline'], child['deadline'])
        self.assertIsNone(child['approval'])
        self.assertEqual('PLAN', child['state'])
        self.assertNotEqual(original['branch'], child['request']['branch'])
        self.assertEqual('d' * 40, child['request']['source_sha'])
        self.assertEqual(original, self.job['request'])
        self.assertEqual(self.job['execution_binding'], child['execution_binding'])

    def test_unqualified_child_or_unreachable_revision_cannot_pass_parent(self):
        with patch('v14.engineering_source.prepare_source'):
            child = enqueue(self.service, self.job, self.failed)
        self.job['data']['post_merge_recovery_job_id'] = child['id']
        self.assertEqual('PENDING', inspect(self.service, self.job)['status'])
        claimed, lease, _ = self.store.claim(child['id'])
        claimed['data'].update(merge_sha='f' * 40, post_merge={'status': 'PASSED'})
        self.store.complete(claimed, lease, 'COMPLETED', {'merge_sha': 'f' * 40, 'post_merge': {'status': 'PASSED'}})
        self.backend.ref = Mock(return_value='1' * 40)
        self.backend.api.return_value = {'status': 'diverged', 'merge_base_commit': {'sha': '2' * 40}}
        self.assertEqual('BLOCKED', inspect(self.service, self.job)['status'])
        self.backend.api.return_value = {'status': 'ahead', 'merge_base_commit': {'sha': 'f' * 40}}
        result = inspect(self.service, self.job)
        self.assertEqual('PASSED', result['status'])
        self.assertEqual('FAILED', result['original_status'])
        self.assertEqual('c' * 40, result['merge_sha'])
        self.assertEqual('f' * 40, result['recovery_merge_sha'])

    def test_child_link_and_budget_survive_loss_of_parent_checkpoint(self):
        with patch('v14.engineering_source.prepare_source'):
            child = enqueue(self.service, self.job, self.failed)
        self.store.recover()
        restarted, _, _ = self.store.claim(self.job['id'])
        self.assertEqual(child['id'], restarted['data']['post_merge_recovery_job_id'])
        self.assertEqual(child['request']['execution_budget'], restarted['data']['recovery_budget_reservation'])
        self.assertEqual(child['id'], enqueue(self.service, restarted, dict(self.failed, branch_head='9' * 40))['id'])
        self.assertEqual(2, len(self.store.list('owner')))

    def test_follow_up_replaces_paused_recovery_and_parent_qualifies_successor(self):
        with patch('v14.engineering_source.prepare_source'):
            child = enqueue(self.service, self.job, self.failed)
        self.store.complete(self.job, self.lease, 'VERIFY', {})
        claimed, lease, _ = self.store.claim(child['id'])
        self.store.record_model_attempt(claimed, {
            'attempt_id': 'initial-recovery-attempt', 'binding_id': child['request']['execution_binding_id'],
            'state': 'DISPATCHED', 'dispatched': True, 'reserved_tokens': 4096,
        })
        self.store.complete(claimed, lease, 'NEEDS_INPUT', {})
        conversation = self.store.conversations.snapshot('owner', child['conversation_id'])
        request = dict(child['request'], request_id='more-recovery-instructions',
                       conversation_id=child['conversation_id'], expected_revision=conversation['revision'],
                       objective='Also check whether the build repair preserves the original behavior.')
        request.pop('execution_budget')
        successor = self.store.create('owner', request)
        self.assertEqual(successor['id'], self.store.create('owner', request)['id'])
        self.assertEqual(child['id'], successor['continuation_of'])
        self.assertEqual(self.job['id'], successor['recovery_of'])
        self.assertEqual(child['recovery_depth'], successor['recovery_depth'])
        self.assertEqual(child['deadline'], successor['deadline'])
        for key, spent in [('model_calls', 1), ('model_tokens', 4096)]:
            self.assertEqual(child['request']['execution_budget'][key] - spent,
                             successor['request']['execution_budget'][key])
        self.assertEqual('CANCELLED', self.store.get(child['id'])['state'])
        parent = self.store.get(self.job['id'])
        self.assertEqual(successor['id'], parent['data']['post_merge_recovery_job_id'])
        self.assertEqual('PENDING', inspect(self.service, parent)['status'])
        claimed, lease, _ = self.store.claim(successor['id'])
        claimed['data'].update(merge_sha='f' * 40, post_merge={'status': 'PASSED'})
        self.store.complete(claimed, lease, 'COMPLETED', {})
        self.backend.ref = Mock(return_value='1' * 40)
        self.backend.api.return_value = {'status': 'ahead', 'merge_base_commit': {'sha': 'f' * 40}}
        self.service.completion_verification = Mock(return_value={'passed': True, 'failures': [], 'requirements': []})
        self.service.tick(parent['id'])
        concluded = self.store.get(parent['id'])
        self.assertEqual('COMPLETED', concluded['state'])
        self.assertEqual('f' * 40, concluded['data']['final_report']['qualified_revision'])

    def test_nested_recovery_compares_only_the_final_qualified_revision(self):
        self.job['data']['post_merge_recovery_job_id'] = 'child'
        request = self.job['request']
        child = {'id': 'child', 'recovery_of': self.job['id'], 'request': request, 'state': 'COMPLETED',
                 'data': {'merge_sha': 'a' * 40, 'post_merge': {'status': 'PASSED'},
                          'post_merge_recovery_job_id': 'grandchild'}}
        grandchild = {'id': 'grandchild', 'recovery_of': 'child', 'request': request, 'state': 'COMPLETED',
                      'data': {'merge_sha': 'b' * 40, 'post_merge': {'status': 'PASSED', 'qualified_revision': 'b' * 40}}}
        self.backend.ref = Mock(return_value='d' * 40)
        with patch.object(self.store, 'get', side_effect=lambda job_id, _: {'child': child, 'grandchild': grandchild}[job_id]):
            self.backend.api.return_value = {'status': 'ahead', 'merge_base_commit': {'sha': 'a' * 40}}
            self.assertEqual('BLOCKED', inspect(self.service, self.job)['status'])
            self.backend.api.return_value = {'status': 'ahead', 'merge_base_commit': {'sha': 'b' * 40}}
            self.assertEqual('b' * 40, inspect(self.service, self.job)['qualified_revision'])
        self.assertIn('/compare/' + 'b' * 40, self.backend.api.call_args.args[0])

    def test_six_hours_of_external_wait_preserve_productive_budget(self):
        self.job['steps'] = 256
        self.job['deadline'] = self.store.now() + 7 * 3600
        self.store.complete(self.job, self.lease, 'VERIFY', {})
        clock = self.store.now()
        self.store.now = lambda: clock
        self.service.completion_verification = Mock(return_value={'passed': True, 'failures': [], 'requirements': []})
        self.backend.step = Mock(return_value={'post_merge': {'status': 'PENDING'}})
        for hour in range(6):
            clock += 3600
            self.service.tick(self.job['id'])
        waiting = self.store.get(self.job['id'])
        self.assertEqual('VERIFY', waiting['state'])
        self.assertEqual(256, waiting['steps'])
        self.assertEqual(6, waiting['external_observations'])
        clock += 120
        self.backend.step.return_value = {'post_merge': {'status': 'PASSED', 'qualified_revision': 'c' * 40}}
        self.service.tick(self.job['id'])
        concluded = self.store.get(self.job['id'])
        self.assertEqual('COMPLETED', concluded['state'])
        self.assertEqual('c' * 40, concluded['data']['final_report']['qualified_revision'])

    def test_configuration_blocks_deadline_and_depth_do_not_spawn_more_jobs(self):
        self.assertIsNone(enqueue(self.service, self.job, dict(self.failed, status='BLOCKED')))
        self.job['recovery_depth'] = self.job['request']['repair_attempts']
        self.assertIsNone(enqueue(self.service, self.job, self.failed))
        self.job['recovery_depth'] = 0
        self.job['deadline'] = self.store.now() + 10
        self.assertIsNone(enqueue(self.service, self.job, self.failed))
        self.backend.api.assert_not_called()

    def test_parent_cancellation_cancels_queued_recovery_and_fences_new_admission(self):
        with patch('v14.engineering_source.prepare_source'):
            child = enqueue(self.service, self.job, self.failed)
        self.store.control(self.job['id'], 'owner', 'cancel', self.job['revision'])
        self.assertEqual('CANCELLED', self.store.get(child['id'])['state'])
        self.assertIsNone(self.store.get(child['id'])['approval'])
        with self.assertRaises(ExecutionBindingError):
            self.store.create('owner', dict(child['request'], request_id='cancel-race'),
                              admission={'ready': True, 'acceptance': child['request']['acceptance']}, recovery_of=self.job)


if __name__ == '__main__':
    unittest.main()
