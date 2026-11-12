import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import tempfile
import unittest
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


if __name__ == "__main__":
    unittest.main()
