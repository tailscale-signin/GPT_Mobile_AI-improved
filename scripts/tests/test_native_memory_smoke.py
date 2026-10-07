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


if __name__ == "__main__":
    unittest.main()
