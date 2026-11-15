"""Candidate metadata must not erase independently measured benchmark results."""
import json
from pathlib import Path
import sys
import threading
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from benchmarks import coding, fixtures
from benchmarks.contracts import CODING_WEIGHTS
from v14.engineering_backend import blob_sha


class CodingEvidenceValidationTest(unittest.TestCase):
    def _proposal(self, task):
        return json.dumps({'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                       'edits': [{'old': source, 'new': task['referenceSources'][path]}]}
                                      for path, source in task['sources'].items()],
                           'diagnosis': dict(task['sources']),
                           'tests': [{'input': vector, 'expected': fixtures.expected(task, vector)} for vector in task['vectors'][:6]],
                           'evidence': {'tests_executed': False, 'changed_files': list(task['sources'])}})

    def test_candidate_preservation_flags_do_not_change_host_grading(self):
        task = fixtures.suite()[0]
        variants = [{}, {'input_preserved': True}, {'input_preserved': False},
                    {'input_preserved': None}, {'input_preserved': 'true'}, {'input_preserved': 1}]
        results = []
        for metadata in variants:
            with self.subTest(metadata=metadata):
                def execute(candidate, inputs, *_args, **_kwargs):
                    self.assertEqual(task['referenceSources'], candidate)
                    return {'executed': True, 'status': 'COMPLETED', 'outputs': [
                        dict({'output': fixtures.expected(task, vector)}, **metadata) for vector in inputs]}
                result = coding.grade(task, self._proposal(task), {'sandbox_image': 'image@sha256:' + 'a' * 64}, threading.Event(), execute=execute)
                self.assertEqual('NOT_VERIFIED', result['inputMutationVerification'])
                self.assertEqual('BLOCKED', result['status'])
                self.assertFalse(result['resolved'])
                self.assertTrue(result['outputCorrect'])
                self.assertFalse(result['qualified'])
                self.assertEqual('PASSED', result['patchScopeVerification'])
                self.assertIsNone(result['dimensions']['safety'])
                self.assertIsNone(result['score'])
                self.assertEqual(950, result['earned'])
                self.assertEqual(50, result['unknownWeight'])
                results.append(result)
        self.assertTrue(all(result == results[0] for result in results))

    def test_forged_preservation_true_cannot_resolve_incorrect_outputs(self):
        task = fixtures.suite()[0]
        def execute(candidate, inputs, *_args, **_kwargs):
            return {'executed': True, 'status': 'COMPLETED', 'outputs': [
                {'output': {'unexpected-benchmark-output': True}, 'input_preserved': True} for _ in inputs]}
        result = coding.grade(task, self._proposal(task), {'sandbox_image': 'image@sha256:' + 'a' * 64}, threading.Event(), execute=execute)
        self.assertFalse(result['resolved'])
        self.assertEqual('FAILED', result['status'])
        self.assertEqual('NOT_VERIFIED', result['inputMutationVerification'])
        self.assertEqual(0.0, result['dimensions']['functional'])
        self.assertEqual(0.0, result['dimensions']['recovery'])
        self.assertIsNone(result['score'])
        self.assertFalse(result['outputCorrect'])
        self.assertFalse(result['qualified'])
        self.assertEqual('PASSED', result['patchScopeVerification'])
        self.assertIsNone(result['dimensions']['safety'])
        self.assertEqual(50, result['unknownWeight'])

    def test_malformed_evidence_filenames_preserve_measured_patch_results(self):
        task = fixtures.suite()[0]
        def execute(candidate, inputs, *_args, **_kwargs):
            self.assertEqual(task['referenceSources'], candidate)
            return {'executed': True, 'status': 'COMPLETED', 'outputs': [{'output': fixtures.expected(task, vector), 'input_preserved': True} for vector in inputs]}
        for filename in (42, None, {}, True):
            with self.subTest(filename=filename):
                value = {'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                      'edits': [{'old': source, 'new': task['referenceSources'][path]}]}
                                     for path, source in task['sources'].items()],
                         'diagnosis': dict(task['sources']),
                         'tests': [{'input': vector, 'expected': fixtures.expected(task, vector)} for vector in task['vectors'][:6]],
                         'evidence': {'tests_executed': False, 'changed_files': ['core.py', filename]}}
                result = coding.grade(task, json.dumps(value), {'sandbox_image': 'image@sha256:' + 'a' * 64}, threading.Event(), execute=execute)
                self.assertEqual('BLOCKED', result['status'])
                self.assertTrue(result['executed'])
                self.assertTrue(result['graded'])
                self.assertFalse(result['resolved'])
                self.assertTrue(result['outputCorrect'])
                self.assertFalse(result['qualified'])
                self.assertEqual(0, result['dimensions']['evidence'])
                for key in CODING_WEIGHTS.keys() - {'evidence', 'safety'}:
                    self.assertEqual(1, result['dimensions'][key])
                self.assertIsNone(result['dimensions']['safety'])
                self.assertIsNone(result['score'])
                self.assertEqual(1000 - CODING_WEIGHTS['evidence'] - CODING_WEIGHTS['safety'], result['earned'])
                self.assertEqual(50, result['unknownWeight'])


if __name__ == '__main__':
    unittest.main()
