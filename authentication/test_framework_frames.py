"""Approved framework symbols retain strict report and capability boundaries."""
import unittest
from uuid import uuid4

from fastapi.testclient import TestClient

from authentication.issue_report_app import create_app
from authentication.issue_reports import ReportError, issue_from_report


class FrameworkFramesTest(unittest.TestCase):
    def report(self, frames):
        return {"reportId": str(uuid4()), "source": "debug", "version": "1.0", "build": 1,
                "commit": "abcdef0", "android": "16", "sdk": 36, "device": "Fixture",
                "feature": "app", "errorCode": "JVM_CRASH", "errorType": "NoSuchElementException",
                "frames": frames, "events": []}

    def test_compose_collection_and_coroutine_frames_cross_the_actual_report_validator(self):
        frames = ["androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNodeImpl.onPointerEvent-H0pRuoY:100",
                  "androidx.collection.IntList.first:200", "kotlinx.coroutines.flow.FlowKt__ReduceKt.first:300"]
        report = self.report(frames)
        report_id, issue = issue_from_report(report)
        self.assertEqual(report["reportId"], report_id)
        for frame in frames:
            self.assertIn("    " + frame, issue["body"])
        self.assertEqual(frames, report["frames"])

    def test_namespace_symbol_and_existing_count_limits_remain_closed(self):
        invalid = ["androidxevil.collection.Reader.read:1", "kotlinx.coroutinesExtra.Reader.read:1",
                   "kotlinx.serialization.Reader.read:1", "example.customer.Reader.read:1",
                   "androidx.compose.Reader.private value:1", "androidx.compose.Reader.read\nprivate:1",
                   "androidx.compose.Reader.read/secret:1", "androidx.compose.Reader.read-../secret:1",
                   "androidx.compose.Reader.read-\nprivate:1", "androidx.compose.Reader-secret.read:1", "androidx.compose.Reader.read:1000000",
                   "androidx." + "a" * 144 + ".m:1"]
        for frame in invalid:
            with self.subTest(frame=frame), self.assertRaises(ReportError) as error:
                issue_from_report(self.report([frame]))
            self.assertEqual("INVALID_REPORT", error.exception.code)
        boundary = "androidx." + "a" * 143 + ".m:999999"
        self.assertIn(boundary, issue_from_report(self.report([boundary]))[1]["body"])
        with self.assertRaises(ReportError):
            issue_from_report(self.report(["androidx.collection.IntList.first:1"] * 9))

    def test_framework_support_is_advertised_only_in_bounded_extended_readiness(self):
        app = create_app("https://reports.example.invalid", object(), b"test-rate-limit-material")
        with TestClient(app, base_url="https://reports.example.invalid") as client:
            legacy = client.get("/health/ready")
            extended = client.get("/health/ready?capabilities=extended")
        self.assertEqual(200, legacy.status_code)
        self.assertEqual(200, extended.status_code)
        feature = "FRAMEWORK_EXCEPTION_FRAMES_V1"
        self.assertNotIn(feature, legacy.json()["reportDiagnosticFeatures"])
        self.assertIn(feature, extended.json()["reportDiagnosticFeatures"])
        self.assertEqual(2, extended.json()["version"])
        self.assertLessEqual(len(legacy.content), 1024)
        self.assertLessEqual(len(extended.content), 4096)


if __name__ == "__main__":
    unittest.main()
