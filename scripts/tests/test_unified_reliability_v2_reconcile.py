"""Fail-closed CI reconciliation tests; component proof is never case acceptance."""
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

    def junit(self, state="PASS"):
        result = ("<failure message='failed'/>" if state == "FAIL" else
                  "<skipped/>" if state == "SKIP" else "")
        (self.results / "TEST-AcceptanceProbe.xml").write_text(
            "<testsuite><testcase classname='dev.example.AcceptanceProbe' "
            f"name='noFakeAttempt'>{result}</testcase></testsuite>"
        )

    def run_report(self, directory=None):
        return r.reconcile(self.scope, self.mapping, directory)

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

    def test_missing_skipped_failed_probes_do_not_claim_evidence(self):
        for state in ("MISSING", "SKIP", "FAIL"):
            with self.subTest(state=state):
                if state != "MISSING":
                    self.junit(state)
                    source = self.results
                else:
                    source = self.root / "empty"
                    source.mkdir(exist_ok=True)
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
        report = self.run_report(archive)
        self.assertEqual(1, report["summary"]["not_run_with_partial_component_proof"])
        candidate = next(row["component_candidate"] for row in report["cases"]
                         if row["case_id"] == "IX05")
        self.assertEqual("ALL_NAMED_TESTS_PASS", candidate["component_status"])

    def test_repeated_failing_test_cannot_be_misrepresented_as_pass(self):
        self.junit("PASS")
        (self.results / "TEST-B.xml").write_text(
            "<testsuite><testcase classname='dev.example.AcceptanceProbe' "
            "name='noFakeAttempt'><failure/></testcase></testsuite>"
        )
        report = self.run_report(self.results)
        status = next(v["component_candidate"]["named_tests"][0]["result"]
                      for v in report["cases"] if v["case_id"] == "IX05")
        self.assertEqual("FAIL", status)

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
