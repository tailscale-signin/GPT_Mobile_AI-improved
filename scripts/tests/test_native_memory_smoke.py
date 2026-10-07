import importlib.util
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("native_smoke", Path(__file__).resolve().parents[1] / "check_native_memory_smoke.py")
smoke = importlib.util.module_from_spec(spec)
spec.loader.exec_module(smoke)


class NativeMemorySmokeReportTest(unittest.TestCase):
    def check_xml(self, content):
        with tempfile.TemporaryDirectory() as directory:
            Path(directory, "TEST-emulator.xml").write_text(content)
            smoke.check_reports(directory)

    def test_zero_tests_or_other_tests_cannot_pass(self):
        for content in ['<testsuite tests="0"/>', '<testsuite><testcase classname="Other" name="test"/></testsuite>']:
            with self.subTest(content=content), self.assertRaisesRegex(ValueError, "did not execute"):
                self.check_xml(content)

    def test_missing_report_cannot_pass(self):
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, "no JUnit XML"):
            smoke.check_reports(directory)

    def test_actual_encoder_case_passes_and_failures_skips_do_not(self):
        for outcome in ["", "<failure/>", "<error/>", "<skipped/>"]:
            content = f'<testsuite><testcase classname="{smoke.TEST_CLASS}" name="{smoke.TEST_METHOD}">{outcome}</testcase></testsuite>'
            if outcome:
                with self.subTest(outcome=outcome), self.assertRaises(ValueError):
                    self.check_xml(content)
            else:
                self.check_xml(content)


class NativeMemoryInstrumentationTest(unittest.TestCase):
    START = f"""INSTRUMENTATION_STATUS: class={smoke.TEST_CLASS}
INSTRUMENTATION_STATUS: current=1
INSTRUMENTATION_STATUS: id=AndroidJUnitRunner
INSTRUMENTATION_STATUS: numtests=1
INSTRUMENTATION_STATUS: test={smoke.TEST_METHOD}
INSTRUMENTATION_STATUS_CODE: 1
"""
    SUCCESS = f"""INSTRUMENTATION_STATUS: class={smoke.TEST_CLASS}
INSTRUMENTATION_STATUS: test={smoke.TEST_METHOD}
INSTRUMENTATION_STATUS_CODE: 0
"""
    FINISH = "INSTRUMENTATION_RESULT: stream=\nOK (1 test)\nINSTRUMENTATION_CODE: -1\n"

    def check_output(self, content):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory, "instrumentation.txt")
            path.write_text(content)
            smoke.check_instrumentation(path)

    def test_real_test_protocol_passes_with_unix_or_adb_line_endings(self):
        output = self.START + self.SUCCESS + self.FINISH
        self.check_output(output)
        self.check_output(output.replace("\n", "\r\n"))

    def test_zero_tests_wrong_test_and_incomplete_crash_cannot_pass(self):
        for output in [self.FINISH, self.START, self.START + self.FINISH, self.START + self.SUCCESS, (self.START + self.SUCCESS + self.FINISH).replace(smoke.TEST_CLASS, "OtherTest")]:
            with self.subTest(output=output), self.assertRaisesRegex(ValueError, "did not execute"):
                self.check_output(output)

    def test_assertions_errors_and_skips_cannot_pass(self):
        for code in [-1, -2, -3, -4]:
            output = self.START + self.SUCCESS.replace("STATUS_CODE: 0", f"STATUS_CODE: {code}") + self.FINISH
            with self.subTest(code=code), self.assertRaisesRegex(ValueError, "failed or was skipped"):
                self.check_output(output)

    def test_aborted_or_invalid_completion_cannot_pass(self):
        output = self.START + self.SUCCESS
        for ending in ["INSTRUMENTATION_FAILED: Unable to find instrumentation info", "INSTRUMENTATION_ABORTED: System has crashed", "INSTRUMENTATION_CODE: 0", "INSTRUMENTATION_CODE: -2"]:
            with self.subTest(ending=ending), self.assertRaises(ValueError):
                self.check_output(output + ending)
        for invalid in [self.SUCCESS + self.FINISH, self.START + self.START + self.SUCCESS + self.FINISH, self.START + self.SUCCESS + self.SUCCESS + self.FINISH]:
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                self.check_output(invalid)


if __name__ == "__main__":
    unittest.main()
