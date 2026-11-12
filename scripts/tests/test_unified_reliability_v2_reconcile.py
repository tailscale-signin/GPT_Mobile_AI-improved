"""Fail-closed CI reconciliation tests; component proof is never case acceptance."""
from datetime import datetime, timedelta, timezone
import hashlib
import json
from pathlib import Path
import sys
import tempfile
import unittest
from zipfile import ZipFile
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "scripts"))
import unified_reliability_v2_reconcile as r  # noqa: E402


class UnifiedV2ReconciliationTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.scope = self.root / "scope"
        self.scope.mkdir()
        self.results = self.root / "junit"
        self.results.mkdir()
        self.now = datetime(2026, 11, 10, 15, 0, tzinfo=timezone.utc)
        self.since = self.now - timedelta(minutes=10)
        self.sha = "a" * 40
        self.run = "https://github.com/example/project/actions/runs/123/attempts/2"
        self.cases = [
            dict(id=f"{prefix}{i:02}", fixture="Synthetic", assertion="Full acceptance",
                 outcome="NOT_RUN", commit=None, evidence=[])
            for prefix, count in (("LA-QA", 36), ("LC-T", 18), ("CM-T", 26), ("IX", 30))
            for i in range(1, count + 1)
        ]
        self.tasks = [
            dict(id=f"V{i:02}", status="IN_PROGRESS", acceptance_ids=[])
            for i in range(1, 49)
        ]
        self.mapping = self.root / "component-fixtures.json"
        self.mapping.write_text(json.dumps({
            "schema": "unified-reliability-v2-component-fixtures.v1",
            "candidates": [{
                "case_id": "IX05",
                "tests": [{"class_name": "dev.example.AcceptanceProbe", "method": "noFakeAttempt"}],
                "remaining_assertions": "Must also verify retries and live dispatch.",
            }]
        }))
        self.save()

    def save(self):
        (self.scope / "tasks.json").write_text(json.dumps(self.tasks))
        (self.scope / "acceptance-tests.json").write_text(json.dumps(self.cases))

    def junit(self, state="PASS", method="noFakeAttempt", observed=None, filename="TEST-AcceptanceProbe.xml",
              directory=None, owner="dev.example.AcceptanceProbe"):
        result = ("<failure message='failed'/>" if state == "FAIL" else
                  "<skipped/>" if state == "SKIP" else "")
        observed = observed or self.now - timedelta(minutes=1)
        path = (directory or self.results) / filename
        path.write_text(
            f"<testsuite tests='2' timestamp='{observed.isoformat()}'>"
            f"<testcase classname='{owner}' name='{method}'>{result}</testcase>"
            f"<testcase classname='dev.example.OtherProbe' name='{filename}'/></testsuite>"
        )
        return path

    def qualification(self, directories=None):
        paths = [report for directory in directories or [self.results]
                 for report in directory.glob("TEST-*.xml")]
        classes, errors = r.junit_observations(paths, self.since, self.now)
        return {"schema_version": 1, "kind": "APP_AUDIT_SOURCE_HARNESS_OBSERVATIONS",
                "checkout": {"commit": self.sha}, "report_errors": errors,
                "execution_environment": {"github_repository": "example/project", "github_run_id": "123",
                                          "github_run_attempt": "2"},
                "execution_window_start": self.since.isoformat(), "collected_at": self.now.isoformat(),
                "junit_receipt": r.current_junit_receipt(classes, errors)}

    def run_report(self, directory=None, **overrides):
        parameters = dict(source_sha=self.sha, run_url=self.run,
                          qualification=self.qualification() if directory is not None else None)
        parameters.update(overrides)
        return r.reconcile(self.scope, self.mapping, directory, **parameters)

    def test_observed_component_pass_does_not_promote_case_or_task(self):
        self.junit()
        report = self.run_report(self.results)
        self.assertEqual(99 + 11, report["summary"]["case_outcomes"]["NOT_RUN"])
        self.assertEqual(1, report["summary"]["not_run_with_partial_component_proof"])
        self.assertEqual(0, report["summary"]["new_case_pass_claims"])
        self.assertEqual(0, report["summary"]["new_task_done_claims"])
        candidate = next(v for v in report["cases"] if v["case_id"] == "IX05")
        self.assertEqual("NOT_RUN", candidate["recorded_outcome"])
        self.assertFalse(candidate["promotion_authorized"])
        self.assertEqual("ALL_NAMED_TESTS_PASS",
                         candidate["component_candidate"]["component_status"])
        self.assertEqual(64, len(candidate["component_candidate"]["named_tests"][0]
                                 ["junit_evidence"][0]["xml_sha256"]))
        self.assertEqual("NOT_RUN", self.cases[-26]["outcome"])

    def test_combined_directories_match_collector_receipt_without_promoting_ledger(self):
        self.junit()
        core = self.root / "core-results"
        core.mkdir()
        self.junit(method="coreBehavior", filename="TEST-CoreProbe.xml", directory=core,
                   owner="dev.example.CoreProbe")
        qualification = self.qualification([self.results, core])
        self.assertEqual(2, qualification["junit_receipt"]["report_count"])
        self.assertEqual(4, qualification["junit_receipt"]["case_count"])
        ledger = {name: (self.scope / name).read_bytes()
                  for name in ("tasks.json", "acceptance-tests.json")}
        for sources in ([self.results, core], [core, self.results]):
            report = self.run_report(sources, qualification=qualification)
            for field, value in qualification["junit_receipt"].items():
                self.assertEqual(value, report["junit_receipt"][field])
            self.assertEqual(1, report["summary"]["not_run_with_partial_component_proof"])
            self.assertEqual(0, report["summary"]["new_case_pass_claims"])
            self.assertEqual(0, report["summary"]["new_task_done_claims"])
            self.assertTrue(all(not row["promotion_authorized"] for row in report["cases"]))
        self.assertEqual(ledger, {name: (self.scope / name).read_bytes() for name in ledger})

    def test_missing_stale_duplicate_corrupt_or_changed_second_directory_fails_closed(self):
        self.junit()
        core = self.root / "core-results"
        core.mkdir()
        second = self.junit(method="coreBehavior", filename="TEST-CoreProbe.xml", directory=core,
                            owner="dev.example.CoreProbe")
        qualification = self.qualification([self.results, core])
        original = second.read_bytes()
        for defect in ("omitted", "missing-directory", "missing-report", "stale", "duplicate-case", "corrupt", "modified", "repeated-input"):
            with self.subTest(defect=defect):
                second.write_bytes(original)
                sources = [self.results, core]
                if defect == "omitted":
                    sources = [self.results]
                elif defect == "missing-directory":
                    sources = [self.results, self.root / "missing"]
                elif defect == "missing-report":
                    second.unlink()
                elif defect == "stale":
                    self.junit(method="coreBehavior", filename=second.name, directory=core,
                               owner="dev.example.CoreProbe", observed=self.since - timedelta(seconds=1))
                elif defect == "duplicate-case":
                    self.junit(filename=second.name, directory=core)
                elif defect == "corrupt":
                    second.write_text("<testsuite>")
                elif defect == "modified":
                    self.junit(method="changedBehavior", filename=second.name, directory=core,
                               owner="dev.example.CoreProbe")
                elif defect == "repeated-input":
                    sources.append(core)
                with self.assertRaises(ValueError):
                    self.run_report(sources, qualification=qualification)

    def test_combined_directory_report_limit_is_global(self):
        self.junit()
        core = self.root / "core-results"
        core.mkdir()
        self.junit(method="coreBehavior", filename="TEST-CoreProbe.xml", directory=core,
                   owner="dev.example.CoreProbe")
        with patch.object(r, "MAX_REPORTS", 1), self.assertRaisesRegex(ValueError, "collection bound"):
            self.run_report([self.results, core], qualification=self.qualification([self.results, core]))

    def test_cli_repeated_directories_reconcile_the_same_bound_receipt(self):
        self.junit()
        core = self.root / "core-results"
        core.mkdir()
        self.junit(method="coreBehavior", filename="TEST-CoreProbe.xml", directory=core,
                   owner="dev.example.CoreProbe")
        qualification = self.qualification([self.results, core])
        receipt_path = self.root / "qualification.json"
        receipt_path.write_text(json.dumps(qualification))
        target = self.root / "report.json"
        original_reconcile = r.reconcile
        with patch.object(r, "validate", return_value=(48, 110)), patch.object(
                r, "reconcile", side_effect=lambda **kwargs: original_reconcile(self.scope, self.mapping, **kwargs)):
            self.assertEqual(0, r.main([
                "--junit-dir", str(self.results), "--junit-dir", str(core),
                "--qualification", str(receipt_path), "--source-sha", self.sha,
                "--run-url", self.run, "--output", str(target), "--fail-on-candidate-regression",
            ]))
        report = json.loads(target.read_text())
        self.assertEqual(qualification["junit_receipt"]["content_sha256"], report["junit_receipt"]["content_sha256"])
        self.assertEqual(2, report["junit_receipt"]["report_count"])
        self.assertEqual({"NOT_RUN": 110}, report["summary"]["case_outcomes"])

    def test_missing_skipped_failed_probes_do_not_claim_evidence(self):
        for state in ("MISSING", "SKIP", "FAIL"):
            with self.subTest(state=state):
                if state != "MISSING":
                    self.junit(state)
                    source = self.results
                else:
                    self.junit(method="unrelated")
                    source = self.results
                report = self.run_report(source)
                self.assertEqual(0, report["summary"]["not_run_with_partial_component_proof"])
                self.assertEqual("NOT_FULLY_OBSERVED", next(
                    v["component_candidate"]["component_status"] for v in report["cases"]
                    if v["case_id"] == "IX05"))

    def test_downloaded_github_actions_zip_can_be_read_without_extraction(self):
        self.junit()
        archive = self.root / "artifact.zip"
        with ZipFile(archive, "w") as z:
            z.write(self.results / "TEST-AcceptanceProbe.xml",
                    arcname="app/build/test-results/testDebugUnitTest/TEST-AcceptanceProbe.xml")
        with self.assertRaisesRegex(ValueError, "artifact SHA-256"):
            self.run_report(archive)
        with self.assertRaisesRegex(ValueError, "mismatch"):
            self.run_report(archive, artifact_sha256="b" * 64)
        artifact_digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        report = self.run_report(archive, artifact_sha256=artifact_digest)
        self.assertEqual(artifact_digest, report["junit_receipt"]["artifact_sha256"])
        self.assertEqual(1, report["summary"]["not_run_with_partial_component_proof"])
        candidate = next(row["component_candidate"] for row in report["cases"]
                         if row["case_id"] == "IX05")
        self.assertEqual("ALL_NAMED_TESTS_PASS", candidate["component_status"])
        self.assertEqual(report, self.run_report([archive], artifact_sha256=artifact_digest))
        for sources in ([archive, archive], [self.results, archive]):
            with self.assertRaisesRegex(ValueError, "one ZIP archive"):
                self.run_report(sources, artifact_sha256=artifact_digest)

    def test_android_and_portable_reports_must_match_the_same_combined_receipt(self):
        self.junit()
        portable = self.root / "portable"
        portable.mkdir()
        (portable / "TEST-CoreProbe.xml").write_text(
            f"<testsuite tests='1' timestamp='{(self.now - timedelta(minutes=1)).isoformat()}'>"
            "<testcase classname='dev.example.CoreProbe' name='portableContract'/></testsuite>"
        )
        qualification = self.qualification([self.results, portable])
        with self.assertRaisesRegex(ValueError, "digest"):
            self.run_report(self.results, qualification=qualification)
        report = self.run_report([self.results, portable], qualification=qualification)
        self.assertEqual(3, report["junit_receipt"]["case_count"])
        self.assertEqual(2, report["junit_receipt"]["report_count"])
        self.assertEqual(0, report["summary"]["new_case_pass_claims"])
        self.assertEqual(0, report["summary"]["new_task_done_claims"])
        # Overlapping roots or copied reports must not inflate passing evidence.
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.run_report([self.results, portable, self.results], qualification=qualification)
        (portable / "TEST-AcceptanceProbe.xml").write_bytes((self.results / "TEST-AcceptanceProbe.xml").read_bytes())
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.run_report([self.results, portable], qualification=qualification)

    def test_repeated_failing_test_cannot_be_misrepresented_as_pass(self):
        self.junit("PASS")
        qualification = self.qualification()
        self.junit("FAIL", filename="TEST-B.xml")
        with self.assertRaisesRegex(ValueError, "duplicate"):
            self.run_report(self.results, qualification=qualification)

    def test_stale_modified_and_wrong_attempt_reports_cannot_be_current(self):
        self.junit()
        qualification = self.qualification()
        with self.assertRaisesRegex(ValueError, "requires"):
            self.run_report(self.results, qualification=None)
        with self.assertRaisesRegex(ValueError, "workflow attempt"):
            self.run_report(self.results, run_url=self.run[:-1] + "1")
        with self.assertRaisesRegex(ValueError, "tested SHA"):
            self.run_report(self.results, source_sha="b" * 40)
        self.junit(observed=self.since - timedelta(days=1))
        with self.assertRaisesRegex(ValueError, "stale"):
            self.run_report(self.results, qualification=qualification)
        self.junit(method="anotherTest")
        with self.assertRaisesRegex(ValueError, "digest"):
            self.run_report(self.results, qualification=qualification)

    def test_duplicate_unknown_or_empty_fixture_mapping_fails_closed(self):
        payload = json.loads(self.mapping.read_text())
        payload["candidates"].append(dict(payload["candidates"][0]))
        self.mapping.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "Duplicate"):
            self.run_report()
        payload["candidates"][1]["case_id"] = "NOPE"
        self.mapping.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "unknown"):
            self.run_report()
        payload["candidates"] = [{"case_id": "IX05", "tests": [],
                                  "remaining_assertions": "Incomplete"}]
        self.mapping.write_text(json.dumps(payload))
        with self.assertRaisesRegex(ValueError, "unverified"):
            self.run_report()

    def test_changed_scope_and_fake_revision_fail_closed(self):
        self.cases.pop()
        self.save()
        with self.assertRaisesRegex(ValueError, "original"):
            self.run_report()
        self.cases.append(dict(id="IX30", fixture="", assertion="",
                               outcome="NOT_RUN", evidence=[]))
        self.save()
        with self.assertRaisesRegex(ValueError, "Source SHA"):
            r.reconcile(self.scope, self.mapping, source_sha="not-a-real-sha")

    def test_reconciliation_cli_enforces_no_fabricated_promotions(self):
        target = self.root / "report.json"
        with patch.object(r, "validate", return_value=(48, 110)), patch.object(
                r, "reconcile", return_value={
                    "summary": {"task_statuses": {"IN_PROGRESS": 48},
                                "case_outcomes": {"NOT_RUN": 110},
                                "not_run_with_partial_component_proof": 0,
                                "new_case_pass_claims": 0, "new_task_done_claims": 0},
                    "cases": [],
                }):
            self.assertEqual(0, r.main(["--output", str(target)]))
        self.assertEqual(0, json.loads(target.read_text())
                         ["summary"]["new_case_pass_claims"])


if __name__ == "__main__":
    unittest.main()
