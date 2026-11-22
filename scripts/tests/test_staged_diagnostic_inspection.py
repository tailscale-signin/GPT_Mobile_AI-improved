import copy
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch
import zipfile


SPEC = importlib.util.spec_from_file_location("staged_replay", Path(__file__).parents[1] / "replay_conversation_debug.py")
replay = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(replay)


class StagedDiagnosticInspectionTest(unittest.TestCase):
    def fixture(self):
        body = b'{"line":"recorded tool failure"}\n'
        stage = dict(id="critical_logs", fileName="critical-app-logs.jsonl", status="COMPLETED", critical=True,
                     records=1, evidenceRecords=1, bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
        manifest = dict(schema=replay.STAGED_SCHEMA, stages=[stage], usefulRecords=1, capturedFully=False,
                        captureCompleted=True, criticalEvidenceAvailable=True, emptyCriticalEvidence=False,
                        retryRecommended=False)
        return manifest, body

    def write(self, directory, manifest, body):
        file = Path(directory) / "capture.zip"
        with zipfile.ZipFile(file, "w", zipfile.ZIP_DEFLATED) as archive:
            archive.writestr("critical-app-logs.jsonl", body)
            archive.writestr("manifest.json", json.dumps(manifest))
        return file

    def test_inspects_stages_without_claiming_task_or_reducer_replay(self):
        manifest, body = self.fixture()
        with tempfile.TemporaryDirectory() as directory:
            summary = replay.inspect_snapshot(replay.load_snapshot(self.write(directory, manifest, body)))
        self.assertEqual("retained-stages-verified", summary["result"])
        self.assertEqual("unavailable", summary["reducerReplay"])
        self.assertFalse(summary["capturedFully"])

    def test_rejects_wrong_bytes_hash_and_record_count(self):
        manifest, body = self.fixture()
        with tempfile.TemporaryDirectory() as directory:
            for key, invalid in (("bytes", len(body) + 1), ("sha256", "0" * 64), ("records", 2)):
                broken = copy.deepcopy(manifest)
                broken["stages"][0][key] = invalid
                with self.subTest(key=key), self.assertRaises(ValueError):
                    replay.load_snapshot(self.write(directory, broken, body))

    def test_empty_logs_report_absence_even_with_explanation_record(self):
        manifest, _ = self.fixture()
        body = b'{"kind":"metadata","reason":"NO_RETAINED_LOGS"}\n'
        manifest["stages"][0].update(status="EMPTY", evidenceRecords=0, bytes=len(body),
                                     sha256=hashlib.sha256(body).hexdigest(), omissionReason="NO_RETAINED_LOGS")
        manifest.update(usefulRecords=0, criticalEvidenceAvailable=False, emptyCriticalEvidence=True, retryRecommended=True)
        with tempfile.TemporaryDirectory() as directory:
            summary = replay.inspect_snapshot(replay.load_snapshot(self.write(directory, manifest, body)))
        self.assertEqual("partial", summary["result"])
        self.assertFalse(summary["criticalEvidenceAvailable"])
        self.assertEqual(0, summary["usefulRecords"])

    def test_rejects_false_completeness_claim(self):
        manifest, body = self.fixture()
        manifest["capturedFully"] = True
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            replay.load_snapshot(self.write(directory, manifest, body))

    def test_critical_availability_counts_and_source_names_are_verified(self):
        manifest, body = self.fixture()
        manifest["evidenceAvailability"] = dict(
            criticalSectionCount=1, criticalSectionsWithEvidence=1,
            criticalSectionsWithoutEvidence=0, incompleteCriticalSections=0,
            availableCriticalSections=["critical_logs"], emptyCriticalSections=[],
            applicationLogRecords=1,
        )
        with tempfile.TemporaryDirectory() as directory:
            self.assertEqual(manifest, replay.load_snapshot(self.write(directory, manifest, body)))
            for key, invalid in (("criticalSectionsWithoutEvidence", 1), ("availableCriticalSections", []),
                                 ("criticalSectionCount", True), ("applicationLogRecords", 0)):
                broken = copy.deepcopy(manifest)
                broken["evidenceAvailability"][key] = invalid
                with self.subTest(key=key), self.assertRaisesRegex(ValueError, "evidence availability"):
                    replay.load_snapshot(self.write(directory, broken, body))

    def test_rejects_malformed_manifest_or_stage_with_validation_error(self):
        manifest, body = self.fixture()
        invalid_values = [[], dict(manifest, stages=[None])]
        malformed_name = copy.deepcopy(manifest)
        malformed_name["stages"][0]["fileName"] = 42
        invalid_values.append(malformed_name)
        with tempfile.TemporaryDirectory() as directory:
            for invalid in invalid_values:
                with self.subTest(manifest=invalid), self.assertRaises(ValueError):
                    replay.load_snapshot(self.write(directory, invalid, body))

    def test_rejects_incomplete_final_line(self):
        manifest, body = self.fixture()
        body = body.rstrip(b"\n")
        manifest["stages"][0].update(bytes=len(body), records=0, evidenceRecords=0, status="PARTIAL", sha256=hashlib.sha256(body).hexdigest())
        with tempfile.TemporaryDirectory() as directory, self.assertRaises(ValueError):
            replay.load_snapshot(self.write(directory, manifest, body))


class DiagnosticBundleInspectionTest(unittest.TestCase):
    session = "11111111-1111-1111-1111-111111111111"
    first_name = f"partial-diagnostics-{session}-part-1-22222222-2222-2222-2222-222222222222.zip"
    latest_name = f"diagnostics-{session}-part-2-33333333-3333-3333-3333-333333333333.zip"

    def archive_bytes(self, entries):
        output = io.BytesIO()
        with zipfile.ZipFile(output, "w") as archive:
            for name, value in entries.items():
                info = zipfile.ZipInfo(name, (2026, 1, 1, 0, 0, 0))
                info.compress_type = zipfile.ZIP_DEFLATED
                archive.writestr(info, value)
        return output.getvalue()

    def descriptor(self, name, body, number):
        return dict(fileName=name, archivePart=number, bytes=len(body), sha256=hashlib.sha256(body).hexdigest())

    def fixture(self, content="private-record-content"):
        body = json.dumps(dict(record=content)).encode() + b"\n"
        first, _ = StagedDiagnosticInspectionTest().fixture()
        first.update(sessionId=self.session, sourceIdentityHash="a" * 64, archivePart=1,
                     previousArchive=None, captureCompleted=False, retryRecommended=True)
        stage = first["stages"][0]
        stage.update(status="PARTIAL", bytes=len(body), sha256=hashlib.sha256(body).hexdigest())
        original = self.archive_bytes({stage["fileName"]: body, "manifest.json": json.dumps(first)})
        descriptor = self.descriptor(self.first_name, original, 1)
        latest = copy.deepcopy(first)
        latest.update(archivePart=2, previousArchive=descriptor, captureCompleted=True, retryRecommended=False)
        latest["stages"][0].update(status="COMPLETED", continuedFrom=dict(
            archive=descriptor, stage=stage["id"],
            **{key: stage[key] for key in ("fileName", "sha256", "records", "bytes")},
        ))
        current = self.archive_bytes({stage["fileName"]: body, "manifest.json": json.dumps(latest)})
        return {self.first_name: original, self.latest_name: current}

    def write(self, directory, parts, *, missing=None, manifest_change=None):
        missing = missing or []
        manifest = dict(
            schema=replay.BUNDLE_SCHEMA, sessionId=self.session, latestArchive=self.latest_name,
            captureCompleted=True, dependencyClosureComplete=not missing, shareComplete=not missing,
            capturedFully=False, missingParts=missing, dependencyListTruncated=False,
            parts=[self.descriptor(name, body, 1 if name == self.first_name else 2) for name, body in parts.items()],
        )
        if manifest_change:
            manifest_change(manifest)
        path = Path(directory) / "bundle.zip"
        path.write_bytes(self.archive_bytes(dict(parts, **{"manifest.json": json.dumps(manifest)})))
        return path

    def changed_part(self, parts, name, change):
        result = dict(parts)
        with zipfile.ZipFile(io.BytesIO(parts[name])) as archive:
            entries = {entry: archive.read(entry) for entry in archive.namelist()}
        manifest = json.loads(entries["manifest.json"])
        change(manifest)
        entries["manifest.json"] = json.dumps(manifest)
        result[name] = self.archive_bytes(entries)
        return result

    def test_inspects_nested_originals_with_verified_dependencies_and_bounded_streaming(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, self.fixture())
            with patch.object(zipfile.ZipFile, "read", side_effect=AssertionError("read complete parts only in test fixtures")):
                summary = replay.inspect_snapshot(replay.load_snapshot(path))
        self.assertEqual(replay.BUNDLE_INSPECTION_SCHEMA, summary["schema"])
        self.assertEqual("retained-bundle-parts-verified", summary["result"])
        self.assertEqual(2, summary["verifiedPartCount"])
        self.assertTrue(summary["dependencyClosureComplete"])
        self.assertFalse(summary["capturedFully"])
        self.assertEqual("not-verified", summary["taskCompletion"])
        self.assertEqual("unavailable", summary["providerDeltaReplay"])
        self.assertEqual("unavailable", summary["reducerReplay"])
        self.assertNotIn("private-record-content", json.dumps(summary))

    def test_missing_dependencies_keep_available_parts_and_actionable_partial_state(self):
        parts = self.fixture()
        parts.pop(self.first_name)
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, parts, missing=[dict(fileName=self.first_name, reason="ARCHIVE_UNAVAILABLE")])
            summary = replay.inspect_snapshot(replay.load_snapshot(path))
        self.assertEqual("partial", summary["result"])
        self.assertTrue(summary["captureCompleted"])
        self.assertFalse(summary["dependencyClosureComplete"])
        self.assertEqual(1, summary["verifiedPartCount"])
        self.assertEqual(self.first_name, summary["unresolvedDependencies"][0]["fileName"])
        self.assertIn("Obtain the missing", summary["recoveryAction"])

    def test_unreported_missing_part_cannot_claim_dependency_closure(self):
        parts = self.fixture()
        parts.pop(self.first_name)
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, "complete dependencies"):
            replay.load_snapshot(self.write(directory, parts))

    def test_rejects_outer_digest_size_duplicate_and_false_completion_claims(self):
        changes = [
            lambda root: root["parts"][0].update(sha256="0" * 64),
            lambda root: root["parts"][0].update(bytes=root["parts"][0]["bytes"] + 1),
            lambda root: root["parts"].append(root["parts"][0]),
            lambda root: root.update(capturedFully=True),
            lambda root: root.update(captureCompleted=False, shareComplete=False),
            lambda root: root.update(shareComplete=False),
            lambda root: root.update(latestArchive="missing.zip"),
        ]
        with tempfile.TemporaryDirectory() as directory:
            for index, change in enumerate(changes):
                with self.subTest(change=index), self.assertRaises(ValueError):
                    replay.load_snapshot(self.write(directory, self.fixture(), manifest_change=change))

    def test_rejects_inner_stage_corruption_even_after_outer_digest_is_updated(self):
        parts = self.changed_part(self.fixture(), self.latest_name, lambda root: root["stages"][0].update(sha256="0" * 64))
        with tempfile.TemporaryDirectory() as directory, self.assertRaisesRegex(ValueError, "stage digest"):
            replay.load_snapshot(self.write(directory, parts))

    def test_rejects_cross_session_cross_source_and_recursive_bundle_members(self):
        changes = [
            lambda root: root.update(sessionId="44444444-4444-4444-4444-444444444444"),
            lambda root: root.update(sourceIdentityHash="b" * 64),
            lambda root: root.update(schema=replay.BUNDLE_SCHEMA),
        ]
        with tempfile.TemporaryDirectory() as directory:
            for index, change in enumerate(changes):
                parts = self.changed_part(self.fixture(), self.latest_name, change)
                with self.subTest(change=index), self.assertRaisesRegex(ValueError, "source|schema"):
                    replay.load_snapshot(self.write(directory, parts))

    def test_rejects_traversal_members_cycles_and_wrong_stage_references(self):
        changes = [
            lambda root: root["previousArchive"].update(fileName="../outside.zip"),
            lambda root: root["previousArchive"].update(fileName=self.latest_name, archivePart=2),
            lambda root: root["stages"][0]["continuedFrom"].update(sha256="f" * 64),
        ]
        with tempfile.TemporaryDirectory() as directory:
            for index, change in enumerate(changes):
                parts = self.changed_part(self.fixture(), self.latest_name, change)
                with self.subTest(change=index), self.assertRaisesRegex(ValueError, "complete dependencies"):
                    replay.load_snapshot(self.write(directory, parts))
            with self.assertRaisesRegex(ValueError, "Invalid or duplicate"):
                replay.load_snapshot(self.write(directory, self.fixture(), manifest_change=lambda root: root["parts"][0].update(fileName="../outside.zip")))
            self.assertFalse((Path(directory).parent / "outside.zip").exists())

    def test_enforces_cumulative_inflation_and_part_count_limits(self):
        parts = self.fixture(content="a" * 20000)
        expanded = []
        for body in parts.values():
            with zipfile.ZipFile(io.BytesIO(body)) as archive:
                expanded.append(sum(entry.file_size for entry in archive.infolist()))
        limit = max(expanded) + min(expanded) // 2
        self.assertGreater(limit, sum(map(len, parts.values())))
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, parts)
            with patch.object(replay, "MAX_BUNDLE_BYTES", limit), self.assertRaisesRegex(ValueError, "cumulative expanded"):
                replay.load_snapshot(path)
            path = self.write(directory, self.fixture(), manifest_change=lambda root: root.update(parts=[root["parts"][0]] * 65))
            with self.assertRaisesRegex(ValueError, "dependency inventory"):
                replay.load_snapshot(path)

    def test_cli_accepts_final_share_and_reports_only_metadata(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write(directory, self.fixture())
            result = subprocess.run([sys.executable, str(Path(__file__).parents[1] / "replay_conversation_debug.py"), str(path)],
                                    check=False, capture_output=True, text=True)
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertEqual("retained-bundle-parts-verified", json.loads(result.stdout)["result"])
        self.assertNotIn("private-record-content", result.stdout)


if __name__ == "__main__":
    unittest.main()
