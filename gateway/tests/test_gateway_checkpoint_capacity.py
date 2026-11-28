"""Storage refusals retain exact work identity without repeatedly executing a stage."""
import copy
import unittest
from unittest.mock import patch

import test_gateway_v14_engineering as fixtures
from v14 import engineering_artifacts
from v14.execution_binding import ExecutionBindingError


class CheckpointCapacityTests(unittest.TestCase):
    setUp = fixtures.DurableEngineeringTests.setUp
    tearDown = fixtures.DurableEngineeringTests.tearDown

    def used_bytes(self, job):
        return self.store.tx(lambda db: db.execute(
            'SELECT COALESCE(SUM(size),0) FROM engineering_artifacts WHERE job_id=?',
            (job['id'],)).fetchone()[0])

    def test_identical_evidence_can_be_repinned_at_full_quota(self):
        created = self.store.create('owner', self.request)
        job = self.store.claim(created['id'])[0]
        value = {'fixture': 'already retained evidence'}
        reference = self.service.artifacts.put_json(job, value, 'first-pin')
        used = self.used_bytes(job)
        with patch.object(engineering_artifacts, 'MAX_JOB_ARTIFACT_BYTES', used):
            self.assertEqual(reference, self.service.artifacts.put_json(job, value, 'second-pin'))
            self.assertEqual(used, self.used_bytes(job))
            with self.assertRaises(ExecutionBindingError) as blocked:
                self.service.artifacts.put_json(job, {'fixture': 'new evidence'}, 'new-result')
        self.assertEqual('EXECUTION_ARTIFACT_CAPACITY', blocked.exception.code)
        self.assertEqual(value, self.service.artifacts.json_value(job, reference))
        self.assertEqual(2, self.store.tx(lambda db: db.execute(
            'SELECT COUNT(*) FROM engineering_artifact_pins WHERE artifact_id=?',
            (reference['result_ref'],)).fetchone()[0]))
        self.assertEqual(used, self.used_bytes(job))

    def test_checkpoint_capacity_pauses_once_and_survives_lease_recovery(self):
        created = self.store.create('owner', self.request)
        self.service.tick(created['id'])
        prior = self.store.get(created['id'])
        reference = self.service.artifacts.put_json(prior, {'fixture': 'prior evidence'}, 'retained')
        calls = []

        def read(job, phase):
            calls.append(phase)
            return {'files': {'source.py': {'content': 'x' * (128 * 1024 + 1)}}}

        self.backend.step = read
        with patch.object(engineering_artifacts, 'MAX_JOB_ARTIFACT_BYTES', self.used_bytes(prior)):
            self.service.tick(created['id'])
            for _ in range(3):
                self.store.recover()
                self.service.tick(created['id'])
        saved = self.store.get(created['id'])
        self.assertEqual(['READ'], calls)
        self.assertEqual('NEEDS_INPUT', saved['state'])
        self.assertEqual(prior['revision'] + 1, saved['revision'])
        self.assertEqual(prior['request'], saved['request'])
        self.assertNotIn(created['id'], self.store.due())
        reason = saved['data']['blocked_reason']
        self.assertEqual('EXECUTION_ARTIFACT_CAPACITY', reason['code'])
        self.assertFalse(reason['retryable'])
        self.assertFalse(reason['requires_reconciliation'])
        self.assertFalse(reason['stage_result_retained'])
        self.assertEqual('READ', reason['failed_stage'])
        self.assertIn('storage capacity', saved['message'])
        report = saved['data']['final_report']
        self.assertEqual('PARTIAL_UNVERIFIED', report['status'])
        self.assertFalse(report['terminal_receipt']['acceptance_verified'])
        self.assertEqual(1, report['terminal_receipt']['remaining_criteria'])
        self.assertEqual(saved['revision'], report['terminal_receipt']['checkpoint_revision'])
        self.assertEqual('UNAVAILABLE', report['artifact_availability'])
        self.assertEqual('EXECUTION_ARTIFACT_CAPACITY', report['artifact_error'])
        self.assertEqual({'fixture': 'prior evidence'}, self.service.artifacts.json_value(saved, reference))

    def assert_mutation_receipt_survives_capacity(self, outcome):
        created = self.store.create('owner', self.request)
        for _ in range(4):
            self.service.tick(created['id'])
        prior = self.store.get(created['id'])
        self.assertEqual('COMMIT', prior['state'])
        self.backend.physical_operation_journal = True
        dispatched = []

        def write(job, phase):
            self.assertEqual('COMMIT', phase)
            job['data']['execution_integrity'] = 'VERIFIED'
            operation = self.service.operations.prepare(
                job, 'PATCH', '/repos/owner/repo/git/refs/heads/feature/job', {'sha': 'c' * 40})
            self.service.operations.dispatch(job, operation)
            if outcome == 'COMPLETED':
                self.service.operations.finish(job, operation, {'object': {'sha': 'c' * 40}}, 200)
            else:
                self.service.operations.failed(job, operation, TimeoutError('fixture response lost'))
            dispatched.append(copy.deepcopy(operation))
            engineering_artifacts.MAX_JOB_ARTIFACT_BYTES = self.used_bytes(job)
            job['data']['files'] = {'source.py': {'content': 'x' * (128 * 1024 + 1)}}
            if outcome == 'UNKNOWN_OUTCOME':
                raise TimeoutError('fixture response lost')
            return {'head_sha': 'c' * 40}

        self.backend.step = write
        with patch.object(engineering_artifacts, 'MAX_JOB_ARTIFACT_BYTES', 1024 * 1024):
            self.service.tick(created['id'])
            for _ in range(3):
                self.store.recover()
                self.service.tick(created['id'])
        saved = self.store.get(created['id'])
        self.assertEqual(1, len(dispatched))
        self.assertEqual('NEEDS_INPUT', saved['state'])
        self.assertEqual(prior['request'], saved['request'])
        self.assertEqual(prior['data']['head_sha'], saved['data']['head_sha'])
        self.assertEqual(dispatched[0], saved['data']['latest_operation'])
        self.assertEqual(dispatched, self.service.operations.page(saved)['operations'])
        self.assertTrue(saved['data']['blocked_reason']['requires_reconciliation'])
        self.assertIn('Reconcile the original operation outcomes', saved['message'])
        self.assertFalse(saved['data']['final_report']['terminal_receipt']['acceptance_verified'])
        self.assertNotIn(created['id'], self.store.due())

    def test_unknown_write_is_not_replayed_or_reclassified_after_checkpoint_refusal(self):
        self.assert_mutation_receipt_survives_capacity('UNKNOWN_OUTCOME')

    def test_confirmed_write_receipt_is_not_lost_or_replayed_after_checkpoint_refusal(self):
        self.assert_mutation_receipt_survives_capacity('COMPLETED')

    def test_capacity_pause_cannot_resurrect_a_cancelled_owner(self):
        created = self.store.create('owner', self.request)
        job, lease, _ = self.store.claim(created['id'])

        def refuse(*args, **kwargs):
            self.store.control(job['id'], 'owner', 'cancel', job['revision'])
            raise ExecutionBindingError('EXECUTION_ARTIFACT_CAPACITY', 'fixture capacity', 507)

        with patch.object(engineering_artifacts.EngineeringArtifacts, 'put_json', side_effect=refuse):
            self.assertFalse(self.store.complete(job, lease, 'READ', {'files': {'source': 'x' * (128 * 1024 + 1)}}))
        self.assertEqual('CANCELLED', self.store.get(job['id'])['state'])
        self.assertNotIn(job['id'], self.store.due())


if __name__ == '__main__':
    unittest.main()
