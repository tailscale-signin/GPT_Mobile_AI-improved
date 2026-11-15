from copy import deepcopy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock, patch

sys.path.insert(0, str(Path(__file__).resolve().parent))
import test_gateway_post_merge_recovery as fixtures
from v14.engineering_post_merge_recovery import enqueue, inspect
from v14.execution_binding import ExecutionBindingError


class RecoveryDurabilityTests(unittest.TestCase):
    def setUp(self):
        self.fixture = fixtures.PostMergeRecoveryTests()
        self.fixture.setUp()
        self.addCleanup(self.fixture.doCleanups)
        self.service, self.store, self.backend = self.fixture.service, self.fixture.store, self.fixture.backend
        self.job, self.failed = self.fixture.job, self.fixture.failed

    def enqueue(self, job=None, failed=None):
        parent = job or self.job
        with patch('v14.engineering_source.prepare_source'):
            child = enqueue(self.service, parent, failed or self.failed)
        # Match the service caller after a successful admission result.
        if child is not None:
            parent['data']['post_merge_recovery_job_id'] = child['id']
        return child

    def test_lost_parent_checkpoint_and_changed_main_reconcile_one_child_before_new_admission(self):
        stale = deepcopy(self.job)
        first = self.enqueue()
        calls = self.backend.api.call_count
        self.backend.api.side_effect = AssertionError('Retained recovery must not prepare another source')
        self.service.validate_execution.side_effect = AssertionError('Retained recovery must not select a new model')
        self.service.preflight_submission.side_effect = AssertionError('Retained recovery must not repeat admission')
        stale['deadline'] = self.store.now() - 1
        second = self.enqueue(stale, dict(self.failed, branch_head='e' * 40))
        self.assertIsNotNone(second, 'A retained child must be reconciled before new-admission deadline checks')
        self.assertEqual(first['id'], second['id'])
        self.assertEqual(first['request']['execution_budget'], second['request']['execution_budget'])
        self.assertEqual('d' * 40, second['request']['source_sha'])
        self.assertEqual(calls, self.backend.api.call_count)
        children = self.store.tx(lambda db: db.execute(
            "SELECT COUNT(*) FROM engineering_jobs WHERE json_extract(data, '$.recovery_of')=?", (self.job['id'],)).fetchone()[0])
        self.assertEqual(1, children)
        self.assertIsNone(second['approval'])

    def test_child_failure_binding_survives_stale_parent_completion_and_restart(self):
        stale = deepcopy(self.job)
        prior = self.store.get(self.job['id'])['data']['post_merge']
        # The actual failure can be newer than the last parent checkpoint.
        self.job['data']['post_merge'] = dict(self.failed, observed_at=999, workflows=[{'id': 91, 'conclusion': 'failure'}])
        first = self.enqueue(failed=self.job['data']['post_merge'])
        stored = self.store.get(self.job['id'])
        self.assertEqual(first['id'], stored['data']['post_merge_recovery_job_id'])
        self.assertEqual(91, stored['data']['post_merge_recovery_origin']['workflows'][0]['id'])
        self.assertEqual(prior, stale['data']['post_merge'])
        self.assertTrue(self.store.complete(stale, self.fixture.lease, 'VERIFY', {'post_merge': prior}))
        self.store.recover()
        resumed, _, _ = self.store.claim(self.job['id'])
        self.assertEqual(first['id'], resumed['data']['post_merge_recovery_job_id'])
        self.assertEqual(91, resumed['data']['post_merge_recovery_origin']['workflows'][0]['id'])
        self.assertEqual(first['id'], self.enqueue(resumed, dict(self.failed, branch_head='e' * 40))['id'])
        observed = inspect(self.service, resumed)
        self.assertEqual('PENDING', observed['status'])
        self.assertEqual(91, observed['workflows'][0]['id'])

    def test_ambiguous_legacy_children_block_instead_of_reallocating_budget(self):
        first = self.enqueue()
        other_request = dict(self.job['request'], request_id='legacy-second-child', branch='codex/legacy-second-child')
        other = self.store.create(self.job['principal'], other_request)
        other.update(recovery_of=self.job['id'], recovery_depth=1)
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(other), other['id'])))
        with self.assertRaises(ExecutionBindingError) as caught:
            self.enqueue(failed=dict(self.failed, branch_head='e' * 40))
        self.assertEqual('POST_MERGE_RECOVERY_AMBIGUOUS', caught.exception.code)
        self.assertNotEqual(first['id'], other['id'])
        self.assertEqual('PLAN', self.store.get(first['id'])['state'])
        self.assertEqual('PLAN', self.store.get(other['id'])['state'])

    def test_later_child_ci_cannot_replace_the_durable_original_failure(self):
        stale = deepcopy(self.job)
        first = self.enqueue()
        expected = deepcopy(self.failed)
        child = self.store.get(first['id'])
        child['data']['ci'] = {'checks_failed': True, 'failure_fingerprint': 'later-child-ci', 'workflows': [{'id': 99}]}
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(child), child['id'])))
        self.assertEqual(first['id'], self.enqueue(stale)['id'])
        self.assertEqual(expected, stale['data']['post_merge_recovery_origin'])
        stale['data']['post_merge_recovery_origin'] = deepcopy(child['data']['ci'])
        self.assertTrue(self.store.complete(stale, self.fixture.lease, 'VERIFY', {}))
        retained = self.store.get(self.job['id'])
        self.assertEqual(expected, retained['data']['post_merge_recovery_origin'])
        self.assertEqual(expected['workflows'], inspect(self.service, retained)['workflows'])

    def test_legacy_origin_missing_remains_unavailable_after_child_ci_changes(self):
        first = self.enqueue()
        parent = self.store.get(self.job['id'])
        parent['data'].pop('post_merge_recovery_origin')
        child = self.store.get(first['id'])
        child['data']['ci'] = {'status': 'FAILED', 'reason': 'CHILD_CI_FAILED', 'workflows': [{'id': 99}]}
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(parent), parent['id'])))
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(child), child['id'])))
        stale = deepcopy(self.job)
        stale['data']['post_merge_recovery_origin'] = deepcopy(child['data']['ci'])
        self.assertEqual(first['id'], self.enqueue(stale)['id'])
        self.assertNotIn('post_merge_recovery_origin', stale['data'])
        result = inspect(self.service, stale)
        self.assertEqual('UNAVAILABLE', result['origin_evidence'])
        self.assertEqual('c' * 40, result['merge_sha'])
        self.assertNotIn('workflows', result)
        stale['data']['post_merge_recovery_origin'] = deepcopy(child['data']['ci'])
        self.assertTrue(self.store.complete(stale, self.fixture.lease, 'VERIFY', {}))
        self.assertNotIn('post_merge_recovery_origin', self.store.get(self.job['id'])['data'])

    def test_recovery_request_collision_cannot_adopt_an_ordinary_job(self):
        with self.assertRaises(ExecutionBindingError) as caught:
            self.store.create(self.job['principal'], dict(self.job['request']),
                              admission={'ready': True, 'acceptance': self.job['request']['acceptance']}, recovery_of=self.job)
        self.assertEqual('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', caught.exception.code)
        self.assertNotIn('post_merge_recovery_job_id', self.store.get(self.job['id'])['data'])

    def test_nested_recovery_qualifies_only_the_final_reachable_revision(self):
        first = self.enqueue()
        child, lease, _ = self.store.claim(first['id'])
        failure = dict(self.failed, merge_sha='f' * 40, branch_head='1' * 40)
        child['data'].update(merge_sha='f' * 40, post_merge=failure)
        self.store.complete(child, lease, 'VERIFY', {'post_merge': failure})
        child, lease, _ = self.store.claim(first['id'])
        second = self.enqueue(child, failure)
        grandchild, grand_lease, _ = self.store.claim(second['id'])
        passed = {'status': 'PASSED', 'merge_sha': '2' * 40, 'reason': 'MERGE_REVISION_WORKFLOWS_VERIFIED'}
        grandchild['data'].update(merge_sha='2' * 40, post_merge=passed)
        self.store.complete(grandchild, grand_lease, 'COMPLETED', {'post_merge': passed})
        self.backend.ref = Mock(return_value='2' * 40)

        def compare(path, **_):
            source = path.split('/compare/', 1)[1].split('...', 1)[0]
            head = self.backend.ref.return_value
            status = 'identical' if source == head else ('ahead' if head == '2' * 40 else 'behind')
            return {'status': status, 'merge_base_commit': {'sha': source if status != 'behind' else head}}

        self.backend.api = Mock(side_effect=compare)
        child['data']['post_merge'] = inspect(self.service, child)
        self.assertEqual('2' * 40, child['data']['post_merge']['recovery_merge_sha'])
        self.store.complete(child, lease, 'COMPLETED', {'post_merge': child['data']['post_merge']})
        qualified = inspect(self.service, self.job)
        self.assertEqual('PASSED', qualified['status'])
        self.assertEqual('c' * 40, qualified['merge_sha'])
        self.assertEqual('FAILED', qualified['original_status'])
        self.assertEqual('2' * 40, qualified['recovery_merge_sha'])
        self.backend.ref.return_value = 'f' * 40
        unavailable = inspect(self.service, self.job)
        self.assertEqual('BLOCKED', unavailable['status'])
        self.assertEqual('MERGE_RECOVERY_REVISION_NOT_REACHABLE', unavailable['reason'])
        self.assertIn('/compare/' + '2' * 40 + '...' + 'f' * 40, self.backend.api.call_args.args[0])

    def test_passed_receipt_must_bind_its_actual_merge_revision(self):
        first = self.enqueue()
        child, lease, _ = self.store.claim(first['id'])
        child['data'].update(merge_sha='f' * 40, post_merge={'status': 'PASSED', 'merge_sha': 'e' * 40})
        self.store.complete(child, lease, 'COMPLETED', {})
        self.backend.api.reset_mock()
        self.backend.ref = Mock(return_value='f' * 40)
        result = inspect(self.service, self.job)
        self.assertEqual('BLOCKED', result['status'])
        self.assertEqual('MERGE_RECOVERY_IDENTITY_UNAVAILABLE', result['reason'])
        self.backend.api.assert_not_called()

    def test_retained_child_cannot_change_repository_base_or_execution_authority(self):
        first = self.enqueue()
        original = self.store.get(first['id'])
        for key, value in [('repository', 'other/repository'), ('base', 'other-main'), ('execution_binding_id', 'another-binding')]:
            with self.subTest(key=key):
                changed = deepcopy(original)
                changed['request'][key] = value
                self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(changed), first['id'])))
                result = inspect(self.service, self.job)
                self.assertEqual('BLOCKED', result['status'])
                self.assertEqual('POST_MERGE_RECOVERY_IDENTITY_UNAVAILABLE', result['reason'])
        self.store.tx(lambda db: db.execute('UPDATE engineering_jobs SET data=? WHERE id=?', (json.dumps(original), first['id'])))


if __name__ == '__main__':
    unittest.main()
