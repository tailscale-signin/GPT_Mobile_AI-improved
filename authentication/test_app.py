import base64
import hashlib
import json
import re
import tempfile
import threading
import unittest
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from uuid import uuid4

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa
from fastapi.testclient import TestClient

from authentication.engineering_report_contract import ENGINEERING_REASON_FIELDS, GATEWAY_ADMISSION_CORE, MODEL_PREPARATION_REASONS
from authentication.app import create_app
from authentication.github_broker import Config, challenge_for
from authentication.issue_report_app import create_app as create_report_app
from authentication.issue_report_diagnostics import CORE_FIELDS, MAX_DIAGNOSTIC_BYTES, validate_diagnostics
from authentication.issue_reports import CONTEXT_ACCEPTANCE_REASONS, CONTEXT_ACCEPTANCE_RECOVERY, CONTEXT_FIELDS, CONTEXT_FIELDS_V2, CONTEXT_FIELDS_V4, CONTEXT_FIELDS_V5, CONTEXT_RECOVERY_ACTIONS, CONTEXT_RECOVERY_REASONS, CONTEXT_SELECTION_FIELDS, CONTEXT_TOOL_PRECHECK_REASONS, ERROR_CODES, GitHubIssuePublisher, GitHubRequestError, IssueReporter, REPOSITORY, ReportError, issue_from_report, marker


class HttpBoundaryTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.calls = []
        def exchange(payload):
            self.calls.append(payload)
            return {"access_token": "ghu_test", "token_type": "bearer"}
        self.app = create_app(Config("Iv1.test", "server-secret", "https://auth.example.org/oauth/github/callback", "https://auth.example.org", Path(self.temp.name) / "db"), exchange)
        self.client = TestClient(self.app, base_url="https://auth.example.org")

    def test_exchange_returns_credentials_only_over_https_with_no_store(self):
        verifier = "v" * 43
        start = self.client.post("/v1/github/oauth/start", json={"codeChallenge": challenge_for(verifier)}).json()
        response = self.client.post("/v1/github/oauth/exchange", json={"state": start["state"], "codeVerifier": verifier, "code": "code"})
        self.assertEqual(200, response.status_code)
        self.assertEqual("ghu_test", response.json()["access_token"])
        self.assertEqual("no-store", response.headers["cache-control"])
        self.assertEqual(1, len(self.calls))
        replay = self.client.post("/v1/github/oauth/exchange", json={"state": start["state"], "codeVerifier": verifier, "code": "code"})
        self.assertEqual(400, replay.status_code)
        self.assertEqual(1, len(self.calls))

    def test_http_wrong_hosts_and_cross_origin_requests_are_rejected(self):
        for url in ("http://auth.example.org", "https://attacker.example.org"):
            self.assertEqual(403, TestClient(self.app, base_url=url).get("/health/ready").status_code)
        response = self.client.post("/v1/github/oauth/start", json={"codeChallenge": "x" * 43}, headers={"Origin": "https://attacker.example.org"})
        self.assertEqual(403, response.status_code)

    def test_malformed_input_never_echoes_secrets_or_accepts_dynamic_configuration(self):
        for body in ({"client_secret": "do-not-echo"}, {"state": "do-not-echo", "code": 123}, {"codeChallenge": "x" * 43, "redirectUri": "https://attacker.example.org"}):
            response = self.client.post("/v1/github/oauth/start", json=body)
            self.assertEqual(400, response.status_code)
            self.assertNotIn("do-not-echo", response.text)
        response = self.client.post("/v1/github/oauth/start", content="do-not-echo" * 1000, headers={"Content-Type": "application/json"})
        self.assertEqual(400, response.status_code)
        self.assertNotIn("do-not-echo", response.text)
        self.assertEqual([], self.calls)

    def test_public_endpoint_rate_limit_is_bounded_and_actionable(self):
        for _ in range(60):
            self.assertEqual(200, self.client.get("/health/ready").status_code)
        response = self.client.get("/health/ready")
        self.assertEqual(429, response.status_code)
        self.assertEqual("600", response.headers["retry-after"])


class IssueReportHttpTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.database = Path(self.temp.name) / "reports.sqlite3"
        self.issued = []
        self.found = None
        self.now = 1_800_000_000
        self.publish_error = None
        self.reporter = IssueReporter(self.database, self, lambda: self.now)
        self.origin = "https://reports.example.org"
        self.app = create_report_app(self.origin, self.reporter, b"rate-limit-test-material")
        self.client = TestClient(self.app, base_url=self.origin)

    def create(self, issue):
        self.issued.append(issue)
        if self.publish_error:
            raise self.publish_error
        return 42

    def find(self, report_id, created):
        return self.found

    def submit(self, report):
        return self.client.post("/v1/issue-reports", json=report)

    def metadata(self):
        return {"reportId": str(uuid4()), "version": "0.9.66.7", "build": 141, "commit": "af658d8",
                "android": "16", "sdk": 36, "device": "ASUS ROG Phone 9"}

    def manual(self):
        return {**self.metadata(), "source": "manual", "title": "Voice playback stopped",
                "description": "Playback stopped when opening Voice Chat."}

    def test_manual_text_attachment_is_explicit_bounded_and_rendered_without_active_content(self):
        report = {**self.manual(), "attachment": {
            "name": "voice-details.txt", "mediaType": "text/plain", "originalBytes": 24,
            "sha256": "a" * 64, "text": "first step\nsecond step",
        }}
        response = self.submit(report)
        self.assertEqual(200, response.status_code)
        body = self.issued[-1]["body"]
        self.assertIn("### Approved text attachment", body)
        self.assertIn("voice-details.txt", body)
        self.assertIn("    first step", body)
        for malformed in (
            {**report, "attachment": {**report["attachment"], "mediaType": "image/png"}},
            {**report, "attachment": {**report["attachment"], "originalBytes": 4097}},
            {**report, "attachment": {**report["attachment"], "extra": "private"}},
            {**self.metadata(), "source": "debug", "feature": "app", "errorCode": "APP_ERROR", "attachment": report["attachment"]},
        ):
            self.assertEqual(422, self.submit(malformed).status_code)

    def context(self, **observed):
        result = {"schemaVersion": 1, "occurredAtMillis": self.now * 1000, "source": "CHAT_FAILURE", **observed}
        fields = CONTEXT_FIELDS_V5 if result["schemaVersion"] in (5, 6, 7) else CONTEXT_FIELDS_V4 if result["schemaVersion"] == 4 else CONTEXT_FIELDS_V2 if result["schemaVersion"] in (2, 3) else CONTEXT_FIELDS
        result["unavailableFields"] = sorted(fields - result.keys())
        return result

    def diagnostics(self, area, values=None, mode="INCIDENT"):
        values = values or {}
        return {"schemaVersion": 1, "area": area, "capturedAtMillis": self.now * 1000 - 1200, "captureMode": mode,
                "values": values, "unavailableFields": sorted(CORE_FIELDS[area] - values.keys())}

    def test_engineering_rejections_preserve_every_known_reason_and_field_only_in_version_six(self):
        for reason, names in ENGINEERING_REASON_FIELDS.items():
            with self.subTest(reason=reason):
                observed = {"stage": "request_validation", "operation": "github_engineering", "httpStatus": 409,
                            "reasonCode": reason, "invalidFields": sorted(names), "dispatched": False, "retryable": False}
                context = self.context(schemaVersion=6, **observed)
                report = {**self.metadata(), "source": "debug", "feature": "gateway", "errorCode": "ENGINEERING_STATE_OR_INPUT_CHANGED", "context": context}
                approved = json.dumps(report, separators=(",", ":"))
                _, issue = issue_from_report(report)
                self.assertIn(f"| Reason code | `{reason}` |", issue["body"])
                self.assertIn("| Invalid request fields | `" + (", ".join(sorted(names)) if names else "[]") + "` |", issue["body"])
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                for old in range(1, 6):
                    with self.assertRaises(ReportError):
                        issue_from_report({**report, "context": self.context(schemaVersion=old, **observed)})
        for invalid in ({"reasonCode": "private exception text"}, {"invalidFields": ["private_branch"]}):
            observed = {"reasonCode": "ENGINEERING_BRANCH_INVALID", **invalid}
            with self.assertRaises(ReportError):
                issue_from_report({**self.manual(), "context": self.context(schemaVersion=6, **observed)})

    def test_actual_gateway_rejections_and_model_preparation_are_supported_without_false_dispatch(self):
        from gateway.v14.engineering_rejection import _REASONS, state_or_input_rejection

        emitted = set()
        for error in [*_REASONS, "unknown private exception"]:
            rejection = state_or_input_rejection(ValueError(error))
            emitted.add(rejection["reasonCode"])
            context = self.context(schemaVersion=6, stage=rejection["failed_stage"], reasonCode=rejection["reasonCode"], invalidFields=rejection["invalid_fields"], retryable=False)
            report = {**self.metadata(), "source": "debug", "feature": "gateway", "errorCode": "ENGINEERING_STATE_OR_INPUT_CHANGED", "context": context}
            _, issue = issue_from_report(report)
            self.assertIn(rejection["reasonCode"], issue["body"])
            self.assertNotIn("unknown private exception", issue["body"])
            self.assertNotIn("| Dispatched |", issue["body"])
        self.assertEqual(set(ENGINEERING_REASON_FIELDS), emitted)
        codes = ("MODEL_PREPARATION_INCOMPLETE", "MODEL_PREPARATION_REJECTED", "MODEL_PREPARATION_STORAGE_UNAVAILABLE", "MODEL_PREPARATION_CONNECTION_FAILED")
        for code in codes:
            context = self.context(schemaVersion=6, stage="model_preparation", dispatched=False)
            _, issue = issue_from_report({**self.metadata(), "source": "debug", "feature": "gateway", "errorCode": code, "context": context})
            self.assertIn(code, issue["title"])
            self.assertIn("| Failure stage | `model_preparation` |", issue["body"])
            self.assertNotIn("| HTTP status |", issue["body"])
            self.assertNotIn("| Invocation hash |", issue["body"])
        for reason in MODEL_PREPARATION_REASONS:
            context = self.context(schemaVersion=6, stage="model_preparation", reasonCode=reason, dispatched=False)
            _, issue = issue_from_report({**self.manual(), "context": context})
            self.assertIn(f"| Reason code | `{reason}` |", issue["body"])
            with self.assertRaises(ReportError):
                issue_from_report({**self.manual(), "context": self.context(schemaVersion=6, stage="response_stream", reasonCode=reason)})

    def test_gateway_admission_without_a_job_round_trips_over_http_and_keeps_approved_bytes(self):
        values = {"profile_hash": "a" * 64, "model_hash": "b" * 64, "gateway_manifest_hash": "c" * 64,
                  "gateway_version": "16.0.0", "gateway_protocol_version": 1, "gateway_admission_ready": False,
                  "gateway_failure_phase": "model_preparation", "gateway_http_status": 409, "gateway_reason_code": "SELECTED_MODEL_NOT_LOADED"}
        capture = {**self.diagnostics("GATEWAY", values, "SELECTED_CAPTURE"), "schemaVersion": 2,
                   "unavailableFields": sorted((CORE_FIELDS["GATEWAY"] | GATEWAY_ADMISSION_CORE) - values.keys())}
        report = {**self.manual(), "context": self.context(schemaVersion=6, source="MANUAL_REPORT", diagnostics=capture)}
        approved = json.dumps(report, separators=(",", ":"))
        response = self.client.post("/v1/issue-reports", content=approved, headers={"Content-Type": "application/json"})
        self.assertEqual(200, response.status_code)
        self.assertEqual(1, len(self.issued))
        body = self.issued[0]["body"]
        for name in values:
            self.assertIn(f"| {name} |", body)
        self.assertIn("`job_id_hash`", body)
        self.assertNotIn("| job_id_hash |", body)
        self.assertIn("timestamp does not establish the time", body)
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(1, len(self.issued))
        with self.assertRaises(ReportError):
            issue_from_report({**report, "context": self.context(schemaVersion=5, source="MANUAL_REPORT", diagnostics=capture)})

    def test_gateway_admission_snapshot_rejects_private_values_wrong_types_and_legacy_schema(self):
        values = {"gateway_manifest_hash": "a" * 64, "gateway_protocol_version": 1, "gateway_admission_ready": True}
        capture = {**self.diagnostics("GATEWAY", values), "schemaVersion": 2,
                   "unavailableFields": sorted((CORE_FIELDS["GATEWAY"] | GATEWAY_ADMISSION_CORE) - values.keys())}
        for extra in ({"gateway_version": "16.0.0 private-path"}, {"gateway_manifest_hash": "private-host"},
                      {"gateway_protocol_version": True}, {"gateway_admission_ready": "true"},
                      {"gateway_failure_phase": "private-stage"}, {"gateway_http_status": 999},
                      {"gateway_reason_code": "PRIVATE_FAILURE"}, {"prompt": "private-prompt"}):
            with self.subTest(extra=extra), self.assertRaises(ValueError):
                validate_diagnostics({**capture, "values": {**values, **extra}})
        with self.assertRaises(ValueError):
            validate_diagnostics({**capture, "schemaVersion": 1})
        with self.assertRaises(ValueError):
            validate_diagnostics({**capture, "area": "PROVIDER"})

    def test_additional_diagnostics_reach_github_with_typed_provider_tool_acceptance_and_voice_facts(self):
        cases = {
            "PROVIDER": {"profile_hash": "a" * 64, "model_hash": "b" * 64, "configured_output_limit": 4096, "effective_output_limit": 2048,
                         "output_tokens": 2048, "reasoning_tokens": 1900, "visible_output_bytes": 0, "finish_reason": "LENGTH", "transport_complete": True,
                         "backend_attempts": 2, "backend_terminal_status": 503, "backend_terminal_code_hash": "c" * 64, "output_byte_scope": "LAST_BACKEND_ATTEMPT",
                         "privacy_observation": "NO_COMPATIBLE_ENDPOINT", "privacy_zdr_required": True, "privacy_provider_allowlist_count": 2},
            "TOOL": {"tool_name_hash": "a" * 64, "tool_action_hash": "b" * 64, "tool_schema_hash": "c" * 64, "original_error_code_hash": "d" * 64,
                     "missing_field_count": 1, "invalid_field_count": 0, "invalid_field_classes": ["MISSING_REQUIRED"]},
            "ACCEPTANCE": {"unmet_criterion_id_hashes": ["a" * 64, "b" * 64], "unmet_criterion_categories": ["DEVICE_EXECUTION", "CI_VERIFICATION"],
                           "unmet_criterion_states": ["NOT_TESTED", "BLOCKED"], "unmet_criterion_blockers": ["DEVICE_EXECUTION_NOT_OBSERVED", "CHECK_SUBSCRIPTION_REQUIRED"],
                           "effective_call_limit": 12, "used_calls": 10, "remaining_calls": 2, "checkpoint_hash": "c" * 64,
                           "unmet_criterion_count": 2, "unmet_criterion_omitted_count": 0, "acceptance_evidence_scope": "CURRENT_VERDICT"},
            "VOICE": {"voice_engine": "SYSTEM", "voice_phase": "SPEAKING", "voice_response_to_queue_ms": 21, "voice_synthesis_to_first_audio_ms": 80,
                      "voice_response_to_first_audio_ms": 101, "voice_first_audio_basis": "UTTERANCE_START", "voice_session_id_hash": "d" * 64,
                      "voice_permission_granted": True, "voice_focus_result": "GRANTED"},
        }
        for area, values in cases.items():
            with self.subTest(area=area):
                capture = self.diagnostics(area, values)
                context = self.context(schemaVersion=5, diagnostics=capture)
                report = {**self.metadata(), "source": "debug", "feature": "chat", "errorCode": "CHAT_FAILED", "context": context}
                response = self.submit(report)
                self.assertEqual(200, response.status_code)
                self.assertEqual({"reportId": report["reportId"], "number": 42}, response.json())
                body = self.issued[-1]["body"]
                self.assertIn("Additional diagnostic observation", body)
                for key, value in values.items():
                    rendered = ", ".join(value) if isinstance(value, list) else str(value).lower() if type(value) is bool else str(value)
                    self.assertIn(f"| {key} | `{rendered}` |", body)
                self.assertEqual(capture, context["diagnostics"])
                self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(len(cases), len(self.issued))

    def test_voice_clock_detail_can_remain_local_without_changing_the_report_protocol(self):
        for basis in ("UTTERANCE_START", "PLAYBACK_HEAD"):
            with self.subTest(basis=basis):
                capture = self.diagnostics("VOICE", {"voice_engine": "KOKORO", "voice_phase": "SPEAKING",
                                                     "voice_first_audio_basis": basis, "voice_response_to_first_audio_ms": 98})
                report = {**self.manual(), "context": self.context(schemaVersion=5, source="VOICE_STATE", diagnostics=capture)}
                approved = json.dumps(report, separators=(",", ":"))
                self.assertEqual(200, self.submit(report).status_code)
                self.assertIn(f"| voice_first_audio_basis | `{basis}` |", self.issued[-1]["body"])
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        for basis in ("AUDIO_TIMESTAMP", "private-clock", "audio_timestamp", 1, None):
            with self.subTest(basis=basis):
                capture = self.diagnostics("VOICE", {"voice_first_audio_basis": basis})
                report = {**self.manual(), "context": self.context(schemaVersion=5, source="VOICE_STATE", diagnostics=capture)}
                self.assertEqual(422, self.submit(report).status_code)
        capture = self.diagnostics("VOICE", {"voice_engine": "KOKORO", "voice_phase": "SPEAKING",
                                             "voice_response_to_first_audio_ms": 98})
        report = {**self.manual(), "context": self.context(schemaVersion=5, source="VOICE_STATE", diagnostics=capture)}
        self.assertEqual(200, self.submit(report).status_code)
        self.assertIn("| voice_response_to_first_audio_ms | `98` |", self.issued[-1]["body"])
        self.assertNotIn("| voice_first_audio_basis |", self.issued[-1]["body"])
        self.assertEqual(3, len(self.issued))

    def test_preparing_voice_report_keeps_unknown_first_audio_as_unavailable(self):
        capture = self.diagnostics("VOICE", {"voice_engine": "KOKORO", "voice_phase": "PREPARING"})
        report = {**self.manual(), "context": self.context(schemaVersion=5, source="VOICE_STATE", diagnostics=capture)}
        approved = json.dumps(report, separators=(",", ":"))
        self.assertEqual(200, self.submit(report).status_code)
        body = self.issued[-1]["body"]
        self.assertIn("| voice_phase | `PREPARING` |", body)
        self.assertNotIn("| voice_first_audio_basis |", body)
        self.assertNotIn("| voice_response_to_first_audio_ms |", body)
        self.assertIn("`voice_response_to_first_audio_ms`", body)
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")))

    def test_model_preparation_capture_reaches_report_with_exact_approved_typed_fields(self):
        values = {"profile_hash": "a" * 64, "model_hash": "b" * 64, "operation_id_hash": "c" * 64,
                  "model_preparation_state": "LOAD_REJECTED", "model_preparation_action": "WAKE", "model_preparation_dispatch": "DISPATCHED",
                  "model_preparation_load_accepted": False, "model_preparation_load_requests": 1, "gateway_model_state": "SLEEPING",
                  "gateway_failure_category": "denied", "gateway_dependency": "selected_model", "gateway_upstream_status": 403,
                  "gateway_upstream_message_code": "INVALID_API_KEY", "gateway_reconciliation_status": 503}
        capture = self.diagnostics("GATEWAY", values, "SELECTED_CAPTURE")
        report = {**self.manual(), "context": self.context(schemaVersion=5, source="MANUAL_REPORT", diagnostics=capture)}
        self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(1, len(self.issued))
        for key, value in values.items():
            rendered = str(value).lower() if type(value) is bool else str(value)
            self.assertIn(f"| {key} | `{rendered}` |", self.issued[0]["body"])

    def test_model_preparation_capture_rejects_private_unknown_and_out_of_range_values(self):
        for field, value in (("operation_id_hash", "private-operation"), ("model_preparation_action", "private-action"),
                             ("model_preparation_load_accepted", "false"), ("model_preparation_load_requests", 2),
                             ("gateway_upstream_status", 999), ("gateway_dependency", "private-path"),
                             ("gateway_upstream_message_code", "private-text")):
            with self.subTest(field=field):
                report = {**self.manual(), "context": self.context(schemaVersion=5, diagnostics=self.diagnostics("GATEWAY", {field: value}))}
                self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual([], self.issued)

    def test_selected_observation_and_absent_capture_do_not_claim_a_failure_timestamp(self):
        for mode, values in (("SELECTED_CAPTURE", {"voice_engine": "SYSTEM", "voice_phase": "SPEAKING"}), ("NO_CAPTURE", {})):
            capture = self.diagnostics("VOICE", values, mode)
            report = {**self.manual(), "context": self.context(schemaVersion=5, source="MANUAL_REPORT", diagnostics=capture)}
            self.assertEqual(200, self.submit(report).status_code)
            body = self.issued[-1]["body"]
            self.assertIn(f"| Observation time (epoch ms) | `{capture['capturedAtMillis']}` |", body)
            self.assertIn("timestamp does not establish the time" if values else "No measurements are inferred", body)
            self.assertNotIn("| voice_read_calls |", body)
            self.assertIn("`voice_capture_attempts`", body)

    def test_protocol_two_composer_budget_and_graph_fields_reach_http_reports_without_new_absence_claims(self):
        from authentication.test_report_extensions import ReportExtensionsTest
        timeline = ReportExtensionsTest().timeline(("EDITOR_FOCUSED", "RELEASE_BACKGROUND_TAP", "EDITOR_UNFOCUSED"))
        legacy = {"composer_focus_transitions": 12, "composer_ime_transitions": 12, "composer_focused": False, "composer_ime_visible": False}
        for area, values in (("APP", timeline), ("APP", legacy), ("TOOL", {"tool_budget_bytes_available": 0}),
                             ("TOOL", {"graph_index_attempt_hash": "a" * 64})):
            capture = self.diagnostics(area, values, mode="SELECTED_CAPTURE")
            report = {**self.manual(), "context": self.context(schemaVersion=5, source="MANUAL_REPORT", diagnostics=capture)}
            payload = json.dumps(report, separators=(",", ":")).encode()
            response = self.client.post("/v1/issue-reports", content=payload, headers={"content-type": "application/json"})
            self.assertEqual(200, response.status_code)
            body = self.issued[-1]["body"]
            for key, value in values.items():
                text = json.dumps(value) if type(value) is bool else str(value)
                self.assertIn(f"| {key} | `{text}` |", body)
            if values == legacy:
                self.assertNotIn("composer_generation", body)
                self.assertNotIn("composer_event_1", body)
            if "tool_budget_bytes_available" in values:
                self.assertNotIn("tool_result_bytes_used", body)
                self.assertNotIn("tool_result_bytes_limit", body)
    def test_protocol_two_composer_tool_omissions_and_malformed_reports_are_checked_before_creation(self):
        from authentication.test_report_extensions import ReportExtensionsTest
        timeline = ReportExtensionsTest().timeline()
        for area, field in (("APP", "composer_event_1"), ("TOOL", "tool_budget_bytes_available"), ("TOOL", "graph_index_attempt_hash")):
            capture = self.diagnostics(area)
            capture["omittedFields"] = [field]
            self.assertEqual(200, self.submit({**self.manual(), "context": self.context(schemaVersion=5, diagnostics=capture)}).status_code)
        issued = len(self.issued)
        malformed = [("APP", {key: value for key, value in timeline.items() if key != "composer_event_2_age_ms"}),
                     ("APP", dict(timeline, composer_event_3_age_ms=1)), ("TOOL", {"tool_budget_bytes_available": "0"}),
                     ("TOOL", {"graph_index_attempt_hash": "private UUID"})]
        for area, values in malformed:
            response = self.submit({**self.manual(), "context": self.context(schemaVersion=5, diagnostics=self.diagnostics(area, values))})
            self.assertEqual(422, response.status_code)
        self.assertEqual(issued, len(self.issued))

    def test_protocol_two_reasoning_probe_reports_keep_separate_ages_and_legacy_payloads(self):
        values = {"reasoning_probe_outcome": "IDENTICAL_PROMPTS", "reasoning_probe_attempts": 2,
                  "reasoning_probe_age_ms": 8000, "reasoning_metadata_age_ms": 8}
        for observed in (values, {"reasoning_capability_age_ms": 8}):
            capture = self.diagnostics("PROVIDER", observed)
            report = {**self.metadata(), "source": "debug", "feature": "chat", "errorCode": "REASONING_ON_UNSUPPORTED",
                      "context": self.context(schemaVersion=5, source="CHAT_FAILURE", stage="provider_admission", diagnostics=capture)}
            payload = json.dumps(report, separators=(",", ":")).encode()
            response = self.client.post("/v1/issue-reports", content=payload, headers={"content-type": "application/json"})
            self.assertEqual(200, response.status_code)
            body = self.issued[-1]["body"]
            for key, value in observed.items():
                self.assertIn(f"| {key} | `{value}` |", body)
            if "reasoning_capability_age_ms" in observed:
                self.assertNotIn("reasoning_probe_age_ms", body)
                self.assertNotIn("reasoning_metadata_age_ms", body)
        for declaration in ("omittedFields", "unavailableFields"):
            capture = self.diagnostics("PROVIDER")
            capture[declaration] = capture.get(declaration, []) + list(values)
            response = self.submit({**self.manual(), "context": self.context(schemaVersion=5, diagnostics=capture)})
            self.assertEqual(200, response.status_code)
            self.assertEqual(capture, validate_diagnostics(capture))
        issued = len(self.issued)
        for key, value in (("reasoning_probe_outcome", "private template"), ("reasoning_probe_attempts", 5), ("reasoning_probe_age_ms", "8000")):
            capture = self.diagnostics("PROVIDER", dict(values, **{key: value}))
            response = self.submit({**self.manual(), "context": self.context(schemaVersion=5, diagnostics=capture)})
            self.assertEqual(422, response.status_code)
        self.assertEqual(issued, len(self.issued))

    def test_protocol_two_playback_reasons_are_closed_and_remain_nonexception_observations(self):
        for reason in ("PRONUNCIATION_UNAVAILABLE", "LEXICON_UNAVAILABLE", "SYNTHESIS_FAILED"):
            with self.subTest(reason=reason):
                capture = self.diagnostics("VOICE", {"voice_engine": "KOKORO", "voice_playback_failure": reason})
                report = {**self.metadata(), "source": "debug", "feature": "voice", "errorCode": "VOICE_PLAYBACK_FAILED",
                          "context": self.context(schemaVersion=5, source="VOICE_STATE", stage="voice_playback", diagnostics=capture)}
                payload = json.dumps(report, separators=(",", ":")).encode()
                response = self.client.post("/v1/issue-reports", content=payload, headers={"content-type": "application/json"})
                self.assertEqual(200, response.status_code)
                body = self.issued[-1]["body"]
                self.assertIn(f"| voice_playback_failure | `{reason}` |", body)
                self.assertEqual(capture, validate_diagnostics(capture))
                self.assertNotIn("errorType", body)
        issued = len(self.issued)
        for reason in ("private synthesis text", "UNKNOWN_WORD_PRIVATE", 2, True):
            capture = self.diagnostics("VOICE", {"voice_playback_failure": reason})
            response = self.submit({**self.manual(), "context": self.context(schemaVersion=5, source="VOICE_STATE", diagnostics=capture)})
            self.assertEqual(422, response.status_code)
        self.assertEqual(issued, len(self.issued))

    def test_nested_diagnostic_private_fields_wrong_types_and_wrong_area_never_publish(self):
        malformed = [
            ("prompt", "private-prompt"), ("requested_model_id", "private-model"), ("endpoint", "https://private.example"),
            ("voice_capture_attempts", "2"), ("voice_capture_attempts", True), ("voice_capture_attempts", -1), ("voice_capture_attempts", 16_000_001),
            ("voice_response_to_first_audio_ms", 86_400_001), ("voice_muted", "false"), ("voice_session_id_hash", "private-session"),
            ("voice_generation", 2**63), ("voice_first_audio_basis", "private-callback"), ("tool_name_hash", "a" * 64),
        ]
        for key, value in malformed:
            with self.subTest(key=key, value=value):
                capture = self.diagnostics("VOICE", {key: value})
                report = {**self.manual(), "context": self.context(schemaVersion=5, diagnostics=capture)}
                response = self.submit(report)
                self.assertEqual(422, response.status_code)
                self.assertEqual({"error": "INVALID_REPORT"}, response.json())
        for updates in ({"privateModel": "private-model"}, {"schemaVersion": True}, {"capturedAtMillis": "1000"}, {"captureMode": "private-mode"}, {"unavailableFields": []}):
            capture = {**self.diagnostics("VOICE"), **updates}
            response = self.submit({**self.manual(), "context": self.context(schemaVersion=5, diagnostics=capture)})
            self.assertEqual(422, response.status_code)
        self.assertFalse(self.issued)

    def test_diagnostics_require_v5_consistent_capture_and_aligned_bounded_criterion_columns(self):
        good = self.diagnostics("ACCEPTANCE", {"unmet_criterion_id_hashes": ["a" * 64], "unmet_criterion_categories": ["DEVICE_EXECUTION"]})
        for updates in (
            {"unmet_criterion_categories": ["DEVICE_EXECUTION", "CI_VERIFICATION"]},
            {"unmet_criterion_categories": ["private-criterion"]},
            {"unmet_criterion_id_hashes": ["a" * 64] * 9},
            {"unmet_criterion_id_hashes": []},
        ):
            capture = self.diagnostics("ACCEPTANCE", {**good["values"], **updates})
            self.assertEqual(422, self.submit({**self.manual(), "context": self.context(schemaVersion=5, diagnostics=capture)}).status_code)
        for context in (
            self.context(schemaVersion=4, diagnostics=good), self.context(schemaVersion=5),
            self.context(schemaVersion=5, diagnostics={**good, "captureMode": "NO_CAPTURE"}),
            self.context(schemaVersion=5, diagnostics={**good, "omittedFields": ["unmet_criterion_id_hashes"]}),
            self.context(schemaVersion=5, diagnostics={**good, "omittedFields": ["private-field"]}),
        ):
            self.assertEqual(422, self.submit({**self.manual(), "context": context}).status_code)
        self.assertFalse(self.issued)

    def test_largest_criterion_capture_and_report_remain_within_both_size_limits(self):
        values = {"unmet_criterion_id_hashes": ["a" * 64] * 8, "unmet_criterion_reason_hashes": ["b" * 64] * 8,
                  "unmet_criterion_categories": ["BUILD_REVISION_BINDING"] * 8, "unmet_criterion_states": ["NOT_TESTED"] * 8,
                  "unmet_criterion_blockers": ["WORKFLOW_RUN_OR_JOB_REQUIRED"] * 8,
                  **{key: "c" * 64 for key in ("run_id_hash", "invocation_id_hash", "call_id_hash", "job_id_hash", "profile_hash", "model_hash", "checkpoint_hash")},
                  **{key: 16_000_000 for key in ("effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count")},
                  "acceptance_evidence_scope": "CURRENT_VERDICT"}
        capture = self.diagnostics("ACCEPTANCE", values)
        self.assertLessEqual(len(json.dumps(capture, separators=(",", ":")).encode()), MAX_DIAGNOSTIC_BYTES)
        self.assertEqual(capture, validate_diagnostics(capture))
        report = {**self.manual(), "context": self.context(schemaVersion=5, diagnostics=capture)}
        self.assertLess(len(json.dumps(report).encode()), 8192)
        self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(1, len(self.issued))

    def test_lost_receipt_v5_reconciles_original_diagnostic_content_without_second_create(self):
        report = {**self.manual(), "context": self.context(schemaVersion=5, diagnostics=self.diagnostics("VOICE", {"voice_engine": "KOKORO", "voice_first_audio_basis": "PLAYBACK_HEAD", "voice_response_to_first_audio_ms": 98}, "SELECTED_CAPTURE"))}
        self.publish_error = GitHubRequestError()
        first = self.submit(report)
        self.assertEqual(503, first.status_code)
        original_body = self.issued[0]["body"]
        self.now += 121
        self.found = 42
        reordered_capture = {**report["context"]["diagnostics"], "values": dict(reversed(list(report["context"]["diagnostics"]["values"].items()))), "unavailableFields": list(reversed(report["context"]["diagnostics"]["unavailableFields"]))}
        response = self.submit({**report, "context": {**report["context"], "diagnostics": reordered_capture}})
        self.assertEqual(200, response.status_code)
        self.assertEqual({"reportId": report["reportId"], "number": 42}, response.json())
        self.assertEqual(1, len(self.issued))
        self.assertEqual(original_body, self.issued[0]["body"])

    def test_typed_capture_privacy_and_reader_failures_keep_specific_context(self):
        for feature, code, stage, extra in (
            ("voice", "VOICE_MICROPHONE_FOCUS_DENIED", "audio_focus", {"operation": "voice_capture", "dispatched": False}),
            ("chat", "PRIVACY_CONFIGURATION", "provider_admission", {"provider": "OPENROUTER", "dispatched": False, "remoteAcceptance": "NO", "recoveryAction": "RETRY_AFTER_CONFIGURATION_CHANGE"}),
            ("tools", "SOURCE_PAYMENT_REQUIRED", "retrieval", {"operation": "read_url", "httpStatus": 402, "dispatched": True}),
        ):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": feature, "errorCode": code,
                          "context": self.context(stage=stage, **extra)}
                self.assertEqual(200, self.submit(report).status_code)
                body = self.issued[-1]["body"]
                self.assertIn(code, body)
                self.assertIn(stage, body)
                if feature == "tools":
                    self.assertIn("402", body)

    def test_privacy_eligibility_report_retains_safe_reason_and_unknown_remote_outcome(self):
        capture = self.diagnostics("PROVIDER", {
            "provider_failure_code": "PRIVACY_PROVIDER_INELIGIBLE",
            "provider_failure_stage": "GENERATION",
            "provider_failure_domain": "APPLICATION_VERIFICATION",
            "privacy_reason_code": "VERIFICATION_UNAVAILABLE",
            "privacy_status": "NOT_VERIFIED",
            "request_replay_allowed": False,
            "logical_continuation_allowed": False,
        })
        context = self.context(schemaVersion=5, source="CHAT_FAILURE", stage="provider_generation",
                               provider="OPENROUTER", dispatched=True, diagnostics=capture)
        report = {**self.metadata(), "source": "debug", "feature": "chat",
                  "errorCode": "PRIVACY_PROVIDER_INELIGIBLE", "context": context}
        self.assertEqual(200, self.submit(report).status_code)
        body = self.issued[-1]["body"]
        self.assertIn("PRIVACY_PROVIDER_INELIGIBLE", body)
        self.assertIn("VERIFICATION_UNAVAILABLE", body)
        self.assertIn("NOT_VERIFIED", body)
        self.assertNotIn("remoteAcceptance", context.keys())
        self.assertIn("remoteAcceptance", context["unavailableFields"])
        for key, value in (("privacy_reason_code", "PRIVATE_KEY"), ("privacy_status", "PRIVATE_ACCOUNT")):
            with self.subTest(key=key):
                invalid = {**capture, "values": {**capture["values"], key: value}}
                rejected = self.submit({**report, "reportId": str(uuid4()), "context": {**context, "diagnostics": invalid}})
                self.assertEqual(422, rejected.status_code)
                self.assertNotIn(value, rejected.text)
        self.assertEqual(1, len(self.issued))

    def test_readiness_adds_context_capability_without_changing_legacy_service_identity(self):
        response = self.client.get("/health/ready")
        self.assertEqual(200, response.status_code)
        self.assertEqual({"service": "gpt-mobile-issue-reporter", "version": 2},
                         {key: response.json()[key] for key in ("service", "version")})
        self.assertEqual([1, 2, 3, 4, 5, 6, 7], response.json()["reportContextVersions"])
        self.assertEqual(["GATEWAY_MODEL_PREPARATION_V1", "REVIEW_COMPARISON_V1", "INCIDENT_FAILURE_DETAILS_V1", "DISPATCH_POLICY_DIAGNOSTICS_V1", "CHANGED_POLICY_FIELDS_V1", "EXECUTION_OUTCOME_DIAGNOSTICS_V1", "RUN_FAILURE_EVIDENCE_V1", "RECOVERY_OBSERVATIONS_V1", "ISSUE_RECOVERY_DETAILS_V1", "ADMISSION_DIAGNOSTICS_V1", "SESSION_RECOVERY_DIAGNOSTICS_V1", "VOICE_PLAYBACK_PHASE_V1", "CAPACITY_ADMISSION_EVIDENCE_V1", "CONVERSATION_CONFLICT_EVIDENCE_V1", "ACCEPTANCE_SCOPE_EVIDENCE_V1", "VOICE_INSTALL_EVIDENCE_V1", "GITHUB_SCHEMA_BUDGET_EVIDENCE_V1", "VOICE_CAPTURE_PLAYBACK_EVIDENCE_V1", "COMPLETION_EVIDENCE_V1", "COMPOSER_IME_EVIDENCE_V1", "GITHUB_PREREQUISITE_EVIDENCE_V1", "GITHUB_GRAPH_POLICY_EVIDENCE_V1", "ACCEPTANCE_RECOVERY_EVIDENCE_V1", "ISSUE_RECOVERY_CODES_V1", "CAPTURE_BOUNDARY_EVIDENCE_V1", "HANDOFF_RECONCILIATION_EVIDENCE_V1", "CHAT_DELIVERY_EVIDENCE_V1", "CLAIM_FRESHNESS_EVIDENCE_V1", "PROFILE_SELECTION_EVIDENCE_V1", "GH_COOLDOWN_V1"], response.json()["reportDiagnosticFeatures"])
        self.assertEqual("no-store", response.headers["cache-control"])
        self.assertFalse(self.issued)
        # The Android client bounds the readiness body before admitting a report.
        self.assertLessEqual(len(response.content), 1024)
        from scripts.check_issue_reporting_configuration import check_readiness
        with self.assertRaisesRegex(ValueError, "reportDiagnosticFeatures"):
            check_readiness(self.origin, lambda _: response.content)
        # Old clients may continue posting their original reports without a
        # capability negotiation or a synthetic empty context object.
        self.assertEqual(200, self.submit(self.manual()).status_code)

    def test_gateway_capability_report_preserves_approved_evidence_and_rejects_private_or_unrelated_fields(self):
        capture = self.diagnostics("PROVIDER", {
            "provider_failure_code": "GATEWAY_CAPABILITY_UNAVAILABLE",
            "provider_failure_stage": "ADMISSION", "gateway_capability_failure_kind": "CONNECT_TIMEOUT",
            "request_replay_allowed": False, "logical_continuation_allowed": False,
        })
        context = self.context(schemaVersion=5, source="CHAT_FAILURE", stage="connection_preflight",
                               provider="LLAMA", dispatched=True, diagnostics=capture)
        report = {**self.metadata(), "source": "debug", "feature": "chat",
                  "errorCode": "GATEWAY_CAPABILITY_UNAVAILABLE", "context": context}
        first = self.submit(report)
        self.assertEqual(200, first.status_code)
        self.assertEqual(first.json(), self.submit(report).json())
        body = self.issued[-1]["body"]
        self.assertIn("| gateway_capability_failure_kind | `CONNECT_TIMEOUT` |", body)
        self.assertIn("| logical_continuation_allowed | `false` |", body)
        self.assertIn("remoteAcceptance", context["unavailableFields"])
        self.assertNotIn("remoteAcceptance", context)
        changed = {**capture, "values": {**capture["values"], "gateway_capability_failure_kind": "DNS"}}
        self.assertEqual(409, self.submit({**report, "context": {**context, "diagnostics": changed}}).status_code)
        for key, value in (
            ("gateway_capability_failure_kind", "https://private.example/secret"),
            ("gateway_capability_failure_kind", "ISOLATION_UNVERIFIED"),
            ("gateway_capability_decode_reason", "EXPECTED_ARRAY"),
            ("gateway_capability_field_path", "$.private.account"),
            ("provider_failure_code", "TRANSPORT_ERROR"),
        ):
            with self.subTest(key=key, value=value):
                invalid = {**capture, "values": {**capture["values"], key: value}}
                rejected = self.submit({**report, "reportId": str(uuid4()), "context": {**context, "diagnostics": invalid}})
                self.assertEqual(422, rejected.status_code)
                self.assertNotIn(value, rejected.text)
        self.assertEqual(1, len(self.issued))

    def test_extended_readiness_negotiates_publication_evidence_without_expanding_legacy_body(self):
        legacy = self.client.get("/health/ready")
        extended = self.client.get("/health/ready?capabilities=extended")
        self.assertEqual(200, extended.status_code)
        additions = ["CONVERSATION_RECOVERY_DIAGNOSTICS_V1", "REQUEST_BYTE_BUDGET_V1", "CLAIM_EXTRACTION_SCHEMA_HASH_V1", "ACCEPTANCE_PUBLICATION_EVIDENCE_V1", "TOOL_REPEAT_ADMISSION_EVIDENCE_V1",
                     "TOOL_EVENT_PERSISTENCE_EVIDENCE_V1", "ACCEPTANCE_OBSERVATION_CAUSES_V1", "SQLITE_CONSTRAINT_EVIDENCE_V1", "URL_READER_AVAILABILITY_V1", "GITHUB_POLICY_RESPONSE_EVIDENCE_V1", "TOOL_REPEAT_HISTORY_EVIDENCE_V1", "GITHUB_CI_SEMANTIC_EVIDENCE_V1", "TOOL_INVENTORY_PAGINATION_EVIDENCE_V1", "GITHUB_SEARCH_VALIDATION_EVIDENCE_V1", "GITHUB_SEARCH_ARGUMENT_EVIDENCE_V1", "REPORT_TEXT_ATTACHMENT_V1", "GITHUB_PR_CONTEXT_RACE_EVIDENCE_V1", "TOOL_PROTOCOL_FRAME_DIAGNOSTICS_V1", "TERMINAL_DELIVERY_CODES_V1", "PROVIDER_TERMINAL_CAUSES_V1", "PAGINATION_AND_PROVIDER_LIMIT_EVIDENCE_V1", "PRIVACY_ELIGIBILITY_EVIDENCE_V1", "GATEWAY_CAPABILITY_EVIDENCE_V1", "STATUS_COVERAGE_EVIDENCE_V1", "AUDIO_FOCUS_MODE_V1", "GITHUB_VALIDATION_CONTEXT_V1", "RUNTIME_INCIDENT_DETAILS_V1", "SELECTED_MODEL_ADMISSION_V1", "REVIEW_CATALOG_OBSERVATION_V1", "ACTION_ONLY_TOOL_PRECHECK_V1", "TOOL_REPEAT_PRIOR_CAUSE_V1", "PROFILE_BEHAVIOR_CHANGE_V1", "APP_SEARCH_READINESS_V1", "VOICE_SILENCED_INPUT_V1", "TASK_AND_TOOL_FAILURE_DETAILS_V1", "READER_AND_RUN_FAILURE_DETAILS_V1", "REPLAY_BUDGET_EVIDENCE_V1", "GRAPH_TRANSPORT_EVIDENCE_V1", "EXECUTION_COMPLETION_EVIDENCE_V1"]
        additions.insert(additions.index("REPLAY_BUDGET_EVIDENCE_V1") + 1, "REPLAY_SOURCE_EVIDENCE_V1")
        additions.insert(additions.index("ACCEPTANCE_OBSERVATION_CAUSES_V1") + 1, "ACCEPTANCE_EMPTY_EXPLANATION_V1")
        additions.insert(additions.index("ACCEPTANCE_EMPTY_EXPLANATION_V1") + 1, "GITHUB_ISSUE_INTAKE_EVIDENCE_V1")
        additions.insert(1, "VOICE_EXECUTION_EVIDENCE_V1")
        additions.insert(2, "VOICE_STREAMING_BACKEND_EVIDENCE_V1")
        additions.insert(3, "VOICE_STREAMING_NPU_EVIDENCE_V1")
        additions.insert(4, "VOICE_STREAMING_PACK_EVIDENCE_V1")
        additions.insert(5, "VOICE_STREAMING_INSTALL_EVIDENCE_V1")
        additions.insert(6, "VOICE_POCKET_COMPILED_EVIDENCE_V1")
        additions[additions.index("CLAIM_EXTRACTION_SCHEMA_HASH_V1") + 1:additions.index("CLAIM_EXTRACTION_SCHEMA_HASH_V1") + 1] = ["TLS_TRANSPORT_PHASE_EVIDENCE_V1", "GITHUB_READ_REVISION_EVIDENCE_V1"]
        additions.extend(["VISIBLE_OUTPUT_RECOVERY_EVIDENCE_V1", "COMBINED_SINGLE_PASS_EVIDENCE_V1", "REPOSITORY_READ_PROGRESS_V1"])
        additions.extend(["SAVED_PAGE_REPLAY_EVIDENCE_V1", "SAVED_PAGE_CAPACITY_EVIDENCE_V1", "GRAPH_LOCAL_CONTEXT_FAILURE_V1", "PROVIDER_LIMIT_CAUSE_EVIDENCE_V1"])
        additions.append("RECOVERY_REPORT_EVIDENCE_V1")
        additions.append("CATALOG_MISSING_TOOL_IDENTITY_V1")
        additions.append("TOOL_PRECHECK_GROUP_V1")
        additions.append("PROVIDER_QUOTA_GROUP_V1")
        additions.extend(["GITHUB_POLICY_SELECTION_FIELDS_V1", "VOICE_FOCUS_RETRY_COUNT_V1", "FAILED_EVENT_OUTPUT_FIELDS_V1"])
        additions.append("DEVICE_PRIVACY_POLICY_CHANGE_V1")
        additions.append("VOICE_FALLBACK_CAUSES_V1")
        additions.append("STAGED_DISPATCH_EVIDENCE_V1")
        additions.insert(additions.index("GITHUB_VALIDATION_CONTEXT_V1") + 1, "CAPABILITY_READINESS_EVIDENCE_V1")
        additions.insert(additions.index("CAPABILITY_READINESS_EVIDENCE_V1") + 1, "RESEARCH_RECOVERY_EVIDENCE_V1")
        additions.insert(0, "FRAMEWORK_EXCEPTION_FRAMES_V1")
        additions.insert(additions.index("PRIVACY_ELIGIBILITY_EVIDENCE_V1") + 1, "PRIVACY_FINAL_REQUEST_EVIDENCE_V1")
        self.assertEqual(legacy.json()["reportDiagnosticFeatures"] + additions,
                         extended.json()["reportDiagnosticFeatures"])
        for key in ("service", "version", "reportContextVersions"):
            self.assertEqual(legacy.json()[key], extended.json()[key])
        self.assertEqual(1022, len(legacy.content))
        self.assertLessEqual(len(legacy.content), 1024)
        self.assertGreater(len(extended.content), 1024)
        self.assertLessEqual(len(extended.content), 4096)
        self.assertEqual(legacy.content, self.client.get("/health/ready?capabilities=unrecognized").content)
        self.assertEqual("no-store", extended.headers["cache-control"])
        from scripts.check_issue_reporting_configuration import check_readiness
        requested = []
        def fetch(url):
            requested.append(url)
            return self.client.get(url).content
        check_readiness(self.origin, fetch)
        self.assertEqual([self.origin + "/health/ready?capabilities=extended"], requested)
        self.assertFalse(self.issued)

    def test_policy_selection_capability_matches_new_names_and_clipped_cardinality(self):
        from authentication import incident_report_contract as capsule
        feature = "GITHUB_POLICY_SELECTION_FIELDS_V1"
        legacy = self.client.get("/health/ready")
        ready = self.client.get("/health/ready?capabilities=extended")
        self.assertNotIn(feature, legacy.json()["reportDiagnosticFeatures"])
        self.assertIn(feature, ready.json()["reportDiagnosticFeatures"])
        self.assertIn("CATALOG_MISSING_TOOL_IDENTITY_V1", ready.json()["reportDiagnosticFeatures"])
        self.assertEqual(1022, len(legacy.content))
        self.assertLessEqual(len(ready.content), 4096)
        new_names = {"githubProfileOverride.nextActionPolicy", "githubProfileOverride.evidenceElaboration", "githubProfileOverride.primaryPatchReviewConsent"}
        cases = [[name] for name in sorted(new_names)] + [sorted(capsule.POLICY_FIELD_NAMES)[:count] for count in (82, 83, 84)]
        for names in cases:
            with self.subTest(names=names):
                self.now += 601
                retained = sorted(names)[:8]
                omitted = len(names) - len(retained)
                values = {"changed_policy_fields": retained, "changed_policy_field_count": len(names),
                          "changed_policy_unknown_count": 0, "changed_policy_malformed_count": 0,
                          "changed_policy_omitted_count": omitted, "changed_policy_observation": "PARTIAL" if omitted else "OBSERVED"}
                if omitted:
                    self.assertTrue(new_names.isdisjoint(retained))
                capture = self.diagnostics("INCIDENT", values)
                capture["schemaVersion"] = 3
                capture["unavailableFields"] = sorted(capsule.CORE - values.keys())
                report = {**self.manual(), "context": self.context(schemaVersion=7, source="CHAT_FAILURE", diagnostics=capture)}
                approved = json.dumps(report, separators=(",", ":"))
                response = self.submit(report)
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual(report["reportId"], response.json()["reportId"])
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                self.assertIn("| changed_policy_field_count | `" + str(len(names)) + "` |", self.issued[-1]["body"])
        self.assertEqual(6, len(self.issued))

    def test_grouped_tool_prechecks_keep_bounded_call_evidence_at_the_http_receiver(self):
        feature = "TOOL_PRECHECK_GROUP_V1"
        self.assertIn(feature, self.client.get("/health/ready?capabilities=extended").json()["reportDiagnosticFeatures"])
        self.assertNotIn(feature, self.client.get("/health/ready").json()["reportDiagnosticFeatures"])
        values = {"precheck_rejection_count": 3, "precheck_call_hashes": ["a" * 64, "b" * 64], "precheck_span_ms": 567}
        capture = self.diagnostics("TOOL", values)
        context = self.context(schemaVersion=5, source="TOOL_RESULT", diagnostics=capture)
        report = {**self.manual(), "context": context}
        self.assertEqual(200, self.submit(report).status_code)
        self.assertIn("precheck_rejection_count", self.issued[-1]["body"])
        for key, invalid in (("precheck_rejection_count", True), ("precheck_call_hashes", ["private-call"]), ("precheck_span_ms", -1)):
            changed = {**capture, "values": {**values, key: invalid}}
            self.assertEqual(422, self.submit({**report, "reportId": str(uuid4()), "context": {**context, "diagnostics": changed}}).status_code)

    def test_catalog_missing_tool_identity_is_advertised_with_receiver_validation(self):
        feature = "CATALOG_MISSING_TOOL_IDENTITY_V1"
        self.assertNotIn(feature, self.client.get("/health/ready").json()["reportDiagnosticFeatures"])
        ready = self.client.get("/health/ready?capabilities=extended")
        self.assertIn(feature, ready.json()["reportDiagnosticFeatures"])
        self.assertIn("RECOVERY_REPORT_EVIDENCE_V1", ready.json()["reportDiagnosticFeatures"])
        self.assertLessEqual(len(ready.content), 4096)
        capture = self.diagnostics("TOOL", {"catalog_revision_hash": "a" * 64,
                                            "missing_tool_hash": "b" * 64, "catalog_rejection_count": 1})
        context = self.context(schemaVersion=5, source="TOOL_RESULT", diagnostics=capture)
        report = {**self.manual(), "context": context}
        first = self.submit(report)
        self.assertEqual(200, first.status_code)
        self.assertEqual(report["reportId"], first.json()["reportId"])
        self.assertEqual(first.json(), self.submit(report).json())
        self.assertIn("| missing_tool_hash | `" + "b" * 64 + "` |", self.issued[-1]["body"])
        for invalid in ("private_tool_name", "b" * 63, "b" * 65, 123, True):
            with self.subTest(invalid=invalid):
                changed = {**capture, "values": {**capture["values"], "missing_tool_hash": invalid}}
                rejected = self.submit({**report, "reportId": str(uuid4()), "context": {**context, "diagnostics": changed}})
                self.assertEqual(422, rejected.status_code)
                self.assertNotIn("private_tool_name", rejected.text)
        self.assertEqual(1, len(self.issued))

    def test_streaming_voice_report_values_are_advertised_then_preserved_by_the_http_receiver(self):
        from authentication import incident_report_contract as capsule
        feature = "VOICE_STREAMING_BACKEND_EVIDENCE_V1"
        self.assertNotIn(feature, self.client.get("/health/ready").json()["reportDiagnosticFeatures"])
        self.assertIn(feature, self.client.get("/health/ready?capabilities=extended").json()["reportDiagnosticFeatures"])
        compiled_feature = "VOICE_POCKET_COMPILED_EVIDENCE_V1"
        self.assertNotIn(compiled_feature, self.client.get("/health/ready").json()["reportDiagnosticFeatures"])
        self.assertIn(compiled_feature, self.client.get("/health/ready?capabilities=extended").json()["reportDiagnosticFeatures"])
        additions = {
            "voice_engine": ("MEDIAPIPE", "COMPILED_POCKET"),
            "voice_selected_backend": ("LITERT_KOKORO_CPU", "LITERT_KOKORO_GPU", "LITERT_KOKORO_NPU", "LITERT_KOKORO_GPU_OPTIMIZED", "LITERT_KOKORO_SM8750", "PHONON_CPU", "POCKET_CPU", "POCKET_GPU", "POCKET_NPU", "POCKET_MULTILINGUAL_CPU"),
            "voice_failure_requested_backend": ("POCKET_GPU", "POCKET_NPU"),
            "voice_install_pack": ("POCKET",),
            "voice_fallback_source": ("LITERT_KOKORO", "PHONON", "POCKET"),
        }
        for field, choices in additions.items():
            for value in choices:
                with self.subTest(field=field, value=value):
                    self.now += 601
                    capture = self.diagnostics("VOICE", {field: value})
                    capture["schemaVersion"] = 3
                    capture["unavailableFields"] = sorted((CORE_FIELDS["VOICE"] | capsule.CORE) - capture["values"].keys())
                    report = {**self.manual(), "context": self.context(schemaVersion=7, source="VOICE_STATE", diagnostics=capture)}
                    approved = json.dumps(report, separators=(",", ":"))
                    response = self.submit(report)
                    self.assertEqual(200, response.status_code, response.text)
                    self.assertIn(f"| {field} | `{value}` |", self.issued[-1]["body"])
                    self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
        self.assertEqual(sum(map(len, additions.values())), len(self.issued))

    def test_failed_event_output_flags_and_omissions_survive_submission_and_idempotent_replay(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        from authentication.issue_reports import ADMISSION_WINDOW
        fixtures = IncidentReportContractTest()
        self.assertIn("FAILED_EVENT_OUTPUT_FIELDS_V1", self.client.get("/health/ready?capabilities=extended").json()["reportDiagnosticFeatures"])
        flags = {"failed_event_provider_text_seen": True, "failed_event_answer_emitted": False}
        for omitted in (False, True):
            self.now += ADMISSION_WINDOW + 1
            capture = fixtures.capture("INCIDENT", {} if omitted else flags)
            if omitted:
                capture["omittedFields"] = sorted(flags)
            report = fixtures.report(capture)
            report["reportId"] = str(uuid4())
            approved = json.dumps(report, separators=(",", ":")).encode()
            for _ in range(2):
                receipt = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
                self.assertEqual(200, receipt.status_code, receipt.text)
            self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
            for name in flags:
                self.assertIn(name, self.issued[-1]["body"])
        for field, value in (("failed_event_answer_emitted", "false"), ("failed_event_unknown", True)):
            self.now += ADMISSION_WINDOW + 1
            report = fixtures.report(fixtures.capture("INCIDENT", {field: value}))
            report["reportId"] = str(uuid4())
            self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual(2, len(self.issued))

    def test_advertised_visible_output_recovery_contract_accepts_exact_approved_http_reports(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        fixtures = IncidentReportContractTest()
        ready = self.client.get("/health/ready?capabilities=extended").json()
        self.assertIn("VISIBLE_OUTPUT_RECOVERY_EVIDENCE_V1", ready["reportDiagnosticFeatures"])
        for reason, action in (
            ("BOUNDED_VISIBLE_OUTPUT_REPAIR", "UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST"),
            ("VISIBLE_OUTPUT_REPAIR_REQUEST_ALREADY_CLAIMED", "INSPECT_SAVED_PROVIDER_ATTEMPT"),
        ):
            with self.subTest(reason=reason):
                capture = fixtures.capture("INCIDENT", {"incident_phase": "final_synthesis", "no_output_stop_reason": reason})
                report = fixtures.report(capture)
                report.update(reportId=str(uuid4()), feature="chat", errorCode="OUTPUT_CONTINUATION_NO_PROGRESS")
                report["context"].update(source="CHAT_FAILURE", stage="final_synthesis", recoveryAction=action, retryable=False, resumeAllowed=False)
                report["context"]["unavailableFields"] = [field for field in report["context"]["unavailableFields"] if field not in report["context"]]
                approved = json.dumps(report, separators=(",", ":"))
                receipt = self.client.post("/v1/issue-reports", content=approved, headers={"Content-Type": "application/json"})
                self.assertEqual(200, receipt.status_code, receipt.text)
                self.assertEqual({"reportId": report["reportId"], "number": 42}, receipt.json())
                body = self.issued[-1]["body"]
                self.assertIn(reason, body)
                self.assertIn(action, body)
                self.assertNotIn("visible_output_repair_request_claimed", body)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")))
                replay = self.client.post("/v1/issue-reports", content=approved, headers={"Content-Type": "application/json"})
                self.assertEqual(receipt.json(), replay.json())
        self.assertEqual(2, len(self.issued))

    def test_policy_response_observations_cross_http_boundary_and_reject_private_or_malformed_data(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        from authentication.issue_reports import ADMISSION_WINDOW
        fixtures = IncidentReportContractTest()
        samples = (
            {"status_policy_protection_404_kind": "EXPLICIT_BRANCH_NOT_PROTECTED", "status_policy_protection_permission_header_names": []},
            {"status_policy_rules_404_kind": "OTHER_404", "status_policy_rules_permission_header_names": ["REPORTED_OAUTH_SCOPES", "ACCEPTED_GITHUB_PERMISSIONS", "SSO"]},
        )
        for values in samples:
            self.now += ADMISSION_WINDOW + 1
            report = fixtures.report(fixtures.capture("ACCEPTANCE", values))
            report["reportId"] = str(uuid4())
            approved = json.dumps(report, separators=(",", ":")).encode()
            response = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
            self.assertEqual(200, response.status_code)
            self.assertLessEqual(len(response.content), 1024)
            self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
            for key in values:
                self.assertIn(f"| {key} |", self.issued[-1]["body"])
            if "status_policy_protection_permission_header_names" in values:
                self.assertIn("| status_policy_protection_permission_header_names | `[]` |", self.issued[-1]["body"])
            self.assertEqual(200, self.submit(report).status_code)
        for values in (
            {"status_policy_rules_404_kind": "EXPLICIT_BRANCH_NOT_PROTECTED"},
            {"status_policy_protection_permission_header_names": ["private-oauth-scope"]},
            {"status_policy_rules_permission_header_names": ["SSO", "SSO"]},
            {"status_policy_protection_permission_header_names": "private-header-value"},
        ):
            report = fixtures.report(fixtures.capture("ACCEPTANCE", values))
            report["reportId"] = str(uuid4())
            self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual(len(samples), len(self.issued))

    def test_atomic_repeat_history_crosses_http_boundary_without_repairing_approved_groups(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        from authentication.issue_reports import ADMISSION_WINDOW
        fixtures = IncidentReportContractTest()
        observations = {
            "dependency_repeat_scope": "TOOL_NAME_AND_ARGUMENTS", "dependency_repeat_prior_admissions": 1,
            "dependency_repeat_batch_admissions": 0, "dependency_repeat_used": 1, "dependency_repeat_limit": 1,
            "dependency_repeat_prior_call_id_hashes": ["a" * 64], "dependency_repeat_prior_outcomes": ["UNKNOWN_OUTCOME"],
            "dependency_repeat_prior_coverage": "COMPLETE", "dependency_repeat_batch_call_id_hashes": [], "dependency_repeat_batch_coverage": "COMPLETE",
        }
        report = fixtures.report(fixtures.capture("TOOL", observations))
        approved = json.dumps(report, separators=(",", ":")).encode()
        response = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
        self.assertEqual(200, response.status_code)
        self.assertLessEqual(len(response.content), 1024)
        self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
        self.assertIn("| dependency_repeat_batch_call_id_hashes | `[]` |", self.issued[-1]["body"])
        self.assertIn("| dependency_repeat_prior_outcomes | `UNKNOWN_OUTCOME` |", self.issued[-1]["body"])
        for malformed in (
            {key: value for key, value in observations.items() if key != "dependency_repeat_prior_outcomes"},
            {**observations, "dependency_repeat_prior_outcomes": []},
            {**observations, "dependency_repeat_prior_call_id_hashes": ["private-call"]},
            {**observations, "dependency_repeat_prior_coverage": "UNAVAILABLE"},
            {**observations, "dependency_repeat_used": 2},
        ):
            self.now += ADMISSION_WINDOW + 1
            bad = fixtures.report(fixtures.capture("TOOL", malformed))
            bad["reportId"] = str(uuid4())
            self.assertEqual(422, self.submit(bad).status_code)
        self_linked = fixtures.report(fixtures.capture("TOOL", observations))
        self_linked["reportId"] = str(uuid4())
        self_linked["context"]["callIdHash"] = "a" * 64
        self_linked["context"]["unavailableFields"].remove("callIdHash")
        original = json.dumps(self_linked, separators=(",", ":"))
        self.assertEqual(422, self.submit(self_linked).status_code)
        self.assertEqual(original, json.dumps(self_linked, separators=(",", ":")))
        self.assertEqual(1, len(self.issued))

    def test_ci_semantic_rules_cross_http_boundary_without_accepting_private_or_unavailable_data(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        from authentication.issue_reports import ADMISSION_WINDOW
        fixtures = IncidentReportContractTest()
        key = "semantic_ci_rules"
        rules = ["LOG_SPAN", "LOG_ORDER", "LOG_START", "UNUSED_FIELD", "MISSING_SELECTOR", "WORKFLOW_SELECTOR", "POSITIVE_ID", "COMMIT_SHA", "BRANCH_NAME"]
        for omitted in (False, True):
            self.now += ADMISSION_WINDOW + 1
            capture = fixtures.capture("TOOL", {} if omitted else {key: rules})
            if omitted:
                capture["omittedFields"] = [key]
            report = fixtures.report(capture)
            report["reportId"] = str(uuid4())
            approved = json.dumps(report, separators=(",", ":")).encode()
            response = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
            self.assertEqual(200, response.status_code)
            self.assertLessEqual(len(response.content), 1024)
            self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
            self.assertIn(key, self.issued[-1]["body"])
            if not omitted:
                self.assertIn(f"| {key} | `{', '.join(rules)}` |", self.issued[-1]["body"])
        for bad in ([], ["private-rule"], ["LOG_ORDER", "private-rule"], ["LOG_START", "LOG_START"], rules + ["LOG_START"]):
            report = fixtures.report(fixtures.capture("TOOL", {key: bad}))
            report["reportId"] = str(uuid4())
            response = self.submit(report)
            self.assertEqual(422, response.status_code)
            self.assertNotIn("private-rule", response.text)
        unobserved = fixtures.capture("TOOL")
        unobserved["unavailableFields"].append(key)
        report = fixtures.report(unobserved)
        report["reportId"] = str(uuid4())
        self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual(2, len(self.issued))

    def test_search_validation_names_cross_http_boundary_with_exact_approval_and_no_private_data(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        from authentication.issue_reports import ADMISSION_WINDOW
        fixtures = IncidentReportContractTest()
        key = "search_validation_field_names"
        names = ["batch_size", "max_new_blobs", "context_lines", "fuzzy_threshold"]
        for omitted in (False, True):
            self.now += ADMISSION_WINDOW + 1
            capture = fixtures.capture("TOOL", {} if omitted else {key: names})
            if omitted:
                capture["omittedFields"] = [key]
            report = fixtures.report(capture)
            report["reportId"] = str(uuid4())
            approved = json.dumps(report, separators=(",", ":")).encode()
            response = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
            self.assertEqual(200, response.status_code)
            self.assertLessEqual(len(response.content), 1024)
            self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
            self.assertIn(key, self.issued[-1]["body"])
            if not omitted:
                self.assertIn(f"| {key} | `{', '.join(names)}` |", self.issued[-1]["body"])
        for bad in ([], ["private-query"], ["context_lines", "private-query"], ["context_lines", "context_lines"], names + ["context_lines"]):
            report = fixtures.report(fixtures.capture("TOOL", {key: bad}))
            report["reportId"] = str(uuid4())
            response = self.submit(report)
            self.assertEqual(422, response.status_code)
            self.assertNotIn("private-query", response.text)
        unobserved = fixtures.capture("TOOL")
        unobserved["unavailableFields"].append(key)
        report = fixtures.report(unobserved)
        report["reportId"] = str(uuid4())
        self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual(2, len(self.issued))

    def test_inventory_pagination_crosses_http_boundary_with_exact_optional_observations(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        from authentication.issue_reports import ADMISSION_WINDOW
        fixtures = IncidentReportContractTest()
        reason, offered = "inventory_pagination_reason", "inventory_restart_offered"
        samples = [({reason: "MISSING_VERSION"}, []), ({reason: "VERSION_MISMATCH", offered: False}, []),
                   ({offered: True}, [reason]), ({reason: "VERSION_MISMATCH"}, [offered])]
        for values, omissions in samples:
            self.now += ADMISSION_WINDOW + 1
            capture = fixtures.capture("TOOL", values)
            if omissions:
                capture["omittedFields"] = omissions
            report = fixtures.report(capture)
            report["reportId"] = str(uuid4())
            approved = json.dumps(report, separators=(",", ":")).encode()
            response = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
            self.assertEqual(200, response.status_code)
            self.assertLessEqual(len(response.content), 1024)
            self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
            for key, value in values.items():
                rendered = str(value).lower() if type(value) is bool else value
                self.assertIn(f"| {key} | `{rendered}` |", self.issued[-1]["body"])
        for values in ({reason: "private-version"}, {reason: True}, {offered: "false"}, {offered: 0}):
            report = fixtures.report(fixtures.capture("TOOL", values))
            report["reportId"] = str(uuid4())
            response = self.submit(report)
            self.assertEqual(422, response.status_code)
            self.assertNotIn("private-version", response.text)
        for key in (reason, offered):
            capture = fixtures.capture("TOOL")
            capture["unavailableFields"].append(key)
            report = fixtures.report(capture)
            report["reportId"] = str(uuid4())
            self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual(len(samples), len(self.issued))

    def test_new_incident_observations_cross_http_boundary_without_replacing_approved_payload(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        fixtures = IncidentReportContractTest()
        cases = (
            ("TOOL", {"dependency_repeat_scope": "TOOL_NAME_AND_ARGUMENTS", "dependency_repeat_prior_admissions": 3,
                      "dependency_repeat_batch_admissions": 0, "dependency_repeat_used": 3, "dependency_repeat_limit": 3}),
            ("INCIDENT", {"tool_event_persistence_operation": "START", "tool_event_persistence_phase": "ALLOCATE_SEQUENCE",
                          "tool_event_persistence_failure": "SEQUENCE_EXHAUSTED", "tool_event_requested_sequence": -1,
                          "tool_event_previous_max_sequence": 2_147_483_647, "tool_event_write_attempted": False}),
            ("INCIDENT", {"sqlite_constraint_kind": "UNKNOWN"}),
            ("INCIDENT", {"review_comparison_state": "BASE_REF_BLOCKED", "review_comparison_policy": "EXPLICIT_BASE_REF"}),
            ("ACCEPTANCE", {"acceptance_publication_entry": "COMPLETED_DRAFT",
                            "acceptance_claim_trust_failures": ["TARGET_OUTSIDE_ACCEPTED_SCOPE"]}),
            ("ACCEPTANCE", {"acceptance_publication_source": "ISSUE_INTAKE", "acceptance_publication_entry": "ISSUE_INTAKE"}),
        )
        for area, values in cases:
            with self.subTest(area=area, values=values):
                # These are independent wire cases, outside the admission window.
                from authentication.issue_reports import ADMISSION_WINDOW
                self.now += ADMISSION_WINDOW + 1
                report = fixtures.report(fixtures.capture(area, values))
                report["reportId"] = str(uuid4())
                approved = json.dumps(report, separators=(",", ":")).encode()
                response = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
                self.assertEqual(200, response.status_code)
                self.assertLessEqual(len(response.content), 1024)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
                self.assertIn(marker(report["reportId"]), self.issued[-1]["body"])
                for key in values:
                    self.assertIn(f"| {key} |", self.issued[-1]["body"])
                self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(len(cases), len(self.issued))

    def test_new_incident_observations_reject_malformed_values_before_issue_creation(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        fixtures = IncidentReportContractTest()
        for area, values in (
            ("TOOL", {"dependency_repeat_prior_admissions": True}),
            ("TOOL", {"dependency_repeat_limit": 16_000_001}),
            ("INCIDENT", {"tool_event_requested_sequence": -2_147_483_649}),
            ("INCIDENT", {"tool_event_assigned_sequence": -1}),
            ("INCIDENT", {"tool_event_write_attempted": "false"}),
            ("INCIDENT", {"sqlite_constraint_kind": "private table name"}),
            ("INCIDENT", {"review_comparison_state": "private ref"}),
            ("ACCEPTANCE", {"acceptance_claim_trust_failures": ["TARGET_OUTSIDE_ACCEPTED_SCOPE"] * 2}),
        ):
            with self.subTest(values=values):
                report = fixtures.report(fixtures.capture(area, values))
                report["reportId"] = str(uuid4())
                self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual([], self.issued)

    def test_versioned_context_keeps_causal_facts_and_explicit_unavailable_fields(self):
        context = self.context(stage="provider_admission", provider="LLAMA", httpStatus=409, dispatched=False,
                               retryable=False, resumeAllowed=False, remoteAcceptance="NO",
                               reasonCode="SELECTION_SCHEMA_INVALID", missingFields=["tool_owner", "required_capability_policy"],
                               runIdHash="a" * 64, invocationIdHash="b" * 64)
        report = {**self.metadata(), "source": "debug", "feature": "gateway", "errorCode": "EXECUTION_SELECTION_INVALID", "context": context}
        response = self.submit(report)
        self.assertEqual(200, response.status_code)
        self.assertEqual({"reportId": report["reportId"], "number": 42}, response.json())
        body = self.issued[0]["body"]
        for rendered in (
            "| Capture source | `CHAT_FAILURE` |",
            "| Failure stage | `provider_admission` |",
            "| Provider family | `LLAMA` |",
            "| HTTP status | `409` |",
            "| Dispatched | `false` |",
            "| Remote acceptance | `NO` |",
            "| Reason code | `SELECTION_SCHEMA_INVALID` |",
            "| Missing selection fields | `required_capability_policy, tool_owner` |",
            "| Run identity SHA-256 | `" + "a" * 64 + "` |",
        ):
            self.assertIn(rendered, body)
        self.assertIn("Unavailable fields: `activeJobIdHash`, `callIdHash`, `exceptionFingerprint`, `invalidFields`, `jobIdHash`, `jobState`, `operation`, `recoveryAction`, `requiresReconciliation`, `retryAfterMillis`.", body)
        self.assertIn("absence does not establish success, non-dispatch, or permission to retry", body)
        self.assertNotIn("| Reported job identity", body)
        self.assertNotIn(context["runIdHash"].encode(), self.database.read_bytes())

    def test_recovery_context_separates_current_refusal_from_the_original_provider_interruption(self):
        context = self.context(schemaVersion=4, stage="lifecycle", reasonCode="PROVIDER_WAIT_EVIDENCE_UNAVAILABLE",
                               recoveryAction="REVIEW_SAVED_RECOVERY", dispatched=False, retryable=False, resumeAllowed=False,
                               remoteAcceptance="NO", originalFailureCode="PROVIDER_RATE_LIMITED", originalHttpStatus=429,
                               checkpointBindingHash="a" * 64, checkpointDigestHash="b" * 64, resumeClaimIdHash="c" * 64,
                               ownershipFence=7, runIdHash="d" * 64)
        report = {**self.metadata(), "source": "debug", "feature": "chat", "errorCode": "CHAT_FAILED", "context": context}
        self.assertEqual(200, self.submit(report).status_code)
        body = self.issued[-1]["body"]
        for row in ("| Context schema | `4` |", "| Dispatched | `false` |", "| Original interruption code | `PROVIDER_RATE_LIMITED` |",
                    "| Original interruption HTTP status | `429` |", "| Recovery ownership fence | `7` |"):
            self.assertIn(row, body)
        self.assertNotIn("| HTTP status |", body)
        self.assertNotIn("| Invocation identity SHA-256 |", body)
        self.assertIn("`invocationIdHash`", body.split("Unavailable fields:", 1)[1])
        self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(1, len(self.issued))

    def test_recovery_extensions_are_strictly_version_four_and_never_accept_private_identifiers(self):
        valid = {"originalFailureCode": "PROVIDER_RATE_LIMITED", "originalHttpStatus": 429,
                 "checkpointBindingHash": "a" * 64, "checkpointDigestHash": "b" * 64,
                 "resumeClaimIdHash": "c" * 64, "ownershipFence": 7}
        for field, values in (("reasonCode", CONTEXT_RECOVERY_REASONS), ("recoveryAction", CONTEXT_RECOVERY_ACTIONS)):
            for value in values:
                with self.subTest(field=field, value=value):
                    issue_from_report({**self.manual(), "context": self.context(schemaVersion=4, **{field: value})})
                    for version in (1, 2, 3):
                        with self.assertRaises(ReportError):
                            issue_from_report({**self.manual(), "context": self.context(schemaVersion=version, **{field: value})})
        for version in (1, 2, 3):
            for field, value in valid.items():
                with self.subTest(version=version, field=field), self.assertRaises(ReportError):
                    issue_from_report({**self.manual(), "context": self.context(schemaVersion=version, **{field: value})})
        invalid = [
            {"originalFailureCode": "429"}, {"originalFailureCode": 429}, {"originalFailureCode": "private-provider-text"},
            {"originalHttpStatus": True}, {"originalHttpStatus": "429"}, {"originalHttpStatus": 99}, {"originalHttpStatus": 600},
            {"ownershipFence": True}, {"ownershipFence": "7"}, {"ownershipFence": 1.5}, {"ownershipFence": -1}, {"ownershipFence": 2 ** 63},
            {"checkpoint_binding": "private-binding"}, {"checkpoint_digest": "private-digest"}, {"resume_claim_id": str(uuid4())},
            {"original_interruption": {"message": "private provider text"}}, {"originalMessage": "private provider text"},
        ]
        invalid += [{field: value} for field in ("checkpointBindingHash", "checkpointDigestHash", "resumeClaimIdHash")
                    for value in ("a" * 63, "A" * 64, "private-identifier", 7, None)]
        for observed in invalid:
            with self.subTest(observed=observed):
                response = self.submit({**self.manual(), "context": self.context(schemaVersion=4, **observed)})
                self.assertEqual(422, response.status_code)
                self.assertEqual({"error": "INVALID_REPORT"}, response.json())
        for fence in (0, 2 ** 63 - 1):
            issue_from_report({**self.manual(), "context": self.context(schemaVersion=4, ownershipFence=fence)})
        self.assertFalse(self.issued)

    def test_tool_precheck_reasons_require_version_four_and_explicit_non_dispatch(self):
        for reason in CONTEXT_TOOL_PRECHECK_REASONS:
            observed = {"source": "TOOL_RESULT", "stage": "tool_precheck", "dispatched": False, "reasonCode": reason}
            report = {**self.metadata(), "source": "debug", "feature": "tools", "errorCode": "TOOL_FAILURE"}
            _, issue = issue_from_report({**report, "context": self.context(schemaVersion=4, **observed)})
            self.assertIn(f"| Reason code | `{reason}` |", issue["body"])
            self.assertIn("| Dispatched | `false` |", issue["body"])
            for version in (1, 2, 3):
                with self.subTest(reason=reason, version=version), self.assertRaises(ReportError):
                    issue_from_report({**report, "context": self.context(schemaVersion=version, **observed)})
            for change in ({"source": "CHAT_FAILURE"}, {"stage": "tool_execution"}, {"dispatched": True}, {"dispatched": None}):
                with self.subTest(reason=reason, change=change), self.assertRaises(ReportError):
                    issue_from_report({**report, "context": self.context(schemaVersion=4, **{**observed, **change})})

    def test_active_job_context_does_not_invent_a_current_request_job_receipt(self):
        context = self.context(stage="conversation_admission", provider="LLAMA", httpStatus=409, dispatched=False,
                               retryable=False, resumeAllowed=False, activeJobIdHash="a" * 64,
                               jobState="WAITING_FOR_CLIENT_TOOL", recoveryAction="RETURN_CLIENT_TOOL_RESULTS")
        report = {**self.metadata(), "source": "debug", "feature": "gateway", "errorCode": "CONVERSATION_CONFLICT", "context": context}
        self.assertEqual(200, self.submit(report).status_code)
        body = self.issued[0]["body"]
        self.assertIn("| Failure stage | `conversation_admission` |", body)
        self.assertIn("| Active job identity SHA-256 | `" + "a" * 64 + "` |", body)
        self.assertIn("| Reported job state | `WAITING_FOR_CLIENT_TOOL` |", body)
        self.assertIn("| Recovery action | `RETURN_CLIENT_TOOL_RESULTS` |", body)
        self.assertNotIn("| Reported job identity", body)
        self.assertIn("`jobIdHash`", body.split("Unavailable fields:", 1)[1])

    def test_acceptance_context_preserves_observed_counts_and_keeps_missing_counts_unknown(self):
        context = self.context(schemaVersion=2, stage="acceptance_verification", provider="OPENROUTER",
                               reasonCode="UNVERIFIED_ACCEPTANCE_CRITERIA", recoveryAction="RESUME_UNFINISHED_WORK",
                               requiredCriteria=5, verifiedCriteria=4, remainingCriteria=1, runIdHash="a" * 64)
        report = {**self.metadata(), "source": "debug", "feature": "chat", "errorCode": "GITHUB_TASK_INCOMPLETE",
                  "context": context}
        self.assertEqual(200, self.submit(report).status_code)
        body = self.issued[-1]["body"]
        for rendered in (
            "| Context schema | `2` |", "| Failure stage | `acceptance_verification` |",
            "| Required acceptance criteria | `5` |", "| Verified acceptance criteria | `4` |",
            "| Remaining acceptance criteria | `1` |", "| Reason code | `UNVERIFIED_ACCEPTANCE_CRITERIA` |",
            "| Recovery action | `RESUME_UNFINISHED_WORK` |",
        ):
            self.assertIn(rendered, body)
        self.assertNotIn("| Dispatched |", body)
        for counts in ({}, {"requiredCriteria": 0, "verifiedCriteria": 0, "remainingCriteria": 0}):
            with self.subTest(counts=counts):
                missing_or_zero = self.context(schemaVersion=2, stage="acceptance_verification", **counts)
                self.assertEqual(200, self.submit({**report, "reportId": str(uuid4()), "context": missing_or_zero}).status_code)
                body = self.issued[-1]["body"]
                if counts:
                    self.assertIn("| Verified acceptance criteria | `0` |", body)
                else:
                    self.assertNotIn("| Verified acceptance criteria |", body)
                    self.assertIn("`verifiedCriteria`", body.split("Unavailable fields:", 1)[1])

    def assert_review_context_does_not_invent_dispatch_or_invocation(self, version):
        for stage, action in (("review_comparison", "SELECT_REVIEW_BASELINE"),
                              ("review_comparison", "RESTORE_REVIEW_COMPARISON_EVIDENCE"), ("approval", None)):
            with self.subTest(version=version, stage=stage, action=action):
                observed = {"stage": stage}
                if action is not None:
                    observed["recoveryAction"] = action
                report = {**self.metadata(), "source": "debug", "feature": "chat", "errorCode": "GITHUB_TASK_INCOMPLETE",
                          "context": self.context(schemaVersion=version, **observed)}
                self.assertEqual(200, self.submit(report).status_code)
                body = self.issued[-1]["body"]
                self.assertIn(f"| Context schema | `{version}` |", body)
                self.assertIn(f"| Failure stage | `{stage}` |", body)
                if action is not None:
                    self.assertIn(f"| Recovery action | `{action}` |", body)
                self.assertNotIn("| Dispatched |", body)
                self.assertNotIn("| Invocation identity SHA-256 |", body)
                self.assertIn("`dispatched`", body.split("Unavailable fields:", 1)[1])
                self.assertIn("`invocationIdHash`", body.split("Unavailable fields:", 1)[1])
                legacy = {**report, "reportId": str(uuid4()), "context": self.context(**observed)}
                self.assertEqual(422, self.submit(legacy).status_code)

    def test_review_context_v3_does_not_invent_dispatch_or_invocation(self):
        self.assert_review_context_does_not_invent_dispatch_or_invocation(3)

    def test_retained_extended_v2_does_not_invent_dispatch_or_invocation(self):
        self.assert_review_context_does_not_invent_dispatch_or_invocation(2)

    def test_acceptance_context_rejects_unbounded_or_inconsistent_counts_and_wrong_schema(self):
        invalid_counts = [{field: value} for field in ("requiredCriteria", "verifiedCriteria", "remainingCriteria")
                          for value in (-1, 100_001, True, 1.5, "5", None)]
        invalid_counts += [
            {"requiredCriteria": 5, "verifiedCriteria": 6}, {"requiredCriteria": 5, "remainingCriteria": 6},
            {"requiredCriteria": 5, "verifiedCriteria": 4, "remainingCriteria": 2},
            {"requiredCriteria": 5, "verifiedCriteria": 4, "remainingCriteria": 0},
            {"reasonCode": "private-criterion"}, {"recoveryAction": "EXECUTE_PRIVATE_TOOL"},
            {"criterionText": "private-criterion"},
        ]
        for version in (2, 3):
            for observed in invalid_counts:
                with self.subTest(version=version, observed=observed):
                    report = {**self.manual(), "context": self.context(schemaVersion=version, **observed)}
                    response = self.submit(report)
                    self.assertEqual(422, response.status_code)
                    self.assertEqual({"error": "INVALID_REPORT"}, response.json())
        for observed in ({"requiredCriteria": 1}, {"stage": "acceptance_verification"},
                         {"reasonCode": "UNVERIFIED_ACCEPTANCE_CRITERIA"}, {"recoveryAction": "RESUME_UNFINISHED_WORK"}):
            with self.subTest(legacy=observed):
                self.assertEqual(422, self.submit({**self.manual(), "context": self.context(**observed)}).status_code)
        context = self.context(schemaVersion=2, stage="acceptance_verification")
        context["unavailableFields"] = sorted(CONTEXT_FIELDS - context.keys())
        self.assertEqual(422, self.submit({**self.manual(), "context": context}).status_code)
        self.assertFalse(self.issued)

    def test_acceptance_reasons_and_recovery_actions_accept_only_the_closed_versioned_contract(self):
        for field, values in (("reasonCode", CONTEXT_ACCEPTANCE_REASONS), ("recoveryAction", CONTEXT_ACCEPTANCE_RECOVERY)):
            for value in sorted(values):
                with self.subTest(field=field, value=value):
                    _, issue = issue_from_report({**self.manual(), "context": self.context(schemaVersion=2, **{field: value})})
                    self.assertIn(f"`{value}`", issue["body"])
        for counts in ({"requiredCriteria": 100_000, "verifiedCriteria": 100_000, "remainingCriteria": 0},
                       {"verifiedCriteria": 1}, {"remainingCriteria": 100_000}):
            with self.subTest(counts=counts):
                issue_from_report({**self.manual(), "context": self.context(schemaVersion=2, **counts)})

    def test_verification_reserve_reports_keep_existing_contract_and_remaining_allowance(self):
        from authentication.test_incident_report_contract import IncidentReportContractTest
        fixtures = IncidentReportContractTest()
        cases = (
            ("chat", "CHAT_FAILURE", "acceptance_verification", "ACCEPTANCE",
             {"effective_call_limit": 64, "used_calls": 62, "remaining_calls": 2, "verification_reserve": 2},
             {"requiredCriteria": 4, "verifiedCriteria": 0, "remainingCriteria": 4, "resumeAllowed": False}),
            ("tools", "TOOL_RESULT", "tool_precheck", "TOOL",
             {"tool_budget_calls_used": 62, "tool_budget_calls_limit": 64, "tool_budget_phase": "BEFORE_DISPATCH"},
             {"dispatched": False, "requiresReconciliation": False}),
        )
        for feature, source, stage, area, values, observed in cases:
            with self.subTest(source=source):
                report = {**self.metadata(), "source": "debug", "feature": feature,
                          "errorCode": "GITHUB_TASK_INCOMPLETE",
                          "context": self.context(schemaVersion=7, source=source, stage=stage,
                                                  reasonCode="VERIFICATION_RESERVE_REACHED",
                                                  diagnostics=fixtures.capture(area, values), **observed)}
                approved = json.dumps(report, separators=(",", ":")).encode()
                response = self.client.post("/v1/issue-reports", content=approved, headers={"content-type": "application/json"})
                self.assertEqual(200, response.status_code, response.text)
                self.assertEqual(approved, json.dumps(report, separators=(",", ":")).encode())
                body = self.issued[-1]["body"]
                self.assertIn("| Reason code | `VERIFICATION_RESERVE_REACHED` |", body)
                for name, value in values.items():
                    self.assertIn(f"| {name} | `{value}` |", body)
                self.assertNotIn("BUDGET_EXHAUSTED", body)
                self.assertNotIn("| tool_budget_kind |", body)
                if source == "CHAT_FAILURE":
                    self.assertIn("| Verified acceptance criteria | `0` |", body)
                    self.assertIn("| Remaining acceptance criteria | `4` |", body)
                report["reportId"] = str(uuid4())
                report["context"]["reasonCode"] = "private unknown reserve reason"
                self.assertEqual(422, self.submit(report).status_code)
        self.assertEqual(len(cases), len(self.issued))

    def test_original_context_v1_digest_still_reconciles_a_pending_report_without_reposting(self):
        report = {**self.metadata(), "reportId": "00000000-0000-4000-8000-000000000001", "source": "debug",
                  "feature": "chat", "errorCode": "PRIVACY_CONFIGURATION",
                  "context": self.context(stage="provider_admission", provider="OPENROUTER", dispatched=False, remoteAcceptance="NO")}
        _, issue = issue_from_report(report)
        # Captured from the context-v1 publisher before context-v2 support was added.
        original_digest = "833d28740249e1092aa11cc2ee3e4c9e7b442dbfa338c74b5b70f1851c64c031"
        self.assertEqual(original_digest, hashlib.sha256(json.dumps(issue, sort_keys=True).encode()).hexdigest())
        with self.reporter._db() as db:
            db.execute("INSERT INTO reports VALUES (?, ?, 'dispatching', ?, 0, NULL)",
                       (report["reportId"], original_digest, self.now - 121))
        self.found = 77
        self.assertEqual({"reportId": report["reportId"], "number": 77}, self.submit(report).json())
        self.assertFalse(self.issued)
        self.assertNotIn("verifiedCriteria", issue["body"])

    def assert_retained_context_v2_digest_reconciles(self, observed, expected_digest, version=2):
        report = {**self.metadata(), "reportId": "00000000-0000-4000-8000-000000000001", "source": "debug",
                  "feature": "chat", "errorCode": "GITHUB_TASK_INCOMPLETE",
                  "context": self.context(schemaVersion=version, **observed)}
        _, issue = issue_from_report(report)
        self.assertEqual(expected_digest, hashlib.sha256(json.dumps(issue, sort_keys=True).encode()).hexdigest())
        with self.reporter._db() as db:
            db.execute("INSERT INTO reports VALUES (?, ?, 'dispatching', ?, 0, NULL)",
                       (report["reportId"], expected_digest, self.now - 121))
        self.found = 77
        self.assertEqual({"reportId": report["reportId"], "number": 77}, self.submit(report).json())
        self.assertFalse(self.issued)
        self.assertEqual(version, report["context"]["schemaVersion"])

    def test_original_context_v2_digest_remains_valid_after_context_v3_upgrade(self):
        # Frozen from the publisher at 7aaa9731a, before the version-3 repair.
        self.assert_retained_context_v2_digest_reconciles(
            {"stage": "acceptance_verification", "reasonCode": "UNVERIFIED_ACCEPTANCE_CRITERIA",
             "requiredCriteria": 5, "verifiedCriteria": 4, "remainingCriteria": 1},
            "ff07f986847ec76f52f5d0e6035d32c4d871bf5ee016d49f3df8ea6241d54a12",
        )

    def test_retained_extended_v2_digest_reconciles_without_rewriting_or_reposting(self):
        self.assert_retained_context_v2_digest_reconciles(
            {"stage": "review_comparison", "recoveryAction": "SELECT_REVIEW_BASELINE"},
            "9c7c90f465bf47dea86a11fc091cbe22c2a6c8c41402b5b7adf7419fffe47852",
        )

    def test_retained_v3_digest_survives_recovery_context_upgrade_without_reposting(self):
        # Frozen from the publisher at e1a75433, before recovery context version 4.
        self.assert_retained_context_v2_digest_reconciles(
            {"stage": "review_comparison", "recoveryAction": "SELECT_REVIEW_BASELINE"},
            "cd9c668e5b802db5d2d5a8193cfefa190af8410751503ec7e6ef5ce43ab1501c",
            version=3,
        )

    def test_context_never_accepts_private_values_unknown_fields_or_ambiguous_types(self):
        context = self.context(stage="tool_execution")
        changes = (
            {"rawMessage": "private-content"}, {"schemaVersion": True}, {"schemaVersion": 5},
            {"source": "private-content"}, {"occurredAtMillis": 0}, {"occurredAtMillis": True},
            {"occurredAtMillis": 10_000_000_000_000}, {"stage": "private-content"},
            {"provider": "private-model"}, {"operation": "private-tool"}, {"httpStatus": True},
            {"httpStatus": 99}, {"httpStatus": 600}, {"retryAfterMillis": -1}, {"retryAfterMillis": 86_400_001},
            {"retryAfterMillis": 1.5}, {"dispatched": "false"}, {"retryable": 1}, {"resumeAllowed": None},
            {"requiresReconciliation": 0}, {"remoteAcceptance": "ACCEPTED"}, {"jobState": "private-job"},
            {"recoveryAction": "REPLAY_MUTATION"}, {"reasonCode": "private-content"},
            {"missingFields": ["private-field"]}, {"invalidFields": ["tool_owner", "tool_owner"]},
            {"runIdHash": "private-id"}, {"activeJobIdHash": "A" * 64}, {"exceptionFingerprint": "x" * 64},
            {"unavailableFields": []}, {"unavailableFields": context["unavailableFields"] + ["stage"]},
            {"unavailableFields": context["unavailableFields"] + context["unavailableFields"][:1]},
        )
        for change in changes:
            with self.subTest(change=change):
                candidate = {**context, **change}
                if "unavailableFields" not in change:
                    candidate["unavailableFields"] = sorted(CONTEXT_FIELDS - candidate.keys())
                response = self.submit({**self.manual(), "context": candidate})
                self.assertEqual(422, response.status_code)
                self.assertEqual({"error": "INVALID_REPORT"}, response.json())
                self.assertNotIn("private", response.text)
        self.assertFalse(self.issued)

    def test_engineering_guard_stage_is_retained_without_inventing_provider_dispatch(self):
        context = self.context(stage="engineering_guard")
        code = "ENGINEERING_STATE_OR_INPUT_CHANGED"
        report = {**self.metadata(), "source": "debug", "feature": "gateway", "errorCode": code,
                  "events": [f"-001ms E gateway {code}"], "context": context}
        self.assertEqual(200, self.submit(report).status_code)
        self.assertIn(code, self.issued[0]["title"])
        body = self.issued[0]["body"]
        self.assertIn(f"-001ms E gateway {code}", body)
        self.assertIn("| Failure stage | `engineering_guard` |", body)
        self.assertNotIn("| Dispatched |", body)
        self.assertNotIn("| Remote acceptance |", body)
        self.assertIn("`dispatched`", body.split("Unavailable fields:", 1)[1])
        self.assertIn("`remoteAcceptance`", body.split("Unavailable fields:", 1)[1])

    def test_existing_voice_recovery_actions_keep_the_recorded_guidance(self):
        for action, code in (("SPEECH_SERVICE", "VOICE_RECOGNIZER_UNAVAILABLE"),
                             ("SPEECH_LANGUAGE", "VOICE_RECOGNIZER_LANGUAGE_UNAVAILABLE"),
                             ("LOCAL_MODEL", "VOICE_MODEL_REQUIRED")):
            with self.subTest(action=action):
                report = {**self.metadata(), "source": "debug", "feature": "voice", "errorCode": code,
                          "context": self.context(source="VOICE_STATE", stage="voice_recognition", operation="voice_recognition",
                                                  recoveryAction=action, retryAfterMillis=2000)}
                self.assertEqual(200, self.submit(report).status_code)
                self.assertIn("| Capture source | `VOICE_STATE` |", self.issued[-1]["body"])
                self.assertIn(f"| Recovery action | `{action}` |", self.issued[-1]["body"])
                self.assertIn("| Retry delay (ms) | `2000` |", self.issued[-1]["body"])

    def test_known_custom_and_local_client_families_do_not_require_private_model_names(self):
        for provider in ("CUSTOM", "LITERT_LM", "FREE", "NVIDIA"):
            with self.subTest(provider=provider):
                report = {**self.metadata(), "source": "debug", "feature": "chat", "errorCode": "CHAT_FAILED",
                          "context": self.context(provider=provider, stage="provider_generation")}
                self.assertEqual(200, self.submit(report).status_code)
                self.assertIn(f"| Provider family | `{provider}` |", self.issued[-1]["body"])

    def test_context_retry_reconciles_same_report_after_lost_receipt_without_new_issue(self):
        report = {**self.manual(), "context": self.context(source="MANUAL_REPORT", stage="voice_playback", operation="voice_playback")}
        self.publish_error = GitHubRequestError()
        self.assertEqual("AWAITING_GITHUB_RECEIPT", self.submit(report).json()["error"])
        self.now += 121
        self.found = 77
        restarted = IssueReporter(self.database, self, lambda: self.now)
        reordered = {**report, "context": {**report["context"], "unavailableFields": list(reversed(report["context"]["unavailableFields"]))}}
        self.assertEqual(77, restarted.submit(reordered, "another-network"))
        self.assertEqual({"reportId": report["reportId"], "number": 77}, self.submit(report).json())
        self.assertEqual(1, len(self.issued))
        changed = {**report, "context": self.context(source="MANUAL_REPORT", stage="voice_recognition", operation="voice_recognition")}
        self.assertEqual(409, self.submit(changed).status_code)
        self.assertEqual(1, len(self.issued))

    def test_legacy_report_body_remains_identical_for_existing_queued_report_digests(self):
        report = self.manual()
        report_id, issue = issue_from_report(report)
        self.assertEqual(report["reportId"], report_id)
        self.assertEqual({
            "title": "Voice playback stopped",
            "body": "### What happened\n\n    Playback stopped when opening Voice Chat."
                    "\n\n### App and device\n\n- App 0.9.66.7 (build 141, commit `af658d8`)"
                    "\n- Android 16 (API 36) · ASUS ROG Phone 9\n\n" + marker(report_id),
        }, issue)

    def test_maximal_context_and_unicode_manual_text_fit_the_unchanged_byte_limit(self):
        context = self.context(
            source="MANUAL_REPORT", stage="conversation_admission", provider="OPENROUTER", operation="github_engineering",
            httpStatus=599, retryAfterMillis=86_400_000, dispatched=True, retryable=False, resumeAllowed=False,
            requiresReconciliation=True, remoteAcceptance="UNKNOWN", jobState="WAITING_FOR_CLIENT_TOOL",
            recoveryAction="RECONCILE_ACTIVE_GATEWAY_JOB", reasonCode="SELECTION_CREDENTIAL_REFERENCE_INVALID",
            missingFields=sorted(CONTEXT_SELECTION_FIELDS), invalidFields=sorted(CONTEXT_SELECTION_FIELDS),
            runIdHash="a" * 64, invocationIdHash="b" * 64, callIdHash="c" * 64,
            jobIdHash="d" * 64, activeJobIdHash="e" * 64, exceptionFingerprint="f" * 64,
        )
        report = {**self.manual(), "title": "漢" * 120, "description": "漢" * 1200, "context": context,
                  "version": "v" * 48, "commit": "a" * 40, "android": "a" * 48, "device": "d" * 80,
                  "build": 2_147_483_647, "sdk": 999,
                  "events": ["-99999ms E gateway " + max(ERROR_CODES, key=len)] * 16}
        recovery = self.context(**{**context, "schemaVersion": 4, "reasonCode": "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE",
                                   "recoveryAction": "RECONCILE_TOOL_RECEIPTS", "requiredCriteria": 100_000,
                                   "verifiedCriteria": 0, "remainingCriteria": 100_000,
                                   "originalFailureCode": max(ERROR_CODES, key=len), "originalHttpStatus": 599,
                                   "checkpointBindingHash": "a" * 64, "checkpointDigestHash": "b" * 64,
                                   "resumeClaimIdHash": "c" * 64, "ownershipFence": 2 ** 63 - 1})
        for value in (context, recovery):
            payload = json.dumps({**report, "reportId": str(uuid4()), "context": value}, ensure_ascii=False, separators=(",", ":")).encode()
            self.assertLessEqual(len(payload), 8192)
            response = self.client.post("/v1/issue-reports", content=payload, headers={"Content-Type": "application/json"})
            self.assertEqual(200, response.status_code)
            self.assertIn("Unavailable fields: none declared by this capture.", self.issued[-1]["body"])

    def test_manual_report_and_retry_return_the_same_issue_after_restart(self):
        report = self.manual()
        report["description"] += "\nBearer placeholder-secret-value\n@someone\n" + marker(str(uuid4()))
        response = self.submit(report)
        self.assertEqual(200, response.status_code)
        self.assertEqual({"reportId": report["reportId"], "number": 42}, response.json())
        self.assertEqual("no-store", response.headers["cache-control"])
        restarted = IssueReporter(self.database, self, lambda: self.now)
        self.assertEqual(42, restarted.submit(report, "different-network"))
        self.assertEqual(1, len(self.issued))
        body = self.issued[0]["body"]
        self.assertIn("    @someone", body)
        self.assertIn("[redacted]", body)
        self.assertNotIn("placeholder-secret-value", body)
        self.assertTrue(body.endswith(marker(report["reportId"])))
        self.assertNotIn(b"Playback stopped", self.database.read_bytes())
        self.assertEqual(409, self.submit({**report, "title": "Changed report"}).status_code)

    def test_debug_only_accepts_public_fields_and_projected_events(self):
        report = {**self.metadata(), "source": "debug", "feature": "voice", "errorCode": "VOICE_PLAYBACK_FAILED",
                  "errorType": "IllegalStateException", "frames": ["android.media.AudioTrack.play:104"],
                  "events": ["-000ms E voice VOICE_PLAYBACK_FAILED", "-023ms I local"]}
        self.assertEqual(200, self.submit(report).status_code)
        self.assertIn("android.media.AudioTrack.play:104", self.issued[0]["body"])
        self.assertIn("Android 16 (API 36)", self.issued[0]["body"])
        self.assertIn("Recent app events", self.issued[0]["body"])
        for change in ({"events": ["raw private log"]}, {"events": ["-023ms E local PRIVATE_CODE"]},
                       {"frames": ["/data/data/private/path"]}, {"feature": []}, {"message": "private"},
                       {"repository": "unrelated/project"}, {"title": "arbitrary debug title"}):
            with self.subTest(change=change):
                self.assertEqual(422, self.submit({**report, "reportId": str(uuid4()), **change}).status_code)
        self.assertEqual(1, len(self.issued))

    def test_specific_voice_gateway_and_tool_failure_codes_survive_public_report_validation(self):
        for feature, code in (
            ("voice", "VOICE_MICROPHONE_PERMISSION_REQUIRED"),
            ("voice", "VOICE_RECOGNIZER_MEMORY_PRESSURE"),
            ("gateway", "GATEWAY_ENGINEERING_GITHUB_REQUIRED"),
            ("tools", "INVALID_ARGUMENTS"),
        ):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": feature, "errorCode": code,
                          "events": [f"-023ms E {feature} {code}"]}
                self.assertEqual(200, self.submit(report).status_code)
                self.assertIn(code, self.issued[-1]["title"])
                self.assertIn(f"-023ms E {feature} {code}", self.issued[-1]["body"])

    def test_local_privacy_denial_survives_public_report_validation(self):
        code = "PRIVACY_SERVICE_UNVERIFIED"
        report = {**self.metadata(), "source": "debug", "feature": "tools", "errorCode": code,
                  "events": [f"-023ms E tools {code}"]}
        self.assertEqual(200, self.submit(report).status_code)
        self.assertIn(code, self.issued[-1]["title"])
        self.assertIn(f"-023ms E tools {code}", self.issued[-1]["body"])

    def test_unknown_extended_failure_codes_remain_rejected(self):
        report = {**self.metadata(), "source": "debug", "feature": "voice", "errorCode": "VOICE_SESSION_FAILED"}
        for change in (
            {"errorCode": "PRIVATE_PROVIDER_TOKEN_VALUE"},
            {"events": ["-023ms E voice PRIVATE_PROVIDER_TOKEN_VALUE"]},
            {"events": ["-023ms E voice PRIVATE_V14_VALUE"]},
            {"errorCode": "VOICE_MICROPHONE_PERMISSION_REQUIRED private message"},
        ):
            with self.subTest(change=change):
                self.assertEqual(422, self.submit({**report, "reportId": str(uuid4()), **change}).status_code)
        self.assertFalse(self.issued)

    def test_active_gateway_job_and_profile_admission_reports_keep_distinct_codes(self):
        for code in ("CONVERSATION_CONFLICT", "ENGINEERING_STATE_OR_INPUT_CHANGED", "EXECUTION_SELECTION_INVALID"):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": "gateway", "errorCode": code,
                          "events": [f"-001ms E gateway {code}"]}
                self.assertEqual(200, self.submit(report).status_code)
                self.assertIn(code, self.issued[-1]["title"])
                self.assertIn(f"-001ms E gateway {code}", self.issued[-1]["body"])

    def test_android_public_codes_match_service_and_keep_the_failure_identity(self):
        source = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/IssueReportCodes.kt").read_text()
        declaration = source.split("val allowed = setOf(", 1)[1].split("\n    )", 1)[0]
        android_codes = set(re.findall(r'"([A-Z][A-Z0-9_]+)"', declaration))
        generated = (Path(__file__).resolve().parents[1] / "app/src/main/kotlin/dev/chungjungsoo/gptmobile/data/diagnostics/RepositoryOperationReportContract.kt").read_text()
        generated_codes = generated.split("val errorCodes = setOf(", 1)[1].split(")", 1)[0]
        android_codes.update(re.findall(r'"([A-Z][A-Z0-9_]+)"', generated_codes))
        self.assertEqual(android_codes, ERROR_CODES)
        for code in sorted(android_codes):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": "tools", "errorCode": code,
                          "events": [f"-023ms W tools {code}", f"-001ms E tools {code}"]}
                _, issue = issue_from_report(report)
                self.assertIn(code, issue["title"])
                self.assertIn(f"-023ms W tools {code}", issue["body"])
                self.assertIn(f"-001ms E tools {code}", issue["body"])
                self.assertIn(report["commit"], issue["body"])

    def test_advertised_recovery_codes_preserve_nested_failure_identity(self):
        from authentication import incident_report_contract as capsule

        features = self.client.get("/health/ready").json()["reportDiagnosticFeatures"]
        self.assertIn("ISSUE_RECOVERY_CODES_V1", features)
        self.assertIn("COMPLETION_EVIDENCE_V1", features)
        codes = ("TOOL_BUDGET_EXHAUSTED", "VALIDATION_POLICY_REQUIRED", "VALIDATION_CHECKS_REQUIRED",
                 "VALIDATION_CHECK_UNSUPPORTED", "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED",
                 "SOURCE_REVISION_REQUIRED", "SOURCE_REVISION_CHANGED", "VALIDATION_REVISION_UNAVAILABLE",
                 "VALIDATION_RUNTIME_UNAVAILABLE", "ENGINEERING_REPOSITORY_ACCESS_REQUIRED", "TOOL_PROTOCOL_INVALID_FRAME")
        for code in codes:
            contexts = [self.context(schemaVersion=4, originalFailureCode=code)]
            fields = [("INCIDENT", "causal_failure_code"), ("VOICE", "voice_chat_failure_code")]
            if code == "TOOL_PROTOCOL_INVALID_FRAME":
                fields.append(("PROVIDER", "provider_failure_code"))
            for area, field in fields:
                capture = self.diagnostics(area, {field: code})
                capture["schemaVersion"] = 3
                capture["unavailableFields"] = sorted((CORE_FIELDS[area] | capsule.CORE) - capture["values"].keys())
                contexts.append(self.context(schemaVersion=7, diagnostics=capture))
            for context in contexts:
                with self.subTest(code=code, context=context):
                    report = {**self.manual(), "context": context}
                    approved = json.dumps(report, separators=(",", ":"))
                    report_id, issue = issue_from_report(report)
                    self.assertEqual(report["reportId"], report_id)
                    self.assertIn(code, issue["body"])
                    self.assertEqual(approved, json.dumps(report, separators=(",", ":")))

    def test_typed_network_and_unknown_outcome_codes_survive_formatting(self):
        for code in ("DNS_RESOLUTION_FAILED", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "NETWORK_TIMEOUT",
                     "TLS_FAILURE", "AUTH_REQUIRED", "RATE_LIMITED", "READ_DEADLINE",
                     "DISPATCH_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN"):
            with self.subTest(code=code):
                report = {**self.metadata(), "source": "debug", "feature": "tools", "errorCode": code,
                          "events": [f"-023ms E tools {code}"]}
                _, issue = issue_from_report(report)
                self.assertIn(code, issue["title"])
                self.assertIn(f"-023ms E tools {code}", issue["body"])

    def test_uncertain_write_is_reconciled_without_another_post(self):
        report = self.manual()
        self.publish_error = GitHubRequestError()
        first = self.submit(report)
        self.assertEqual(503, first.status_code)
        self.assertEqual("AWAITING_GITHUB_RECEIPT", first.json()["error"])
        self.assertEqual(503, self.submit(report).status_code)
        self.now += 121
        restarted = IssueReporter(self.database, self, lambda: self.now)
        with self.assertRaises(ReportError) as pending:
            restarted.submit(report, "client")
        self.assertEqual(503, pending.exception.status)
        self.now += 121
        self.found = 77
        self.assertEqual(77, restarted.submit(report, "client"))
        self.assertEqual(1, len(self.issued))

    def test_concurrent_retry_shares_the_original_dispatch_lease(self):
        entered, release = threading.Event(), threading.Event()
        original_create = self.create

        def delayed(issue):
            entered.set()
            if not release.wait(5):
                raise AssertionError("Timed out waiting for concurrent submission")
            return original_create(issue)

        self.create = delayed
        report = self.manual()
        with ThreadPoolExecutor(1) as executor:
            initial = executor.submit(self.reporter.submit, report, "client")
            try:
                self.assertTrue(entered.wait(5))
                self.assertEqual(503, self.submit(report).status_code)
            finally:
                release.set()
            self.assertEqual(42, initial.result(timeout=5))
        self.assertEqual(42, self.submit(report).json()["number"])
        self.assertEqual(1, len(self.issued))

    def test_certain_rate_limit_rejection_retries_after_cooldown(self):
        report = self.manual()
        self.publish_error = ReportError(503, "GITHUB_UNAVAILABLE", 120)
        self.assertEqual("120", self.submit(report).headers["retry-after"])
        self.assertEqual(503, self.submit(self.manual()).status_code)
        self.assertEqual(1, len(self.issued))
        self.now += 120
        self.publish_error = None
        self.assertEqual(200, self.submit(report).status_code)
        self.assertEqual(2, len(self.issued))

    def test_http_origin_size_and_submission_limits(self):
        self.assertEqual(403, TestClient(self.app, base_url="http://reports.example.org").get("/health/ready").status_code)
        self.assertEqual(403, self.client.post("/v1/issue-reports", json=self.manual(), headers={"Origin": "https://elsewhere.example.org"}).status_code)
        self.assertEqual(413, self.client.post("/v1/issue-reports", content="x" * 8193, headers={"Content-Type": "application/json"}).status_code)
        self.assertEqual(415, self.client.post("/v1/issue-reports", data={"title": "hello"}).status_code)
        for _ in range(4):
            self.assertEqual(200, self.submit(self.manual()).status_code)
        self.assertEqual(429, self.submit(self.manual()).status_code)
        self.assertEqual(4, len(self.issued))

    def test_github_publisher_scopes_the_installation_and_matches_only_its_bot(self):
        key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        pem = Path(self.temp.name) / "ephemeral-key.pem"
        pem.write_bytes(key.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8, serialization.NoEncryption()))
        publisher = GitHubIssuePublisher("123", "456", "issue-test", pem, lambda: self.now)
        report_id = str(uuid4())
        posted = []

        def request(path, token, payload=None):
            if path == "/app/installations/456/access_tokens":
                header, claims, signature = token.split(".")
                key.public_key().verify(base64.urlsafe_b64decode(signature + "=="), f"{header}.{claims}".encode(),
                                        padding.PKCS1v15(), hashes.SHA256())
                self.assertEqual("123", json.loads(base64.urlsafe_b64decode(claims + "=="))["iss"])
                self.assertEqual({"repositories": [REPOSITORY.split("/")[1]], "permissions": {"issues": "write"}}, payload)
                return {"token": "installation-test", "expires_at": "2027-02-01T00:00:00Z"}
            self.assertEqual("installation-test", token)
            self.assertTrue(path.startswith(f"/repos/{REPOSITORY}/issues"))
            if payload is not None:
                posted.append(payload)
                return {"number": 42}
            body = "Report\n\n" + marker(report_id)
            return [{"number": 2, "body": body, "user": {"login": "unrelated"}},
                    {"number": 42, "body": body, "user": {"login": "issue-test[bot]"}}]

        publisher._request = request
        self.assertEqual(42, publisher.create({"title": "Report", "body": "Details"}))
        self.assertEqual(42, publisher.find(report_id, self.now))
        self.assertEqual(1, len(posted))


if __name__ == "__main__":
    unittest.main()
