"""Production-result grading must retain missing attempt measurements."""
from copy import deepcopy
from types import SimpleNamespace
import unittest

from benchmarks.engineering_qualification import snapshot


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
