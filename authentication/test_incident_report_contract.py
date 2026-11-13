import copy
import json
from pathlib import Path
import re
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
            ("dispatch_policy_sections", ["profile.secretRef"]), ("dispatch_policy_stage", "private-stage"),
            ("dispatch_policy_resume_required", "true"),
        ]
        for key, value in invalid:
            with self.subTest(key=key):
                capture = self.capture(values={key: value})
                with self.assertRaises(ValueError):
                    validate_diagnostics(capture)
                with self.assertRaises(ReportError):
                    issue_from_report(self.report(capture))

    def test_dispatch_policy_diagnostics_keep_closed_categories(self):
        capture = self.capture("INCIDENT", {"dispatch_policy_sections": ["PROFILE", "TOOLS", "PERMISSIONS"],
                                            "dispatch_policy_stage": "delivery", "dispatch_policy_resume_required": True,
                                            "changed_policy_fields": ["profile.apiUrl", "profile.secretRef", "providerPermissions", "tools"],
                                            "changed_policy_field_count": 5, "changed_policy_unknown_count": 1,
                                            "changed_policy_malformed_count": 0, "changed_policy_omitted_count": 0,
                                            "changed_policy_observation": "PARTIAL"})
        approved = json.dumps(capture, separators=(",", ":"))
        self.assertEqual(capture, validate_diagnostics(capture))
        issue_from_report(self.report(capture))
        self.assertEqual(approved, json.dumps(capture, separators=(",", ":")))

    def test_execution_outcome_observations_are_bounded_and_approved_bytes_are_preserved(self):
        for area, fields in (
            ("TOOL", {"tool_budget_kind": "RESULT_BYTES", "tool_budget_phase": "AFTER_DISPATCH",
                      "tool_execution_observation": "REMOTE_READ_SUCCEEDED", "tool_result_bytes_used": 24576,
                      "tool_result_bytes_limit": 24576}),
            ("TOOL", {"tool_permission_scope": "ANDROID_LOCATION"}),
            ("PROVIDER", {"provider_rejection_category": "BILLING_UNAVAILABLE", "gateway_terminal_status": "failed"}),
            ("INCIDENT", {"combined_requirement_gap": "TOPIC", "combined_issue_count": 1}),
        ):
            with self.subTest(area=area, fields=fields):
                report = self.report(self.capture(area, fields))
                if area == "INCIDENT":
                    report["errorCode"] = "COMBINED_REQUIREMENTS_UNMET"
                before = json.dumps(report, separators=(",", ":"))
                issue_from_report(report)
                self.assertEqual(before, json.dumps(report, separators=(",", ":")))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture(values={"tool_result_bytes_used": 2, "tool_result_bytes_limit": 1}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture(values={"tool_permission_scope": "PRIVATE_PERMISSION"}))

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


    def test_run_failure_evidence_accepts_only_closed_values_and_stays_in_the_capsule(self):
        examples = (
            ("TOOL", {"semantic_read_rules": ["LINE_WINDOW"], "validation_field_names": ["ranges", "end_line"],
                      "dependency_admission_reasons": ["CALL_ALLOWANCE"], "saved_view_restore_state": "CURSOR_INDEX_MISSING",
                      "url_reader_dns_state": "RECOVERY_COOLDOWN"}),
            ("INCIDENT", {"incident_capture_site": "CHAT_REPOSITORY_PREFLIGHT", "incident_origin": "APPLICATION",
                          "local_readiness_failure": "PENDING_CLEANUP", "pending_tool_count": 2,
                          "pending_tool_identity_hashes": ["a" * 64], "pending_tool_receipt_state": "SOME_TERMINAL_RECEIPTS_MISSING"}),
            ("APP", {"crash_surface": "CHAT", "crash_boundary": "ANDROID_VIEW_DRAW"}),
            ("ACCEPTANCE", {"fixture_approval_state": "READY", "fixture_decision_mode": "AUTO_APPROVE", "branch_protection_observation": "ACCESS_DENIED",
                            "branch_rules_observation": "OBSERVED"}),
            ("PROVIDER", {"provider_wait_state": "RESUMED", "provider_wait_terminal_status": "COMPLETED",
                          "provider_wait_claim_hash": "b" * 64, "provider_wait_checkpoint_hash": "c" * 64, "provider_route_hash": "d" * 64}),
        )
        for area, values in examples:
            with self.subTest(area=area):
                capture = self.capture(area, values)
                self.assertEqual(capture, validate_diagnostics(capture))
                self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)
        for key in ("semantic_read_rules", "dependency_admission_reasons"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {key: ["private-value"]}))

    def test_rate_limit_scheduling_is_an_observation_without_replay_authority(self):
        capture = self.capture("PROVIDER", {"incident_phase": "provider_wait", "causal_provenance": "MODEL_INVOCATION",
                                            "causal_failure_code": "PROVIDER_RATE_LIMITED", "provider_wait_state": "WAITING_PROVIDER_LIMIT",
                                            "provider_wait_persisted": True, "recovery_observation": "RETRY_SCHEDULED", "retry_delay_ms": 30_228,
                                            "retry_delay_source": "PROVIDER_HEADER", "recovery_authority": "VALID"})
        validate_diagnostics(capture)
        self.assertNotIn("request_replay_allowed", capture["values"])
        self.assertNotIn("logical_continuation_allowed", capture["values"])

    def test_policy_change_evidence_validates_tool_and_chat_without_rewriting_or_granting_replay(self):
        observed = {"changed_policy_fields": ["budget", "profile.enabled", "tool_inventory"], "changed_policy_field_count": 4,
                    "changed_policy_unknown_count": 0, "changed_policy_malformed_count": 0, "changed_policy_omitted_count": 0,
                    "changed_policy_observation": "OBSERVED"}
        variants = [observed,
                    {**observed, "changed_policy_field_count": 6, "changed_policy_unknown_count": 1, "changed_policy_malformed_count": 1,
                     "changed_policy_observation": "PARTIAL"},
                    {"changed_policy_observation": "MALFORMED"},
                    {"changed_policy_observation": "MALFORMED", "changed_policy_field_count": 257},
                    {key: value for key, value in {**observed, "changed_policy_field_count": 0}.items() if key != "changed_policy_fields"}]
        for area in ("TOOL", "INCIDENT"):
            for fields in variants:
                with self.subTest(area=area, fields=fields):
                    capture = self.capture(area, fields)
                    report = self.report(capture)
                    if area == "INCIDENT":
                        report.update(feature="chat", errorCode="RECONCILIATION_REQUIRED")
                        report["context"].update(source="CHAT_FAILURE", requiresReconciliation=True)
                        report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                    approved = json.dumps(report, separators=(",", ":"))
                    issue_from_report(report)
                    self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                    self.assertNotIn("request_replay_allowed", capture["values"])
                    self.assertNotIn("dispatched", report["context"])
                    self.assertNotIn("remoteAcceptance", report["context"])

    def test_policy_names_and_count_alignment_reject_private_or_incoherent_public_payloads(self):
        fields = {"changed_policy_fields": ["budget", "profile.enabled", "tool_inventory"], "changed_policy_field_count": 3,
                  "changed_policy_unknown_count": 0, "changed_policy_malformed_count": 0, "changed_policy_omitted_count": 0,
                  "changed_policy_observation": "OBSERVED"}
        bad = [{**fields, "changed_policy_fields": ["profile.PRIVATE_SCOPE"]},
               {**fields, "changed_policy_fields": ["budget"] * 3},
               {**fields, "changed_policy_fields": sorted(capsule.POLICY_FIELD_NAMES)[:9], "changed_policy_field_count": 9},
               {**fields, "changed_policy_unknown_count": True}, {**fields, "changed_policy_unknown_count": 1},
               {**fields, "changed_policy_field_count": 1}, {**fields, "changed_policy_observation": "PARTIAL"},
               {**fields, "changed_policy_field_count": 4, "changed_policy_omitted_count": 1, "changed_policy_observation": "PARTIAL"},
               {**fields, "changed_policy_observation": "MALFORMED"},
               {key: value for key, value in fields.items() if key != "changed_policy_observation"},
               {key: value for key, value in fields.items() if key != "changed_policy_unknown_count"}]
        for values in bad:
            with self.subTest(values=values):
                with self.assertRaises(ReportError):
                    issue_from_report(self.report(self.capture(values=values)))

    def test_policy_field_vocabulary_matches_native_and_actual_production_producers(self):
        root = Path(__file__).resolve().parents[1]
        base = root / "app/src/main/kotlin/dev/chungjungsoo/gptmobile"
        source = (base / "data/diagnostics/IssueReportIncidentCapsule.kt").read_text()
        names = re.search(r"val policyFieldNames = setOf\((.*?)\n    \)", source, re.DOTALL)
        self.assertIsNotNone(names)
        self.assertEqual(set(re.findall(r'"([^"\n]+)"', names.group(1))), capsule.POLICY_FIELD_NAMES)
        authority = (base / "data/context/ConfiguredDispatchAuthority.kt").read_text().split("internal fun policyFields", 1)[1].split("\n}", 1)[0]
        policy_keys = set(re.findall(r'put\("([^"\n]+)"', authority))
        profile = (base / "data/database/entity/PlatformV2.kt").read_text().split("data class PlatformV2(", 1)[1].split(") : Parcelable", 1)[0]
        profile_keys = {"profile." + name for name in re.findall(r"\bval (\w+):", profile)}
        resolver = (base / "data/agent/tool/AgentToolResolver.kt").read_text()
        self.assertIn('"tool_inventory"', resolver)
        self.assertEqual(policy_keys | profile_keys | {"profile.removed", "tool_inventory", "providerPermissions"}, capsule.POLICY_FIELD_NAMES)
        self.assertEqual(56, len(capsule.POLICY_FIELD_NAMES))

    def test_exception_capture_survives_validation_without_claiming_provider_dispatch(self):
        for site in ("CHAT_REPOSITORY_TERMINAL_CATCH", "OPENAI_ADAPTER_STREAM_CATCH"):
            with self.subTest(site=site):
                capture = self.capture("INCIDENT", {
                    "incident_phase": "unknown", "causal_provenance": "UNAVAILABLE",
                    "causal_failure_code": "CHAT_FAILED", "incident_evidence_status": "CAUSAL_EVIDENCE_UNAVAILABLE",
                    "incident_capture_site": site, "incident_phase_provenance": "CAPTURE_BOUNDARY_ONLY",
                    "exception_type_hashes": ["d" * 64, "e" * 64],
                })
                del capture["values"]["causal_id_hash"]
                capture["unavailableFields"] = sorted((CORE_FIELDS["INCIDENT"] | capsule.CORE) - capture["values"].keys())
                report = self.report(capture)
                report.update(feature="chat", errorCode="CHAT_FAILED")
                report["context"].update(source="CHAT_FAILURE", provider="OPENROUTER", runIdHash="b" * 64, exceptionFingerprint="c" * 64)
                report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertIn(site, issue["body"])
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                self.assertTrue({"stage", "invocationIdHash", "httpStatus", "dispatched", "retryable", "remoteAcceptance"}.issubset(report["context"]["unavailableFields"]))
                for key, value in (("incident_capture_site", "private-site"), ("incident_phase_provenance", "INFERRED_DISPATCH"), ("exception_type_hashes", ["private-error"])):
                    invalid = copy.deepcopy(report)
                    invalid["context"]["diagnostics"]["values"][key] = value
                    with self.assertRaises(ReportError):
                        issue_from_report(invalid)

    def test_exception_capture_vocabulary_matches_native_contract(self):
        source = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/IssueReportIncidentCapsule.kt").read_text()
        sites = re.search(r"enum class ExceptionCaptureSite\s*\{([^}]+)\}", source)
        self.assertIsNotNone(sites)
        self.assertEqual(set(re.findall(r"[A-Z][A-Z_]+", sites.group(1))), capsule.ENUMS["incident_capture_site"])
        provenance = re.search(r'"incident_phase_provenance"\s+to\s+setOf\(([^)]+)\)', source)
        self.assertIsNotNone(provenance)
        self.assertEqual(set(re.findall(r'"([^"\n]+)"', provenance.group(1))), capsule.ENUMS["incident_phase_provenance"])


if __name__ == "__main__":
    unittest.main()
