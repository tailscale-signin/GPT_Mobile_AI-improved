import copy
import hashlib
import json
from pathlib import Path
import re
import unittest

from authentication import incident_report_contract as capsule
from authentication.issue_report_diagnostics import CORE_FIELDS, GATEWAY_CAPABILITY_DECODE_REASONS, GATEWAY_CAPABILITY_FAILURE_KINDS, GATEWAY_CAPABILITY_FIELDS, MAX_DIAGNOSTIC_BYTES, PRIVACY_ELIGIBILITY_REASONS, validate_diagnostics
from authentication.issue_reports import CONTEXT_ENUMS_V6, CONTEXT_ENUMS_V7, CONTEXT_FIELDS_V5, ReportError, issue_from_report


class IncidentReportContractTest(unittest.TestCase):
    def test_voice_execution_and_original_fallback_failure_survive_system_engine_change(self):
        values = {
            "voice_engine": "SYSTEM", "voice_phase": "SPEAKING",
            "voice_selected_backend": "QNN_HTP", "voice_effective_provider": "SYSTEM",
            "voice_fallback_source": "QNN_HTP", "voice_fallback_target": "SYSTEM",
            "voice_fallback_outcome": "SUCCEEDED", "voice_fallback_failure": "SYNTHESIS_FAILED",
            "voice_fallback_count": 1, "voice_playback_failure": "SYNTHESIS_FAILED",
        }
        capture = self.capture("VOICE", values)
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        report = self.report(capture)
        approved = json.dumps(report, separators=(",", ":"))
        _, issue = issue_from_report(report)
        self.assertIn("| voice_engine | `SYSTEM` |", issue["body"])
        self.assertIn("| voice_fallback_failure | `SYNTHESIS_FAILED` |", issue["body"])
        self.assertIn("| voice_fallback_outcome | `SUCCEEDED` |", issue["body"])
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        for field in capsule.VOICE_EXECUTION_FIELDS:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("VOICE", {**values, field: "private speech or request-id"}))
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {field: values[field]}))
        for field in ("speech_text", "voice_provider_request_id", "voice_native_exception"):
            with self.subTest(field=field), self.assertRaises(ReportError):
                issue_from_report(self.report(self.capture("VOICE", {**values, field: "private-canary"})))

    def test_voice_execution_count_and_enums_are_closed_and_match_native_contract(self):
        for field, choices in capsule.VOICE_EXECUTION_ENUMS.items():
            for choice in choices:
                self.assertTrue(capsule.valid_value(field, choice), (field, choice))
            for invalid in (True, 1, None, [], {}, "private-canary"):
                self.assertFalse(capsule.valid_value(field, invalid), (field, invalid))
        for count in (0, 1, 100):
            self.assertTrue(capsule.valid_value("voice_fallback_count", count))
        for invalid in (-1, 101, "1", 1.0, True, None):
            self.assertFalse(capsule.valid_value("voice_fallback_count", invalid))
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture("VOICE", {"voice_fallback_count": invalid}))
        source = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/VoiceExecutionDiagnostics.kt").read_text()
        for field, choices in capsule.VOICE_EXECUTION_ENUMS.items():
            if field == "voice_fallback_failure":
                match = re.search(r"val playbackFailures = setOf\((.*?)\n    \)", source, re.S)
            else:
                match = re.search(r'"' + field + r'" to setOf\(([^)]+)\)', source)
            self.assertIsNotNone(match, field)
            self.assertEqual(choices, set(re.findall(r'"([^"\n]+)"', match.group(1))))

    def test_gateway_fallback_preserves_remote_source_after_system_recovery(self):
        values = {
            "voice_engine": "SYSTEM", "voice_phase": "SPEAKING",
            "voice_selected_backend": "GATEWAY_CUDA", "voice_effective_provider": "SYSTEM",
            "voice_fallback_source": "GATEWAY_CUDA", "voice_fallback_target": "SYSTEM",
            "voice_fallback_outcome": "SUCCEEDED", "voice_fallback_failure": "SYNTHESIS_FAILED",
            "voice_fallback_count": 1,
        }
        capture = self.capture("VOICE", values)
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        report = self.report(capture)
        approved = json.dumps(report, separators=(",", ":"))
        _, issue = issue_from_report(report)
        self.assertIn("| voice_fallback_source | `GATEWAY_CUDA` |", issue["body"])
        self.assertIn("| voice_effective_provider | `SYSTEM` |", issue["body"])
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        for unclosed in ("gateway-cuda", "CUDA", "https://private.gateway.example/voice?request=private-id"):
            with self.subTest(unclosed=unclosed), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("VOICE", {**values, "voice_fallback_source": unclosed}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("VOICE", {**values, "voice_fallback_target": "GATEWAY_CUDA"}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", {"voice_fallback_source": "GATEWAY_CUDA"}))

    def test_search_argument_rule_survives_submission_without_raw_query_or_exception(self):
        values = {
            "search_argument_field": "query", "search_argument_rule": "FUZZY_IDENTIFIER",
            "search_argument_expected": "IDENTIFIER_2_80", "search_native_action": "search_repository",
            "search_mode": "literal", "search_query_hash": "e" * 64,
        }
        capture = self.capture("TOOL", values)
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        self.assertIn("FUZZY_IDENTIFIER", str(issue_from_report(self.report(capture))))
        for key in values:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {**values, key: "private query or exception"}))
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("INCIDENT", {key: values[key]}))
        self.assertNotIn("query", capsule.fields_for("TOOL"))

    def test_execution_completion_evidence_crosses_report_contract_without_private_fields(self):
        samples = [
            ("WORKSPACE_EVIDENCE_CONFLICT", "application_persistence", "REVIEW_APPLICATION_EVIDENCE", {
                "evidence_conflict_reason": "IMMUTABLE_PAYLOAD_CHANGED",
                "evidence_record_kind": "synthesis_checkpoint", "evidence_writer_phase": "TERMINAL_RECONCILIATION", "ownership_fence": 7,
                "evidence_record_hash": "a" * 64,
                "evidence_proposed_payload_hash": "b" * 64,
                "evidence_retained_payload_hash": "c" * 64,
            }),
            ("OUTPUT_CONTINUATION_NO_PROGRESS", "final_synthesis", "UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST", {
                "no_output_kind": "REASONING_ONLY", "no_output_visible_bytes": 0,
                "no_output_reasoning_bytes": 19128, "no_output_tool_call_count": 0,
                "no_output_repairs": 1, "no_output_repair_limit": 1, "no_output_repair_scheduled": False,
                "no_output_continuation_attempt": 1, "no_output_continuation_limit": 2,
                "no_output_generated_tokens": 2048, "no_output_reasoning_tokens": 2048,
                "no_output_effective_limit": 4096, "no_output_serialized_limit": 2048,
                "no_output_stop_reason": "EMPTY_OUTPUT_REPAIR_LIMIT", "no_output_parent_invocation_hash": "d" * 64,
                "no_output_draft_hash": "a" * 64,
            }),
            ("BILLING_UNAVAILABLE", "response_stream", None, {
                "provider_failure_kind": "BUDGET", "upstream_error_code": "insufficient_quota",
                "stream_event_type": "response.failed", "provider_response_id_hash": "e" * 64,
            }),
            ("PROVIDER_WAIT_CHECKPOINT_CHANGED", "lifecycle", "REVIEW_SAVED_RECOVERY", {
                "checkpoint_validation_stage": "TOOL_JOURNAL", "checkpoint_mismatch_reason": "TOOL_JOURNAL_CHANGED_DURING_RESTORE",
                "provider_wait_expected_tool_journal_hash": "a" * 64,
                "provider_wait_observed_tool_journal_hash": "b" * 64,
                "provider_wait_queued_tool_count": 2, "provider_wait_unsettled_tool_count": 1,
            }),
            ("PROVIDER_RATE_LIMITED", "provider_wait", None, {
                "provider_failure_kind": "RATE_LIMIT", "provider_semantic_status": 429,
                "provider_wait_decision_state": "NOT_SCHEDULED", "provider_wait_no_wait_reason": "OWNER_CHANGED",
                "provider_wait_scheduler_state": "NOT_SCHEDULED", "provider_wait_persisted": False,
            }),
        ]
        for code, stage, action, values in samples:
            with self.subTest(code=code):
                capture = self.capture("INCIDENT", {**values, "incident_phase": stage, "causal_failure_code": code})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                report["errorCode"] = code
                report["context"]["stage"] = stage
                if "stage" in report["context"]["unavailableFields"]:
                    report["context"]["unavailableFields"].remove("stage")
                if action:
                    report["context"]["recoveryAction"] = action
                    report["context"]["unavailableFields"].remove("recoveryAction")
                self.assertIn(code, str(issue_from_report(report)))
        for key, value in [
            ("no_output_kind", "private provider body"), ("upstream_error_code", "secret-token"),
            ("no_output_reasoning_bytes", "19128"), ("no_output_reasoning_bytes", True),
            ("provider_wait_expected_tool_journal_hash", "raw-private-id"),
            ("provider_wait_no_wait_reason", "private exception"), ("provider_semantic_status", 200),
            ("stream_event_type", "private event"), ("provider_response_id_hash", "resp_private_id"),
            ("evidence_record_kind", "private record"), ("evidence_writer_phase", "arbitrary phase"),
            ("ownership_fence", -1), ("ownership_fence", True), ("ownership_fence", 2 ** 63),
            ("no_output_generated_tokens", "2048"), ("no_output_stop_reason", "private reasoning"),
        ]:
            self.assertFalse(capsule.valid_value(key, value), (key, value))
        self.assertNotIn("private_payload", capsule.fields_for("INCIDENT"))
        self.assertNotIn("no_output_kind", capsule.fields_for("TOOL"))

    def test_numeric_stream_denials_survive_approved_report_without_changing_http_status(self):
        for code, kind, failure in (
            ("401", "AUTHENTICATION", "AUTHENTICATION_FAILED"),
            ("429", "RATE_LIMIT", "PROVIDER_RATE_LIMITED"),
        ):
            with self.subTest(code=code):
                values = {
                    "incident_phase": "response_stream", "causal_failure_code": failure,
                    "provider_failure_kind": kind, "stream_event_type": "error",
                    "upstream_error_code": code, "upstream_error_type": code,
                }
                if kind == "RATE_LIMIT":
                    values["provider_semantic_status"] = 429
                capture = self.capture("INCIDENT", values)
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                report["errorCode"] = failure
                report["context"].update(stage="response_stream", httpStatus=200)
                report["context"]["unavailableFields"] = [
                    field for field in report["context"]["unavailableFields"] if field not in {"stage", "httpStatus"}
                ]
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertIn(f"| upstream_error_code | `{code}` |", issue["body"])
                self.assertIn(f"| upstream_error_type | `{code}` |", issue["body"])
                self.assertIn("| HTTP status | `200` |", issue["body"])
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        for field in ("upstream_error_code", "upstream_error_type"):
            for invalid in (401, 429, True, "403", "private provider text"):
                with self.subTest(field=field, invalid=invalid), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("INCIDENT", {field: invalid}))

    def test_unknown_provider_code_hashes_use_the_existing_approved_report_field(self):
        observed = set()
        for upstream in ("private-upstream-a", "private-upstream-b"):
            digest = hashlib.sha256(f"gptmobile.issue-context.v1:provider-error-code:{upstream}".encode()).hexdigest()
            values = {"provider_failure_kind": "UNKNOWN", "original_error_code_hash": digest}
            capture = self.capture("PROVIDER", values)
            self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
            report = self.report(capture)
            report["errorCode"] = "PROVIDER_GENERATION_FAILED"
            approved = json.dumps(report, separators=(",", ":"))
            _, issue = issue_from_report(report)
            self.assertIn(f"| original_error_code_hash | `{digest}` |", issue["body"])
            self.assertNotIn(upstream, approved)
            self.assertNotIn(upstream, issue["body"])
            self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
            observed.add(digest)
        self.assertEqual(2, len(observed))
        for key, value in (("upstream_error_code_hash", "a" * 64), ("original_error_code_hash", "private-text"), ("original_error_code_hash", int("1" * 64))):
            with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {key: value}))

    def test_conversation_recovery_details_survive_approved_report_submission(self):
        samples = [
            ("PROVIDER", "GITHUB_VERIFICATION_INTERNAL_ERROR", "INSPECT_VERIFICATION_FAILURE", {
                "verification_phase": "ACCEPTANCE_RENDERING",
                "incident_capture_site": "GITHUB_VERIFICATION_CATCH",
                "provider_failure_code": "GITHUB_VERIFICATION_INTERNAL_ERROR",
            }),
            ("PROVIDER", "OUTPUT_CONTINUATION_UNAVAILABLE", "SEND_ORIGINAL_REQUEST", {
                "continuation_cause": "NO_SAVED_TASK", "continuation_saved_characters": 0,
                "continuation_attempt_count": 0, "continuation_output_cap": 6144,
                "continuation_trigger": "RESTORED_DRAFT",
                "incident_capture_site": "OUTPUT_CONTINUATION_OPEN_CATCH",
            }),
            ("PROVIDER", "PROVIDER_WAIT_CHECKPOINT_CHANGED", "REVIEW_SAVED_RECOVERY", {
                "checkpoint_mismatch_reason": "DIGEST_CHANGED",
                "observed_checkpoint_state": "TOOL_RESULTS_READY",
                "provider_wait_observed_checkpoint_hash": "b" * 64,
            }),
            ("TOOL", "INVALID_ARGUMENTS", None, {
                "issue_continuation_state": "MISSING_AFTER",
                "validation_field_names": ["after", "expected_updated_at"],
            }),
            ("ACCEPTANCE", "GITHUB_EVIDENCE_REQUIRED", "RESUME_UNFINISHED_WORK", {
                "acceptance_advertised_tool_count": 0,
            }),
            ("TOOL", "INVALID_ARGUMENTS", None, {
                "catalog_selector_state": "NAMES_NULL",
                "argument_field_names": ["names", "inventory_cursor", "inventory_version"],
                "argument_field_types": ["NULL", "STRING", "STRING"],
            }),
        ]
        for area, code, recovery, details in samples:
            with self.subTest(code=code):
                capture = self.capture(area, {**details, "causal_failure_code": code})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                report["errorCode"] = code
                if recovery:
                    report["context"]["recoveryAction"] = recovery
                    report["context"]["unavailableFields"].remove("recoveryAction")
                issue = issue_from_report(report)
                self.assertIn(code, str(issue))
        for code in capsule.CONVERSATION_RECOVERY_CODES:
            capture = self.capture("PROVIDER", {"causal_failure_code": code, "provider_failure_code": code})
            report = self.report(capture)
            report["errorCode"] = code
            self.assertIn(code, str(issue_from_report(report)))

    def test_conversation_recovery_details_reject_payloads_outside_closed_contract(self):
        for key, value in [
            ("checkpoint_mismatch_reason", "private checkpoint body"),
            ("provider_wait_observed_checkpoint_hash", "raw-checkpoint-identifier"),
            ("continuation_saved_characters", True),
            ("continuation_attempt_count", -1),
            ("continuation_output_cap", 16_000_001),
            ("verification_phase", "private source path"),
        ]:
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {key: value}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", {"issue_continuation_state": "MISSING_AFTER"}))
        for area, value in (("PROVIDER", 0), ("TOOL", 1), ("ACCEPTANCE", True), ("ACCEPTANCE", -1), ("ACCEPTANCE", 16_000_001)):
            with self.subTest(area=area, value=value), self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, {"acceptance_advertised_tool_count": value}))

    def test_gateway_preflight_receipt_retains_all_closed_blockers_without_private_values(self):
        from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE

        values = {
            "gateway_admission_ready": False,
            "gateway_preflight_reason": "ACCEPTANCE_CONTRACT_REQUIRED",
            "gateway_preflight_blocker_count": 2,
            "gateway_preflight_blocker_codes": [
                "ACCEPTANCE_CONTRACT_REQUIRED", "VALIDATION_RUNTIME_UNAVAILABLE"
            ],
            "gateway_preflight_recovery_actions": [
                "DEFINE_ACCEPTANCE_REQUIREMENTS", "PROVISION_VALIDATION_RUNTIME"
            ],
            "gateway_preflight_discovery_state": "REVIEW_REQUIRED",
            "gateway_preflight_candidate_count": 3,
        }
        receipt = {
            "schemaVersion": 2, "area": "GATEWAY", "capturedAtMillis": 1_800_000_000_000,
            "captureMode": "INCIDENT", "values": values,
            "unavailableFields": sorted((CORE_FIELDS["GATEWAY"] | GATEWAY_ADMISSION_CORE) - values.keys()),
        }
        self.assertEqual(receipt, validate_diagnostics(copy.deepcopy(receipt)))
        for field, malformed in [
            ("gateway_preflight_blocker_codes", ["PRIVATE_SOURCE_PATH"]),
            ("gateway_preflight_recovery_actions", ["RUN_HOST_COMMAND"]),
            ("gateway_preflight_discovery_state", "PRIVATE_STATUS"),
            ("gateway_preflight_candidate_count", -1),
        ]:
            with self.subTest(field=field), self.assertRaises(ValueError):
                forged = copy.deepcopy(receipt)
                forged["values"][field] = malformed
                validate_diagnostics(forged)

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

    def test_search_readiness_and_silenced_input_accept_only_bounded_observations(self):
        for area, fields in (
            ("INCIDENT", {"app_search_readiness": "NO_ENGINE_RESOLVED"}),
            ("VOICE", {"voice_silenced_audio_mode": "IN_COMMUNICATION",
                       "voice_silenced_system_mic_muted": False}),
        ):
            capture = self.capture(area, fields)
            self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
            for key in fields:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture(area, {key: "private device or connection"}))
                with self.subTest(area=area, key=key), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("TOOL", {key: fields[key]}))

    def test_remote_pagination_and_provider_limit_reports_survive_submission(self):
        pagination = {
            "remote_pagination_page": 6, "remote_pagination_pages_observed": 1, "remote_pagination_reported_total": 170,
            "remote_pagination_unique_records": 20, "remote_pagination_duplicate_records": 0,
            "remote_pagination_contiguous": False, "remote_pagination_changed": False, "remote_pagination_reason": "INCOMPLETE",
        }
        quota = {"provider_limit_source": "upstream_provider_shared_pool", "provider_cooldown_scope_hash": "a" * 64}
        for area, fields in (("TOOL", pagination), ("PROVIDER", quota)):
            with self.subTest(area=area):
                report = self.report(self.capture(area, fields))
                _, issue = issue_from_report(report)
                for field in fields:
                    self.assertIn(field, issue["body"])
                with self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("APP", fields))
        for area, key, invalid in (
            ("TOOL", "remote_pagination_page", True), ("TOOL", "remote_pagination_reported_total", 16_000_001),
            ("TOOL", "remote_pagination_reason", "private text"), ("TOOL", "remote_pagination_changed", 1),
            ("PROVIDER", "provider_limit_source", "private text"), ("PROVIDER", "provider_cooldown_scope_hash", "private text"),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, {key: invalid}))

    def test_selected_model_and_review_prerequisites_survive_submission(self):
        model = {"provider_failure_code": "SELECTED_MODEL_LOADING", "gateway_model_state": "LOADING",
                 "gateway_model_advertised": True, "gateway_model_loaded": False}
        values = validate_diagnostics(self.capture("PROVIDER", model))["values"]
        self.assertEqual(model, {key: values[key] for key in model})
        for key, bad in (("gateway_model_loaded", "false"), ("gateway_model_state", "private model name")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {**model, key: bad}))
        for reason in ("STATUS_DISCOVERY_UNAVAILABLE", "STATUS_CONNECTION_AMBIGUOUS", "STATUS_ACQUISITION_UNAVAILABLE"):
            capture = self.capture("INCIDENT", {"github_prerequisite_observation": reason})
            self.assertEqual(reason, validate_diagnostics(capture)["values"]["github_prerequisite_observation"])

    def test_interrupted_run_and_unavailable_continuation_keep_their_codes_in_approved_issues(self):
        for code in ("GITHUB_RUN_INTERRUPTED", "OUTPUT_CONTINUATION_UNAVAILABLE", "OUTPUT_CONTINUATION_NO_PROGRESS", "PROVIDER_INVOCATION_FAILED", "RESEARCH_PARTIAL"):
            with self.subTest(code=code):
                report = self.report(self.capture())
                report.pop("context")
                report.update(feature="chat", errorCode=code, events=["-0ms E chat " + code])
                _, issue = issue_from_report(report)
                self.assertIn(code, issue["title"])
                self.assertIn(code, issue["body"])

    def test_catalog_rejections_and_reader_options_survive_the_report_boundary(self):
        for values in ({"round_catalog_tool_count": 6, "tool_reason_code": "UNKNOWN_TOOL"},
                       {"argument_field_names": ["excludeDomains"], "argument_field_types": ["STRING"],
                        "validation_field_names": ["excludeDomains"], "unknown_field_hashes": ["a" * 64]}):
            capture = self.capture("TOOL", values)
            self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        for values in ({"round_catalog_tool_count": -1}, {"validation_field_names": ["private-value"]}):
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", values))

    def test_new_provider_terminal_causes_are_accepted_as_closed_diagnostic_values(self):
        for code in ("BUDGET_RESTRICTED", "OUTPUT_CONTINUATION_NO_PROGRESS", "PROVIDER_INVOCATION_FAILED"):
            with self.subTest(code=code):
                capture = self.capture("PROVIDER", {"provider_failure_code": code})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                with self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("PROVIDER", {"provider_failure_code": code + " private text"}))

    def test_privacy_eligibility_reasons_roundtrip_without_reclassifying_unknown_dispatch(self):
        code = "PRIVACY_PROVIDER_INELIGIBLE"
        for reason in PRIVACY_ELIGIBILITY_REASONS:
            for status in ("NOT_VERIFIED", "INCOMPATIBLE"):
                with self.subTest(reason=reason, status=status):
                    capture = self.capture("PROVIDER", {
                        "provider_failure_code": code, "causal_failure_code": code,
                        "privacy_reason_code": reason, "privacy_status": status,
                    })
                    self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                    report = self.report(capture)
                    report.update(feature="chat", errorCode=code, events=["-0ms E chat " + code])
                    report["context"].update(source="CHAT_FAILURE", stage="provider_generation", dispatched=True)
                    report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                    before = copy.deepcopy(report)
                    _, issue = issue_from_report(report)
                    self.assertEqual(before, report)
                    self.assertIn(code, issue["title"])
                    self.assertIn(reason, issue["body"])
                    self.assertIn(status, issue["body"])
                    self.assertIn("remoteAcceptance", report["context"]["unavailableFields"])
                    self.assertNotIn("remoteAcceptance", report["context"])

    def test_privacy_eligibility_rejects_unknown_private_values_and_wrong_area(self):
        for field, invalid in (
            ("privacy_reason_code", "PRIVATE_KEY_OR_PROVIDER_MESSAGE"),
            ("privacy_reason_code", "NO_STRICT_ZDR_ENDPOINT PRIVATE_DETAILS"),
            ("privacy_reason_code", 1), ("privacy_reason_code", None),
            ("privacy_status", "AVAILABLE"), ("privacy_status", "CHECKING"),
            ("privacy_status", True), ("privacy_status", "PRIVATE_ACCOUNT"),
            ("provider_failure_code", "PRIVACY_PROVIDER_INELIGIBLE PRIVATE_DETAILS"),
        ):
            with self.subTest(field=field, invalid=invalid), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {field: invalid}))
        for area in ("TOOL", "INCIDENT", "VOICE", "APP", "GATEWAY", "ACCEPTANCE"):
            with self.subTest(area=area), self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, {"privacy_reason_code": "VERIFICATION_UNAVAILABLE"}))

    def test_privacy_reason_contract_matches_the_app_closed_eligibility_enum(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/privacy/PrivacyProfileEligibility.kt").read_text()
        enum = source.split("enum class Reason(", 1)[1].split("fun decision(", 1)[0]
        reasons = set(re.findall(r'^\s*([A-Z][A-Z_]+)\(', enum, re.MULTILINE))
        self.assertEqual(reasons, PRIVACY_ELIGIBILITY_REASONS)

    def test_gateway_capability_evidence_roundtrips_without_changing_unknown_remote_outcome(self):
        observations = [(kind, None) for kind in GATEWAY_CAPABILITY_FAILURE_KINDS]
        observations += [("DECODE_REJECTED", reason) for reason in GATEWAY_CAPABILITY_DECODE_REASONS]
        for kind, reason in observations:
            with self.subTest(kind=kind, reason=reason):
                code = "GATEWAY_ISOLATION_UNAVAILABLE" if kind == "ISOLATION_UNVERIFIED" else "GATEWAY_CAPABILITY_UNAVAILABLE"
                fields = {
                    "provider_failure_code": code, "causal_failure_code": code,
                    "incident_phase": "connection_preflight", "gateway_capability_failure_kind": kind,
                    "request_replay_allowed": False, "logical_continuation_allowed": False,
                }
                if reason is not None:
                    fields["gateway_capability_decode_reason"] = reason
                capture = self.capture("PROVIDER", fields)
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                report.update(feature="chat", errorCode=code)
                report["context"].update(source="CHAT_FAILURE", provider="LLAMA", stage="connection_preflight", dispatched=True)
                report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                before = copy.deepcopy(report)
                _, issue = issue_from_report(report)
                self.assertEqual(before, report)
                self.assertIn(f"| gateway_capability_failure_kind | `{kind}` |", issue["body"])
                if reason is not None:
                    self.assertIn(f"| gateway_capability_decode_reason | `{reason}` |", issue["body"])
                self.assertIn("| logical_continuation_allowed | `false` |", issue["body"])
                self.assertIn("remoteAcceptance", report["context"]["unavailableFields"])
                self.assertNotIn("remoteAcceptance", report["context"])
                self.assertTrue(report["context"]["dispatched"])

    def test_gateway_capability_evidence_rejects_private_values_and_mismatched_code_or_kind(self):
        valid = {"provider_failure_code": "GATEWAY_CAPABILITY_UNAVAILABLE", "gateway_capability_failure_kind": "DECODE_REJECTED",
                 "gateway_capability_decode_reason": "EXPECTED_ARRAY"}
        for field in GATEWAY_CAPABILITY_FIELDS:
            for invalid in ("https://private.example/token", "$.private.account", "EXPECTED_ARRAY private response", "", True, 1, None, [], {}):
                with self.subTest(field=field, invalid=invalid), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("PROVIDER", {**valid, field: invalid}))
        malformed = [
            {key: value for key, value in valid.items() if key != "provider_failure_code"},
            {key: value for key, value in valid.items() if key != "gateway_capability_failure_kind"},
            {**valid, "provider_failure_code": "TRANSPORT_ERROR"},
            {**valid, "provider_failure_code": "GATEWAY_ISOLATION_UNAVAILABLE"},
            {**valid, "gateway_capability_failure_kind": "CONNECT_TIMEOUT"},
            {**valid, "gateway_capability_failure_kind": "ISOLATION_UNVERIFIED"},
            {**valid, "gateway_capability_field_path": "$.execution.private"},
            {**valid, "gateway_capability_message": "private server response"},
            {"provider_failure_code": "GATEWAY_CAPABILITY_UNAVAILABLE", "gateway_capability_failure_kind": "ISOLATION_UNVERIFIED"},
            {"provider_failure_code": "GATEWAY_ISOLATION_UNAVAILABLE", "gateway_capability_failure_kind": "UNKNOWN"},
        ]
        for fields in malformed:
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", fields))
        for area in ("TOOL", "INCIDENT", "VOICE", "APP", "GATEWAY", "ACCEPTANCE"):
            with self.subTest(area=area), self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, valid))

    def test_gateway_capability_fields_remain_optional_and_explicitly_omitted_values_are_not_invented(self):
        for code in ("GATEWAY_CAPABILITY_UNAVAILABLE", "GATEWAY_ISOLATION_UNAVAILABLE"):
            legacy = self.capture("PROVIDER", {"provider_failure_code": code})
            self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        omitted = self.capture("PROVIDER")
        omitted["omittedFields"] = sorted(GATEWAY_CAPABILITY_FIELDS)
        self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
        for field in GATEWAY_CAPABILITY_FIELDS:
            unavailable = self.capture("PROVIDER")
            unavailable["unavailableFields"].append(field)
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_diagnostics(unavailable)
        no_capture = {"schemaVersion": 1, "area": "PROVIDER", "capturedAtMillis": 1_800_000_000_000,
                      "captureMode": "NO_CAPTURE", "values": {}, "unavailableFields": sorted(CORE_FIELDS["PROVIDER"])}
        self.assertEqual(no_capture, validate_diagnostics(copy.deepcopy(no_capture)))
        for field in GATEWAY_CAPABILITY_FIELDS:
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_diagnostics({**no_capture, "omittedFields": [field]})

    def test_voice_pronunciation_reason_is_closed_and_contains_no_spoken_word(self):
        values = {
            "incident_phase": "voice_playback",
            "causal_provenance": "UNAVAILABLE",
            "causal_failure_code": "VOICE_PLAYBACK_FAILED",
            "incident_evidence_status": "CAUSAL_EVIDENCE_UNAVAILABLE",
            "voice_pronunciation_failure_kind": "UNKNOWN_WORD",
            "voice_pronunciation_unknown_words": 1,
            "voice_pronunciation_unknown_word_length": 16,
            "voice_pronunciation_audio_queued": True,
        }
        valid = self.capture("VOICE", values)
        self.assertEqual(valid, validate_diagnostics(copy.deepcopy(valid)))
        for length in (0, 257, True, "private word"):
            with self.subTest(length=length), self.assertRaises(ValueError):
                forged = copy.deepcopy(valid)
                forged["values"]["voice_pronunciation_unknown_word_length"] = length
                validate_diagnostics(forged)
        for kind in ("private_spoken_word", "UNKNOWN", "", 4, None):
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                forged = copy.deepcopy(valid)
                forged["values"]["voice_pronunciation_failure_kind"] = kind
                validate_diagnostics(forged)

    def test_tool_protocol_frame_kind_is_closed_and_private_input_never_published(self):
        observed = self.capture("PROVIDER", {
            "provider_failure_code": "TOOL_PROTOCOL_INVALID_FRAME",
            "tool_protocol_frame_kind": "MALFORMED_JSON",
        })
        self.assertEqual(observed, validate_diagnostics(copy.deepcopy(observed)))
        for invalid in ("private arguments", "OTHER", None, 3):
            forged = copy.deepcopy(observed)
            forged["values"]["tool_protocol_frame_kind"] = invalid
            with self.assertRaises(ValueError):
                validate_diagnostics(forged)

    def test_action_only_precheck_accepts_bounded_context(self):
        fields = {"tool_action": "stage", "action_only_tool_family": "REPOSITORY_WORKSPACE", "action_only_validation": "NO_RECEIPT"}
        observed = self.capture("TOOL", fields)
        self.assertEqual(observed, validate_diagnostics(copy.deepcopy(observed)))
        for key in ("action_only_tool_family", "action_only_validation"):
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {**fields, key: "private-freeform"}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", {"action_only_validation": "NO_RECEIPT"}))

    def test_copilot_actions_and_tool_precheck_reasons_roundtrip_without_raw_tool_arguments(self):
        reasons = (
            "TOOL_ARGUMENT_SCHEMA_REJECTED",
            "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "GITHUB_REPOSITORY_INPUT_REJECTED", "GITHUB_CI_RUN_SELECTOR_REQUIRED", "GITHUB_REPOSITORY_SELECTOR_INCOMPLETE", "GITHUB_SEARCH_QUERY_EMPTY", "GITHUB_SEARCH_QUERY_TOO_LONG", "GITHUB_ARGUMENT_PRECHECK_UNCLASSIFIED",
            "SAVED_VIEW_CURSOR_MALFORMED",
            "SAVED_VIEW_CONTEXT_MISMATCH",
        )
        actions = ("agent_merge_status", "agent_merge_enable", "agent_merge_update", "agent_merge_disable")
        for action in actions:
            with self.subTest(action=action):
                capture = self.capture("TOOL", {"native_tool_action": action, "tool_action": action})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                report["context"].update(stage="tool_precheck", dispatched=False)
                report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                _, issue = issue_from_report(report)
                self.assertIn(action, issue["body"])
                self.assertNotIn("private-token-value", issue["body"])
        for reason in reasons:
            with self.subTest(reason=reason):
                report = self.report(self.capture("TOOL"))
                report["context"].update(stage="tool_precheck", dispatched=False, reasonCode=reason)
                report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                _, issue = issue_from_report(report)
                self.assertIn(reason, issue["body"])
                invalid = copy.deepcopy(report)
                invalid["context"]["dispatched"] = True
                with self.assertRaises(ReportError):
                    issue_from_report(invalid)
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("TOOL", {"native_tool_action": "private_copilot_command"}))

    def test_protocol_recovery_outcome_is_closed_and_area_scoped(self):
        choices = (
            "TEXT_FALLBACK_EXHAUSTED",
            "TOOL_RETRY_EXHAUSTED",
            "BACKEND_HANDLED_TOOLS",
            "SINGLE_PASS_NOT_RETRYABLE",
            "TEXT_ALREADY_EMITTED",
            "NO_SAFE_REPAIR",
        )
        for value in choices:
            with self.subTest(value=value):
                observed = self.capture("PROVIDER", {
                    "provider_failure_code": "TOOL_PROTOCOL_INVALID_FRAME",
                    "tool_protocol_recovery_outcome": value,
                })
                self.assertEqual(observed, validate_diagnostics(copy.deepcopy(observed)))
        for invalid in ("PRIVATE_TOOL_ARGS", "", "UNKNOWN", 3, None):
            with self.subTest(invalid=invalid), self.assertRaises(ValueError):
                forged = self.capture("PROVIDER", {"tool_protocol_recovery_outcome": invalid})
                validate_diagnostics(forged)
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("TOOL", {"tool_protocol_recovery_outcome": "NO_SAFE_REPAIR"}))

    def test_claim_extraction_evidence_is_typed_bounded_and_private(self):
        details = {
            "incident_phase": "claim_extraction",
            "causal_provenance": "UNAVAILABLE",
            "causal_failure_code": "GITHUB_TASK_INCOMPLETE",
            "incident_evidence_status": "TYPED_EVIDENCE",
            "claim_extraction_failure_kind": "SCHEMA_REJECTED",
            "claim_extraction_validation_stage": "CLAIM_RECORD",
            "claim_extraction_response_characters": 1024,
            "claim_extraction_max_claims": 2,
            "claim_extraction_completed": True,
        }
        capture = self.capture("PROVIDER", details)
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        for key, invalid in (
            ("claim_extraction_failure_kind", "private-response"),
            ("claim_extraction_validation_stage", "private-response"),
            ("claim_extraction_response_characters", -1),
            ("claim_extraction_max_claims", "8"),
            ("claim_extraction_completed", "true"),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                bad = copy.deepcopy(capture)
                bad["values"][key] = invalid
                validate_diagnostics(bad)
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("TOOL", {"claim_extraction_failure_kind": "SCHEMA_REJECTED"}))
        self.assertNotIn("private-response", json.dumps(capture))

    def test_prerequisite_observations_are_bounded_and_area_scoped(self):
        for area, observation in (("INCIDENT", "STATUS_READ_FAILED"), ("ACCEPTANCE", "CATALOG_TOOL_NOT_EXPOSED")):
            capture = self.capture(area, {"github_prerequisite_observation": observation})
            self.assertEqual(validate_diagnostics(copy.deepcopy(capture)), capture)
        for area, observation in (("TOOL", "STATUS_READ_FAILED"), ("ACCEPTANCE", "private raw error")):
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, {"github_prerequisite_observation": observation}))

    def test_pending_gateway_tool_results_accept_closed_handoff_diagnostics(self):
        capture = self.capture("PROVIDER", {
            "provider_failure_code": "CLIENT_TOOL_RESULTS_REQUIRED",
            "conflict_pending_tool_count": 2,
            "conflict_pending_tool_state": "IDENTIFIED",
            "conflict_pending_tool_ids_hash": "a" * 64,
            "conflict_handoff_sequence": 3,
        })
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        for value in ("private job", "CLIENT_TOOL_RESULTS_REQUIRED extra", False, -1):
            with self.subTest(value=value), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {"provider_failure_code": value}))

    def test_pr_context_race_capture_is_bounded_and_does_not_reveal_revision_or_cause(self):
        observed = {"pr_context_revision_changed": True, "pr_context_read_requests": 3, "pr_context_read_responses": 3}
        capture = self.capture("TOOL", observed)
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        report = self.report(capture)
        report["errorCode"] = "GITHUB_PR_HEAD_CHANGED"
        _, issue = issue_from_report(report)
        for field in observed:
            self.assertIn(f"| {field} |", issue["body"])
        self.assertNotIn("private-head-sha", issue["body"])
        self.assertEqual(observed, {field: capture["values"][field] for field in capsule.PR_CONTEXT_RACE_FIELDS})
        for field, valid, invalid in (
            ("pr_context_revision_changed", True, ["true", 1, "private", None]),
            ("pr_context_read_requests", 3, [-1, "3", True, 16000001]),
            ("pr_context_read_responses", 3, [-1, "3", True, 16000001]),
        ):
            for value in invalid:
                with self.subTest(field=field, value=value), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("TOOL", {field: value}))
            for area in CORE_FIELDS.keys() - {"TOOL"}:
                with self.subTest(field=field, area=area), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture(area, {field: valid}))
            missing = self.capture("TOOL")
            missing["unavailableFields"].append(field)
            with self.assertRaises(ValueError):
                validate_diagnostics(missing)
        legacy = {"schemaVersion": 1, "area": "TOOL", "capturedAtMillis": 1000,
                  "captureMode": "INCIDENT", "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"])}
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        legacy["values"]["pr_context_revision_changed"] = True
        with self.assertRaises(ValueError):
            validate_diagnostics(legacy)

    def test_search_validation_names_preserve_observed_order_without_private_values_or_siblings(self):
        key = "search_validation_field_names"
        names = ["fuzzy_threshold", "context_lines", "max_new_blobs", "batch_size"]
        for sample in [names[:size] for size in range(1, 5)] + [list(reversed(names))]:
            with self.subTest(sample=sample):
                capture = self.capture("TOOL", {key: sample})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                self.assertIn(f"| {key} | `{', '.join(sample)}` |", issue["body"])
                self.assertNotIn("validation_field_names", capture["values"])
                for control in ("dispatched", "remoteAcceptance", "retryable", "recoveryAction"):
                    self.assertNotIn(control, report["context"])
        omitted = self.capture("TOOL")
        omitted["omittedFields"] = [key]
        self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
        _, issue = issue_from_report(self.report(omitted))
        self.assertIn(key, issue["body"])
        self.assertNotIn(key, omitted["values"])
        absent = self.capture("TOOL")
        self.assertEqual(absent, validate_diagnostics(copy.deepcopy(absent)))
        self.assertNotIn(key, absent["values"])
        self.assertNotIn(key, absent["unavailableFields"])
        selected = self.capture("TOOL", {key: ["context_lines"]})
        selected["captureMode"] = "SELECTED_CAPTURE"
        self.assertEqual(selected, validate_diagnostics(copy.deepcopy(selected)))
        for name in names:
            with self.subTest(global_name=name), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {"validation_field_names": [name]}))

    def test_search_validation_names_reject_malformed_unavailable_cross_area_and_legacy_data(self):
        key = "search_validation_field_names"
        for bad in (None, {}, "context_lines", True, 1, [], [None], [True], [1], ["private-query"],
                    ["query"], ["CONTEXT_LINES"], ["context_lines", "private-query"],
                    ["context_lines", "context_lines"], ["fuzzy_threshold", "context_lines", "max_new_blobs", "batch_size", "context_lines"]):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {key: bad}))
        absent = self.capture("TOOL")
        absent["unavailableFields"].append(key)
        with self.assertRaises(ValueError):
            validate_diagnostics(absent)
        for area in CORE_FIELDS.keys() - {"TOOL"}:
            for marker in ("values", "omittedFields", "unavailableFields"):
                other = self.capture(area)
                if marker == "values":
                    other[marker][key] = ["context_lines"]
                else:
                    other.setdefault(marker, []).append(key)
                with self.subTest(area=area, marker=marker), self.assertRaises(ValueError):
                    validate_diagnostics(other)
        legacy = {"schemaVersion": 1, "area": "TOOL", "capturedAtMillis": 1000, "captureMode": "INCIDENT",
                  "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"])}
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        for marker in ("values", "omittedFields"):
            bad = copy.deepcopy(legacy)
            if marker == "values":
                bad[marker][key] = ["context_lines"]
            else:
                bad[marker] = [key]
            with self.assertRaises(ValueError):
                validate_diagnostics(bad)
            disabled = self.capture("TOOL")
            disabled.update({"captureMode": "NO_CAPTURE", "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"] | capsule.CORE)})
            if marker == "values":
                disabled[marker][key] = ["context_lines"]
            else:
                disabled[marker] = [key]
            with self.assertRaises(ValueError):
                validate_diagnostics(disabled)

    def test_inventory_pagination_observations_are_independent_and_preserve_false_without_authority(self):
        reason, offered = "inventory_pagination_reason", "inventory_restart_offered"
        samples = [{reason: code} for code in ("MISSING_VERSION", "VERSION_MISMATCH")] + [{offered: flag} for flag in (False, True)]
        samples += [{reason: code, offered: flag} for code in ("MISSING_VERSION", "VERSION_MISMATCH") for flag in (False, True)]
        for observed in samples:
            with self.subTest(observed=observed):
                capture = self.capture("TOOL", observed)
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                for key, value in observed.items():
                    rendered = str(value).lower() if type(value) is bool else value
                    self.assertIn(f"| {key} | `{rendered}` |", issue["body"])
                self.assertEqual(observed, {key: value for key, value in capture["values"].items() if key in capsule.INVENTORY_PAGINATION_FIELDS})
                for key in ("dispatched", "remoteAcceptance", "retryable", "recoveryAction"):
                    self.assertNotIn(key, report["context"])
        for key, neighbor in ((reason, {offered: False}), (offered, {reason: "VERSION_MISMATCH"})):
            omitted = self.capture("TOOL", neighbor)
            omitted["omittedFields"] = [key]
            self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
            _, issue = issue_from_report(self.report(omitted))
            self.assertIn(key, issue["body"])
            self.assertNotIn(key, omitted["values"])
        absent = self.capture("TOOL")
        self.assertEqual(absent, validate_diagnostics(copy.deepcopy(absent)))
        self.assertFalse(capsule.INVENTORY_PAGINATION_FIELDS & absent["values"].keys())
        self.assertFalse(capsule.INVENTORY_PAGINATION_FIELDS & set(absent["unavailableFields"]))

    def test_inventory_pagination_rejects_coercion_private_values_unavailable_and_wrong_boundaries(self):
        invalid = {
            "inventory_pagination_reason": [None, {}, [], True, 1, "private-reason", "missing_version"],
            "inventory_restart_offered": [None, {}, [], "true", "false", 0, 1, "private-offer"],
        }
        legacy = {"schemaVersion": 1, "area": "TOOL", "capturedAtMillis": 1000, "captureMode": "INCIDENT",
                  "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"])}
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        for key, malformed in invalid.items():
            valid = False if key == "inventory_restart_offered" else "MISSING_VERSION"
            for bad in malformed:
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("TOOL", {key: bad}))
            unavailable = self.capture("TOOL")
            unavailable["unavailableFields"].append(key)
            with self.assertRaises(ValueError):
                validate_diagnostics(unavailable)
            for area in CORE_FIELDS.keys() - {"TOOL"}:
                for marker in ("values", "omittedFields", "unavailableFields"):
                    other = self.capture(area)
                    if marker == "values":
                        other[marker][key] = valid
                    else:
                        other.setdefault(marker, []).append(key)
                    with self.subTest(key=key, area=area, marker=marker), self.assertRaises(ValueError):
                        validate_diagnostics(other)
            for marker in ("values", "omittedFields"):
                bad = copy.deepcopy(legacy)
                if marker == "values":
                    bad[marker][key] = valid
                else:
                    bad[marker] = [key]
                with self.assertRaises(ValueError):
                    validate_diagnostics(bad)
                disabled = self.capture("TOOL")
                disabled.update({"captureMode": "NO_CAPTURE", "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"] | capsule.CORE)})
                if marker == "values":
                    disabled[marker][key] = valid
                else:
                    disabled[marker] = [key]
                with self.assertRaises(ValueError):
                    validate_diagnostics(disabled)
            selected = self.capture("TOOL", {key: valid})
            selected["captureMode"] = "SELECTED_CAPTURE"
            self.assertEqual(selected, validate_diagnostics(copy.deepcopy(selected)))

    def test_ci_semantic_rules_preserve_closed_observed_order_and_independent_omissions(self):
        key = "semantic_ci_rules"
        rules = ["BRANCH_NAME", "COMMIT_SHA", "POSITIVE_ID", "WORKFLOW_SELECTOR", "MISSING_SELECTOR", "UNUSED_FIELD", "LOG_START", "LOG_ORDER", "LOG_SPAN"]
        for sample in [rules[:size] for size in range(1, 10)] + [list(reversed(rules))]:
            with self.subTest(sample=sample):
                capture = self.capture("TOOL", {key: sample})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                self.assertIn(f"| {key} | `{', '.join(sample)}` |", issue["body"])
                self.assertNotIn("validation_field_names", capture["values"])
        omitted = self.capture("TOOL")
        omitted["omittedFields"] = [key]
        self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
        _, issue = issue_from_report(self.report(omitted))
        self.assertIn(key, issue["body"])
        self.assertNotIn(key, omitted["values"])
        absent = self.capture("TOOL")
        self.assertEqual(absent, validate_diagnostics(copy.deepcopy(absent)))
        self.assertNotIn(key, absent["values"])
        self.assertNotIn(key, absent["unavailableFields"])
        selected = self.capture("TOOL", {key: ["LOG_ORDER"]})
        selected["captureMode"] = "SELECTED_CAPTURE"
        self.assertEqual(selected, validate_diagnostics(copy.deepcopy(selected)))

    def test_ci_semantic_rules_reject_malformed_unavailable_cross_area_and_legacy_values(self):
        key = "semantic_ci_rules"
        for bad in (None, {}, "LOG_START", True, 1, [], [None], [True], [1], ["private-rule"],
                    ["LOG_START", "private-rule"], ["LOG_START", "LOG_START"], ["LOG_START"] * 10):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {key: bad}))
        absent = self.capture("TOOL")
        absent["unavailableFields"].append(key)
        with self.assertRaises(ValueError):
            validate_diagnostics(absent)
        for area in CORE_FIELDS.keys() - {"TOOL"}:
            for marker in ("values", "omittedFields", "unavailableFields"):
                other = self.capture(area)
                if marker == "values":
                    other[marker][key] = ["LOG_SPAN"]
                else:
                    other.setdefault(marker, []).append(key)
                with self.subTest(area=area, marker=marker), self.assertRaises(ValueError):
                    validate_diagnostics(other)
        legacy = {"schemaVersion": 1, "area": "TOOL", "capturedAtMillis": 1000, "captureMode": "INCIDENT",
                  "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"])}
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        for marker in ("values", "omittedFields"):
            bad = copy.deepcopy(legacy)
            if marker == "values":
                bad[marker][key] = ["LOG_START"]
            else:
                bad[marker] = [key]
            with self.assertRaises(ValueError):
                validate_diagnostics(bad)
            disabled = self.capture("TOOL")
            disabled.update({"captureMode": "NO_CAPTURE", "values": {}, "unavailableFields": sorted(CORE_FIELDS["TOOL"] | capsule.CORE)})
            if marker == "values":
                disabled[marker][key] = ["LOG_START"]
            else:
                disabled[marker] = [key]
            with self.assertRaises(ValueError):
                validate_diagnostics(disabled)

    def test_policy_response_fields_preserve_measured_empty_ordered_subsets_and_independent_unknowns(self):
        for key in capsule.POLICY_RESPONSE_FIELDS:
            if key in capsule.POLICY_RESPONSE_HEADER_FIELDS:
                samples = [[name for index, name in enumerate(capsule.POLICY_RESPONSE_HEADER_ORDER) if mask & (1 << index)]
                           for mask in range(16)]
            else:
                samples = sorted(capsule.ENUMS[key])
            for sample in samples:
                with self.subTest(key=key, sample=sample):
                    capture = self.capture("ACCEPTANCE", {key: sample})
                    self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                    report = self.report(capture)
                    approved = json.dumps(report, separators=(",", ":"))
                    _, issue = issue_from_report(report)
                    self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                    rendered = "[]" if sample == [] else ", ".join(sample) if isinstance(sample, list) else sample
                    self.assertIn(f"| {key} | `{rendered}` |", issue["body"])
                    self.assertEqual({key}, capsule.POLICY_RESPONSE_FIELDS & capture["values"].keys())
            for marker in ("omittedFields", "unavailableFields"):
                capture = self.capture("ACCEPTANCE")
                capture.setdefault(marker, []).append(key)
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                _, issue = issue_from_report(self.report(capture))
                self.assertIn(key, issue["body"])
                self.assertFalse(capsule.POLICY_RESPONSE_FIELDS & capture["values"].keys())
        legacy = self.capture("ACCEPTANCE")
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        self.assertFalse(capsule.POLICY_RESPONSE_FIELDS & legacy["values"].keys())

    def test_policy_response_contract_rejects_malformed_cross_area_and_no_capture_data(self):
        for key in capsule.POLICY_RESPONSE_FIELDS:
            if key in capsule.POLICY_RESPONSE_HEADER_FIELDS:
                valid = []
                malformed = ["SSO", ["private-scope"], [True], [1], [None], [{}], ["SSO", "private-scope"],
                             ["SSO", "SSO"], ["SSO"] * 5, list(reversed(capsule.POLICY_RESPONSE_HEADER_ORDER))]
            else:
                valid = "OTHER_404"
                malformed = [[], ["OTHER_404"], True, 404, "private-message"]
                if key == "status_policy_rules_404_kind":
                    malformed.append("EXPLICIT_BRANCH_NOT_PROTECTED")
            for bad in [None, {}, *malformed]:
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("ACCEPTANCE", {key: bad}))
            for area in CORE_FIELDS.keys() - {"ACCEPTANCE"}:
                for marker in ("values", "omittedFields", "unavailableFields"):
                    other = self.capture(area)
                    if marker == "values":
                        other[marker][key] = valid
                    else:
                        other.setdefault(marker, []).append(key)
                    with self.subTest(key=key, area=area, marker=marker), self.assertRaises(ValueError):
                        validate_diagnostics(other)
            for marker in ("values", "omittedFields"):
                disabled = self.capture("ACCEPTANCE")
                disabled.update({"captureMode": "NO_CAPTURE", "values": {}, "unavailableFields": sorted(CORE_FIELDS["ACCEPTANCE"] | capsule.CORE)})
                if marker == "values":
                    disabled[marker][key] = valid
                else:
                    disabled[marker] = [key]
                with self.assertRaises(ValueError):
                    validate_diagnostics(disabled)
            unavailable = self.capture("ACCEPTANCE")
            unavailable["unavailableFields"] += [key, key]
            with self.assertRaises(ValueError):
                validate_diagnostics(unavailable)
            observed = self.capture("ACCEPTANCE", {key: valid})
            observed["unavailableFields"].append(key)
            with self.assertRaises(ValueError):
                validate_diagnostics(observed)
        selected = self.capture("ACCEPTANCE", {"status_policy_rules_permission_header_names": []})
        selected["captureMode"] = "SELECTED_CAPTURE"
        self.assertEqual(selected, validate_diagnostics(copy.deepcopy(selected)))

    def repeat_history(self, prior_count=1, batch_count=1, prior=None, outcomes=None, prior_coverage="COMPLETE", batch=None, batch_coverage="COMPLETE"):
        prior = ["a" * 64] if prior is None else prior
        batch = ["b" * 64] if batch is None else batch
        return {
            "dependency_repeat_scope": "TOOL_NAME_AND_ARGUMENTS", "dependency_repeat_prior_admissions": prior_count,
            "dependency_repeat_batch_admissions": batch_count, "dependency_repeat_used": prior_count + batch_count,
            "dependency_repeat_limit": prior_count + batch_count,
            "dependency_repeat_prior_call_id_hashes": prior, "dependency_repeat_prior_outcomes": ["UNOBSERVED"] * len(prior) if outcomes is None else outcomes,
            "dependency_repeat_prior_coverage": prior_coverage, "dependency_repeat_batch_call_id_hashes": batch, "dependency_repeat_batch_coverage": batch_coverage,
        }

    def test_repeat_history_preserves_coherent_coverage_and_exact_approved_outcomes(self):
        samples = [
            self.repeat_history(0, 0, prior=[], batch=[]),
            self.repeat_history(3, 0, prior_coverage="PARTIAL", batch=[]),
            self.repeat_history(3, 2, prior=[], prior_coverage="UNAVAILABLE", batch_coverage="PARTIAL"),
            self.repeat_history(3, 2, prior_coverage="PARTIAL", batch=[], batch_coverage="UNAVAILABLE"),
            self.repeat_history(16_000_000, 0, prior=[], prior_coverage="UNAVAILABLE", batch=[]),
            self.repeat_history(8, 8, prior=[f"{i:064x}" for i in range(8)], batch=[f"{i:064x}" for i in range(8, 16)]),
        ] + [self.repeat_history(outcomes=[outcome]) for outcome in sorted(capsule.REPEAT_HISTORY_OUTCOMES)]
        for scope in ("TOOL_NAME", "TOOL_NAME_AND_ARGUMENTS"):
            for sample in samples:
                values = {**sample, "dependency_repeat_scope": scope, "call_id_hash": "f" * 64}
                capture = self.capture("TOOL", values)
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                report = self.report(capture)
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                for key in capsule.REPEAT_HISTORY_FIELDS:
                    self.assertIn(f"| {key} |", issue["body"])
                self.assertFalse({"remote_acceptance", "requires_reconciliation", "request_replay_allowed"} & capture["values"].keys())

    def test_last_unsent_reason_requires_an_observed_owned_prior_rejection(self):
        values = self.repeat_history(outcomes=["NOT_DISPATCHED"])
        evidence = {**values, capsule.LAST_UNSENT_CODE: "INVALID_ARGUMENTS"}
        observed = self.capture("TOOL", evidence)
        self.assertEqual(observed, validate_diagnostics(copy.deepcopy(observed)))
        for fields in ({"dependency_repeat_last_unsent_code": "OTHER"},
                       {**evidence, capsule.LAST_UNSENT_CODE: "private-text"},
                       {**evidence, "dependency_repeat_prior_outcomes": ["DISPATCHED_SUCCESS"]},
                       {**self.repeat_history(prior_count=2, outcomes=["NOT_DISPATCHED"], prior_coverage="PARTIAL"), capsule.LAST_UNSENT_CODE: "OTHER"}):
            with self.subTest(fields=fields), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", fields))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", {capsule.LAST_UNSENT_CODE: "OTHER"}))

    def test_repeat_history_rejects_partial_malformed_incoherent_and_self_linked_values(self):
        good = self.repeat_history()
        invalid = [{key: value for key, value in good.items() if key != missing} for missing in capsule.REPEAT_HISTORY_FIELDS | capsule.TOOL_REPEAT_FIELDS]
        invalid.extend([
            self.repeat_history(prior=["a" * 64, "a" * 64], prior_count=2),
            self.repeat_history(prior=["b" * 64]), self.repeat_history(prior=["f" * 64]), self.repeat_history(batch=["f" * 64]),
            self.repeat_history(prior=["A" * 64]), self.repeat_history(outcomes=[]), self.repeat_history(outcomes=["UNOBSERVED", "UNKNOWN_OUTCOME"]),
            self.repeat_history(prior_coverage="PARTIAL"), self.repeat_history(batch_coverage="PARTIAL"),
            self.repeat_history(prior_coverage="UNAVAILABLE"), self.repeat_history(batch_coverage="UNAVAILABLE"),
            self.repeat_history(0, 0, prior=[], prior_coverage="UNAVAILABLE", batch=[]),
            self.repeat_history(0, 0, prior=[], batch=[], batch_coverage="PARTIAL"), self.repeat_history(prior_count=2),
        ])
        for key in capsule.REPEAT_HISTORY_FIELDS:
            malformed = [None, {}, True, 1, "private-value"]
            malformed += [["PRIVATE"], [None], [True], [1], [f"{i:064x}" for i in range(9)]] if key in capsule.REPEAT_HISTORY_LIST_FIELDS else [[]]
            invalid.extend({**good, key: value} for value in malformed)
        invalid.extend({**good, key: value} for key, value in (
            ("dependency_repeat_scope", "UNKNOWN"), ("dependency_repeat_prior_admissions", -1),
            ("dependency_repeat_batch_admissions", 16_000_001), ("dependency_repeat_used", 3), ("dependency_repeat_limit", 3),
            ("dependency_repeat_prior_admissions", "1"), ("dependency_repeat_prior_admissions", 1.0), ("dependency_repeat_prior_admissions", True),
        ))
        for values in invalid:
            with self.subTest(values=values), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {"call_id_hash": "f" * 64, **values}))
        legacy = {key: value for key, value in good.items() if key in capsule.TOOL_REPEAT_FIELDS}
        legacy["dependency_repeat_used"] = 10
        self.assertEqual(self.capture("TOOL", legacy), validate_diagnostics(self.capture("TOOL", legacy)))

    def test_repeat_history_metadata_remains_unknown_and_area_capture_boundaries_stay_closed(self):
        for key in capsule.REPEAT_HISTORY_FIELDS:
            for marker in ("omittedFields", "unavailableFields"):
                capture = self.capture("TOOL")
                capture.setdefault(marker, []).append(key)
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                self.assertFalse(capsule.REPEAT_HISTORY_FIELDS & capture["values"].keys())
                for area in CORE_FIELDS.keys() - {"TOOL"}:
                    other = self.capture(area)
                    other.setdefault(marker, []).append(key)
                    with self.assertRaises(ValueError):
                        validate_diagnostics(other)
                observed = self.capture("TOOL", self.repeat_history())
                observed.setdefault(marker, []).append(key)
                with self.assertRaises(ValueError):
                    validate_diagnostics(observed)
        for area in CORE_FIELDS.keys() - {"TOOL"}:
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, self.repeat_history()))
        observed = self.capture("TOOL", self.repeat_history())
        observed["captureMode"] = "NO_CAPTURE"
        with self.assertRaises(ValueError):
            validate_diagnostics(observed)
        observed["captureMode"] = "SELECTED_CAPTURE"
        self.assertEqual(observed, validate_diagnostics(copy.deepcopy(observed)))

    def test_repeat_admission_preserves_observed_operands_without_reconstructing_omitted_siblings(self):
        values = {"dependency_repeat_scope": "TOOL_NAME_AND_ARGUMENTS", "dependency_repeat_prior_admissions": 2,
                  "dependency_repeat_batch_admissions": 1, "dependency_repeat_used": 3, "dependency_repeat_limit": 3}
        capture = self.capture("TOOL", values)
        report = self.report(capture)
        approved = json.dumps(report, separators=(",", ":"))
        _, issue = issue_from_report(report)
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        for key, value in values.items():
            self.assertIn(f"| {key} | `{value}` |", issue["body"])
        # The producer qualifies a coherent snapshot. The public envelope can
        # retain only some scalars after compaction and must not invent others.
        for key in capsule.TOOL_REPEAT_COUNTS:
            for value in (0, 16_000_000):
                partial = self.capture("TOOL", {key: value})
                partial["omittedFields"] = sorted(capsule.TOOL_REPEAT_FIELDS - {key})
                self.assertEqual(partial, validate_diagnostics(copy.deepcopy(partial)))
                self.assertEqual({key}, capsule.TOOL_REPEAT_FIELDS & partial["values"].keys())
        for scope in ("TOOL_NAME", "TOOL_NAME_AND_ARGUMENTS"):
            partial = self.capture("TOOL", {"dependency_repeat_scope": scope})
            self.assertEqual(partial, validate_diagnostics(copy.deepcopy(partial)))
        exceeded = self.capture("TOOL", {"dependency_repeat_used": 4, "dependency_repeat_limit": 3})
        self.assertEqual(exceeded, validate_diagnostics(copy.deepcopy(exceeded)))
        legacy = self.capture("TOOL")
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        self.assertFalse(capsule.TOOL_REPEAT_FIELDS & legacy["values"].keys())
        for key in ("dispatched", "retryable", "remote_acceptance", "request_replay_allowed"):
            self.assertNotIn(key, capture["values"])

    def test_tool_event_persistence_preserves_local_write_facts_and_signed_sequence_boundaries(self):
        cases = (
            {"tool_event_persistence_operation": "START", "tool_event_persistence_phase": "INSERT",
             "tool_event_persistence_failure": "RUN_SEQUENCE_CONFLICT", "tool_event_requested_sequence": 0,
             "tool_event_previous_max_sequence": 2, "tool_event_assigned_sequence": 3, "tool_event_write_attempted": True},
            {"tool_event_persistence_operation": "START", "tool_event_persistence_phase": "ALLOCATE_SEQUENCE",
             "tool_event_persistence_failure": "SEQUENCE_EXHAUSTED", "tool_event_requested_sequence": -2_147_483_648,
             "tool_event_previous_max_sequence": 2_147_483_647, "tool_event_write_attempted": False},
            {"tool_event_persistence_failure": "UNKNOWN_CONSTRAINT"},
        )
        for values in cases:
            capture = self.capture("INCIDENT", values)
            report = self.report(capture)
            report.update({"feature": "chat", "errorCode": "CHAT_FAILED"})
            report["context"]["source"] = "CHAT_FAILURE"
            approved = json.dumps(report, separators=(",", ":"))
            _, issue = issue_from_report(report)
            self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
            for key in values:
                self.assertIn(key, issue["body"])
            for key in ("dispatched", "remote_acceptance", "retryable", "request_replay_allowed"):
                self.assertNotIn(key, capture["values"])
            self.assertEqual(set(values), capsule.TOOL_EVENT_PERSISTENCE_FIELDS & capture["values"].keys())
        for key in capsule.TOOL_EVENT_PERSISTENCE_FIELDS:
            if key in capsule.TOOL_EVENT_SIGNED_SEQUENCES:
                valid = (-2_147_483_648, -1, 0, 2_147_483_647)
            elif key == "tool_event_assigned_sequence":
                valid = (0, 2_147_483_647)
            elif key == "tool_event_write_attempted":
                valid = (False, True)
            else:
                valid = capsule.ENUMS[key]
            for value in valid:
                capture = self.capture("INCIDENT", {key: value})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        omitted = self.capture("INCIDENT")
        omitted["omittedFields"] = sorted(capsule.TOOL_EVENT_PERSISTENCE_FIELDS)
        self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))

    def test_acceptance_observation_causes_keep_closed_entries_and_comparison_policy(self):
        reasons = ["TOOL_NOT_IN_AUTHORIZED_CATALOG", "UNRECOGNIZED_EVIDENCE_OPERATION", "TARGET_OUTSIDE_ACCEPTED_SCOPE"]
        for entry in capsule.ENUMS["acceptance_publication_entry"]:
            for observed in ([reason] for reason in reasons):
                capture = self.capture("ACCEPTANCE", {"acceptance_publication_entry": entry,
                                                      "acceptance_claim_trust_failures": observed})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        capture = self.capture("ACCEPTANCE", {"acceptance_claim_trust_failures": reasons})
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        for state in capsule.ENUMS["review_comparison_state"]:
            for policy in capsule.ENUMS["review_comparison_policy"]:
                capture = self.capture("INCIDENT", {"review_comparison_state": state, "review_comparison_policy": policy})
                report = self.report(capture)
                report.update({"feature": "chat", "errorCode": "GITHUB_REVIEW_COMPARISON_REQUIRED"})
                report["context"]["source"] = "CHAT_FAILURE"
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                self.assertIn(f"| review_comparison_state | `{state}` |", issue["body"])
                self.assertIn(f"| review_comparison_policy | `{policy}` |", issue["body"])
        for area, fields in (("ACCEPTANCE", capsule.ACCEPTANCE_OBSERVATION_FIELDS),
                             ("INCIDENT", capsule.REVIEW_COMPARISON_OBSERVATION_FIELDS)):
            legacy = self.capture(area)
            self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
            self.assertFalse(fields & legacy["values"].keys())
            legacy["omittedFields"] = sorted(fields)
            self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))

    def test_new_observations_reject_private_malformed_and_cross_area_values(self):
        groups = (("TOOL", capsule.TOOL_REPEAT_FIELDS), ("INCIDENT", capsule.TOOL_EVENT_PERSISTENCE_FIELDS),
                  ("INCIDENT", capsule.REVIEW_COMPARISON_OBSERVATION_FIELDS), ("ACCEPTANCE", capsule.ACCEPTANCE_OBSERVATION_FIELDS))
        for area, fields in groups:
            for key in fields:
                if key in capsule.TOOL_REPEAT_COUNTS:
                    valid, malformed = 0, [-1, 16_000_001, "1", 1.0, True, False]
                elif key in capsule.TOOL_EVENT_SIGNED_SEQUENCES:
                    valid, malformed = 0, [-2_147_483_649, 2_147_483_648, "1", 1.0, True, False]
                elif key == "tool_event_assigned_sequence":
                    valid, malformed = 0, [-1, 2_147_483_648, "1", 1.0, True, False]
                elif key == "tool_event_write_attempted":
                    valid, malformed = False, [0, 1, "false", "true"]
                elif key == "acceptance_claim_trust_failures":
                    reason = "TARGET_OUTSIDE_ACCEPTED_SCOPE"
                    valid, malformed = [reason], [[], [reason, reason], [reason] * 4, ["UNKNOWN"], [True], [1], [{}], reason]
                else:
                    valid, malformed = sorted(capsule.ENUMS[key])[0], [True, False, 0, "unobserved"]
                for bad in [None, {}, "private URL, SQL, arguments, claim, or connection", *malformed]:
                    with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                        validate_diagnostics(self.capture(area, {key: bad}))
                for other in CORE_FIELDS.keys() - {area}:
                    control = self.capture(other)
                    if other == "GATEWAY":
                        from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE
                        control["unavailableFields"] = sorted(set(control["unavailableFields"]) | GATEWAY_ADMISSION_CORE)
                    validate_diagnostics(control)
                    with self.subTest(key=key, other=other), self.assertRaises(ValueError):
                        validate_diagnostics({**control, "values": {**control["values"], key: valid}})
                    with self.assertRaises(ValueError):
                        validate_diagnostics({**control, "omittedFields": [key]})
        for area, key in (("TOOL", "dependency_repeat_key"), ("TOOL", "dependency_repeat_arguments_hash"),
                          ("INCIDENT", "tool_event_sql"), ("INCIDENT", "tool_event_id"),
                          ("INCIDENT", "review_comparison_base_ref"), ("ACCEPTANCE", "acceptance_untrusted_receipt_ids")):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, {key: "private-value"}))

    def test_sqlite_constraint_category_is_closed_and_available_at_each_exception_capture_area(self):
        expected = {"UNIQUE", "FOREIGN_KEY", "NOT_NULL", "CHECK", "PRIMARY_KEY", "TRIGGER", "UNKNOWN"}
        self.assertEqual(expected, capsule.ENUMS["sqlite_constraint_kind"])
        for area in CORE_FIELDS:
            control = self.capture(area)
            if area == "GATEWAY":
                from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE
                control["unavailableFields"] = sorted(set(control["unavailableFields"]) | GATEWAY_ADMISSION_CORE)
            for value in expected:
                capture = {**control, "values": {**control["values"], "sqlite_constraint_kind": value}}
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
            omitted = {**control, "omittedFields": ["sqlite_constraint_kind"]}
            self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
            for value in (None, True, 1, {}, [], "unique", "UNIQUE constraint failed: private_table.private_column"):
                with self.subTest(area=area, value=value), self.assertRaises(ValueError):
                    validate_diagnostics({**control, "values": {**control["values"], "sqlite_constraint_kind": value}})

    def test_acceptance_publication_preserves_closed_observations_and_exact_approved_payload(self):
        legacy = self.capture("ACCEPTANCE")
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        self.assertFalse(capsule.ACCEPTANCE_PUBLICATION_FIELDS & legacy["values"].keys())
        samples = (
            {"acceptance_publication_source": "PARTIAL_FALLBACK", "acceptance_extraction_observation": "NO_DRAFT",
             "acceptance_draft_state": "ABSENT", "acceptance_receipt_snapshot_count": 3, "acceptance_claim_count": 0},
            {"acceptance_publication_source": "PARTIAL_FALLBACK", "acceptance_extraction_observation": "PLAN_INPUT_LIMIT",
             "acceptance_draft_state": "PRESENT", "acceptance_receipt_snapshot_count": 3,
             "acceptance_extraction_planned_batches": 2, "acceptance_extraction_required_tokens": 4097,
             "acceptance_extraction_input_limit_tokens": 4096},
            {"acceptance_publication_source": "EVIDENCE_ADVANCED", "acceptance_receipt_snapshot_count": 4},
            {"acceptance_publication_source": "CLAIM_EXTRACTION", "acceptance_extraction_observation": "COMPLETED",
             "acceptance_draft_state": "EMPTY", "acceptance_receipt_snapshot_count": 0,
             "acceptance_extraction_planned_batches": 1, "acceptance_claim_count": 0},
        )
        for values in samples:
            with self.subTest(values=values):
                capture = self.capture("ACCEPTANCE", values)
                original = copy.deepcopy(capture)
                self.assertEqual(original, validate_diagnostics(capture))
                report = self.report(capture)
                report.update({"feature": "chat", "errorCode": "GITHUB_TASK_INCOMPLETE"})
                report["context"]["source"] = "CHAT_FAILURE"
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                for key in values:
                    self.assertIn(key, issue["body"])
                for key in ("dispatched", "remote_acceptance", "retryable", "request_replay_allowed"):
                    self.assertNotIn(key, capture["values"])
                self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)
        for key in capsule.ACCEPTANCE_PUBLICATION_FIELDS:
            if key in capsule.ACCEPTANCE_PUBLICATION_COUNTS:
                valid = (0, 16_000_000)
            elif key in capsule.ACCEPTANCE_PUBLICATION_TOKENS:
                valid = (0, 2_147_483_647)
            else:
                valid = capsule.ENUMS[key]
            for value in valid:
                capture = self.capture("ACCEPTANCE", {key: value})
                self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        omitted = self.capture("ACCEPTANCE")
        omitted["omittedFields"] = sorted(capsule.ACCEPTANCE_PUBLICATION_FIELDS)
        self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
        _, issue = issue_from_report(self.report(omitted))
        for key in omitted["omittedFields"]:
            self.assertIn(key, issue["body"])

    def test_acceptance_publication_rejects_malformed_private_and_wrong_area_fields(self):
        for key in capsule.ACCEPTANCE_PUBLICATION_FIELDS:
            if key in capsule.ACCEPTANCE_PUBLICATION_COUNTS:
                valid, malformed = 0, [-1, 16_000_001, "1", 1.0, 1.5]
            elif key in capsule.ACCEPTANCE_PUBLICATION_TOKENS:
                valid, malformed = 0, [-1, 2_147_483_648, "1", 1.0, 1.5]
            else:
                valid, malformed = sorted(capsule.ENUMS[key])[0], [0, "UNKNOWN", "private draft"]
            for value in (None, True, False, [], {}, *malformed):
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("ACCEPTANCE", {key: value}))
            for area in CORE_FIELDS.keys() - {"ACCEPTANCE"}:
                control = self.capture(area)
                if area == "GATEWAY":
                    from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE
                    control["unavailableFields"] = sorted(set(control["unavailableFields"]) | GATEWAY_ADMISSION_CORE)
                validate_diagnostics(control)
                with self.subTest(key=key, area=area), self.assertRaises(ValueError):
                    validate_diagnostics({**control, "values": {**control["values"], key: valid}})
                with self.assertRaises(ValueError):
                    validate_diagnostics({**control, "omittedFields": [key]})
        for key in ("acceptance_draft", "acceptance_claims", "acceptance_receipts", "acceptance_connection", "acceptance_exception"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", {key: "private source or answer"}))

    def test_handoff_reconciliation_preserves_bounded_observations_and_omissions(self):
        counts = {"handoff_replay_receipt_count", "handoff_replay_matched_count", "handoff_durable_receipt_count",
                  "handoff_durable_matched_count", "handoff_retained_matched_count"}
        hashes = {"handoff_tool_binding_hash", "handoff_saved_tool_binding_hash", "handoff_evidence_digest_hash", "handoff_journal_digest_hash"}
        self.assertEqual(counts, capsule.HANDOFF_RECONCILIATION_COUNTS)
        self.assertEqual(hashes, capsule.HANDOFF_RECONCILIATION_HASHES)
        self.assertEqual(counts | hashes | {"handoff_checkpoint_state", "handoff_durable_lookup"}, capsule.HANDOFF_RECONCILIATION_FIELDS)
        for state in ("TOOL_HANDOFF", "TOOL_RESULTS_READY"):
            for lookup in ("RETURNED_EMPTY", "RETURNED_RECEIPTS"):
                for boundary in (0, 1024):
                    values = {**dict.fromkeys(counts, boundary), **dict.fromkeys(hashes, "b" * 64),
                              "handoff_checkpoint_state": state, "handoff_durable_lookup": lookup}
                    capture = self.capture("INCIDENT", values)
                    original = copy.deepcopy(capture)
                    self.assertEqual(original, validate_diagnostics(capture))
                    report = self.report(capture)
                    approved = json.dumps(report, separators=(",", ":"))
                    _, issue = issue_from_report(report)
                    self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                    for key in values:
                        self.assertIn(key, issue["body"])
                    self.assertNotIn("dispatched", capture["values"])
                    self.assertNotIn("retryable", capture["values"])
                    self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)
        omitted = self.capture("INCIDENT")
        omitted["omittedFields"] = sorted(capsule.HANDOFF_RECONCILIATION_FIELDS)
        original = copy.deepcopy(omitted)
        self.assertEqual(original, validate_diagnostics(omitted))
        _, issue = issue_from_report(self.report(omitted))
        for key in omitted["omittedFields"]:
            self.assertIn(key, issue["body"])

    def test_handoff_reconciliation_rejects_malformed_values_and_other_areas(self):
        for key in capsule.HANDOFF_RECONCILIATION_FIELDS:
            if key in capsule.HANDOFF_RECONCILIATION_COUNTS:
                valid, malformed = 0, [-1, 1025, "1", 1.0, 1.5]
            elif key in capsule.HANDOFF_RECONCILIATION_HASHES:
                valid, malformed = "a" * 64, ["A" * 64, "a" * 63, "a" * 65, 1]
            else:
                valid, malformed = sorted(capsule.ENUMS[key])[0], [1, "UNKNOWN", "JOURNAL_ABSENT", "JOURNAL_CORRUPT"]
            for bad in [None, True, False, {}, [], "private-binding-or-call", *malformed]:
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("INCIDENT", {key: bad}))
            for area in CORE_FIELDS.keys() - {"INCIDENT"}:
                with self.subTest(key=key, area=area):
                    capture = self.capture(area)
                    # Validate the control to avoid a false positive from an unrelated area field.
                    if area == "GATEWAY":
                        from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE
                        capture["unavailableFields"] = sorted(set(capture["unavailableFields"]) | GATEWAY_ADMISSION_CORE)
                    validate_diagnostics(capture)
                    with self.assertRaises(ValueError):
                        validate_diagnostics({**capture, "values": {**capture["values"], key: valid}})
                    with self.assertRaises(ValueError):
                        validate_diagnostics({**capture, "omittedFields": [key]})
        for key in ("handoff_tool_binding", "handoff_call_id", "handoff_tool_name", "handoff_arguments", "handoff_result"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("INCIDENT", {key: "private-value"}))
        for schema in (1, 2):
            with self.subTest(schema=schema), self.assertRaises(ValueError):
                validate_diagnostics({**self.capture("INCIDENT", {"handoff_durable_lookup": "RETURNED_EMPTY"}), "schemaVersion": schema})

    def test_handoff_reconciliation_vocabulary_matches_android_contract(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/IssueReportIncidentCapsule.kt").read_text()
        for name, expected in (("handoffReconciliationCounts", capsule.HANDOFF_RECONCILIATION_COUNTS),
                               ("handoffReconciliationHashes", capsule.HANDOFF_RECONCILIATION_HASHES)):
            match = re.search(r"val " + name + r" = setOf\(([^\n]*)\)", source)
            self.assertIsNotNone(match, name)
            self.assertEqual(expected, set(re.findall(r'"([^"\n]+)"', match.group(1))), name)
        for key in ("handoff_checkpoint_state", "handoff_durable_lookup"):
            match = re.search(r'"' + key + r'" to setOf\(([^\n]*)\)', source)
            self.assertIsNotNone(match, key)
            self.assertEqual(capsule.ENUMS[key], set(re.findall(r'"([^"\n]+)"', match.group(1))), key)

    def test_chat_delivery_preserves_closed_terminal_evidence_without_content_or_cause(self):
        counts = {"chat_delivery_content_bytes", "chat_delivery_thinking_bytes", "chat_delivery_success_events",
                  "chat_delivery_tool_events", "chat_delivery_progress_events", "chat_delivery_github_rounds"}
        self.assertEqual(counts, capsule.CHAT_DELIVERY_COUNTS)
        self.assertEqual(counts | {"chat_delivery_terminal", "chat_delivery_answer_state", "chat_delivery_counts_capped"}, capsule.CHAT_DELIVERY_FIELDS)
        for state in ("EMPTY_CONTENT", "NO_ANSWER_CONTENT", "GITHUB_ANSWER_PENDING"):
            for boundary in (0, 16_000_000):
                values = {**dict.fromkeys(counts, boundary), "chat_delivery_terminal": "DONE", "chat_delivery_answer_state": state,
                          "chat_delivery_counts_capped": boundary == 16_000_000, "incident_capture_site": "CHAT_FLOW_EMPTY_ANSWER",
                          "incident_phase": "delivery", "incident_origin": "APPLICATION", "incident_phase_provenance": "CAPTURE_BOUNDARY_ONLY",
                          "causal_provenance": "UNAVAILABLE", "incident_evidence_status": "CAUSAL_EVIDENCE_UNAVAILABLE"}
                capture = self.capture("INCIDENT", values)
                del capture["values"]["causal_id_hash"]
                capture["unavailableFields"] = sorted((CORE_FIELDS["INCIDENT"] | capsule.CORE) - capture["values"].keys())
                original = copy.deepcopy(capture)
                self.assertEqual(original, validate_diagnostics(capture))
                report = self.report(capture)
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                for key in values:
                    self.assertIn(key, issue["body"])
                for absent in ("causal_id_hash", "exception_type_hashes", "dispatched", "retryable"):
                    self.assertNotIn(absent, capture["values"])
        omitted = self.capture("INCIDENT")
        omitted["omittedFields"] = sorted(capsule.CHAT_DELIVERY_FIELDS)
        self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
        _, issue = issue_from_report(self.report(omitted))
        for key in omitted["omittedFields"]:
            self.assertIn(key, issue["body"])

    def test_chat_delivery_rejects_malformed_values_private_text_and_other_areas(self):
        for key in capsule.CHAT_DELIVERY_FIELDS | {"incident_capture_site"}:
            if key in capsule.CHAT_DELIVERY_COUNTS:
                valid, malformed = 0, [-1, 16_000_001, "1", 1.0, True, False]
            elif key == "chat_delivery_counts_capped":
                valid, malformed = False, [0, 1, "false", "true"]
            else:
                valid = "CHAT_FLOW_EMPTY_ANSWER" if key == "incident_capture_site" else sorted(capsule.ENUMS[key])[0]
                malformed = [0, 1, True, False, "UNKNOWN"]
            for bad in [None, {}, [], "private answer or thinking", *malformed]:
                with self.subTest(key=key, bad=bad), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("INCIDENT", {key: bad}))
            for area in CORE_FIELDS.keys() - {"INCIDENT"}:
                capture = self.capture(area)
                if area == "GATEWAY":
                    from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE
                    capture["unavailableFields"] = sorted(set(capture["unavailableFields"]) | GATEWAY_ADMISSION_CORE)
                validate_diagnostics(capture)
                with self.subTest(area=area, key=key), self.assertRaises(ValueError):
                    validate_diagnostics({**capture, "values": {**capture["values"], key: valid}})
                if key != "incident_capture_site":
                    with self.assertRaises(ValueError):
                        validate_diagnostics({**capture, "omittedFields": [key]})
        for key in ("chat_delivery_content", "chat_delivery_thinking", "chat_delivery_tool_arguments", "chat_delivery_exception"):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("INCIDENT", {key: "private-text"}))

    def test_chat_delivery_vocabulary_matches_android_contract(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/IssueReportIncidentCapsule.kt").read_text()
        match = re.search(r"val chatDeliveryCounts = setOf\(([^\n]*)\)", source)
        self.assertIsNotNone(match)
        self.assertEqual(capsule.CHAT_DELIVERY_COUNTS, set(re.findall(r'"([^"\n]+)"', match.group(1))))
        for key in ("chat_delivery_terminal", "chat_delivery_answer_state"):
            match = re.search(r'"' + key + r'" to setOf\(([^\n]*)\)', source)
            self.assertIsNotNone(match, key)
            self.assertEqual(capsule.ENUMS[key], set(re.findall(r'"([^"\n]+)"', match.group(1))), key)

    def test_claim_freshness_preserves_observed_empty_distinct_predicates_and_absence(self):
        key = "acceptance_claim_freshness_predicates"
        predicates = ["OBSERVATION_TIME_UNAVAILABLE", "OBSERVATION_TIME_FUTURE", "MUTABLE_AGE_EXCEEDED", "MUTABLE_EPOCH_MISMATCH"]
        self.assertEqual({key}, capsule.CLAIM_FRESHNESS_FIELDS)
        self.assertEqual(set(predicates), capsule.ENUM_LISTS[key])
        for observed in [predicates[:size] for size in range(5)] + [predicates[::-1]]:
            with self.subTest(observed=observed):
                capture = self.capture("ACCEPTANCE", {key: observed})
                original = copy.deepcopy(capture)
                self.assertEqual(original, validate_diagnostics(capture))
                report = self.report(capture)
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                self.assertIn(key, issue["body"])
                self.assertEqual(observed, capture["values"][key])
        unavailable = self.capture("ACCEPTANCE")
        validate_diagnostics(unavailable)
        self.assertNotIn(key, unavailable["values"])
        self.assertNotIn(key, unavailable.get("omittedFields", []))
        omitted = {**unavailable, "omittedFields": [key]}
        self.assertEqual(omitted, validate_diagnostics(copy.deepcopy(omitted)))
        _, issue = issue_from_report(self.report(omitted))
        self.assertIn(key, issue["body"])

    def test_claim_freshness_rejects_duplicates_oversized_private_or_wrong_area_evidence(self):
        key = "acceptance_claim_freshness_predicates"
        predicate = "MUTABLE_AGE_EXCEEDED"
        for bad in (None, True, 0, predicate, {}, [predicate, predicate], [predicate] * 5, ["private/path"],
                    ["mutable_age_exceeded"], [True], [0], [None], [[]], [{}]):
            with self.subTest(bad=bad), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", {key: bad}))
        for area in CORE_FIELDS.keys() - {"ACCEPTANCE"}:
            capture = self.capture(area)
            if area == "GATEWAY":
                from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE
                capture["unavailableFields"] = sorted(set(capture["unavailableFields"]) | GATEWAY_ADMISSION_CORE)
            validate_diagnostics(capture)
            for value in ([], [predicate]):
                with self.subTest(area=area, value=value), self.assertRaises(ValueError):
                    validate_diagnostics({**capture, "values": {**capture["values"], key: value}})
            with self.subTest(area=area, omitted=True), self.assertRaises(ValueError):
                validate_diagnostics({**capture, "omittedFields": [key]})
        for private_key in ("acceptance_claim_timestamp", "acceptance_claim_path", "acceptance_claim_value", "freshness_predicates"):
            with self.subTest(key=private_key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", {private_key: "private-value"}))

    def test_claim_freshness_vocabulary_matches_android_contract(self):
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/IssueReportIncidentCapsule.kt").read_text()
        match = re.search(r"val claimFreshnessFields = setOf\(([^\n]*)\)", source)
        self.assertIsNotNone(match)
        self.assertEqual(capsule.CLAIM_FRESHNESS_FIELDS, set(re.findall(r'"([^"\n]+)"', match.group(1))))
        key = "acceptance_claim_freshness_predicates"
        match = re.search(r'"' + key + r'" to setOf\(([^\n]*)\)', source)
        self.assertIsNotNone(match)
        self.assertEqual(capsule.ENUM_LISTS[key], set(re.findall(r'"([^"\n]+)"', match.group(1))))

    def test_search_failure_counts_and_catalog_selector_are_bounded(self):
        fields = {"search_failure_observation": "NO_ENGINE_SUCCEEDED", "search_attempt_count": 3,
                  "search_dispatched_attempt_count": 1, "search_budget_rejected_attempt_count": 1,
                  "catalog_selector_state": "ACTION_WITHOUT_NAMES"}
        validate_diagnostics(self.capture("TOOL", fields))
        for key, value in fields.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("TOOL", {key: 1025 if type(value) is int else "private"}))
            with self.subTest(area="ACCEPTANCE", key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", {key: value}))

    def test_deadline_and_acquisition_evidence_are_bounded_and_acceptance_scoped(self):
        values = {"task_deadline_check": "BEFORE_TOOL_RESTORE", "task_deadline_observation": "WINDOW_EXPIRED",
                  "task_deadline_window_seconds": 60, "task_deadline_overdue_seconds": 0,
                  "acceptance_acquisition_allowed": False, "acceptance_cleanup_reserve": 0,
                  "acceptance_fixture_start_minimum": 6, "acceptance_prerequisite_blockers": ["OWNED_FIXTURE_REQUIRED"]}
        validate_diagnostics(self.capture("ACCEPTANCE", values))
        validate_diagnostics(self.capture("ACCEPTANCE", {**values, "task_deadline_check": "ADMISSION"}))
        for field, bad in (("task_deadline_check", "private text"), ("task_deadline_observation", "unknown clock"),
                           ("task_deadline_window_seconds", -1), ("task_deadline_overdue_seconds", 16000001),
                           ("acceptance_acquisition_allowed", "false"), ("acceptance_cleanup_reserve", -1),
                           ("acceptance_fixture_start_minimum", "6"), ("acceptance_prerequisite_blockers", ["private-id"])):
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", {field: bad}))
            with self.subTest(wrong_area=field), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {field: values[field]}))

    def test_empty_acceptance_explanation_is_closed_and_cannot_expose_contract_text(self):
        fields = {
            "acceptance_applicability": "UNDETERMINED",
            "acceptance_contract_state": "RETAINED",
            "acceptance_plan_state": "DEFINED",
            "acceptance_extraction_state": "FAILED",
            "acceptance_coverage_state": "RECEIPTS_RETAINED",
            "acceptance_empty_reason": "PRIMARY_FAILURE",
            "acceptance_original_failure_present": True,
        }
        captured = self.capture("ACCEPTANCE", fields)
        self.assertEqual(captured, validate_diagnostics(copy.deepcopy(captured)))
        self.assertIn("PRIMARY_FAILURE", str(issue_from_report(self.report(captured))))
        for key, value in fields.items():
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", {key: "private task instructions"}))
            with self.subTest(wrong_area=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("PROVIDER", {key: value}))
        for bad in (0, 1, "true", None, []):
            self.assertFalse(capsule.valid_value("acceptance_original_failure_present", bad))
        self.assertNotIn("requested_actions", capsule.fields_for("ACCEPTANCE"))
        self.assertNotIn("acceptance_criteria", capsule.fields_for("ACCEPTANCE"))

    def test_acceptance_recovery_evidence_preserves_unknown_policy_and_prerequisites(self):
        values = {
            "acceptance_preparation_state": "PREREQUISITES_REQUIRED",
            "acceptance_ready_read_count": 0, "acceptance_additional_ready_read_count": 0,
            "acceptance_fixture_creation_admissible": False,
            "acceptance_blocked_operation_families": ["github_ci", "github_read"],
            "status_policy_protection_http_status": 404,
            "status_policy_rules_http_status": 403,
            "status_branch_protected": False, "status_policy_complete": False,
            "status_policy_recovery_hint": "CHECK_POLICY_READ_PERMISSIONS",
            "status_missing_observations": ["COMMIT_CHECKS", "LATEST_WORKFLOW"],
        }
        capture = self.capture("ACCEPTANCE", values)
        original = copy.deepcopy(capture)
        validate_diagnostics(capture)
        self.assertEqual(original, capture)
        self.assertFalse(capture["values"]["status_policy_complete"])
        self.assertFalse(capture["values"]["acceptance_fixture_creation_admissible"])
        for key, bad in (
            ("status_policy_protection_http_status", "404"),
            ("status_policy_rules_http_status", 600),
            ("status_branch_protected", "false"),
            ("status_policy_complete", 0),
            ("acceptance_fixture_creation_admissible", "true"),
            ("acceptance_ready_read_count", -1),
            ("acceptance_preparation_state", "private repository path"),
            ("acceptance_blocked_operation_families", ["private-owner/private-repo"]),
            ("status_policy_recovery_hint", "BYPASS_POLICY"),
            ("status_missing_observations", ["private-observation"]),
        ):
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", {key: bad}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("VOICE", values))
        root = Path(__file__).resolve().parents[1]
        source = (root / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/github/GitHubAcceptanceRecoveryEvidence.kt").read_text()
        from authentication import issue_report_diagnostics as contract
        for name, expected in (("preparationStates", contract.ENUMS["acceptance_preparation_state"]),
                               ("operationFamilies", contract.ENUM_LISTS["acceptance_blocked_operation_families"]),
                               ("policyRecoveryHints", contract.ENUMS["status_policy_recovery_hint"]),
                               ("fields", contract.ACCEPTANCE_RECOVERY_FIELDS)):
            match = re.search(r"val " + name + r" = setOf\((.*?)\n?    \)", source, re.S)
            if name != "fields":
                match = re.search(r"val " + name + r" = setOf\(([^\n]*)\)", source)
            self.assertIsNotNone(match, name)
            self.assertEqual(expected, set(re.findall(r'"([^"\n]+)"', match.group(1))), name)

    def test_admission_diagnostics_preserve_typed_capacity_queue_and_preflight(self):
        samples = {
            "PROVIDER": {"admission_prompt_tokens": 9639, "admission_safety_tokens": 2409,
                         "admission_output_tokens": 4096, "admission_capacity_tokens": 5632,
                         "admission_dominant_component": "tool_schemas", "admission_dominant_tokens": 4414,
                         "reasoning_model_state": "LOADED", "reasoning_capability_age_ms": 1000,
                         "admission_measurement": "UTF8_ESTIMATE", "reasoning_recovery_action": "CHOOSE_AUTO_OR_QUALIFIED_MODEL"},
            "GATEWAY": {"queue_blocker": "ACTIVE_LEASE", "queue_active_leases": 1, "queue_concurrency_limit": 1,
                        "gateway_validation_policy_state": "NOT_CONFIGURED", "gateway_preflight_reason": "VALIDATION_POLICY_REQUIRED", "gateway_preflight_blocker_count": 1},
            "ACCEPTANCE": {"status_policy_recovery": "CHECK_PROTECTION_READ_PERMISSION", "acceptance_approval_blocked_total": 7, "acceptance_input_blocked_total": 16,
                           "acceptance_live_blocked_total": 0, "acceptance_continuation_blocked_total": 13},
        }
        for area, values in samples.items():
            with self.subTest(area=area):
                capture = self.capture(area, values)
                if area == "GATEWAY":
                    from authentication.engineering_report_contract import GATEWAY_ADMISSION_CORE
                    capture["unavailableFields"] = sorted(set(capture["unavailableFields"]) | GATEWAY_ADMISSION_CORE)
                original = copy.deepcopy(capture)
                validate_diagnostics(capture)
                self.assertEqual(original, capture)
                for key, value in values.items():
                    bad = copy.deepcopy(capture)
                    bad["values"][key] = -1 if type(value) is int else "private host/path or prompt"
                    with self.assertRaises(ValueError):
                        validate_diagnostics(bad)
        for code in ("CONTEXT_CAPACITY_EXCEEDED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CAPACITY_UNVERIFIED"):
            capture = self.capture("PROVIDER", {"provider_failure_code": code})
            validate_diagnostics(capture)
            report = self.report(capture)
            report.update(feature="chat", errorCode=code)
            _, issue = issue_from_report(report)
            self.assertIn(code, issue["title"])

    def test_graph_prerequisites_and_separate_policy_endpoint_statuses_survive_submission(self):
        values = {"status_policy_recovery": "CHECK_RULES_READ_PERMISSION", "branch_protection_http_status": 200,
                  "branch_rules_http_status": 403, "branch_protection_observation": "OBSERVED", "branch_rules_observation": "ACCESS_DENIED",
                  "unmet_criterion_blockers": ["GRAPH_INDEX_REQUIRED", "GRAPH_INPUT_REQUIRED", "GRAPH_INDEX_UNCONFIRMED"]}
        capture = self.capture("ACCEPTANCE", values)
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        report = self.report(capture)
        report.update(feature="chat", errorCode="GITHUB_TASK_INCOMPLETE")
        _, issue = issue_from_report(report)
        for key in values:
            self.assertIn(key, issue["body"])
        for key in ("branch_protection_http_status", "branch_rules_http_status"):
            for invalid in (99, 600, True, "403", "private endpoint detail"):
                with self.subTest(key=key, value=invalid), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("ACCEPTANCE", {key: invalid}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", {"branch_rules_http_status": 403}))

    def test_voice_capture_and_playback_evidence_is_bounded_and_area_specific(self):
        values = {"voice_playback_failure": "AUDIO_FOCUS_DENIED", "voice_playback_focus_attempts": 2,
                  "voice_capture_silenced_stage": "CAPTURE_READ", "voice_caption_alignment": "NO_TIMING",
                  "voice_install_recognition_status": "CHECKING", "voice_install_playback_status": "READY"}
        validate_diagnostics(self.capture("VOICE", values))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", values))
        for key in values:
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture("VOICE", {key: "raw recorder or speech text"}))

    def test_kokoro_pronunciation_focus_receipt_is_bounded_and_contains_no_spoken_text(self):
        values = {
            "voice_playback_audio_mode": "IN_COMMUNICATION",
            "voice_playback_failure": "AUDIO_FOCUS_TIMEOUT",
            "voice_focus_last_transition": "PAUSE",
            "voice_focus_transition_count": 2,
            "voice_pronunciation_unknown_words": 1,
            "voice_pronunciation_initialisms": 3,
            "voice_pronunciation_audio_queued": True,
            "voice_pronunciation_first_audio_observed": True,
        }
        receipt = self.capture("VOICE", values)
        validate_diagnostics(receipt)
        self.assertEqual(values, {key: receipt["values"][key] for key in values})
        for reason in ("AUDIO_FOCUS_TIMEOUT", "AUDIO_FOCUS_RESUME_FAILED"):
            validate_diagnostics(self.capture("VOICE", {"voice_playback_failure": reason}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", values))
        invalid = {
            "voice_focus_last_transition": "PRIVATE_SPEECH",
            "voice_focus_transition_count": -1,
            "voice_pronunciation_unknown_words": 16_000_001,
            "voice_pronunciation_initialisms": "PRIVATE_SPEECH",
            "voice_pronunciation_audio_queued": "true",
            "voice_pronunciation_first_audio_observed": None,
        }
        for field, bad in invalid.items():
            with self.subTest(field=field), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("VOICE", {field: bad}))

    def test_github_prerequisite_evidence_is_typed_and_keeps_policy_unknown(self):
        values = {"repository_graph_observation": "NOT_INDEXED", "graph_input_blocked_count": 16,
                  "workflow_dependency_observation": "MISSING_EXECUTED_JOB",
                  "branch_protection_observation": "NOT_FOUND_OR_INACCESSIBLE", "branch_rules_observation": "OBSERVED"}
        sample = self.capture("ACCEPTANCE", values)
        original = copy.deepcopy(sample)
        validate_diagnostics(sample)
        self.assertEqual(original, sample)
        for key in values:
            invalid = dict(values, **{key: -1 if key == "graph_input_blocked_count" else "private repository or run"})
            with self.subTest(key=key), self.assertRaises(ValueError):
                validate_diagnostics(self.capture("ACCEPTANCE", invalid))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", values))

    def test_completion_and_composer_report_fields(self):
        validate_diagnostics(self.capture("PROVIDER", {
            "provider_failure_code": "TOOL_PROTOCOL_INVALID_FRAME",
            "empty_completion_observed_attempts": 2, "empty_completion_reasoning_only_attempts": 1,
        }))
        validate_diagnostics(self.capture("APP", {
            "composer_focus_transitions": 1, "composer_ime_transitions": 2,
            "composer_focused": True, "composer_ime_visible": False,
        }))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("APP", {"composer_focus_transitions": 256}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("APP", {"composer_focused": "draft text"}))

    def test_github_schema_budget_preserves_specific_code_and_bounded_counts(self):
        code = "GITHUB_TOOL_CONTEXT_BUDGET"
        counts = {"required_schema_tokens": 5000, "available_schema_tokens": 0}
        capture = self.capture("PROVIDER", {"provider_failure_code": code, "causal_failure_code": code, **counts})
        report = self.report(capture)
        report.update(feature="chat", errorCode=code)
        report["events"] = ["-0ms E chat " + code]
        report["context"]["source"] = "CHAT_FAILURE"
        _, issue = issue_from_report(report)
        self.assertIn(code, issue["title"])
        for key in counts:
            self.assertIn(key, issue["body"])
            for invalid in (-1, 16_000_001, True, "5000"):
                bad = copy.deepcopy(capture)
                bad["values"][key] = invalid
                with self.assertRaises(ValueError):
                    validate_diagnostics(bad)

    def test_github_cooldown_preserves_full_long_range_closed_types_and_exact_report(self):
        from authentication.issue_report_diagnostics import GITHUB_COOLDOWN_LONGS, GITHUB_COOLDOWN_FIELDS
        longs = {"github_cooldown_delay_ms", "github_cooldown_deadline_ms", "github_cooldown_observed_at_ms"}
        fields = longs | {"github_cooldown_resource", "github_cooldown_basis", "github_cooldown_remaining_requests"}
        self.assertEqual(longs, GITHUB_COOLDOWN_LONGS)
        self.assertEqual(fields, GITHUB_COOLDOWN_FIELDS)
        code = "GITHUB_COOLDOWN_ACTIVE"
        sample = {"github_cooldown_resource": "CORE", "github_cooldown_basis": "RESOURCE_RESET",
                  "github_cooldown_delay_ms": 20_000, "github_cooldown_deadline_ms": 130_000,
                  "github_cooldown_observed_at_ms": 100_000, "github_cooldown_remaining_requests": 0}
        for area, code_field in (("PROVIDER", "provider_failure_code"), ("TOOL", "tool_error_code")):
            values = {**sample, code_field: code, "causal_failure_code": code}
            for boundary in (0, 86_400_001, 9_223_372_036_854_775_807):
                capture = self.capture(area, {**values, **dict.fromkeys(longs, boundary)})
                original = copy.deepcopy(capture)
                self.assertEqual(original, validate_diagnostics(capture))
                report = self.report(capture)
                report.update(feature="chat", errorCode=code, events=["-0ms E chat " + code])
                report["context"]["source"] = "CHAT_FAILURE"
                approved = copy.deepcopy(report)
                _, issue = issue_from_report(report)
                self.assertEqual(approved, report)
                self.assertIn(code, issue["title"])
                for key in fields:
                    self.assertIn(key, issue["body"])
                self.assertIn(str(boundary), issue["body"])
                self.assertNotIn("dispatched", report["context"])
                self.assertNotIn("remoteAcceptance", report["context"])
            for resource in ("CORE", "SEARCH", "GRAPHQL", "OTHER"):
                for basis in ("SECONDARY_DEADLINE", "RESOURCE_RESET", "RESOURCE_RETRY_AFTER", "RESOURCE_RESET_AND_RETRY_AFTER"):
                    validate_diagnostics(self.capture(area, {**values, "github_cooldown_resource": resource,
                        "github_cooldown_basis": basis, "github_cooldown_remaining_requests": 16_000_000}))
            for key in fields:
                invalid = ("PRIVATE_RESOURCE", "core", 1, False) if key in {"github_cooldown_resource", "github_cooldown_basis"} else (
                    -1, "20000", 1.0, False, 9_223_372_036_854_775_808)
                if key == "github_cooldown_remaining_requests":
                    invalid += (16_000_001,)
                for value in invalid + (None, [], {}):
                    with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                        validate_diagnostics(self.capture(area, {**values, key: value}))
        for key in fields:
            for area in ("VOICE", "APP", "INCIDENT", "GATEWAY", "ACCEPTANCE"):
                with self.subTest(key=key, area=area), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture(area, {key: sample[key]}))

    def test_github_cooldown_optional_snapshot_values_and_code_only_reports_stay_absent(self):
        from authentication.issue_report_diagnostics import GITHUB_COOLDOWN_FIELDS
        code = "GITHUB_COOLDOWN_ACTIVE"
        for area, code_field in (("PROVIDER", "provider_failure_code"), ("TOOL", "tool_error_code")):
            secondary = self.capture(area, {"github_cooldown_resource": "SEARCH", "github_cooldown_basis": "SECONDARY_DEADLINE",
                "github_cooldown_delay_ms": 20_000, "github_cooldown_deadline_ms": 130_000})
            validate_diagnostics(secondary)
            self.assertNotIn("github_cooldown_observed_at_ms", secondary["values"])
            self.assertNotIn("github_cooldown_remaining_requests", secondary["values"])
            omitted = self.capture(area, {code_field: code})
            omitted["omittedFields"] = sorted(GITHUB_COOLDOWN_FIELDS)
            validate_diagnostics(omitted)
        for area, field in (("PROVIDER", "provider_failure_code"), ("INCIDENT", "causal_failure_code"), ("VOICE", "voice_chat_failure_code"), ("TOOL", "tool_error_code")):
            capture = self.capture(area, {field: code})
            report = self.report(capture)
            report.update(feature="chat", errorCode=code, events=["-0ms E chat " + code])
            report["context"]["source"] = "CHAT_FAILURE"
            approved = copy.deepcopy(report)
            _, issue = issue_from_report(report)
            self.assertEqual(approved, report)
            self.assertIn(code, issue["body"])
            self.assertTrue(GITHUB_COOLDOWN_FIELDS.isdisjoint(report["context"]["diagnostics"]["values"]))

    def test_github_http_rate_report_keeps_response_status_separate_from_model_provider(self):
        for code, status in (("GITHUB_PRIMARY_RATE_LIMITED", 403), ("GITHUB_SECONDARY_RATE_LIMITED", 429)):
            for measured in (False, True):
                values = {"provider_failure_code": code, "provider_failure_stage": "GENERATION", "provider_failure_domain": "TOOL_EXECUTION",
                          "github_cooldown_resource": "CORE", "github_cooldown_observed_at_ms": 100_000,
                          "request_replay_allowed": False}
                if measured:
                    values.update(github_cooldown_basis="RESOURCE_RETRY_AFTER", github_cooldown_delay_ms=20_000,
                                  github_cooldown_deadline_ms=120_000, github_cooldown_remaining_requests=0)
                report = self.report(self.capture("PROVIDER", values))
                report.update(feature="chat", errorCode=code)
                report["context"].update(source="CHAT_FAILURE", provider="LLAMA", stage="tool_execution", httpStatus=status,
                                         retryable=False, recoveryAction="RETRY_AFTER_BACKOFF")
                report["context"]["unavailableFields"] = sorted(CONTEXT_FIELDS_V5 - report["context"].keys())
                approved = copy.deepcopy(report)
                _, issue = issue_from_report(report)
                self.assertEqual(approved, report)
                self.assertIn(code, issue["title"])
                self.assertIn("TOOL_EXECUTION", issue["body"])
                self.assertIn("tool_execution", issue["body"])
                self.assertNotIn("dispatched", report["context"])
                self.assertNotIn("remoteAcceptance", report["context"])
                if not measured:
                    self.assertTrue({"github_cooldown_basis", "github_cooldown_delay_ms", "github_cooldown_deadline_ms", "github_cooldown_remaining_requests"}.isdisjoint(values))
                for area, field in (("INCIDENT", "causal_failure_code"), ("VOICE", "voice_chat_failure_code")):
                    validate_diagnostics(self.capture(area, {field: code}))

    def test_profile_selection_preserves_closed_comparisons_hashes_and_exact_report(self):
        from authentication.issue_report_diagnostics import PROFILE_SELECTION_FLAGS, PROFILE_SELECTION_HASHES, PROFILE_SELECTION_FIELDS
        flags = {"github_selection_profile_match", "github_selection_registered_profile", "github_selection_model_match"}
        hashes = {"github_selection_profile_hash", "github_selection_registered_model_hash"}
        self.assertEqual(flags, PROFILE_SELECTION_FLAGS)
        self.assertEqual(hashes, PROFILE_SELECTION_HASHES)
        self.assertEqual(flags | hashes, PROFILE_SELECTION_FIELDS)
        code = "GITHUB_PROFILE_LOCKED"
        values = {**{key: key == "github_selection_registered_profile" for key in flags},
                  **{key: "a" * 64 for key in hashes}, "provider_failure_code": code, "causal_failure_code": code}
        capture = self.capture("PROVIDER", values)
        original = copy.deepcopy(capture)
        validate_diagnostics(capture)
        self.assertEqual(original, capture)
        report = self.report(capture)
        report.update(feature="chat", errorCode=code)
        report["events"] = ["-0ms E chat " + code]
        report["context"]["source"] = "CHAT_FAILURE"
        approved = copy.deepcopy(report)
        _, issue = issue_from_report(report)
        self.assertEqual(approved, report)
        self.assertIn(code, issue["title"])
        for key in PROFILE_SELECTION_FIELDS:
            self.assertIn(key, issue["body"])
        omitted = self.capture("PROVIDER", {"provider_failure_code": code})
        omitted["omittedFields"] = sorted(PROFILE_SELECTION_FIELDS)
        validate_diagnostics(omitted)
        unregistered = self.capture("PROVIDER", {"github_selection_profile_match": False,
                                               "github_selection_registered_profile": False,
                                               "github_selection_profile_hash": "b" * 64})
        validate_diagnostics(unregistered)
        self.assertNotIn("github_selection_model_match", unregistered["values"])
        self.assertNotIn("github_selection_registered_model_hash", unregistered["values"])
        for key in PROFILE_SELECTION_FIELDS:
            invalid = ("false", 0, 1, None, [], {}, "PRIVATE_TOKEN") if key in flags else (
                "A" * 64, "a" * 63, "a" * 65, False, 1, None, [], "PRIVATE_MODEL")
            for value in invalid:
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("PROVIDER", {**values, key: value}))
            for area in ("TOOL", "VOICE", "APP", "INCIDENT", "GATEWAY", "ACCEPTANCE"):
                with self.subTest(key=key, area=area), self.assertRaises(ValueError):
                    validate_diagnostics(self.capture(area, {key: values[key]}))

    def test_session_recovery_diagnostics_validate_through_issue_submission(self):
        samples = {
            "PROVIDER": {"provider_failure_code": "CONVERSATION_CONFLICT", "conflict_active_job_kind": "generation",
                         "conflict_conversation_revision": 7, "conflict_pending_tool_count": 1, "conflict_conversation_hash": "a" * 64},
            "TOOL": {"github_transport_kind": "DNS", "github_transport_read_attempts": 3,
                     "github_transport_unanswered_reads": 3, "github_transport_http_responses": 0},
            "ACCEPTANCE": {"acceptance_pending_remote_continuations": 14},
        }
        for area, values in samples.items():
            with self.subTest(area=area):
                capture = self.capture(area, values)
                original = copy.deepcopy(capture)
                self.assertEqual(original, validate_diagnostics(capture))
                report = self.report(capture)
                if area != "TOOL":
                    report["feature"] = "chat"
                    report["errorCode"] = "CONVERSATION_CONFLICT" if area == "PROVIDER" else "GITHUB_TASK_INCOMPLETE"
                    report["context"]["source"] = "CHAT_FAILURE"
                _, issue = issue_from_report(report)
                for key in values:
                    self.assertIn(key, issue["body"])
        bad = self.capture("PROVIDER", {"conflict_pending_tool_count": 129})
        with self.assertRaises(ValueError):
            validate_diagnostics(bad)

    def test_capacity_components_and_route_provenance_survive_without_private_payloads(self):
        values = {
            "admission_prompt_tokens": 10461, "admission_safety_tokens": 2615,
            "admission_output_tokens": 4096, "admission_capacity_tokens": 5632,
            "admission_measurement": "UTF8_ESTIMATE", "configured_output_limit": 0,
            "admission_system_tokens": 2000, "admission_history_tokens": 2500,
            "admission_current_request_tokens": 500, "admission_tool_schema_tokens": 4414,
            "admission_tool_result_tokens": 400, "admission_response_schema_tokens": 100,
            "admission_envelope_tokens": 547, "admission_route_context_tokens": 5632,
            "admission_configured_context_tokens": 8192, "admission_catalog_context_tokens": 32768,
            "admission_input_ceiling_tokens": 5632, "admission_output_ceiling_tokens": 4096,
            "admission_capacity_age_ms": 1000, "admission_capacity_provenance": "VERIFIED_ROUTE",
        }
        capture = self.capture("PROVIDER", values)
        original = copy.deepcopy(capture)
        report = self.report(capture)
        report.update(feature="chat", errorCode="CONTEXT_CAPACITY_EXCEEDED")
        report["context"]["source"] = "CHAT_FAILURE"
        self.assertEqual(original, validate_diagnostics(capture))
        _, issue = issue_from_report(report)
        for key in values:
            self.assertIn(key, issue["body"])
        self.assertEqual(original, capture)
        self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)
        for key, value in (
            ("admission_route_context_tokens", 0), ("admission_capacity_age_ms", 16_000_001),
            ("admission_history_tokens", True), ("admission_history_tokens", "3"),
            ("admission_capacity_provenance", "private endpoint"), ("admission_raw_prompt", "private prompt"),
        ):
            with self.subTest(key=key, value=value):
                invalid = copy.deepcopy(capture)
                invalid["values"][key] = value
                with self.assertRaises(ValueError):
                    validate_diagnostics(invalid)

    def test_conflict_handoff_evidence_does_not_claim_local_run_ownership(self):
        values = {
            "conflict_active_job_provenance": "CONVERSATION_ACTIVE_JOB",
            "conflict_pending_tool_state": "IDENTIFIED", "conflict_pending_tool_count": 2,
            "conflict_pending_tool_ids_hash": "a" * 64, "conflict_handoff_sequence": 3,
            "conflict_stream_job_relation": "DIFFERS_VERIFIED_STREAM_JOB",
        }
        capture = self.capture("PROVIDER", values)
        original = copy.deepcopy(capture)
        self.assertEqual(original, validate_diagnostics(capture))
        report = self.report(capture)
        report.update(feature="chat", errorCode="CONVERSATION_CONFLICT")
        report["context"]["source"] = "CHAT_FAILURE"
        _, issue = issue_from_report(report)
        for key in values:
            self.assertIn(key, issue["body"])
        self.assertEqual(original, capture)
        validate_diagnostics(self.capture("PROVIDER", {"conflict_pending_tool_state": "EMPTY", "conflict_pending_tool_count": 0}))
        for key, value in (
            ("conflict_pending_tool_count", 129), ("conflict_handoff_sequence", 0),
            ("conflict_pending_tool_ids_hash", "private-call-id"),
            ("conflict_active_job_provenance", "OWNED_BY_THIS_ANDROID_RUN"),
            ("conflict_stream_job_relation", "MATCHES_UNVERIFIED_BODY"),
        ):
            with self.subTest(key=key, value=value):
                invalid = copy.deepcopy(capture)
                invalid["values"][key] = value
                with self.assertRaises(ValueError):
                    validate_diagnostics(invalid)

    def test_retained_acceptance_scope_survives_issue_submission_without_promoting_coverage(self):
        for capability_scope in ("FULL_CATALOG", "OBSERVED_CAPABILITIES"):
            for pagination_mode in ("COMPLETE_INVENTORY_REQUESTED", "BOUNDED_SMOKE_SAMPLE"):
                with self.subTest(capability_scope=capability_scope, pagination_mode=pagination_mode):
                    capture = self.capture("ACCEPTANCE", {
                        "acceptance_capability_scope": capability_scope,
                        "acceptance_pagination_mode": pagination_mode,
                        "acceptance_pending_remote_continuations": 13,
                        "acceptance_continuation_blocked_total": 0,
                        "acceptance_approval_blocked_total": 7,
                        "acceptance_input_blocked_total": 16,
                    })
                    report = self.report(capture)
                    report.update(feature="chat", errorCode="GITHUB_TASK_INCOMPLETE")
                    report["context"]["source"] = "CHAT_FAILURE"
                    approved = json.dumps(report, separators=(",", ":"))
                    self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
                    _, issue = issue_from_report(report)
                    self.assertIn(capability_scope, issue["body"])
                    self.assertIn(pagination_mode, issue["body"])
                    self.assertIn("GITHUB_TASK_INCOMPLETE", issue["title"])
                    self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                    self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)

        legacy = self.capture("ACCEPTANCE", {"acceptance_pending_remote_continuations": 13})
        self.assertEqual(legacy, validate_diagnostics(copy.deepcopy(legacy)))
        self.assertNotIn("acceptance_capability_scope", legacy["values"])
        self.assertNotIn("acceptance_pagination_mode", legacy["values"])

    def test_acceptance_scope_rejects_unknown_values_wrong_types_and_other_areas(self):
        for key, value in (
            ("acceptance_capability_scope", "GENERAL"),
            ("acceptance_pagination_mode", "ALL_PAGES_VERIFIED"),
            ("acceptance_capability_scope", "private task or repository"),
            ("acceptance_pagination_mode", True),
            ("acceptance_capability_scope", 1),
            ("acceptance_pagination_mode", ["BOUNDED_SMOKE_SAMPLE"]),
        ):
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("ACCEPTANCE", {key: value}))
        for area in ("PROVIDER", "TOOL"):
            with self.subTest(area=area):
                with self.assertRaises(ValueError):
                    validate_diagnostics(self.capture(area, {"acceptance_capability_scope": "FULL_CATALOG"}))

    def test_voice_install_queue_transfer_and_failure_evidence_survives_approved_submission(self):
        values = {
            "voice_install_state": "RUNNING", "voice_install_scheduler": "JOB_SCHEDULER",
            "voice_install_pending_reason": "EXECUTING", "voice_install_elapsed_ms": 120_000,
            "voice_install_pack": "MOONSHINE", "voice_install_transfer_stage": "FAILED",
            "voice_install_failure_kind": "HTTP", "voice_install_http_status": 503,
            "voice_install_downloaded_bytes": 2_000_000, "voice_install_total_bytes": 177_000_000,
        }
        capture = {"schemaVersion": 1, "area": "VOICE", "capturedAtMillis": 1_800_000_000_000,
                   "captureMode": "INCIDENT", "values": values,
                   "unavailableFields": sorted(CORE_FIELDS["VOICE"] - values.keys())}
        report = self.report(capture)
        report.update(feature="voice", errorCode="VOICE_MODEL_REQUIRED")
        report["context"]["source"] = "VOICE_STATE"
        report["context"]["schemaVersion"] = 5
        approved = json.dumps(report, separators=(",", ":"))
        self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        _, issue = issue_from_report(report)
        for key in values:
            self.assertIn(key, issue["body"])
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)
        missing = self.capture("VOICE", {"voice_install_failure": "WORK_NOT_FOUND"})
        self.assertEqual(missing, validate_diagnostics(copy.deepcopy(missing)))
        unknown = self.capture("VOICE", {"voice_install_pending_reason": "UNAVAILABLE"})
        validate_diagnostics(unknown)
        self.assertNotIn("voice_install_total_bytes", unknown["values"])
        self.assertNotIn("voice_install_http_status", unknown["values"])

    def test_voice_install_evidence_rejects_private_values_bad_types_and_excessive_bounds(self):
        for key, value in (
            ("voice_install_elapsed_ms", 16_000_001), ("voice_install_downloaded_bytes", -1),
            ("voice_install_total_bytes", 1_000_000_001), ("voice_install_total_bytes", "177000000"),
            ("voice_install_http_status", 600), ("voice_install_http_status", True),
            ("voice_install_pack", "private model URL"), ("voice_install_transfer_stage", "private path"),
            ("voice_install_failure_kind", "raw exception"), ("voice_install_pending_reason", "private network"),
        ):
            with self.subTest(key=key, value=value):
                with self.assertRaises(ValueError):
                    validate_diagnostics(self.capture("VOICE", {key: value}))
        validate_diagnostics(self.capture("VOICE", {"voice_install_total_bytes": 1_000_000_000, "voice_install_http_status": 599}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("PROVIDER", {"voice_install_pack": "KOKORO"}))

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
            ("TOOL", {"tool_budget_kind": "COMBINED_WORK", "tool_budget_phase": "BEFORE_DISPATCH"}),
            ("PROVIDER", {"payload_contract": "OPENAI_RESPONSES", "provider_parameter": "input", "provider_rejection_category": "INVALID_REQUEST"}),
            ("TOOL", {"mcp_failure_phase": "execution", "mcp_failure_category": "MCP_TOOL_ERROR"}),
            ("TOOL", {"mcp_failure_phase": "execution", "mcp_failure_category": "MCP_OUTPUT_INVALID"}),
            ("PROVIDER", {"provider_rejection_category": "BILLING_UNAVAILABLE", "gateway_terminal_status": "failed"}),
            ("INCIDENT", {"combined_requirement_gap": "TOPIC", "combined_issue_count": 84, "combined_failed_check_count": 3,
                          "combined_unverified_check_count": 6, "combined_repair_rounds": 2,
                          "combined_repair_stop": "CORRECTION_CAPACITY_UNAVAILABLE"}),
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
                      "dependency_admission_reasons": ["CALL_ALLOWANCE"], "saved_view_restore_state": "BODY_MISSING_INDEX_PRESENT",
                      "url_reader_dns_state": "RECOVERY_COOLDOWN", "url_reader_availability": "NEGATIVE_CACHE"}),
            ("TOOL", {"saved_view_restore_state": "CURSOR_INDEX_INVALID"}),
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

        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("TOOL", {"url_reader_availability": "https://example.com/private"}))

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
        authority_source = (base / "data/context/ConfiguredDispatchAuthority.kt").read_text()
        override_helper = authority_source.split("internal fun githubOverrideChanges", 1)[1].split("\n    }", 1)[0]
        reported_keys = re.search(r"return\s+listOf\(([^)]*)\)", override_helper)
        self.assertIsNotNone(reported_keys)
        override_keys = {"githubProfileOverride." + key for key in re.findall(r'"([A-Za-z]+)"', reported_keys.group(1))}
        self.assertEqual(11, len(override_keys))
        behavior_source = (base / "data/model/ProfileBehaviorSettings.kt").read_text().split("data class ProfileBehaviorSettings(", 1)[1].split(") {", 1)[0]
        behavior_keys = {"profileBehavior." + name for name in re.findall(r"\bval (\w+):", behavior_source)}
        self.assertEqual(9, len(behavior_keys))
        self.assertEqual(policy_keys | profile_keys | override_keys | behavior_keys | {"profile.removed", "tool_inventory", "providerPermissions"}, capsule.POLICY_FIELD_NAMES)
        self.assertEqual(76, len(capsule.POLICY_FIELD_NAMES))

    def test_replay_capacity_reason_and_estimates_are_bounded_incident_fields(self):
        code = "REPLAY_EVIDENCE_REQUIRED"
        fields = {"causal_failure_code": code, "replay_budget_state": "COMPACTED_IDENTITIES_EXCEED_ALLOWANCE",
                  "replay_allowance": 128, "replay_tokens": 341, "replay_exchange_count": 2}
        capture = self.capture("INCIDENT", fields)
        report = self.report(capture)
        report.update(feature="chat", errorCode=code)
        report["context"]["source"] = "CHAT_FAILURE"
        _, issue = issue_from_report(report)
        self.assertIn(code, issue["title"])
        self.assertIn("replay_allowance", issue["body"])
        for key, value in (("replay_tokens", -1), ("replay_allowance", True), ("replay_exchange_count", "2"),
                           ("replay_tokens", 16_000_001), ("replay_budget_state", "private text")):
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture("INCIDENT", {key: value}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.capture("TOOL", {"replay_allowance": 0}))

    def test_graph_transport_archives_only_closed_tool_values(self):
        for kind in ("DNS", "DISCONNECTED", "ABORTED", "TIMEOUT", "TLS", "UNKNOWN"):
            capture = self.capture("TOOL", {"tool_reason_code": "GRAPH_TRANSPORT_UNAVAILABLE", "graph_transport_kind": kind})
            self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
        for area, value in (("PROVIDER", "DNS"), ("TOOL", "private endpoint"), ("TOOL", True)):
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, {"graph_transport_kind": value}))

    def test_reader_and_reconciliation_evidence_is_scoped_and_bounded(self):
        for area, fields in (
            ("INCIDENT", {"reconciliation_source": "SAVED_AND_TRACKED", "reconciliation_tracked_signature_count": 1024}),
            ("TOOL", {"url_reader_refusal": "LOCATION_MISSING"}),
            ("TOOL", {"round_catalog_discovery_available": False}),
        ):
            capture = self.capture(area, fields)
            self.assertEqual(capture, validate_diagnostics(copy.deepcopy(capture)))
            other_area = "TOOL" if area == "INCIDENT" else "INCIDENT"
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture(other_area, fields))
        for area, fields in (
            ("INCIDENT", {"reconciliation_tracked_signature_count": 1025}),
            ("INCIDENT", {"reconciliation_source": "private text"}),
            ("TOOL", {"url_reader_refusal": "private endpoint"}),
            ("TOOL", {"round_catalog_discovery_available": "false"}),
        ):
            with self.assertRaises(ValueError):
                validate_diagnostics(self.capture(area, fields))

    def test_exception_capture_survives_validation_without_claiming_provider_dispatch(self):
        for site in ("AGENT_ROUND_CATCH", "CHAT_REPOSITORY_TERMINAL_CATCH", "OPENAI_ADAPTER_STREAM_CATCH", "PROVIDER_ADAPTER_STREAM_CATCH", "CHAT_REPOSITORY_ERROR_VALUE"):
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

    def test_error_value_boundary_preserves_unknown_cause_without_exception_metadata(self):
        capture = self.capture("INCIDENT", {
            "incident_phase": "unknown", "causal_provenance": "UNAVAILABLE",
            "causal_failure_code": "CHAT_FAILED", "incident_evidence_status": "CAUSAL_EVIDENCE_UNAVAILABLE",
            "incident_capture_site": "CHAT_REPOSITORY_ERROR_VALUE", "incident_phase_provenance": "CAPTURE_BOUNDARY_ONLY",
        })
        del capture["values"]["causal_id_hash"]
        capture["unavailableFields"] = sorted((CORE_FIELDS["INCIDENT"] | capsule.CORE) - capture["values"].keys())
        report = self.report(capture)
        approved = json.dumps(report, separators=(",", ":"))
        _, issue = issue_from_report(report)
        self.assertIn("CHAT_REPOSITORY_ERROR_VALUE", issue["body"])
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        self.assertNotIn("exception_type_hashes", capture["values"])
        self.assertTrue({"stage", "httpStatus", "dispatched", "retryable", "remoteAcceptance", "exceptionFingerprint"}.issubset(report["context"]["unavailableFields"]))

    def test_failed_event_presence_flags_roundtrip_without_untrusted_payload(self):
        values = {
            "incident_phase": "unknown", "causal_provenance": "UNAVAILABLE",
            "causal_failure_code": "CHAT_FAILED", "incident_evidence_status": "CAUSAL_EVIDENCE_UNAVAILABLE",
            "incident_capture_site": "AGENT_FAILED_EVENT", "incident_phase_provenance": "CAPTURE_BOUNDARY_ONLY",
            "failed_event_code_present": False, "failed_event_invocation_present": False,
            "failed_event_details_present": False,
        }
        report = self.report(self.capture("INCIDENT", values))
        report.update(feature="chat", errorCode="CHAT_FAILED")
        _, issue = issue_from_report(report)
        self.assertIn("AGENT_FAILED_EVENT", issue["body"])
        for key in capsule.FAILED_EVENT_FIELDS:
            invalid = copy.deepcopy(report)
            invalid["context"]["diagnostics"]["values"][key] = "private-value"
            with self.subTest(key=key), self.assertRaises(ReportError):
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
