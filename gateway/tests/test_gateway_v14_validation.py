import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from v14.engineering_validation import validate, patch_identity, run_bounded


class IsolatedValidationTests(unittest.TestCase):
    def test_missing_policy_is_blocked_not_tested(self):
        with patch.dict(os.environ, {'GATEWAY_ENGINEERING_VALIDATION_POLICY': ''}):
            receipt = validate({'request': {'repository': 'owner/repo'}, 'data': {'head_sha': 'a' * 40}}, [])
        self.assertEqual('BLOCKED', receipt['status'])
        self.assertEqual(patch_identity('a' * 40, []), receipt['patch_identity'])

    def test_revision_and_patch_each_invalidate_validation(self):
        changes = [{'path': 'file.kt', 'content': 'before'}]
        self.assertNotEqual(patch_identity('a' * 40, changes), patch_identity('b' * 40, changes))
        self.assertNotEqual(patch_identity('a' * 40, changes), patch_identity('a' * 40, [{'path': 'file.kt', 'content': 'after'}]))

    def test_command_output_and_runtime_are_bounded(self):
        with tempfile.TemporaryDirectory() as directory:
            receipt = run_bounded([sys.executable, '-c', 'print("tested")'], directory, 5)
            self.assertEqual(0, receipt['exit_code'])
            self.assertIn('tested', receipt['output_tail'])
            with self.assertRaises(ValueError):
                run_bounded([sys.executable, '-c', 'print("x" * 9000)'], directory, 5, maximum=100)
            with self.assertRaises(TimeoutError):
                run_bounded([sys.executable, '-c', 'import time; time.sleep(10)'], directory, .1)
