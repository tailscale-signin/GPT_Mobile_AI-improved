"""Closed, bounded context-v5/v6 observations. This validator never rewrites approved data."""

from __future__ import annotations

import json
import re

from .engineering_report_contract import ENGINEERING_REASONS, GATEWAY_ADMISSION_CORE, GATEWAY_ADMISSION_FIELDS, GATEWAY_ADMISSION_PHASES, MODEL_PREPARATION_REASONS


MAX_DIAGNOSTIC_BYTES = 3072
DIAGNOSTIC_AREAS = {"PROVIDER", "TOOL", "ACCEPTANCE", "VOICE", "GATEWAY", "APP"}
DIAGNOSTIC_CAPTURE_MODES = {"INCIDENT", "SELECTED_CAPTURE", "NO_CAPTURE"}
COMMON_HASHES = {"run_id_hash", "invocation_id_hash", "call_id_hash", "job_id_hash", "profile_hash", "model_hash"}
HASH_FIELDS = COMMON_HASHES | {
    "voice_session_id_hash", "original_error_code_hash", "original_reason_code_hash", "server_request_id_hash", "generation_id_hash",
    "privacy_policy_hash", "backend_terminal_code_hash", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "argument_shape_hash", "checkpoint_hash", "gateway_manifest_hash", "gateway_operation_id_hash",
}
HASH_LISTS = {"unmet_criterion_id_hashes", "unmet_criterion_reason_hashes"}
FLAGS = {
    "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "privacy_zdr_required", "privacy_fallbacks_allowed",
    "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_input_silenced", "gateway_admission_ready",
}
ATTEMPTS = {"backend_attempts", "backend_completed_attempts", "backend_failed_attempt", "repair_attempts", "repair_attempt_limit"}
DURATIONS = {"voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms"}
COUNTS = {
    "configured_output_limit", "effective_output_limit", "backend_output_limit", "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes",
    "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "missing_field_count", "invalid_field_count", "validation_error_count",
    "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count",
    "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters",
}
FINISH_REASONS = {"STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "OTHER", "UNKNOWN"}
ENUMS = {
    "response_format": {"TEXT", "GITHUB_REVIEW_V1", "CLAIM_JSON"},
    "finish_reason": FINISH_REASONS,
    "backend_finish_reason": FINISH_REASONS,
    "backend_status": {"COMPLETED", "FAILED", "UNKNOWN"},
    "output_byte_scope": {"CLIENT_STREAM", "LAST_BACKEND_ATTEMPT"},
    "privacy_observation": {"CACHED_REJECTION", "NO_COMPATIBLE_ENDPOINT"},
    "usage_source": {
        "provider_reported", "runtime_reported", "gateway_reported", "mixed_reported", "gateway_reported_partial", "estimated", "unavailable",
        "provider_reported_initial", "provider_reported_interim", "runtime_reported_initial", "runtime_reported_interim", "gateway_reported_initial", "gateway_reported_interim", "mixed_reported_initial", "mixed_reported_interim",
    },
    "acceptance_evidence_scope": {"CURRENT_VERDICT", "RETAINED_VERDICT"},
    "voice_engine": {"SYSTEM", "LOCAL", "KOKORO"},
    "voice_phase": {"PAUSED", "STARTING", "LISTENING", "FINALIZING", "REVIEWING", "THINKING", "SPEAKING", "ERROR", "CLOSED"},
    "voice_focus_result": {"GRANTED", "DENIED", "ERROR", "NOT_REQUESTED"},
    "voice_first_audio_basis": {"UTTERANCE_START", "PLAYBACK_HEAD"},
    "gateway_reason_code": ENGINEERING_REASONS | MODEL_PREPARATION_REASONS,
    "gateway_failure_phase": GATEWAY_ADMISSION_PHASES,
    "gateway_state": {"QUEUED", "RUNNING", "WAITING_FOR_CLIENT_TOOL", "WAITING_FOR_APPROVAL", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED"},
    "app_exit_kind": {"JVM_CRASH", "NATIVE_CRASH", "APP_NOT_RESPONDING", "STARTUP_FAILED"},
}
ENUM_LISTS = {
    "invalid_field_classes": {"MISSING_REQUIRED", "WRONG_TYPE", "EMPTY_STRING", "OUT_OF_RANGE", "INVALID_ENUM", "PATTERN_MISMATCH", "UNKNOWN_FIELD", "COMPOSITE_CONSTRAINT"},
    "unmet_criterion_categories": {"BUILD_REVISION_BINDING", "AUTHORIZED_CATALOG", "FIXTURE_LIFECYCLE", "TOOL_ACTION_COVERAGE", "EVIDENCE_SEPARATION", "DEVICE_EXECUTION", "REPOSITORY_GRAPH", "CI_VERIFICATION", "API_OPERATION", "REPOSITORY_LIFECYCLE", "REQUIREMENT"},
    "unmet_criterion_states": {"PASS", "FAIL", "BLOCKED", "NOT_TESTED"},
    "unmet_criterion_blockers": {
        "OUT_OF_SCOPE", "REPOSITORY_SCOPE_EXCLUDED", "OPERATION_IDENTITY_REQUIRED", "AUTHORIZATION_REQUIRED", "HISTORICAL_CONTRACT_CHANGED", "APPROVAL_REQUIRED", "FIXTURE_APPROVAL_MODE_REQUIRED",
        "LIVE_EVIDENCE_REQUIRED", "CONTINUATION_REQUIRED", "PRECHECK_REJECTED", "OPERATION_FAILED", "USER_DECISION_REQUIRED", "CHECK_SUBSCRIPTION_REQUIRED", "OWNED_FIXTURE_REQUIRED",
        "ISSUE_ID_REQUIRED", "PULL_REQUEST_ID_REQUIRED", "WORKFLOW_RUN_OR_JOB_REQUIRED", "RELEASE_OR_TAG_REQUIRED", "REPOSITORY_INPUT_REQUIRED", "REPOSITORY_LIFECYCLE_REQUIRED",
        "EXECUTION_NOT_EXERCISED", "BLOCKER_DETAIL_NOT_RECORDED", "NONE", "VERIFICATION_FAILED", "REQUIRED_OPERATION_BLOCKED", "DEVICE_EXECUTION_NOT_OBSERVED", "CATALOG_NOT_EXHAUSTED",
        "FIXTURE_PROOF_INCOMPLETE", "GRAPH_QUALIFICATION_INCOMPLETE", "REPOSITORY_OPERATION_UNVERIFIED", "MATCHING_EVIDENCE_MISSING",
    },
}
CRITERION_FIELDS = HASH_LISTS | {"unmet_criterion_categories", "unmet_criterion_states", "unmet_criterion_blockers"}
FIELDS_BY_AREA = {
    "PROVIDER": COMMON_HASHES | {
        "original_error_code_hash", "server_request_id_hash", "generation_id_hash", "response_format", "finish_reason", "configured_output_limit", "effective_output_limit", "backend_output_limit",
        "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes", "backend_attempts", "backend_completed_attempts", "backend_failed_attempt",
        "repair_attempts", "repair_attempt_limit", "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "usage_source", "output_byte_scope",
        "privacy_policy_hash", "privacy_zdr_required", "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "privacy_fallbacks_allowed", "privacy_observation",
        "backend_terminal_code_hash", "backend_terminal_status", "backend_finish_reason", "backend_status",
    },
    "TOOL": COMMON_HASHES | {"tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "original_reason_code_hash", "argument_shape_hash", "missing_field_count", "invalid_field_count", "validation_error_count", "invalid_field_classes"},
    "ACCEPTANCE": COMMON_HASHES | CRITERION_FIELDS | {"effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count", "checkpoint_hash", "acceptance_evidence_scope"},
    "VOICE": {"voice_session_id_hash", "profile_hash", "model_hash", "voice_generation", "voice_engine", "voice_phase", "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms", "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_focus_result", "voice_input_silenced", "voice_first_audio_basis"},
    "GATEWAY": COMMON_HASHES | {"checkpoint_hash", "original_error_code_hash", "gateway_state", "transport_complete", "request_replay_allowed", "logical_continuation_allowed"},
    "APP": {"original_error_code_hash", "app_exit_kind"},
}
CORE_FIELDS = {
    "PROVIDER": {"profile_hash", "model_hash", "configured_output_limit", "output_tokens", "finish_reason", "backend_attempts", "transport_complete"},
    "TOOL": {"tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "missing_field_count", "invalid_field_count"},
    "ACCEPTANCE": {"unmet_criterion_id_hashes", "unmet_criterion_categories", "unmet_criterion_states", "effective_call_limit", "used_calls", "remaining_calls", "checkpoint_hash"},
    "VOICE": {"voice_engine", "voice_phase", "voice_capture_attempts", "voice_recognized_characters", "voice_response_to_queue_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms"},
    "GATEWAY": {"job_id_hash", "gateway_state", "checkpoint_hash", "transport_complete"},
    "APP": {"original_error_code_hash", "app_exit_kind"},
}


def _string_list(value, allowed):
    return isinstance(value, list) and all(isinstance(item, str) and item in allowed for item in value) and len(set(value)) == len(value)


def _valid_value(key, value):
    if key in HASH_LISTS or key in ENUM_LISTS:
        return isinstance(value, list) and 1 <= len(value) <= 8 and all(
            isinstance(item, str) and (bool(re.fullmatch(r"[a-f0-9]{64}", item)) if key in HASH_LISTS else item in ENUM_LISTS[key]) for item in value
        )
    if key == "gateway_version":
        return isinstance(value, str) and bool(re.fullmatch(r"[0-9]{1,4}(?:\.[0-9]{1,4}){1,3}", value))
    if key in HASH_FIELDS:
        return isinstance(value, str) and bool(re.fullmatch(r"[a-f0-9]{64}", value))
    if key in ENUMS:
        return isinstance(value, str) and value in ENUMS[key]
    if key in FLAGS:
        return type(value) is bool
    if type(value) is not int:
        return False
    if key == "voice_generation":
        return 0 <= value <= 9_223_372_036_854_775_807
    if key == "gateway_protocol_version":
        return 1 <= value <= 1000
    if key in {"backend_terminal_status", "gateway_http_status"}:
        return 100 <= value <= 599
    if key in ATTEMPTS:
        return 0 <= value <= 10_000
    if key in DURATIONS:
        return 0 <= value <= 86_400_000
    return key in COUNTS and 0 <= value <= 16_000_000


def validate_diagnostics(value):
    required = {"schemaVersion", "area", "capturedAtMillis", "captureMode", "values", "unavailableFields"}
    if not isinstance(value, dict) or not required <= value.keys() or set(value) - required - {"omittedFields"}:
        raise ValueError("invalid diagnostic fields")
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] not in {1, 2}:
        raise ValueError("invalid diagnostic version")
    area, mode, captured = value["area"], value["captureMode"], value["capturedAtMillis"]
    if not isinstance(area, str) or area not in DIAGNOSTIC_AREAS or not isinstance(mode, str) or mode not in DIAGNOSTIC_CAPTURE_MODES:
        raise ValueError("invalid diagnostic capture")
    if type(captured) is not int or not 1 <= captured <= 9_999_999_999_999:
        raise ValueError("invalid diagnostic time")
    extended = value["schemaVersion"] == 2
    if extended and area != "GATEWAY":
        raise ValueError("invalid diagnostic extension area")
    allowed = FIELDS_BY_AREA[area] | (GATEWAY_ADMISSION_FIELDS if extended else set())
    core = CORE_FIELDS[area] | (GATEWAY_ADMISSION_CORE if extended else set())
    values = value["values"]
    if not isinstance(values, dict) or set(values) - allowed or not all(_valid_value(key, item) for key, item in values.items()):
        raise ValueError("invalid diagnostic values")
    lengths = {len(values[key]) for key in CRITERION_FIELDS & values.keys()}
    if len(lengths) > 1:
        raise ValueError("unaligned criterion observations")
    unavailable, omitted = value["unavailableFields"], value.get("omittedFields", [])
    if not _string_list(unavailable, core) or set(unavailable) != core - values.keys():
        raise ValueError("invalid diagnostic availability")
    if not _string_list(omitted, allowed) or set(omitted) & values.keys():
        raise ValueError("invalid diagnostic omissions")
    if mode == "NO_CAPTURE" and (values or omitted):
        raise ValueError("no-capture observation contains data")
    if len(json.dumps(value, separators=(",", ":"), ensure_ascii=False).encode()) > MAX_DIAGNOSTIC_BYTES:
        raise ValueError("diagnostic capture too large")
    return value


def diagnostics_body(value):
    """Only called after validation; positional criterion arrays retain their order."""
    rows = [
        ("Area", value["area"]), ("Capture mode", value["captureMode"]),
        ("Observation time (epoch ms)", str(value["capturedAtMillis"])),
    ]
    for key, item in sorted(value["values"].items()):
        rendered = ", ".join(item) if isinstance(item, list) else str(item).lower() if type(item) is bool else str(item)
        rows.append((key, rendered))
    body = "\n\n### Additional diagnostic observation\n\n| Field | Recorded value |\n| --- | --- |\n"
    body += "\n".join(f"| {key} | `{item}` |" for key, item in rows)
    if value["captureMode"] == "SELECTED_CAPTURE":
        body += "\n\nThe user selected this observation; its timestamp does not establish the time of the reported failure."
    elif value["captureMode"] == "NO_CAPTURE":
        body += "\n\nNo observation was available for the selected area. No measurements are inferred."
    unavailable = sorted(value["unavailableFields"])
    body += "\n\nUnavailable diagnostic fields: " + (", ".join(f"`{key}`" for key in unavailable) if unavailable else "none") + "."
    if value.get("omittedFields"):
        body += "\n\nFields omitted before approval to bound the capture: " + ", ".join(f"`{key}`" for key in sorted(value["omittedFields"])) + "."
    return body
