"""Qualification uses named execution receipts, never inventory totals as proof."""
from datetime import datetime, timedelta, timezone
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[2]
SPEC = importlib.util.spec_from_file_location("app_audit_qualification", ROOT / "scripts/collect_app_audit_qualification.py")
COLLECTOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COLLECTOR)
PACKAGE = "dev.chungjungsoo.gptmobile"
ALPHA = "app/src/test/kotlin/dev/chungjungsoo/gptmobile/AlphaTest.kt"
BETA = "app/src/test/kotlin/dev/chungjungsoo/gptmobile/BetaTest.kt"
DEVICE = "app/src/androidTest/kotlin/dev/chungjungsoo/gptmobile/DeviceTest.kt"
COMMAND = "scripts/check_fixture.py"
UNSET = object()


class AppAuditQualificationEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.now = datetime(2026, 11, 10, 12, 0, tzinfo=timezone.utc)
        self.since = self.now - timedelta(minutes=10)
        self.fresh = self.now - timedelta(minutes=2)
        self.write_catalog({"connections": [ALPHA]})

    def write_catalog(self, areas, indexed_lines=987654):
        entries = []
        for area, paths in areas.items():
            entries.append('    "' + area + '" to listOf(\n' + ',\n'.join('        "' + path + '"' for path in paths) + '\n    )')
            for relative in paths:
                path = self.root / relative
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("package " + PACKAGE + "\nclass " + path.stem + "\n" if relative.endswith(".kt") else "# Named host command\n")
        catalog_path = self.root / COLLECTOR.CATALOG
        catalog_path.parent.mkdir(parents=True, exist_ok=True)
        catalog_path.write_text("val harnesses = mapOf(\n" + ",\n".join(entries) + "\n)\n")
        inventory_path = self.root / COLLECTOR.INVENTORY
        inventory_path.parent.mkdir(parents=True, exist_ok=True)
        inventory_path.write_text(json.dumps({
            "schema_version": 1,
            "indexed_line_count": indexed_lines,
            "line_coverage_available": False,
            "areas": [{"id": area, "indexed_line_count": indexed_lines, "file_count": 99} for area in areas],
            "files": [],
        }))

    def junit(self, cases=None, observed=UNSET, name="TEST-fixture.xml", attributes=None):
        cases = cases if cases is not None else [(PACKAGE + ".AlphaTest", "observedBehavior", "PASS")]
        counts = {"tests": len(cases), "failures": 0, "errors": 0, "skipped": 0}
        for _, _, outcome in cases:
            if outcome != "PASS":
                counts[{"FAILURE": "failures", "ERROR": "errors", "SKIPPED": "skipped"}[outcome]] += 1
        suite = ET.Element("testsuite", {key: str(value) for key, value in counts.items()})
        observed = self.fresh if observed is UNSET else observed
        if observed is not None:
            suite.set("timestamp", observed.isoformat() if isinstance(observed, datetime) else observed)
        if attributes:
            suite.attrib.update({key: str(value) for key, value in attributes.items()})
        for owner, case_name, outcome in cases:
            case = ET.SubElement(suite, "testcase", classname=owner, name=case_name)
            if outcome != "PASS":
                ET.SubElement(case, outcome.lower())
        path = self.root / name
        path.write_bytes(ET.tostring(suite))
        return path

    def coverage(self, packages=None, sessions=UNSET, name="jacoco.xml"):
        report = ET.Element("report", name="Fixture")
        sessions = [(self.since + timedelta(seconds=1), self.fresh)] if sessions is UNSET else sessions
        for start, dump in sessions:
            def millis(value):
                return str(int(value.timestamp() * 1000)) if isinstance(value, datetime) else str(value)
            ET.SubElement(report, "sessioninfo", id="fixture-session", start=millis(start), dump=millis(dump))
        packages = packages if packages is not None else [("dev/chungjungsoo/gptmobile/data", "Source.kt", (8, 2), (3, 5))]
        for package, source, lines, branches in packages:
            node = ET.SubElement(ET.SubElement(report, "package", name=package), "sourcefile", name=source)
            ET.SubElement(node, "counter", type="LINE", covered=str(lines[0]), missed=str(lines[1]))
            ET.SubElement(node, "counter", type="BRANCH", covered=str(branches[0]), missed=str(branches[1]))
        # Report-wide counters deliberately disagree: only application-source counters count.
        ET.SubElement(report, "counter", type="LINE", covered="1000000", missed="0")
        path = self.root / name
        path.write_bytes(ET.tostring(report))
        return path

    def collect(self, reports=(), since=UNSET, coverage=None):
        return COLLECTOR.collect(self.root, list(reports), self.since if since is UNSET else since, self.now, coverage)

    def harness(self, result, relative=ALPHA):
        return next(harness for area in result["areas"] for harness in area["harnesses"] if harness["harness"] == relative)

    def assert_unqualified_coverage(self, result):
        self.assertFalse(result["coverage"]["available"])
        self.assertNotIn('"percent"', json.dumps(result["coverage"]))

    def test_named_class_is_required_and_an_unobserved_harness_keeps_area_partial(self):
        self.write_catalog({"connections": [ALPHA, BETA]})
        result = self.collect([self.junit()])
        self.assertEqual("PASS", self.harness(result)["status"])
        self.assertEqual("NOT_OBSERVED", self.harness(result, BETA)["status"])
        self.assertEqual("PARTIAL", result["areas"][0]["named_harness_status"])
        self.assertFalse(result["historical_audit_mutation"])
        self.assertFalse(result["grants_execution"])
        self.assertFalse(result["promotes_audit_score"])

    def test_lookalike_and_other_package_class_names_cannot_qualify_a_harness(self):
        report = self.junit([(PACKAGE + ".AlphaTestExtra", "observedBehavior", "PASS"), ("other.AlphaTest", "observedBehavior", "PASS")])
        result = self.collect([report])
        self.assertEqual("NOT_OBSERVED", self.harness(result)["status"])
        self.assertEqual([], self.harness(result)["cases"])

    def test_failure_error_and_skipped_outcomes_remain_distinct(self):
        for outcome, expected in (("FAILURE", "FAIL"), ("ERROR", "FAIL"), ("SKIPPED", "SKIPPED")):
            with self.subTest(outcome=outcome):
                result = self.collect([self.junit([(PACKAGE + ".AlphaTest", "observedBehavior", outcome)])])
                self.assertEqual(expected, self.harness(result)["status"])
                self.assertEqual(outcome, self.harness(result)["cases"][0]["outcome"])
                self.assertNotEqual("PASS", result["areas"][0]["named_harness_status"])

    def test_a_pass_with_a_skipped_case_is_partial(self):
        result = self.collect([self.junit([(PACKAGE + ".AlphaTest", "executed", "PASS"), (PACKAGE + ".AlphaTest", "notExecuted", "SKIPPED")])])
        self.assertEqual("PARTIAL", self.harness(result)["status"])
        self.assertEqual("PARTIAL", result["areas"][0]["named_harness_status"])

    def test_suite_counts_without_leaf_testcases_are_not_execution_proof(self):
        result = self.collect([self.junit([], attributes={"tests": 5000})])
        self.assertEqual("NOT_OBSERVED", self.harness(result)["status"])
        self.assertEqual([], self.harness(result)["cases"])
        self.assertNotEqual("PASS", result["areas"][0]["named_harness_status"])

    def test_contradictory_suite_counts_cannot_publish_a_pass(self):
        for attributes in ({"tests": 2}, {"failures": 1}, {"errors": 1}, {"skipped": 1}, {"tests": "not-a-number"}):
            with self.subTest(attributes=attributes):
                result = self.collect([self.junit(attributes=attributes)])
                self.assertTrue(result["report_errors"])
                self.assertNotEqual("PASS", self.harness(result)["status"])

    def test_nested_suites_are_observed_but_contradictory_aggregate_counts_are_not_trusted(self):
        for wrapper in ("testsuite", "testsuites"):
            with self.subTest(wrapper=wrapper):
                path = self.junit()
                child = ET.parse(path).getroot()
                parent = ET.Element(wrapper, tests="1", failures="0", errors="0", skipped="0")
                parent.append(child)
                path.write_bytes(ET.tostring(parent))
                self.assertEqual("PASS", self.harness(self.collect([path]))["status"])
                parent.set("failures", "1")
                path.write_bytes(ET.tostring(parent))
                result = self.collect([path])
                self.assertTrue(result["report_errors"])
                self.assertNotEqual("PASS", self.harness(result)["status"])

    def test_malformed_and_conflicting_xml_blocks_otherwise_green_report_set(self):
        valid = self.junit()
        invalid = self.root / "TEST-invalid.xml"
        malformed = [
            "<testsuite private_payload='do-not-publish'>",
            "<not-junit />",
            '<!DOCTYPE testsuite [<!ENTITY payload "do-not-publish">]><testsuite/>',
            '<testsuite tests="1"><testcase classname="x" name="x"><failure/><skipped/></testcase></testsuite>',
            '<testsuite tests="1"><testcase classname="x" name="x"><failure/><failure/></testcase></testsuite>',
        ]
        for text in malformed:
            with self.subTest(xml=text):
                invalid.write_text(text)
                result = self.collect([valid, invalid])
                self.assertTrue(result["report_errors"])
                self.assertEqual("UNVERIFIED", self.harness(result)["status"])
                self.assertNotIn("do-not-publish", json.dumps(result))

    def test_stale_future_missing_and_invalid_timestamps_are_not_verified(self):
        for observed in (self.since - timedelta(seconds=1), self.now + timedelta(seconds=1), None, "not-a-timestamp"):
            with self.subTest(observed=observed):
                result = self.collect([self.junit(observed=observed)])
                self.assertEqual("UNVERIFIED", self.harness(result)["status"])
                self.assertFalse(self.harness(result)["cases"][0]["current_run_timestamp"])
        self.assertEqual("UNVERIFIED", self.harness(self.collect([self.junit()], since=None))["status"])

    def test_timezone_offsets_are_compared_as_instants(self):
        observed = self.fresh.astimezone(timezone(timedelta(hours=3))).isoformat()
        self.assertEqual("PASS", self.harness(self.collect([self.junit(observed=observed)]))["status"])

    def test_jvm_reports_do_not_qualify_device_or_external_command_fixtures(self):
        self.write_catalog({"connections": [ALPHA, DEVICE, COMMAND]})
        report = self.junit([(PACKAGE + ".AlphaTest", "jvmBehavior", "PASS"), (PACKAGE + ".DeviceTest", "deviceBehavior", "PASS")])
        result = self.collect([report])
        self.assertEqual("PASS", self.harness(result)["status"])
        for path in (DEVICE, COMMAND):
            with self.subTest(harness=path):
                self.assertEqual("NOT_OBSERVED", self.harness(result, path)["status"])
                self.assertEqual([], self.harness(result, path)["cases"])
        self.assertEqual("PARTIAL", result["areas"][0]["named_harness_status"])

    def test_duplicate_case_identity_across_reports_is_unverified(self):
        result = self.collect([self.junit(), self.junit(name="TEST-repeat.xml")])
        self.assertEqual("UNVERIFIED", self.harness(result)["status"])
        self.assertEqual("DUPLICATE_TEST_CASES", self.harness(result)["reason"])

    def test_same_method_name_in_distinct_nested_classes_is_not_a_duplicate(self):
        owners = [PACKAGE + ".AlphaTest$First", PACKAGE + ".AlphaTest$Second"]
        result = self.collect([self.junit([(owner, "sameMethod", "PASS") for owner in owners])])
        self.assertEqual("PASS", self.harness(result)["status"])
        self.assertEqual(set(owners), {case["class_name"] for case in self.harness(result)["cases"]})

    def test_failure_and_stdout_payloads_are_not_copied_to_output(self):
        path = self.junit([(PACKAGE + ".AlphaTest", "safeTestName", "FAILURE")])
        suite = ET.parse(path).getroot()
        failure = suite.find("testcase/failure")
        failure.set("message", "private-message-token")
        failure.text = "private-stacktrace-and-source"
        ET.SubElement(suite, "system-out").text = "private-stdout-credential"
        ET.SubElement(suite, "system-err").text = "private-stderr-payload"
        path.write_bytes(ET.tostring(suite))
        result = self.collect([path])
        self.assertEqual("FAIL", self.harness(result)["status"])
        self.assertNotIn("private-", json.dumps(result))
        self.assertEqual(64, len(self.harness(result)["cases"][0]["report_sha256"]))

    def test_inventory_counts_do_not_make_coverage_available(self):
        result = self.collect([self.junit()])
        self.assertEqual("PASS", self.harness(result)["status"])
        self.assert_unqualified_coverage(result)
        self.assertNotIn("987654", json.dumps(result["coverage"]))

    def test_coverage_uses_application_source_counters_and_excludes_other_packages(self):
        coverage = self.coverage([
            ("dev/chungjungsoo/gptmobile", "Root.kt", (3, 1), (1, 3)),
            ("dev/chungjungsoo/gptmobile/data", "Child.kt", (5, 1), (2, 2)),
            ("dev/chungjungsoo/gptmobile_external", "Lookalike.kt", (500, 0), (600, 0)),
            ("org/vendor", "External.java", (900, 0), (1000, 0)),
        ])
        result = self.collect([self.junit()], coverage=coverage)["coverage"]
        self.assertTrue(result["available"])
        self.assertEqual(2, result["source_files"])
        self.assertEqual({"covered": 8, "missed": 2, "percent": 80.0}, result["counters"]["LINE"])
        self.assertEqual({"covered": 3, "missed": 5, "percent": 37.5}, result["counters"]["BRANCH"])

    def test_large_coverage_report_does_not_expand_the_junit_report_limit(self):
        coverage = self.coverage()
        coverage.write_bytes(coverage.read_bytes() + b"\n" * COLLECTOR.MAX_REPORT_BYTES)
        self.assertGreater(coverage.stat().st_size, COLLECTOR.MAX_REPORT_BYTES)
        result = self.collect([self.junit()], coverage=coverage)["coverage"]
        self.assertTrue(result["available"])
        self.assertEqual({"covered": 8, "missed": 2, "percent": 80.0}, result["counters"]["LINE"])

        junit = self.junit(name="TEST-oversized.xml")
        junit.write_bytes(junit.read_bytes() + b"\n" * COLLECTOR.MAX_REPORT_BYTES)
        result = self.collect([junit], coverage=coverage)
        self.assertEqual([{"report_name": junit.name, "reason": "REPORT_TOO_LARGE"}], result["report_errors"])
        self.assert_unqualified_coverage(result)

    def test_coverage_size_limit_is_bounded_and_has_an_actionable_reason(self):
        coverage = self.coverage()
        with patch.object(COLLECTOR, "MAX_COVERAGE_REPORT_BYTES", coverage.stat().st_size - 1):
            result = self.collect([self.junit()], coverage=coverage)
        self.assert_unqualified_coverage(result)
        self.assertEqual("INSTRUMENTED_REPORT_TOO_LARGE", result["coverage"]["reason"])

    def test_coverage_doctype_is_supported_but_entity_definitions_are_rejected(self):
        coverage = self.coverage()
        payload = coverage.read_bytes()
        coverage.write_bytes(b'<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">' + payload)
        self.assertTrue(self.collect([self.junit()], coverage=coverage)["coverage"]["available"])
        coverage.write_bytes(b'<!DOCTYPE report [<!ENTITY private "do-not-export">]>' + payload)
        result = self.collect([self.junit()], coverage=coverage)
        self.assert_unqualified_coverage(result)
        self.assertNotIn("do-not-export", json.dumps(result))

    def test_changing_inventory_totals_does_not_change_measured_coverage(self):
        report, coverage = self.junit(), self.coverage()
        first = self.collect([report], coverage=coverage)
        self.write_catalog({"connections": [ALPHA]}, indexed_lines=1)
        second = self.collect([report], coverage=coverage)
        self.assertTrue(first["coverage"]["available"])
        self.assertEqual(first["coverage"], second["coverage"])
        self.assertNotEqual(first["source_inventory_sha256"], second["source_inventory_sha256"])

    def test_missing_stale_future_or_inverted_coverage_sessions_are_not_promoted(self):
        sessions = [
            [],
            [(self.since - timedelta(seconds=1), self.fresh)],
            [(self.fresh, self.now + timedelta(seconds=1))],
            [(self.fresh, self.since)],
            [("invalid", self.fresh)],
            [(self.since, self.fresh), (self.since - timedelta(days=1), self.since - timedelta(hours=1))],
        ]
        for value in sessions:
            with self.subTest(sessions=value):
                self.assert_unqualified_coverage(self.collect([self.junit()], coverage=self.coverage(sessions=value)))

    def test_coverage_is_not_bound_by_missing_stale_or_malformed_junit_evidence(self):
        coverage = self.coverage()
        stale = self.junit(observed=self.since - timedelta(seconds=1), name="TEST-stale.xml")
        current = self.junit()
        malformed = self.root / "TEST-malformed.xml"
        malformed.write_text("<testsuite>")
        for reports in ([], [stale], [current, stale], [current, malformed], [self.junit([], name="TEST-count-only.xml", attributes={"tests": 123})]):
            with self.subTest(reports=[path.name for path in reports]):
                self.assert_unqualified_coverage(self.collect(reports, coverage=coverage))
        self.assert_unqualified_coverage(self.collect([current], since=None, coverage=coverage))

    def test_duplicate_identities_and_skipped_only_reports_cannot_bind_coverage(self):
        coverage = self.coverage()
        current = self.junit()
        duplicate = self.junit(name="TEST-duplicate.xml")
        skipped = self.junit([(PACKAGE + ".AlphaTest", "notExecuted", "SKIPPED")], name="TEST-skipped.xml")
        for reports in ([current, duplicate], [skipped]):
            with self.subTest(reports=[path.name for path in reports]):
                result = self.collect(reports, coverage=coverage)
                self.assertNotEqual("PASS", self.harness(result)["status"])
                self.assert_unqualified_coverage(result)

    def test_failed_execution_can_measure_coverage_without_qualifying_the_harness(self):
        for outcome in ("FAILURE", "ERROR"):
            with self.subTest(outcome=outcome):
                report = self.junit([(PACKAGE + ".AlphaTest", "executedAndFailed", outcome)])
                result = self.collect([report], coverage=self.coverage())
                self.assertEqual("FAIL", self.harness(result)["status"])
                self.assertEqual("FAIL", result["areas"][0]["named_harness_status"])
                self.assertTrue(result["coverage"]["available"])
                self.assertFalse(result["promotes_audit_score"])

    def test_duplicate_and_negative_coverage_counters_are_rejected(self):
        cases = [
            [("dev/chungjungsoo/gptmobile/data", "Same.kt", (3, 1), (2, 1))] * 2,
            [("dev/chungjungsoo/gptmobile/data", "Negative.kt", (-3, 1), (2, 1))],
        ]
        for packages in cases:
            with self.subTest(packages=packages):
                self.assert_unqualified_coverage(self.collect([self.junit()], coverage=self.coverage(packages)))
        path = self.coverage()
        root = ET.parse(path).getroot()
        ET.SubElement(root.find("package/sourcefile"), "counter", type="LINE", covered="1", missed="0")
        path.write_bytes(ET.tostring(root))
        self.assert_unqualified_coverage(self.collect([self.junit()], coverage=path))

    def test_zero_application_line_execution_is_not_coverage_proof(self):
        path = self.coverage([("dev/chungjungsoo/gptmobile/data", "NotExecuted.kt", (0, 10), (3, 5))])
        self.assert_unqualified_coverage(self.collect([self.junit()], coverage=path))


if __name__ == "__main__":
    unittest.main()
