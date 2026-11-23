import unittest

from authentication.issue_report_diagnostics import CORE_FIELDS, validate_diagnostics


class FailureDetailsTest(unittest.TestCase):
    def test_repository_interruptions_keep_their_closed_cause_without_private_source_data(self):
        from authentication import repository_operation_report_contract as contract
        from authentication.issue_reports import ERROR_CODES

        self.assertTrue(contract.ERROR_CODES <= ERROR_CODES)
        for code in contract.ERROR_CODES:
            values = {"tool_action": "index", "tool_error_code": code,
                      "tool_dispatch_phase": "CANCELLED_LOCALLY"}
            capture = self.snapshot("TOOL", values)
            self.assertEqual(capture, validate_diagnostics(capture))
            for field in values:
                with self.subTest(code=code, field=field), self.assertRaises(ValueError):
                    validate_diagnostics(self.snapshot("TOOL", {**values, field: values[field] + " private/path"}))
        for reason in contract.TOOL_REASON_CODES:
            capture = self.snapshot("TOOL", {"tool_reason_code": reason})
            self.assertEqual(capture, validate_diagnostics(capture))

    def test_privacy_denial_is_a_closed_policy_category_not_a_network_failure(self):
        from authentication import incident_report_contract as capsule

        def capture(area, fields):
            return {**self.snapshot(area, fields), "schemaVersion": 3,
                    "unavailableFields": sorted((CORE_FIELDS[area] | capsule.CORE) - fields.keys())}

        for area, fields in (
            ("TOOL", {"tool_error_code": "PRIVACY_SERVICE_UNVERIFIED", "mcp_failure_phase": "local_validation",
                      "mcp_failure_category": "POLICY_BLOCKED", "mcp_connection_health": "CONNECTION_REFUSED",
                      "mcp_health_observation": "RETAINED_SNAPSHOT"}),
            ("PROVIDER", {"provider_failure_code": "PRIVACY_SERVICE_UNVERIFIED"}),
        ):
            sample = capture(area, fields)
            self.assertEqual(sample, validate_diagnostics(sample))
        for field, value in (("tool_error_code", "PRIVACY_SERVICE_UNVERIFIED private detail"),
                             ("mcp_failure_category", "POLICY_BLOCKED private detail")):
            with self.assertRaises(ValueError):
                validate_diagnostics(capture("TOOL", {field: value}))

    def snapshot(self, area, values):
        return {"schemaVersion": 1, "area": area, "capturedAtMillis": 1700000000000,
                "captureMode": "INCIDENT", "values": values,
                "unavailableFields": sorted(CORE_FIELDS[area] - values.keys())}

    def test_reasoning_probe_evidence_has_closed_outcomes_source_request_bounds_and_independent_ages(self):
        from authentication.issue_report_diagnostics import ENUMS, REASONING_PROBE_FIELDS
        values = {"reasoning_probe_outcome": "IDENTICAL_PROMPTS", "reasoning_probe_attempts": 2,
                  "reasoning_probe_age_ms": 8000, "reasoning_metadata_age_ms": 8}
        for outcome in ENUMS["reasoning_probe_outcome"]:
            sample = self.snapshot("PROVIDER", dict(values, reasoning_probe_outcome=outcome))
            self.assertEqual(sample, validate_diagnostics(sample))
        for key in REASONING_PROBE_FIELDS:
            invalid = ["private template", None, True, [], {}]
            invalid += [0] if key == "reasoning_probe_outcome" else [-1, "2", 2.5, 5 if key == "reasoning_probe_attempts" else 86_400_001]
            for value in invalid:
                with self.subTest(key=key, value=value), self.assertRaises(ValueError):
                    validate_diagnostics(self.snapshot("PROVIDER", dict(values, **{key: value})))
            for declaration in ("omittedFields", "unavailableFields"):
                sample = self.snapshot("PROVIDER", {})
                sample[declaration] = sample.get(declaration, []) + [key]
                self.assertEqual(sample, validate_diagnostics(sample))
            with self.assertRaises(ValueError):
                validate_diagnostics(self.snapshot("VOICE", {key: values[key]}))
            sample = self.snapshot("PROVIDER", values)
            sample["unavailableFields"].append(key)
            with self.assertRaises(ValueError):
                validate_diagnostics(sample)
        for attempts in (0, 4):
            self.assertTrue(validate_diagnostics(self.snapshot("PROVIDER", dict(values, reasoning_probe_attempts=attempts))))
        for age in (0, 86_400_000):
            self.assertTrue(validate_diagnostics(self.snapshot("PROVIDER", dict(values, reasoning_probe_age_ms=age, reasoning_metadata_age_ms=age))))
        legacy = self.snapshot("PROVIDER", {"reasoning_capability_age_ms": 8})
        self.assertEqual(legacy, validate_diagnostics(legacy))

    def test_runtime_incident_fields_are_scoped_and_bounded(self):
        rows = {
            "APP": {"native_trace_frames": 2, "native_trace_truncated": False, "native_signal": "SIGSEGV",
                    "native_trace_format": "protobuf", "native_trace_reason": "protobuf_crashed_thread", "native_top_frame_hash": "a" * 64},
            "TOOL": {"dispatch_gateway_selection_change": "REPLACED", "read_handle_kind": "NONCANONICAL_HANDLE", "search_explicit_mode": False, "search_query_length": 10},
            "ACCEPTANCE": {"acceptance_verified_claim_count": 5, "acceptance_unmeasured_claim_count": 2,
                           "acceptance_missing_pointer_hash": "c" * 64, "acceptance_pointer_receipt_hash": "e" * 64},
            "PROVIDER": {"reasoning_probe_enable_thinking": "IDENTICAL_PROMPTS", "reasoning_probe_thinking": "PREFIX_MISMATCH",
                         "provider_wait_decision_state": "NOT_RECORDED", "required_schema_count": 3, "required_catalog_schema_tokens": 300,
                         "required_recovery_schema_tokens": 600, "required_github_operation_tokens": 2135},
        }
        for area, fields in rows.items():
            capture = self.snapshot(area, fields)
            self.assertEqual(capture, validate_diagnostics(capture))
            for key in fields:
                with self.subTest(key=key), self.assertRaises(ValueError):
                    validate_diagnostics(self.snapshot(area, {key: "private input"}))
                with self.assertRaises(ValueError):
                    validate_diagnostics(self.snapshot("VOICE", {key: fields[key]}))
        with self.assertRaises(ValueError):
            validate_diagnostics(self.snapshot("APP", {"native_trace_frames": 17}))

    def test_readable_tool_and_recovery_facts_validate_without_private_values(self):
        for area, values in [
            ("TOOL", {"tool_action": "headers", "tool_error_code": "INVALID_ARGUMENTS",
                      "tool_reason_code": "TOOL_ARGUMENT_SCHEMA_REJECTED", "unknown_field_count": 1}),
            ("TOOL", {"tool_error_code": "INVALID_ARGUMENTS", "tool_reason_code": "SAVED_VIEW_CURSOR_MALFORMED",
                      "tool_dispatch_phase": "PREFLIGHT_REJECTED"}),
            ("PROVIDER", {"provider_wait_eligible": True, "provider_checkpoint_present": False,
                          "provider_consecutive_rejections": 3}),
            ("ACCEPTANCE", {"unmet_requirement_count": 2, "unmet_capability_count": 9}),
            ("APP", {"chat_failure_code_state": "ABSENT", "run_id_hash": "a" * 64}),
            ("PROVIDER", {"provider_failure_stage": "GENERATION", "provider_failure_domain": "PROVIDER", "provider_failure_code": "HTTP_ERROR"}),
            ("VOICE", {"voice_reply_state": "FAILED_WITHOUT_DETAILS", "voice_reply_characters": 0}),
        ]:
            sample = self.snapshot(area, values)
            self.assertEqual(sample, validate_diagnostics(sample))

    def test_recovery_fields_round_trip_and_reject_private_or_invalid_data(self):
        for area, values in [
            ("VOICE", {"voice_install_state": "QUEUED", "voice_install_scheduler": "JOB_SCHEDULER", "voice_install_progress_percent": 30}),
            ("PROVIDER", {"reasoning_requested_mode": "OFF", "reasoning_admission_basis": "NO_VERIFIED_CONTROL"}),
            ("TOOL", {"github_transport_kind": "DNS", "github_transport_wait_ms": 30000, "tool_reason_code": "SAVED_VIEW_CONTEXT_MISMATCH", "argument_page_size": 101}),
            ("ACCEPTANCE", {"workflow_input_blocked_count": 2, "repository_input_blocked_count": 16, "acceptance_failed_operation_count": 1, "acceptance_unresolved_claim_count": 1, "acceptance_claim_failure_reasons": ["POINTER_NOT_IN_EVIDENCE"]}),
        ]:
            self.assertEqual(self.snapshot(area, values), validate_diagnostics(self.snapshot(area, values)))
        for area, values in [
            ("TOOL", {"github_transport_kind": "private exception"}),
            ("TOOL", {"github_transport_wait_ms": -1}),
            ("PROVIDER", {"reasoning_requested_mode": "private-model"}),
            ("VOICE", {"voice_install_failure": "https://private-host"}),
            ("VOICE", {"argument_page_size": 1}),
        ]:
            with self.subTest(area=area, values=values), self.assertRaises(ValueError):
                validate_diagnostics(self.snapshot(area, values))

    def test_changed_github_subfields_are_closed_vocabulary(self):
        from authentication.incident_report_contract import valid_value
        self.assertTrue(valid_value("changed_policy_fields", ["githubProfileOverride", "githubProfileOverride.sampling"]))
        self.assertFalse(valid_value("changed_policy_fields", ["githubProfileOverride.private-token"]))

    def test_unknown_values_and_wrong_area_are_rejected(self):
        for area, values in [
            ("TOOL", {"tool_action": "private/path"}),
            ("TOOL", {"tool_error_code": "PRIVATE_ERROR"}),
            ("TOOL", {"unknown_field_count": -1}),
            ("TOOL", {"unknown_field_count": True}),
            ("APP", {"chat_failure_code_state": "raw exception"}),
            ("TOOL", {"provider_wait_eligible": True}),
            ("PROVIDER", {"provider_failure_code": "private error text"}),
            ("VOICE", {"voice_reply_state": "private answer text"}),
        ]:
            with self.subTest(area=area, values=values), self.assertRaises(ValueError):
                validate_diagnostics(self.snapshot(area, values))
