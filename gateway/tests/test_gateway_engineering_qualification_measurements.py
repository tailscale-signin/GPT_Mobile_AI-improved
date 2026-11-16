"""Production-result grading must retain missing attempt measurements."""
from copy import deepcopy
from unittest.mock import Mock
from types import SimpleNamespace
import unittest

from benchmarks.engineering_qualification import CONTINUOUS_SCHEMA, CONTINUOUS_SCENARIOS, run_manifest, snapshot
from v14.batch import digest
from v14.engineering import record_verified_mutation_reconciliation


class EngineeringQualificationMeasurementsTest(unittest.TestCase):
    def result(self, attempts):
        job = {
            'id': 'eng_' + 'a' * 32,
            'state': 'COMPLETED',
            'request': {'repository': 'owner/repo', 'model': 'selected-model', 'source_sha': 'b' * 40},
            'data': {'completion_verification': {'passed': True, 'requirements': []},
                     'pr': 42, 'head_sha': 'c' * 40},
            'continuation_pr': 42,
            'repairs': {},
            'created_at': 1,
            'finished_at': 2,
        }
        # The storage boundary supplies bounded durable rows. This deliberately
        # tests the real evaluator without claiming model or GitHub execution.
        service = SimpleNamespace(
            evidence_access=lambda principal, job_id: deepcopy(job),
            store=SimpleNamespace(now=lambda: 2, tx=lambda read: (deepcopy(attempts), [])),
        )
        return snapshot(service, 'owner', job['id'], 'existing_pr')

    def continuous_result(self, scenario, history=(), *, steps=1001, sessions=4, binding='bound'):
        job_id = 'eng_' + 'a' * 32
        job = {
            'id': job_id, 'state': 'COMPLETED', 'steps': steps,
            'execution_session': {'number': sessions}, 'created_at': 0, 'finished_at': 1000,
            'request': {'repository': 'owner/repo', 'model': 'selected', 'execution_binding_id': binding},
            'data': {'completion_verification': {'passed': True, 'requirements': []}, 'retry_history': list(history)},
        }
        attempts = [{'dispatched': True, 'state': 'COMPLETED', 'usage': {'total_tokens': 7}}]
        service = SimpleNamespace(
            evidence_access=lambda owner, key: deepcopy(job),
            store=SimpleNamespace(now=lambda: 1000, tx=lambda read: (attempts, [])),
        )
        return snapshot(service, 'owner', job_id, scenario)

    def wait(self, revision, start, delay, *, resumed_after=None, restart=False):
        scheduled = {
            'job_hash': digest('eng_' + 'a' * 32), 'binding_hash': digest('bound'), 'stage': 'CI',
            'checkpoint_revision': revision, 'observed_at': start, 'retry_at': start + delay,
            'boot_hash': digest('first-boot'), 'outcome': 'SCHEDULED', 'http_status': 429,
        }
        resumed = dict(scheduled, outcome='RESUMED', observed_at=start + (delay if resumed_after is None else resumed_after))
        if restart:
            resumed['boot_hash'] = digest('second-boot')
        return [scheduled, resumed]

    def test_continuous_retry_qualification_requires_three_completed_server_waits(self):
        history = self.wait(3, 10, 30) + self.wait(5, 200, 60) + self.wait(7, 400, 120)
        self.assertEqual('PASSED', self.continuous_result('repeated_rate_limits', history)['status'])
        history[-1]['observed_at'] -= 1
        self.assertEqual('NOT_VERIFIED', self.continuous_result('repeated_rate_limits', history)['status'])

    def test_restart_qualification_reconciles_the_exact_binding_and_checkpoint(self):
        history = self.wait(3, 10, 30, restart=True)
        self.assertEqual('PASSED', self.continuous_result('restart_during_wait', history)['status'])
        self.assertEqual('NOT_VERIFIED', self.continuous_result('restart_during_wait', history, binding='different')['status'])
        history[-1]['checkpoint_revision'] += 1
        self.assertEqual('NOT_VERIFIED', self.continuous_result('restart_during_wait', history)['status'])
        self.assertEqual('NOT_VERIFIED', self.continuous_result('windows_restart_and_disconnect', history)['status'])

    def test_steps_require_both_cumulative_work_and_session_renewal(self):
        self.assertEqual('PASSED', self.continuous_result('one_thousand_steps')['status'])
        self.assertEqual('NOT_VERIFIED', self.continuous_result('one_thousand_steps', steps=999)['status'])
        self.assertEqual('NOT_VERIFIED', self.continuous_result('one_thousand_steps', sessions=1)['status'])

    def test_empty_continuous_manifest_preserves_all_unexecuted_cases_without_submission(self):
        client = Mock()
        manifest = {'schema': CONTINUOUS_SCHEMA, 'test_repository': 'owner/repo',
                    'cases': [{'scenario': case} for case in CONTINUOUS_SCENARIOS]}
        result = run_manifest(client, manifest)
        self.assertEqual(10, result['total'])
        self.assertEqual(0, result['passed'])
        self.assertTrue(all(row['status'] == 'NOT_EXECUTED' for row in result['cases']))
        client.submit_engineering.assert_not_called()
        client.request.assert_not_called()

    def test_lost_mutation_requires_a_matched_dispatched_reconciliation(self):
        job_id = 'eng_' + 'a' * 32
        operation_id = 'operation_' + 'b' * 40
        dispatched = {'dispatched': True, 'job_id': job_id, 'operation_id': operation_id,
                      'request_ref': {'sha256': 'c' * 64}}
        job = {'id': job_id, 'state': 'COMPLETED', 'created_at': 1, 'finished_at': 2,
               'request': {'repository': 'owner/repo', 'model': 'selected', 'execution_binding_id': 'binding'},
               'data': {'head_sha': 'a' * 40, 'completion_verification': {'passed': True,
                        'requirements': [{'id': 'lost_mutation', 'status': 'BEHAVIOR_VERIFIED'}]}},
               'repairs': {}}
        attempts = [{'dispatched': True, 'state': 'COMPLETED', 'usage': {'total_tokens': 7}}]
        def evaluate(ops):
            service = SimpleNamespace(evidence_access=lambda principal, key: deepcopy(job),
                store=SimpleNamespace(now=lambda: 2, tx=lambda read: (deepcopy(attempts), deepcopy(ops))))
            return snapshot(service, 'owner', job_id, 'lost_mutation')
        before = evaluate([dispatched])
        self.assertEqual('NOT_VERIFIED', before['status'])
        self.assertEqual(0, before['duplicate_writes'])
        self.assertFalse(before['lost_mutation_reconciliation_observed'])
        job['data']['latest_operation'] = dict(dispatched, state='UNKNOWN_OUTCOME', binding_id='binding')
        proof = record_verified_mutation_reconciliation(job, 'COMMIT', {'head_sha': 'd' * 40})
        self.assertIsNotNone(proof)
        self.assertEqual('UNKNOWN_OUTCOME', proof['original_state'])
        self.assertEqual('PASSED', evaluate([dispatched])['status'])
        self.assertEqual('NOT_VERIFIED', evaluate([])['status'])

    def test_host_reconciliation_refuses_prepared_or_unrelated_operations(self):
        job = {'id': 'eng_test', 'request': {'execution_binding_id': 'binding'},
               'data': {'head_sha': 'a' * 40, 'latest_operation': {
                   'job_id': 'eng_test', 'operation_id': 'operation_test', 'state': 'PREPARED',
                   'dispatched': False, 'binding_id': 'binding'}}}
        self.assertIsNone(record_verified_mutation_reconciliation(job, 'COMMIT', {'head_sha': 'b' * 40}))
        job['data']['latest_operation'].update(state='UNKNOWN_OUTCOME', dispatched=True)
        self.assertIsNone(record_verified_mutation_reconciliation(job, 'READ', {'head_sha': 'b' * 40}))
        self.assertIsNone(record_verified_mutation_reconciliation(job, 'COMMIT', {}))
        self.assertNotIn('mutation_reconciliations', job['data'])

    def test_completed_job_without_attempt_measurements_is_not_qualified(self):
        result = self.result([])
        self.assertEqual('NOT_VERIFIED', result['status'])
        self.assertIsNone(result['first_attempt_success'])
        self.assertIsNone(result['tokens'])
        self.assertEqual('NOT_EXECUTED', result['liveGitHub'])

    def test_truncated_attempt_history_cannot_claim_first_attempt_success(self):
        attempts = [{'dispatched': True, 'state': 'COMPLETED', 'usage': {'total_tokens': 1}}
                    for _ in range(512)]
        attempts.append({'dispatched': True, 'state': 'FAILED'})
        result = self.result(attempts)
        self.assertFalse(result['measurement_complete'])
        self.assertEqual('NOT_VERIFIED', result['status'])
        self.assertIsNone(result['first_attempt_success'])
        self.assertIsNone(result['tokens'])

    def test_complete_observed_attempt_preserves_verified_result(self):
        result = self.result([{'dispatched': True, 'state': 'COMPLETED', 'usage': {'total_tokens': 7}}])
        self.assertTrue(result['measurement_complete'])
        self.assertEqual('PASSED', result['status'])
        self.assertTrue(result['first_attempt_success'])
        self.assertEqual(7, result['tokens'])


if __name__ == '__main__':
    unittest.main()
