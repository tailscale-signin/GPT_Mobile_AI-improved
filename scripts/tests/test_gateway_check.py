import contextlib
import io
import pathlib
import subprocess
import unittest
from unittest import mock

from gateway import check_gateway


class GatewayCheckTest(unittest.TestCase):
    def test_timeout_preserves_partial_diagnostics_and_is_a_failure(self):
        for output in ('test_waiting ...\nThread blocked', b'test_waiting ...\nThread blocked\xff'):
            with self.subTest(output=output), mock.patch.object(check_gateway.subprocess, 'run', side_effect=subprocess.TimeoutExpired('suite', 120, output=output)):
                captured = io.StringIO()
                with contextlib.redirect_stdout(captured):
                    passed = check_gateway.run_suite(pathlib.Path('gateway'), 'test_gateway_v14*.py')
                self.assertFalse(passed)
                self.assertIn('FAILED (suite exceeded 120s)', captured.getvalue())
                self.assertIn('test_waiting ...\nThread blocked', captured.getvalue())

    def test_a_test_failure_remains_a_failure_with_its_output(self):
        result = subprocess.CompletedProcess('suite', 1, stdout='FAIL: test_regression\nRan 1 test\nFAILED (failures=1)\n')
        with mock.patch.object(check_gateway.subprocess, 'run', return_value=result), contextlib.redirect_stdout(io.StringIO()) as captured:
            self.assertFalse(check_gateway.run_suite(pathlib.Path('gateway'), 'test_gateway_v14*.py'))
        self.assertIn('FAIL: test_regression', captured.getvalue())
