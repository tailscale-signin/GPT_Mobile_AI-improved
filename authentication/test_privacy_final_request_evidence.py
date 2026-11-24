"""Closed final-wire refusal reports; these tests make no provider requests."""

import copy
import json
from pathlib import Path
import re
import unittest

from fastapi.testclient import TestClient

from authentication import incident_report_contract as capsule
from authentication.issue_report_app import create_app
from authentication.issue_report_diagnostics import (
    CORE_FIELDS,
    MAX_DIAGNOSTIC_BYTES,
    PRIVACY_FINAL_REQUEST_CODE,
    PRIVACY_FINAL_REQUEST_OUTCOMES,
    validate_diagnostics,
)
from authentication.issue_reports import CONTEXT_FIELDS_V5, issue_from_report
from scripts.check_issue_reporting_configuration import (
    MAX_READINESS_BYTES,
    REQUIRED_DIAGNOSTIC_FEATURES,
    check_readiness,
)


FEATURE = "PRIVACY_FINAL_REQUEST_EVIDENCE_V1"
FIELD = "privacy_dispatch_outcome"


class PrivacyFinalRequestEvidenceTest(unittest.TestCase):
    def capture(self, values=None, area="PROVIDER"):
        values = {
            "incident_phase": "request_validation",
            "causal_provenance": "UNAVAILABLE",
            "incident_evidence_status": "CAUSAL_EVIDENCE_UNAVAILABLE",
            **(values or {}),
        }
        return {
            "schemaVersion": 3, "area": area, "capturedAtMillis": 1_800_000_000_000,
            "captureMode": "INCIDENT", "values": values,
            "unavailableFields": sorted((CORE_FIELDS[area] | capsule.CORE) - values.keys()),
        }

    def fields(self, outcome="KNOWN_VALUE_REINTRODUCED"):
        return {
            "provider_failure_code": PRIVACY_FINAL_REQUEST_CODE,
            "provider_failure_stage": "ADMISSION",
            "provider_failure_domain": "APPLICATION_VERIFICATION",
            FIELD: outcome,
            "request_replay_allowed": False,
            "logical_continuation_allowed": False,
        }

    def report(self, capture, **context_fields):
        context = {
            "schemaVersion": 7, "occurredAtMillis": 1_800_000_000_000,
            "source": "CHAT_FAILURE", "diagnostics": capture, **context_fields,
        }
        context["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - context.keys())
        return {
            "reportId": "11111111-1111-4111-8111-111111111111", "source": "debug",
            "feature": "chat", "errorCode": PRIVACY_FINAL_REQUEST_CODE,
            "version": "1.0", "build": 1, "commit": "abcdef0", "android": "16",
            "sdk": 36, "device": "Fixture", "context": context,
        }

    def test_all_four_refusal_outcomes_roundtrip_without_overwriting_prior_unknown_work(self):
        for outcome in PRIVACY_FINAL_REQUEST_OUTCOMES:
            with self.subTest(outcome=outcome):
                capture = self.capture(self.fields(outcome))
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)
                report = self.report(capture, stage="request_validation", dispatched=True,
                                     remoteAcceptance="UNKNOWN", requiresReconciliation=True)
                original = copy.deepcopy(report)
                _, issue = issue_from_report(report)
                self.assertEqual(original, report)
                self.assertIn(f"| {FIELD} | `{outcome}` |", issue["body"])
                self.assertIn(PRIVACY_FINAL_REQUEST_CODE, issue["title"])
                self.assertIs(report["context"]["dispatched"], True)
                self.assertEqual("UNKNOWN", report["context"]["remoteAcceptance"])
                self.assertIs(report["context"]["requiresReconciliation"], True)

    def test_unknown_success_malformed_and_private_receipt_values_are_rejected(self):
        for invalid in ("KNOWN_VALUES_ABSENT", "PRIVATE_OUTCOME", "KNOWN_VALUE_REINTRODUCED PRIVATE_VALUE",
                        True, False, 1, None, [], {}):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                validate_diagnostics(self.capture(self.fields(invalid)))
        for field, value in (
            ("privacy_receipt", {"outcome": "KNOWN_VALUE_REINTRODUCED", "private_request_id": "PRIVATE_ID"}),
            ("payload_hmac", "PRIVATE_HMAC"), ("private_request_id", "PRIVATE_ID"),
            ("known_private_values", ["PRIVATE_VALUE"]),
            ("request_replay_allowed", "false"), ("logical_continuation_allowed", "false"),
        ):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_diagnostics(self.capture({**self.fields(), field: value}))

    def test_outcome_requires_its_own_code_admission_domain_and_provider_area(self):
        for key, invalid in (
            ("provider_failure_code", "HTTP_ERROR"),
            ("provider_failure_code", "PRIVACY_PROVIDER_INELIGIBLE"),
            ("provider_failure_stage", "GENERATION"),
            ("provider_failure_domain", "PROVIDER"),
        ):
            with self.subTest(key=key, invalid=invalid), self.assertRaises(ValueError):
                validate_diagnostics(self.capture({**self.fields(), key: invalid}))
        for key in ("provider_failure_code", "provider_failure_stage", "provider_failure_domain"):
            fields = self.fields()
            del fields[key]
            with self.subTest(missing=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture(fields))
        for area in ("TOOL", "ACCEPTANCE", "VOICE", "GATEWAY", "APP", "INCIDENT"):
            with self.subTest(area=area), self.assertRaises(ValueError):
                validate_diagnostics(self.capture({FIELD: "KNOWN_VALUE_REINTRODUCED"}, area=area))

    def test_closed_code_survives_existing_public_cause_and_event_surfaces(self):
        for field, area in (("provider_failure_code", "PROVIDER"), ("causal_failure_code", "INCIDENT"),
                            ("voice_chat_failure_code", "VOICE")):
            capture = self.capture({field: PRIVACY_FINAL_REQUEST_CODE}, area=area)
            self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
            report = self.report(capture)
            report["events"] = ["-1ms E chat " + PRIVACY_FINAL_REQUEST_CODE]
            original = copy.deepcopy(report)
            _, issue = issue_from_report(report)
            self.assertEqual(original, report)
            self.assertIn(PRIVACY_FINAL_REQUEST_CODE, issue["body"])

    def test_extended_readiness_and_probe_require_the_distinct_new_capability(self):
        client = TestClient(create_app("https://reports.project.org", None, b"synthetic-rate-key"),
                            base_url="https://reports.project.org")
        legacy = client.get("/health/ready")
        extended = client.get("/health/ready?capabilities=extended")
        self.assertEqual(200, legacy.status_code)
        self.assertEqual(200, extended.status_code)
        self.assertLessEqual(len(legacy.content), 1024)
        self.assertLessEqual(len(extended.content), MAX_READINESS_BYTES)
        self.assertNotIn(FEATURE, legacy.json()["reportDiagnosticFeatures"])
        self.assertIn(FEATURE, extended.json()["reportDiagnosticFeatures"])
        self.assertIn("PRIVACY_ELIGIBILITY_EVIDENCE_V1", extended.json()["reportDiagnosticFeatures"])
        self.assertIn(FEATURE, REQUIRED_DIAGNOSTIC_FEATURES)
        check_readiness("https://reports.project.org", lambda _: extended.content)
        missing = extended.json()
        missing["reportDiagnosticFeatures"].remove(FEATURE)
        with self.assertRaisesRegex(ValueError, "reportDiagnosticFeatures"):
            check_readiness("https://reports.project.org", lambda _: json.dumps(missing, separators=(",", ":")).encode())

    def test_existing_privacy_eligibility_values_still_validate_independently(self):
        capture = self.capture({"provider_failure_code": "PRIVACY_PROVIDER_INELIGIBLE",
                                "privacy_reason_code": "VERIFICATION_UNAVAILABLE", "privacy_status": "NOT_VERIFIED"})
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        self.assertNotIn(FIELD, capture["values"])
        report = self.report(capture)
        report["errorCode"] = "PRIVACY_PROVIDER_INELIGIBLE"
        _, issue = issue_from_report(report)
        self.assertIn("VERIFICATION_UNAVAILABLE", issue["body"])

    def test_receiver_outcome_set_matches_the_android_closed_projection(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/PrivacyFinalRequestFailureEvidence.kt").read_text()
        match = re.search(r"val outcomes = setOf\(([^)]*)\)", source)
        self.assertIsNotNone(match)
        android = set(re.findall(r'"([A-Z_]+)"', match.group(1)))
        self.assertEqual({"POLICY_REVISION_MISMATCH", "KNOWN_VALUE_REINTRODUCED", "NO_SUPPORTED_TEXT_SURFACES",
                          "WIRE_VALIDATION_UNAVAILABLE"}, android)
        self.assertEqual(android, PRIVACY_FINAL_REQUEST_OUTCOMES)
        self.assertIn(f'const val CODE = "{PRIVACY_FINAL_REQUEST_CODE}"', source)
        self.assertIn(f'const val FEATURE = "{FEATURE}"', source)
