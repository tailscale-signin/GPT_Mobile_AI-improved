"""Closed, bounded context-v5/v6 observations. This validator never rewrites approved data."""

from __future__ import annotations

import json
import re

from .engineering_report_contract import ENGINEERING_REASONS, GATEWAY_ADMISSION_CORE, GATEWAY_ADMISSION_FIELDS, GATEWAY_ADMISSION_PHASES, MODEL_PREPARATION_REASONS
from . import incident_report_contract as capsule


MAX_DIAGNOSTIC_BYTES = 3072
DIAGNOSTIC_AREAS = {"PROVIDER", "TOOL", "ACCEPTANCE", "VOICE", "GATEWAY", "APP", "INCIDENT"}
DIAGNOSTIC_CAPTURE_MODES = {"INCIDENT", "SELECTED_CAPTURE", "NO_CAPTURE"}
COMMON_HASHES = {"run_id_hash", "invocation_id_hash", "call_id_hash", "job_id_hash", "profile_hash", "model_hash"}
HASH_FIELDS = COMMON_HASHES | {
    "voice_session_id_hash", "original_error_code_hash", "original_reason_code_hash", "server_request_id_hash", "generation_id_hash",
    "privacy_policy_hash", "backend_terminal_code_hash", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "argument_shape_hash", "checkpoint_hash", "gateway_manifest_hash", "gateway_operation_id_hash", "operation_id_hash",
}
HASH_LISTS = {"unmet_criterion_id_hashes", "unmet_criterion_reason_hashes"}
FLAGS = {
    "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed",
    "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "privacy_zdr_required", "privacy_fallbacks_allowed",
    "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_input_silenced", "gateway_admission_ready", "model_preparation_load_accepted",
}
ATTEMPTS = {"backend_attempts", "backend_completed_attempts", "backend_failed_attempt", "repair_attempts", "repair_attempt_limit"}
DURATIONS = {"voice_speech_end_to_transcript_ms", "voice_transcript_to_request_ms", "voice_request_to_response_ms", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms"}
COUNTS = {"voice_reply_characters",
    "provider_consecutive_rejections", "unmet_requirement_count", "unmet_capability_count",
    "unknown_field_count",
    "configured_output_limit", "effective_output_limit", "backend_output_limit", "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes",
    "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "missing_field_count", "invalid_field_count", "validation_error_count",
    "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count",
    "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters",
}
FINISH_REASONS = {"STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "OTHER", "UNKNOWN"}
ENUMS = {
    "provider_failure_stage": {"ADMISSION", "ENGINEERING_GUARD", "GENERATION", "CONTINUATION", "REVIEW_COMPATIBILITY", "ANSWER_REVIEW", "CLAIM_VERIFICATION", "DELIVERY"},
    "provider_failure_domain": {"PROVIDER", "APPROVAL", "APPLICATION_VERIFICATION", "TOOL_EXECUTION", "UNKNOWN"},
    "provider_failure_code": {"HTTP_ERROR", "TRANSPORT_ERROR", "UNCLASSIFIED_PROVIDER_FAILURE", "OUTPUT_LIMIT", "CONTEXT_CAPACITY_EXCEEDED", "LOCAL_SERVICE_UNREACHABLE", "GATEWAY_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "RUN_DEADLINE_REACHED", "PROVIDER_RATE_LIMITED", "PRIVACY_CONFIGURATION"},
    "voice_reply_state": {"FAILED_WITHOUT_DETAILS", "EMPTY_REPLY"},
    "tool_action": {"status", "list", "headers", "context", "comments", "reviews", "threads", "history", "commit", "branch", "changes", "checks", "workflows", "runs", "jobs", "logs", "failure", "read", "search", "create", "update", "close", "reopen", "review", "diff", "summary", "files", "tree", "get", "rerun", "rerun_failed_jobs", "cancel", "dispatch"},
    "tool_error_code": {"APP_ERROR", "DNS_RESOLUTION_FAILED", "TOOL_FAILURE", "INVALID_ARGUMENTS", "TOOL_UNAVAILABLE", "SAVED_VIEW_UNAVAILABLE", "GITHUB_VALIDATION_FAILED", "HTTP_ERROR", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_REVIEW_BLOCKED", "PRECHECK_REJECTED", "TOOL_OUTCOME_UNKNOWN", "UNKNOWN_TOOL", "GITHUB_CONFLICT", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_ENDPOINT_PERMISSION_REQUIRED"},
    "tool_reason_code": {"BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "TOOL_ARGUMENT_SCHEMA_REJECTED", "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "ISSUE_SELECTOR_REQUIRED", "PR_SELECTOR_REQUIRED"},
    "tool_dispatch_phase": {"PREFLIGHT_REJECTED", "READ_FAILED", "OBSERVED", "OUTCOME_UNKNOWN", "PARTIAL_ACCEPTED", "COMPLETED", "FAILED", "PRECHECK_REJECTED"},
    "chat_failure_code_state": {"ABSENT", "KNOWN", "UNRECOGNIZED"},

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
    "model_preparation_state": {"PREPARING", "PRECHECK_REJECTED", "LOAD_REJECTED", "LOAD_ACCEPTED", "LOAD_OUTCOME_UNKNOWN", "WAITING_FOR_MODEL", "METADATA_QUALIFIED", "QUALIFICATION_BLOCKED"},
    "model_preparation_action": {"NONE", "LOAD", "WAKE"},
    "model_preparation_dispatch": {"NOT_DISPATCHED", "DISPATCHED"},
    "gateway_model_state": {"UNKNOWN", "UNAVAILABLE", "UNLOADED", "LOADING", "LOADED", "SLEEPING"},
    "gateway_failure_category": {"connection_refused", "connect_timeout", "read_timeout", "dns", "tls", "connection_closed", "connection", "http", "denied", "unsupported", "invalid_response", "oversize", "deadline", "configuration", "model_unavailable", "model_not_loaded", "model_loading", "generation_unavailable", "unverified", "storage", "draining"},
    "gateway_dependency": {"model_backend", "backend_health", "backend_models", "backend_properties", "backend_template", "selected_model", "durable_storage", "process"},
    "gateway_upstream_message_code": {"MODEL_NOT_LOADED", "MODEL_LOADING", "MODEL_NOT_FOUND", "MODEL_LOAD_FAILED", "MODEL_SELECTOR_REQUIRED", "INVALID_API_KEY", "ENDPOINT_DISABLED"},
    "app_exit_kind": {"JVM_CRASH", "NATIVE_CRASH", "APP_NOT_RESPONDING", "STARTUP_FAILED"},
}
ENUM_LISTS = {
    "invalid_field_classes": {"MISSING_REQUIRED", "WRONG_TYPE", "EMPTY_STRING", "OUT_OF_RANGE", "INVALID_ENUM", "PATTERN_MISMATCH", "UNKNOWN_FIELD", "COMPOSITE_CONSTRAINT"},
    "unmet_criterion_categories": {"BUILD_REVISION_BINDING", "AUTHORIZED_CATALOG", "FIXTURE_LIFECYCLE", "TOOL_ACTION_COVERAGE", "EVIDENCE_SEPARATION", "DEVICE_EXECUTION", "REPOSITORY_GRAPH", "CI_VERIFICATION", "CI_OBSERVATION", "API_OPERATION", "REPOSITORY_LIFECYCLE", "REQUIREMENT"},
    "unmet_criterion_states": {"PASS", "FAIL", "BLOCKED", "NOT_TESTED"},
    "unmet_criterion_blockers": {
        "OUT_OF_SCOPE", "REPOSITORY_SCOPE_EXCLUDED", "OPERATION_IDENTITY_REQUIRED", "AUTHORIZATION_REQUIRED", "HISTORICAL_CONTRACT_CHANGED", "APPROVAL_REQUIRED", "FIXTURE_APPROVAL_MODE_REQUIRED", "FIXTURE_APPROVAL_STATE_UNKNOWN",
        "LIVE_EVIDENCE_REQUIRED", "CONTINUATION_REQUIRED", "PRECHECK_REJECTED", "OPERATION_FAILED", "USER_DECISION_REQUIRED", "CHECK_SUBSCRIPTION_REQUIRED", "OWNED_FIXTURE_REQUIRED",
        "ISSUE_ID_REQUIRED", "PULL_REQUEST_ID_REQUIRED", "WORKFLOW_RUN_OR_JOB_REQUIRED", "RELEASE_OR_TAG_REQUIRED", "REPOSITORY_INPUT_REQUIRED", "REPOSITORY_LIFECYCLE_REQUIRED",
        "EXECUTION_NOT_EXERCISED", "BLOCKER_DETAIL_NOT_RECORDED", "NONE", "VERIFICATION_FAILED", "REQUIRED_OPERATION_BLOCKED", "DEVICE_EXECUTION_NOT_OBSERVED", "CATALOG_NOT_EXHAUSTED",
        "FIXTURE_PROOF_INCOMPLETE", "GRAPH_QUALIFICATION_INCOMPLETE", "REPOSITORY_OPERATION_UNVERIFIED", "MATCHING_EVIDENCE_MISSING",
    },
}
CRITERION_FIELDS = HASH_LISTS | {"unmet_criterion_categories", "unmet_criterion_states", "unmet_criterion_blockers"}
FIELDS_BY_AREA = {
    "PROVIDER": COMMON_HASHES | {"provider_failure_stage", "provider_failure_domain", "provider_failure_code", "provider_wait_eligible", "provider_checkpoint_present", "provider_checkpoint_continuation_allowed", "provider_consecutive_rejections",
        "original_error_code_hash", "server_request_id_hash", "generation_id_hash", "response_format", "finish_reason", "configured_output_limit", "effective_output_limit", "backend_output_limit",
        "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes", "backend_attempts", "backend_completed_attempts", "backend_failed_attempt",
        "repair_attempts", "repair_attempt_limit", "transport_complete", "generation_completed", "request_replay_allowed", "logical_continuation_allowed", "usage_source", "output_byte_scope",
        "privacy_policy_hash", "privacy_zdr_required", "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "privacy_fallbacks_allowed", "privacy_observation",
        "backend_terminal_code_hash", "backend_terminal_status", "backend_finish_reason", "backend_status",
    },
    "TOOL": COMMON_HASHES | {"tool_action", "tool_error_code", "tool_reason_code", "tool_dispatch_phase", "unknown_field_count", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "original_reason_code_hash", "argument_shape_hash", "missing_field_count", "invalid_field_count", "validation_error_count", "invalid_field_classes"},
    "ACCEPTANCE": COMMON_HASHES | CRITERION_FIELDS | {"unmet_requirement_count", "unmet_capability_count", "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count", "checkpoint_hash", "acceptance_evidence_scope"},
    "VOICE": {"voice_reply_state", "voice_reply_characters", "voice_session_id_hash", "profile_hash", "model_hash", "voice_generation", "voice_engine", "voice_phase", "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters", "voice_speech_end_to_transcript_ms", "voice_transcript_to_request_ms", "voice_request_to_response_ms", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms", "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_focus_result", "voice_input_silenced", "voice_first_audio_basis"},
    "GATEWAY": COMMON_HASHES | {
        "checkpoint_hash", "original_error_code_hash", "original_reason_code_hash", "gateway_state", "transport_complete", "request_replay_allowed", "logical_continuation_allowed",
        "operation_id_hash", "model_preparation_state", "model_preparation_action", "model_preparation_dispatch", "model_preparation_load_accepted", "model_preparation_load_requests",
        "gateway_model_state", "gateway_failure_category", "gateway_dependency", "gateway_upstream_status", "gateway_upstream_message_code", "gateway_reconciliation_status",
    },
    "APP": COMMON_HASHES | {"original_error_code_hash", "app_exit_kind", "chat_failure_code_state"},
    "INCIDENT": COMMON_HASHES | {"original_error_code_hash", "chat_failure_code_state"},
}
# Bounded diagnostic additions for open issue recovery.
ENUMS["tool_reason_code"].add("SAVED_VIEW_CONTEXT_MISMATCH")
ENUMS.update({'voice_install_state': {'IDLE', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_NETWORK'}, 'voice_install_scheduler': {'JOB_SCHEDULER', 'WORK_MANAGER'}, 'voice_install_failure': {'SCHEDULE_REJECTED', 'SCHEDULE_EXCEPTION', 'ASSET_FAILURE', 'WORKER_EXCEPTION'}, 'github_transport_kind': {'DNS', 'DISCONNECTED', 'ABORTED', 'TIMEOUT', 'TLS', 'CANCELLED', 'UNKNOWN', 'CIRCUIT_OPEN', 'HTTP'}, 'reasoning_requested_mode': {'AUTO', 'ON', 'OFF'}, 'reasoning_admission_basis': {'NO_VERIFIED_CONTROL'}})
COUNTS.update(['voice_install_stop_reason', 'voice_install_progress_percent', 'read_range_count', 'read_invalid_window_count', 'argument_page_size', 'repository_input_blocked_count', 'workflow_input_blocked_count', 'live_evidence_blocked_count', 'acceptance_failed_operation_count'])
DURATIONS.add("github_transport_wait_ms")
FIELDS_BY_AREA['VOICE'].update(['voice_install_state', 'voice_install_scheduler', 'voice_install_failure', 'voice_install_stop_reason', 'voice_install_progress_percent'])
FIELDS_BY_AREA['TOOL'].update(['github_transport_kind', 'github_transport_wait_ms', 'read_range_count', 'read_invalid_window_count', 'argument_page_size'])
FIELDS_BY_AREA['PROVIDER'].update(['reasoning_requested_mode', 'reasoning_admission_basis'])
FIELDS_BY_AREA['ACCEPTANCE'].update(['repository_input_blocked_count', 'workflow_input_blocked_count', 'live_evidence_blocked_count', 'acceptance_failed_operation_count'])

ENUMS.update({"reasoning_route_kind": {"GATEWAY", "DIRECT"}, "reasoning_control_state": {"VERIFIED", "UNSUPPORTED", "UNVERIFIED"}})
HASH_FIELDS.add("reasoning_template_hash")
FIELDS_BY_AREA["PROVIDER"].update({"reasoning_route_kind", "reasoning_control_state", "reasoning_template_hash"})

ENUM_LISTS["read_window_rejections"] = {"BAD_START", "CONFLICTING_LIMITS", "BAD_MAX_LINES", "BAD_END"}
FIELDS_BY_AREA["TOOL"].add("read_window_rejections")

COUNTS.add("acceptance_unresolved_claim_count")
ENUM_LISTS["acceptance_claim_failure_reasons"] = {'EXACT_RECEIPT_MATCH', 'CLAIM_VALUE_MISSING', 'OPERATION_FAILED', 'POINTER_NOT_IN_EVIDENCE', 'RECEIPT_NOT_FOUND', 'OBSERVATION_TIME_UNKNOWN', 'AUTHORIZATION_REVALIDATION_REQUIRED', 'UNTRUSTED_RECEIPT', 'VALUE_MISMATCH', 'FIELD_SEMANTICS_MISMATCH', 'HISTORICAL_OBSERVATION', 'MUTABLE_EVIDENCE_EXPIRED_OR_REVISION_CHANGED'}
FIELDS_BY_AREA["ACCEPTANCE"].update({"acceptance_unresolved_claim_count", "acceptance_claim_failure_reasons"})

CORE_FIELDS = {
    "PROVIDER": {"profile_hash", "model_hash", "configured_output_limit", "output_tokens", "finish_reason", "backend_attempts", "transport_complete"},
    "TOOL": {"tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "missing_field_count", "invalid_field_count"},
    "ACCEPTANCE": {"unmet_criterion_id_hashes", "unmet_criterion_categories", "unmet_criterion_states", "effective_call_limit", "used_calls", "remaining_calls", "checkpoint_hash"},
    "VOICE": {"voice_engine", "voice_phase", "voice_capture_attempts", "voice_recognized_characters", "voice_response_to_queue_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms"},
    "GATEWAY": {"job_id_hash", "gateway_state", "checkpoint_hash", "transport_complete"},
    "APP": {"original_error_code_hash", "app_exit_kind"},
    "INCIDENT": set(),
}


def _string_list(value, allowed):
    return isinstance(value, list) and all(isinstance(item, str) and item in allowed for item in value) and len(set(value)) == len(value)


def _valid_value(key, value):
    if capsule.valid_value(key, value):
        return True
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
    if key in {"backend_terminal_status", "gateway_http_status", "gateway_upstream_status", "gateway_reconciliation_status"}:
        return 100 <= value <= 599
    if key == "model_preparation_load_requests":
        return 0 <= value <= 1
    if key in ATTEMPTS:
        return 0 <= value <= 10_000
    if key in DURATIONS:
        return 0 <= value <= 86_400_000
    if key == "voice_install_progress_percent":
        return 0 <= value <= 100
    return key in COUNTS and 0 <= value <= 16_000_000


def validate_diagnostics(value):
    required = {"schemaVersion", "area", "capturedAtMillis", "captureMode", "values", "unavailableFields"}
    if not isinstance(value, dict) or not required <= value.keys() or set(value) - required - {"omittedFields"}:
        raise ValueError("invalid diagnostic fields")
    if type(value["schemaVersion"]) is not int or value["schemaVersion"] not in {1, 2, 3}:
        raise ValueError("invalid diagnostic version")
    area, mode, captured = value["area"], value["captureMode"], value["capturedAtMillis"]
    if not isinstance(area, str) or area not in DIAGNOSTIC_AREAS or not isinstance(mode, str) or mode not in DIAGNOSTIC_CAPTURE_MODES:
        raise ValueError("invalid diagnostic capture")
    if type(captured) is not int or not 1 <= captured <= 9_999_999_999_999:
        raise ValueError("invalid diagnostic time")
    version = value["schemaVersion"]
    extended = version >= 2 and area == "GATEWAY"
    if version == 2 and area != "GATEWAY" or area == "INCIDENT" and version != 3:
        raise ValueError("invalid diagnostic extension area")
    allowed = FIELDS_BY_AREA[area] | (GATEWAY_ADMISSION_FIELDS if extended else set()) | (capsule.fields_for(area) if version == 3 else set())
    core = CORE_FIELDS[area] | (GATEWAY_ADMISSION_CORE if extended else set()) | (capsule.CORE if version == 3 else set())
    values = value["values"]
    if not isinstance(values, dict) or set(values) - allowed or not all(_valid_value(key, item) for key, item in values.items()):
        raise ValueError("invalid diagnostic values")
    lengths = {len(values[key]) for key in CRITERION_FIELDS & values.keys()}
    if len(lengths) > 1:
        raise ValueError("unaligned criterion observations")
    if version == 3 and not capsule.aligned(values):
        raise ValueError("unaligned argument shape observations")
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
