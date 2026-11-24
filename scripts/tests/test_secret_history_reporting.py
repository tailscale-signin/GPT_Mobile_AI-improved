import contextlib
import copy
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SCRIPT = Path(__file__).resolve().parents[1] / "report_secret_history.py"
SPEC = importlib.util.spec_from_file_location("report_secret_history", SCRIPT)
REPORTING = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(REPORTING)


class SecretHistoryReportingTest(unittest.TestCase):
    def finding(self):
        return {
            "RuleID": "generic-api-key", "Commit": "a" * 40,
            "File": "docs/fixture.json", "StartLine": 8,
            "Secret": "REDACTED", "Match": "private source surrounds REDACTED",
            "Author": "private author fixture", "Email": "private@example.invalid",
            "Message": "private commit message fixture", "Fingerprint": "unused fixture",
        }

    def parse(self, value, exit_code=1):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            path.write_text(json.dumps(value))
            return REPORTING.finding_identities(path, exit_code)

    def execute(self, value, exit_code, write_report=True):
        calls, paths = [], []

        def scanner(command, **kwargs):
            calls.append((command, kwargs))
            report = Path(command[command.index("--report-path") + 1])
            paths.append(report)
            if write_report:
                report.write_text(json.dumps(value))
            return subprocess.CompletedProcess(command, exit_code)

        output = io.StringIO()
        with mock.patch.object(REPORTING.subprocess, "run", side_effect=scanner):
            with contextlib.redirect_stdout(output):
                result = REPORTING.run_history(Path("/pinned/scanner"))
        return result, output.getvalue(), calls, paths

    def test_only_allowlisted_identity_fields_survive(self):
        finding = self.finding()
        finding["File"] = "docs/quoted\"and\nnewline.json"
        identities = self.parse([finding])
        self.assertEqual([{
            "ruleId": "generic-api-key", "commit": "a" * 40,
            "path": finding["File"], "line": 8,
        }], identities)
        encoded = json.dumps(identities)
        for private in ("private source", "private author", "private@example", "private commit", "unused fixture"):
            self.assertNotIn(private, encoded)
        self.assertNotIn("\n", encoded)

    def test_actual_scan_arguments_and_detection_exit_are_preserved(self):
        result, output, calls, paths = self.execute([self.finding()], 1)
        self.assertEqual(1, result)
        self.assertEqual(1, len(calls))
        command, kwargs = calls[0]
        self.assertEqual([
            "/pinned/scanner", "git", "--redact=100", "--no-banner", "--no-color",
            "--report-format=json", "--report-path", str(paths[0]),
            "--log-opts=--all --full-history --diff-merges=first-parent --root", ".",
        ], command)
        self.assertEqual({"check": False}, kwargs)
        self.assertEqual(1, output.count("SECRET_HISTORY_FINDING "))
        self.assertIn('"identityReportComplete": true', output)
        self.assertIn('"findingCount": 1', output)
        self.assertNotIn("private", output)
        self.assertNotIn("REDACTED", output)
        self.assertTrue(all(not path.parent.exists() for path in paths))

    def test_clean_report_preserves_success_with_zero_identities(self):
        result, output, _calls, paths = self.execute([], 0)
        self.assertEqual(0, result)
        self.assertNotIn("SECRET_HISTORY_FINDING ", output)
        self.assertIn('"findingCount": 0', output)
        self.assertIn('"identityReportComplete": true', output)
        self.assertTrue(all(not path.parent.exists() for path in paths))

    def test_exit_and_report_disagreement_cannot_pass(self):
        for exit_code, report in ((0, [self.finding()]), (1, []), (2, []), (2, [self.finding()])):
            with self.subTest(exit_code=exit_code, finding_count=len(report)):
                with self.assertRaises(ValueError):
                    self.parse(report, exit_code)

    def test_invalid_finding_metadata_rejects_complete_report(self):
        controls = [
            ("RuleID", "rule\nname"), ("RuleID", ""), ("RuleID", 7),
            ("Commit", "a" * 39), ("Commit", "z" * 40), ("Commit", None),
            ("File", ""), ("File", None), ("File", "a\x00b"), ("File", "x" * 4097),
            ("StartLine", 0), ("StartLine", -1), ("StartLine", True),
            ("StartLine", 1.5), ("StartLine", "8"), ("StartLine", 2_147_483_648),
        ]
        for key, value in controls:
            with self.subTest(field=key, value_type=type(value).__name__):
                finding = self.finding()
                finding[key] = value
                with self.assertRaises(ValueError):
                    self.parse([finding])

    def test_unredacted_or_missing_secret_fields_reject(self):
        for key, value in (("Secret", "private fixture"), ("Secret", None),
                           ("Match", "private fixture"), ("Match", None), ("Match", 5)):
            with self.subTest(field=key, value_type=type(value).__name__):
                finding = self.finding()
                finding[key] = value
                with self.assertRaises(ValueError):
                    self.parse([finding])

    def test_malformed_duplicate_and_non_json_numbers_reject(self):
        for raw in (b"{", b"{}", b"null", b"[null]", b'[{},{}]',
                    b'[{"RuleID":"one","RuleID":"two"}]', b"[NaN]"):
            with self.subTest(raw=raw):
                with tempfile.TemporaryDirectory() as temporary:
                    path = Path(temporary) / "report.json"
                    path.write_bytes(raw)
                    with self.assertRaises(ValueError):
                        REPORTING.finding_identities(path, 1)

    def test_report_count_and_serialized_identity_limits_are_enforced(self):
        with mock.patch.object(REPORTING, "MAX_FINDINGS", 1):
            self.assertEqual(1, len(self.parse([self.finding()])))
            with self.assertRaises(ValueError):
                self.parse([self.finding(), self.finding()])
        identity = self.parse([self.finding()])
        exact = sum(len(line.encode("ascii")) for line in REPORTING.identity_lines(identity))
        with mock.patch.object(REPORTING, "MAX_IDENTITY_BYTES", exact):
            self.assertEqual(identity, self.parse([self.finding()]))
        with mock.patch.object(REPORTING, "MAX_IDENTITY_BYTES", exact - 1):
            with self.assertRaises(ValueError):
                self.parse([self.finding()])

    def test_emitted_identity_limit_includes_prefix_spaces_escaping_and_newline(self):
        finding = self.finding()
        finding["File"] = "docs/é\"line\nfixture.json"
        result, output, _calls, _paths = self.execute([finding], 1)
        self.assertEqual(1, result)
        emitted = next(line for line in output.splitlines(keepends=True)
                       if line.startswith("SECRET_HISTORY_FINDING "))
        exact = len(emitted.encode("ascii"))
        with mock.patch.object(REPORTING, "MAX_IDENTITY_BYTES", exact):
            self.assertEqual(1, self.execute([finding], 1)[0])
        with mock.patch.object(REPORTING, "MAX_IDENTITY_BYTES", exact - 1):
            refused, refused_output, _calls, _paths = self.execute([finding], 1)
            self.assertEqual(2, refused)
            self.assertNotIn("SECRET_HISTORY_FINDING ", refused_output)
            self.assertIn('"identityReportComplete": false', refused_output)

    def test_report_byte_read_is_bounded_before_json_decode(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "report.json"
            path.write_bytes(b"[] ")
            with mock.patch.object(REPORTING, "MAX_REPORT_BYTES", 3):
                self.assertEqual([], REPORTING.finding_identities(path, 0))
            with mock.patch.object(REPORTING, "MAX_REPORT_BYTES", 2):
                with mock.patch.object(REPORTING.json, "loads", side_effect=AssertionError("must not decode")):
                    with self.assertRaises(ValueError):
                        REPORTING.finding_identities(path, 0)

    def test_invalid_later_finding_emits_no_partial_identity_or_private_data(self):
        invalid = copy.deepcopy(self.finding())
        invalid["Secret"] = "private synthetic value"
        result, output, _calls, paths = self.execute([self.finding(), invalid], 1)
        self.assertEqual(2, result)
        self.assertNotIn("SECRET_HISTORY_FINDING ", output)
        self.assertIn('"identityReportComplete": false', output)
        self.assertNotIn("private", output)
        self.assertNotIn("docs/fixture", output)
        self.assertTrue(all(not path.parent.exists() for path in paths))

    def test_missing_report_refuses_zero_and_retains_scanner_failure(self):
        for exit_code, expected in ((0, 2), (1, 2), (2, 2), (128, 128), (-15, 143)):
            with self.subTest(exit_code=exit_code):
                result, output, _calls, paths = self.execute([], exit_code, write_report=False)
                self.assertEqual(expected, result)
                self.assertIn('"identityReportComplete": false', output)
                self.assertNotIn("SECRET_HISTORY_FINDING ", output)
                self.assertTrue(all(not path.parent.exists() for path in paths))

    def test_scanner_launch_failure_has_no_exception_content(self):
        output = io.StringIO()
        with mock.patch.object(REPORTING.subprocess, "run", side_effect=OSError("private exception fixture")):
            with contextlib.redirect_stdout(output):
                self.assertEqual(2, REPORTING.run_history(Path("/pinned/scanner")))
        self.assertIn('"identityReportComplete": false', output.getvalue())
        self.assertNotIn("private", output.getvalue())


if __name__ == "__main__":
    unittest.main()
