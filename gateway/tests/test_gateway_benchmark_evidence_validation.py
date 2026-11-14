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
    def test_malformed_evidence_filenames_preserve_measured_patch_results(self):
        task = fixtures.suite()[0]
        def execute(candidate, inputs, *_args, **_kwargs):
            self.assertEqual(task['referenceSources'], candidate)
            return {'executed': True, 'status': 'COMPLETED', 'outputs': [{'output': fixtures.expected(task, vector)} for vector in inputs]}
        for filename in (42, None, {}, True):
            with self.subTest(filename=filename):
                value = {'changes': [{'path': path, 'expected_blob_sha': blob_sha(source),
                                      'edits': [{'old': source, 'new': task['referenceSources'][path]}]}
                                     for path, source in task['sources'].items()],
                         'diagnosis': dict(task['sources']),
                         'tests': [{'input': vector, 'expected': fixtures.expected(task, vector)} for vector in task['vectors'][:6]],
                         'evidence': {'tests_executed': False, 'changed_files': ['core.py', filename]}}
                result = coding.grade(task, json.dumps(value), {'sandbox_image': 'image@sha256:' + 'a' * 64}, threading.Event(), execute=execute)
                self.assertEqual('PASSED', result['status'])
                self.assertTrue(result['executed'])
                self.assertTrue(result['graded'])
                self.assertTrue(result['resolved'])
                self.assertEqual(0, result['dimensions']['evidence'])
                for key in CODING_WEIGHTS.keys() - {'evidence'}:
                    self.assertEqual(1, result['dimensions'][key])
                self.assertEqual(1000 - CODING_WEIGHTS['evidence'], result['score'])


if __name__ == '__main__':
    unittest.main()
