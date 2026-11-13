import copy
import json
import unittest

from authentication import incident_report_contract as capsule
from authentication.issue_report_diagnostics import CORE_FIELDS, MAX_DIAGNOSTIC_BYTES, validate_diagnostics
from authentication.issue_reports import CONTEXT_ENUMS_V6, CONTEXT_ENUMS_V7, CONTEXT_FIELDS_V5, ReportError, issue_from_report


class IncidentReportContractTest(unittest.TestCase):
    def capture(self, area="TOOL", values=None):
        values = {
            "incident_phase": "tool_precheck", "causal_provenance": "TOOL_CALL", "causal_id_hash": "a" * 64,
            "causal_failure_code": "INVALID_ARGUMENTS", "incident_evidence_status": "TYPED_EVIDENCE", **(values or {}),
        }
        return {"schemaVersion": 3, "area": area, "capturedAtMillis": 1_800_000_000_000, "captureMode": "INCIDENT",
                "values": values, "unavailableFields": sorted((CORE_FIELDS[area] | capsule.CORE) - values.keys())}

    def report(self, capture):
        context = {"schemaVersion": 7, "occurredAtMillis": 1_800_000_000_000, "source": "TOOL_RESULT", "diagnostics": capture}
        context["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - context.keys())
        return {"reportId": "11111111-1111-4111-8111-111111111111", "source": "debug", "feature": "tools", "errorCode": "INVALID_ARGUMENTS",
                "version": "1.0", "build": 1, "commit": "abcdef0", "android": "16", "sdk": 36, "device": "Fixture", "context": context}

    def test_typed_capsule_validates_without_rewriting_approved_input(self):
        capture = self.capture(values={
            "native_tool_action": "create", "validation_field_names": ["issue_number"], "validation_path_hashes": ["b" * 64],
            "argument_field_names": ["action", "issue_number"], "argument_field_types": ["STRING", "STRING"],
            "unknown_field_count": 1, "unknown_field_hashes": ["c" * 64], "saved_view_scope_hash": "d" * 64,
            "saved_view_owner_operation": "github_issue", "saved_view_cursor_issued": True,
            "github_error_resources": ["PullRequest"], "github_error_fields": ["head"], "github_error_codes": ["invalid"],
        })
        report = self.report(capture)
        approved = json.dumps(report, separators=(",", ":"))
        self.assertEqual(capture, validate_diagnostics(capture))
        _, issue = issue_from_report(report)
        self.assertIn("issue_number", issue["body"])
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)

    def test_arbitrary_private_extensions_bad_types_and_misaligned_shapes_are_rejected(self):
        invalid = [
            ("raw_arguments", {"body": "private-secret"}), ("native_tool_action", "private-secret"),
            ("validation_field_names", ["private-secret"]), ("github_error_fields", ["private-secret"]),
            ("github_error_codes", ["private-secret"]), ("unknown_field_hashes", ["private-secret"]),
            ("provider_wait_persisted", "true"), ("recovery_attempt_count", True),
            ("causal_failure_code", "PRIVATE_ACCOUNT_TOKEN"), ("argument_field_names", ["action"]),
        ]
        for key, value in invalid:
            with self.subTest(key=key):
                capture = self.capture(values={key: value})
                with self.assertRaises(ValueError):
                    validate_diagnostics(capture)
                with self.assertRaises(ReportError):
                    issue_from_report(self.report(capture))

    def test_capsule_requires_context_seven_and_old_approved_versions_remain_valid(self):
        report = self.report(self.capture())
        report["context"]["schemaVersion"] = 6
        with self.assertRaises(ReportError):
            issue_from_report(report)
        for version in (5, 6):
            legacy = copy.deepcopy(report)
            legacy["context"]["schemaVersion"] = version
            legacy["context"]["diagnostics"] = {"schemaVersion": 1, "area": "TOOL", "capturedAtMillis": 1_800_000_000_000,
                                                   "captureMode": "INCIDENT", "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"])}
            before = json.dumps(legacy, separators=(",", ":"))
            issue_from_report(legacy)
            self.assertEqual(before, json.dumps(legacy, separators=(",", ":")))
            legacy["context"]["schemaVersion"] = 7
            with self.assertRaises(ReportError):
                issue_from_report(legacy)


    def test_voice_reply_context_stages_match_the_context_seven_client_contract(self):
        for stage in ("voice_reply", "voice_response_wait"):
            with self.subTest(stage=stage):
                self.assertIn(stage, CONTEXT_ENUMS_V7["stage"])
                self.assertNotIn(stage, CONTEXT_ENUMS_V6["stage"])
                capture = self.capture("VOICE", {
                    "incident_phase": stage,
                    "causal_provenance": "EXPLICIT_CAUSE",
                    "causal_failure_code": "VOICE_SESSION_FAILED",
                    "voice_reply_failed": True,
                    "voice_reply_failure_evidence": "MISSING_FAILURE_RECORD",
                })
                report = self.report(capture)
                report.update(feature="voice", errorCode="VOICE_SESSION_FAILED")
                report["context"].update(source="VOICE_STATE", stage=stage)
                report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertIn(f"| Failure stage | `{stage}` |", issue["body"])
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))


    def test_rate_limit_scheduling_is_an_observation_without_replay_authority(self):
        capture = self.capture("PROVIDER", {"incident_phase": "provider_wait", "causal_provenance": "MODEL_INVOCATION",
                                            "causal_failure_code": "PROVIDER_RATE_LIMITED", "provider_wait_state": "WAITING_PROVIDER_LIMIT",
                                            "provider_wait_persisted": True, "recovery_observation": "RETRY_SCHEDULED", "retry_delay_ms": 30_228,
                                            "retry_delay_source": "PROVIDER_HEADER", "recovery_authority": "VALID"})
        validate_diagnostics(capture)
        self.assertNotIn("request_replay_allowed", capture["values"])
        self.assertNotIn("logical_continuation_allowed", capture["values"])


if __name__ == "__main__":
    unittest.main()
