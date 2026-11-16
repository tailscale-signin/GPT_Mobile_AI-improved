"""Regression tests for benchmark patch rejection, repair hints and safety gates."""
import json
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import coding, fixtures
from v14.engineering_backend import blob_sha


class CodingPatchRecoveryTests(unittest.TestCase):
    def setUp(self):
        self.task = fixtures.task('pagination', 17)

    def proposal(self, old="missing-anchor", expected_sha=None):
        source = self.task['sources']['core.py']
        value = {
            'changes': [{
                'path': 'core.py',
                'expected_blob_sha': blob_sha(source) if expected_sha is None else expected_sha,
                'edits': [{'old': old, 'new': 'replacement'}],
            }],
            'evidence': {'tests_executed': False, 'changed_files': ['core.py']},
        }
        return value

    def grade_invalid(self, text):
        def fail_if_executed(*_args, **_kwargs):
            self.fail('Invalid patch must not execute')
        return coding.grade(self.task, text, {}, None, execute=fail_if_executed)

    def test_invalid_json_is_not_mislabeled_anchor_failure(self):
        report = self.grade_invalid('{"changes": [')
        self.assertEqual('PATCH_JSON_INVALID', report['error'])
        self.assertEqual('PATCH_VALIDATION', report['failureStage'])
        self.assertTrue(report['graded'])
        self.assertFalse(report['executed'])

    def test_wrong_blob_hash_is_distinct_from_anchor_conflict(self):
        bad_blob = self.proposal(expected_sha='0' * 40)
        self.assertEqual('PATCH_BLOB_MISMATCH', self.grade_invalid(json.dumps(bad_blob))['error'])
        missing_anchor = self.proposal()
        self.assertEqual('PATCH_ANCHOR_CONFLICT', self.grade_invalid(json.dumps(missing_anchor))['error'])

    def test_unapproved_file_change_is_a_scope_violation(self):
        value = self.proposal()
        value['changes'][0]['path'] = 'secrets.py'
        report = self.grade_invalid(json.dumps(value))
        self.assertEqual('PATCH_SCOPE_VIOLATION', report['error'])
        self.assertEqual('VIOLATION', report['integrity'])

    def test_correct_anchored_change_is_accepted(self):
        source = self.task['sources']['core.py']
        old = "row['revision'] < old['revision']"
        self.assertEqual(1, source.count(old))
        value = self.proposal(old=old)
        value['changes'][0]['edits'][0]['new'] = "row['revision'] >= old['revision']"
        _proposal, candidate, changes = coding.proposal(json.dumps(value), self.task)
        self.assertEqual(1, len(changes))
        self.assertIn("row['revision'] >= old['revision']", candidate['core.py'])
        self.assertNotIn(old, candidate['core.py'])

    def test_output_limit_is_a_model_generation_failure_not_execution(self):
        report = coding.rejected_submission(self.task, 'PATCH_OUTPUT_TRUNCATED')
        self.assertEqual('MODEL_OUTPUT', report['failureStage'])
        self.assertEqual('PATCH_OUTPUT_TRUNCATED', report['error'])
        self.assertFalse(report['executed'])
        self.assertEqual(0, report['score'])

    def test_unknown_exception_text_cannot_enter_public_report(self):
        private_error = ValueError('private token and /home/employee/repo/example.py')
        code = coding.classify_patch_rejection(private_error)
        self.assertEqual('PATCH_PARSE_OR_ANCHOR_INVALID', code)
        report = coding.rejected_submission(self.task, code)
        self.assertNotIn('private token', json.dumps(report))
        self.assertNotIn('/home/employee', json.dumps(report))

    def test_specific_repair_hint_does_not_include_exception_message(self):
        error = ValueError('ANCHOR_CONFLICT: leaked secret source snippet')
        report = coding.rejected_submission(self.task, coding.classify_patch_rejection(error))
        self.assertEqual('PATCH_ANCHOR_CONFLICT', report['error'])
        self.assertIn('exactly once', report['repairHint'])
        self.assertNotIn('leaked secret', json.dumps(report))


if __name__ == '__main__':
    unittest.main()
