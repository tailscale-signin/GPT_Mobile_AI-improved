import unittest

from authentication.issue_report_diagnostics import CORE_FIELDS, validate_diagnostics


class FailureDetailsTest(unittest.TestCase):
    def snapshot(self, area, values):
        return {"schemaVersion": 1, "area": area, "capturedAtMillis": 1700000000000,
                "captureMode": "INCIDENT", "values": values,
                "unavailableFields": sorted(CORE_FIELDS[area] - values.keys())}

    def test_readable_tool_and_recovery_facts_validate_without_private_values(self):
        for area, values in [
            ("TOOL", {"tool_action": "headers", "tool_error_code": "INVALID_ARGUMENTS",
                      "tool_reason_code": "TOOL_ARGUMENT_SCHEMA_REJECTED", "unknown_field_count": 1}),
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
