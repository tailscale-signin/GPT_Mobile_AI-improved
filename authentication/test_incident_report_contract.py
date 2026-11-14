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
        authority_source = (base / "data/context/ConfiguredDispatchAuthority.kt").read_text()
        override_helper = authority_source.split("internal fun githubOverrideChanges", 1)[1].split("internal fun profileRevision", 1)[0]
        override_keys = {"githubProfileOverride." + key for key in re.findall(r'"([A-Za-z]+)"', override_helper) if key != "githubProfileOverride"}
        self.assertEqual(11, len(override_keys))
        self.assertEqual(policy_keys | profile_keys | override_keys | {"profile.removed", "tool_inventory", "providerPermissions"}, capsule.POLICY_FIELD_NAMES)
        self.assertEqual(67, len(capsule.POLICY_FIELD_NAMES))

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
