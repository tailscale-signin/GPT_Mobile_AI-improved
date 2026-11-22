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
    "voice_engine_warm", "voice_permission_granted", "voice_foreground", "voice_muted", "voice_input_silenced", "voice_pronunciation_audio_queued", "voice_pronunciation_first_audio_observed", "gateway_admission_ready", "model_preparation_load_accepted",
}
ATTEMPTS = {"backend_attempts", "backend_completed_attempts", "backend_failed_attempt", "repair_attempts", "repair_attempt_limit"}
DURATIONS = {"voice_speech_end_to_transcript_ms", "voice_transcript_to_request_ms", "voice_request_to_response_ms", "voice_response_to_queue_ms", "voice_queue_to_synthesis_ms", "voice_synthesis_to_first_audio_ms", "voice_response_to_first_audio_ms", "voice_warmup_ms"}
COUNTS = {"voice_reply_characters",
    "provider_consecutive_rejections", "unmet_requirement_count", "unmet_capability_count",
    "unknown_field_count",
    "configured_output_limit", "effective_output_limit", "backend_output_limit", "input_tokens", "output_tokens", "reasoning_tokens", "visible_output_bytes", "reasoning_output_bytes",
    "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "missing_field_count", "invalid_field_count", "validation_error_count",
    "effective_call_limit", "used_calls", "remaining_calls", "verification_reserve", "pending_continuations", "unresolved_outcomes", "unmet_criterion_count", "unmet_criterion_omitted_count",
    "voice_capture_attempts", "voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_recognized_characters", "voice_focus_transition_count", "voice_pronunciation_unknown_words", "voice_pronunciation_initialisms",
}
FINISH_REASONS = {"STOP", "TOOL_CALLS", "LENGTH", "CONTENT_FILTER", "OTHER", "UNKNOWN"}
PRIVACY_ELIGIBILITY_REASONS = {
    "BATCH_UNSUPPORTED", "MODEL_ROUTER_UNSUPPORTED", "ENDPOINT_UNSUPPORTED", "CREDENTIAL_MISSING",
    "ROUTING_UNSUPPORTED", "ACCOUNT_PRIVACY_UNVERIFIED", "ACCOUNT_APPROVAL_EXPIRED",
    "ACCOUNT_APPROVAL_INVALID", "ACCOUNT_APPROVAL_AMBIGUOUS", "PUBLISHED_POLICY_CHANGED",
    "MODEL_RESOLUTION_UNVERIFIED", "CATALOG_MALFORMED", "MODEL_NOT_LISTED",
    "NO_STRICT_ZDR_ENDPOINT", "PROVIDER_RESTRICTIONS", "AMBIGUOUS_ENDPOINT",
    "MANAGEMENT_KEY_MISSING", "GUARDRAIL_UNVERIFIED", "GUARDRAIL_UPDATE_UNCONFIRMED", "GUARDRAIL_ASSIGNMENT_UNVERIFIED",
    "KEY_UNAVAILABLE", "VERIFICATION_RATE_LIMITED", "VERIFICATION_UNAVAILABLE",
}
GATEWAY_CAPABILITY_FIELDS = {"gateway_capability_failure_kind", "gateway_capability_decode_reason"}
GATEWAY_CAPABILITY_FAILURE_KINDS = {
    "CONNECT_TIMEOUT", "TIMEOUT", "DNS", "TLS", "CONNECTION", "CONNECTION_CLOSED",
    "HTTP_REJECTED", "DECODE_REJECTED", "ISOLATION_UNVERIFIED", "UNKNOWN",
}
GATEWAY_CAPABILITY_DECODE_REASONS = {
    "MALFORMED_JSON", "SCHEMA_MISMATCH", "NULL_NOT_ALLOWED", "EXPECTED_BOOLEAN", "EXPECTED_INTEGER",
    "EXPECTED_STRING", "EXPECTED_OBJECT", "MISSING_REQUIRED_FIELD", "EXPECTED_ARRAY",
}
ENUMS = {
    "provider_failure_stage": {"ADMISSION", "ENGINEERING_GUARD", "GENERATION", "CONTINUATION", "REVIEW_COMPATIBILITY", "ANSWER_REVIEW", "CLAIM_VERIFICATION", "DELIVERY"},
    "provider_failure_domain": {"PROVIDER", "APPROVAL", "APPLICATION_VERIFICATION", "TOOL_EXECUTION", "UNKNOWN"},
    "provider_failure_code": {"BUDGET_RESTRICTED", "OUTPUT_CONTINUATION_NO_PROGRESS", "PROVIDER_INVOCATION_FAILED", "HTTP_ERROR", "TRANSPORT_ERROR", "UNCLASSIFIED_PROVIDER_FAILURE", "OUTPUT_LIMIT", "CAPACITY_UNVERIFIED", "INPUT_CAPACITY_EXCEEDED", "OUTPUT_CAPACITY_EXCEEDED", "CONTEXT_CAPACITY_EXCEEDED", "LOCAL_SERVICE_UNREACHABLE", "GATEWAY_OUTCOME_UNKNOWN", "TIMEOUT_OUTCOME_UNKNOWN", "RUN_DEADLINE_REACHED", "PROVIDER_RATE_LIMITED", "PRIVACY_CONFIGURATION", "PRIVACY_PROVIDER_INELIGIBLE"},
    "privacy_reason_code": PRIVACY_ELIGIBILITY_REASONS,
    "privacy_status": {"NOT_VERIFIED", "INCOMPATIBLE"},
    "gateway_capability_failure_kind": GATEWAY_CAPABILITY_FAILURE_KINDS,
    "gateway_capability_decode_reason": GATEWAY_CAPABILITY_DECODE_REASONS,
    "voice_reply_state": {"FAILED_WITHOUT_DETAILS", "EMPTY_REPLY"},
    "action_only_tool_family": {"REPOSITORY_WORKSPACE", "REPOSITORY_GRAPH", "GITHUB_NATIVE", "GITHUB_OPERATION", "OTHER"},
    "action_only_validation": {"NO_RECEIPT", "CALL_BOUND_VALIDATOR", "VALIDATOR_CALL_MISMATCH", "NO_VALIDATOR_ID"},
    "tool_action": {"stage", "agent_merge_status", "agent_merge_enable", "agent_merge_update", "agent_merge_disable", "status", "list", "headers", "context", "comments", "reviews", "threads", "history", "commit", "branch", "changes", "checks", "workflows", "runs", "jobs", "logs", "failure", "read", "search", "create", "update", "close", "reopen", "review", "diff", "summary", "files", "tree", "get", "rerun", "rerun_failed_jobs", "cancel", "dispatch"},
    "tool_error_code": {"APP_ERROR", "DNS_RESOLUTION_FAILED", "TOOL_FAILURE", "TOOL_PRECHECK_FAILED", "INVALID_ARGUMENTS", "TOOL_UNAVAILABLE", "EVIDENCE_UNAVAILABLE", "SAVED_VIEW_CURSOR_ALTERED", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_UNAVAILABLE", "GITHUB_VALIDATION_FAILED", "HTTP_ERROR", "GITHUB_NOT_FOUND_OR_INACCESSIBLE", "GITHUB_REVIEW_BLOCKED", "PRECHECK_REJECTED", "TOOL_OUTCOME_UNKNOWN", "UNKNOWN_TOOL", "GITHUB_CONFLICT", "GITHUB_PR_HEAD_CHANGED", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED", "GITHUB_FORBIDDEN_UNKNOWN", "GITHUB_ENDPOINT_PERMISSION_REQUIRED"},
    "tool_reason_code": {"BUDGET_EXHAUSTED", "COMBINED_BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE", "TOOL_PRECHECK_FAILED", "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED", "TOOL_ARGUMENT_SCHEMA_REJECTED", "TOOL_ARGUMENT_CONSTRAINT_REJECTED", "GITHUB_REPOSITORY_INPUT_REJECTED", "GITHUB_CI_RUN_SELECTOR_REQUIRED", "GITHUB_REPOSITORY_SELECTOR_INCOMPLETE", "GITHUB_SEARCH_QUERY_EMPTY", "GITHUB_SEARCH_QUERY_TOO_LONG", "GITHUB_SEARCH_FUZZY_QUERY_INVALID", "GITHUB_ARGUMENT_PRECHECK_UNCLASSIFIED", "ISSUE_SELECTOR_REQUIRED", "PR_SELECTOR_REQUIRED"},
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
    "voice_phase": {"PAUSED", "STARTING", "LISTENING", "FINALIZING", "REVIEWING", "THINKING", "PREPARING", "SPEAKING", "ERROR", "CLOSED"},
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
        "GRAPH_INDEX_REQUIRED", "GRAPH_INDEX_UNCONFIRMED", "GRAPH_INPUT_REQUIRED",
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
        "privacy_policy_hash", "privacy_zdr_required", "privacy_provider_allowlist_count", "privacy_provider_denylist_count", "privacy_provider_order_count", "privacy_fallbacks_allowed", "privacy_observation", "privacy_reason_code", "privacy_status",
        "backend_terminal_code_hash", "backend_terminal_status", "backend_finish_reason", "backend_status",
    },
    "TOOL": COMMON_HASHES | {"action_only_tool_family", "action_only_validation", "tool_action", "tool_error_code", "tool_reason_code", "tool_dispatch_phase", "unknown_field_count", "tool_name_hash", "tool_action_hash", "tool_schema_hash", "original_error_code_hash", "original_reason_code_hash", "argument_shape_hash", "missing_field_count", "invalid_field_count", "validation_error_count", "invalid_field_classes"},
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
FIELDS_BY_AREA["PROVIDER"].update(GATEWAY_CAPABILITY_FIELDS)
ENUMS["provider_failure_code"].update(capsule.CONVERSATION_RECOVERY_CODES | capsule.EXECUTION_COMPLETION_CODES | {"OUTPUT_CONTINUATION_UNAVAILABLE"})
FIELDS_BY_AREA["PROVIDER"].update({"gateway_model_state", "gateway_model_advertised", "gateway_model_loaded"})
FLAGS.update({"gateway_model_advertised", "gateway_model_loaded"})
ENUMS["provider_failure_code"].update({"SELECTED_MODEL_LOADING", "SELECTED_MODEL_NOT_LOADED", "SELECTED_MODEL_NOT_AVAILABLE"})
ENUMS["provider_failure_code"].update({"GATEWAY_CAPABILITY_UNAVAILABLE", "GATEWAY_ISOLATION_UNAVAILABLE"})
# Bounded diagnostic additions for open issue recovery.
ENUMS["tool_reason_code"].update({"SAVED_VIEW_CONTEXT_MISMATCH", "SAVED_VIEW_CURSOR_MALFORMED", "GITHUB_PATH_NOT_FOUND"})
# Only the app's bound repository-graph receipt may produce these public categories.
# The service validates this closed vocabulary; no request parameters, URLs, or host details are exported.
ENUMS["tool_reason_code"].update({"GRAPH_HTTP_REJECTED", "GRAPH_TRANSPORT_UNAVAILABLE", "GRAPH_TLS_REJECTED", "GRAPH_INDEX_OUTCOME_UNKNOWN", "GRAPH_OPERATION_FAILED", "GRAPH_REQUEST_REJECTED", "GRAPH_REVISION_MISMATCH", "GRAPH_JOB_CHANGED", "GRAPH_JOB_ACK_MISSING", "GRAPH_INTERNAL_STORAGE", "GRAPH_INTERNAL_IO", "GRAPH_INTERNAL_SERVER"})
ENUMS.update({'voice_install_state': {'IDLE', 'QUEUED', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED', 'WAITING_NETWORK'}, 'voice_install_scheduler': {'JOB_SCHEDULER', 'WORK_MANAGER'}, 'voice_install_failure': {'SCHEDULE_REJECTED', 'SCHEDULE_EXCEPTION', 'ASSET_FAILURE', 'WORKER_EXCEPTION'}, 'github_transport_kind': {'DNS', 'DISCONNECTED', 'ABORTED', 'TIMEOUT', 'TLS', 'CANCELLED', 'UNKNOWN', 'CIRCUIT_OPEN', 'HTTP'}, 'reasoning_requested_mode': {'AUTO', 'ON', 'OFF'}, 'reasoning_admission_basis': {'NO_VERIFIED_CONTROL'}})
COUNTS.update(['voice_install_stop_reason', 'voice_install_progress_percent', 'read_range_count', 'read_invalid_window_count', 'argument_page_size', 'repository_input_blocked_count', 'workflow_input_blocked_count', 'live_evidence_blocked_count', 'acceptance_failed_operation_count'])
DURATIONS.add("github_transport_wait_ms")
FIELDS_BY_AREA['VOICE'].update(['voice_install_state', 'voice_install_scheduler', 'voice_install_failure', 'voice_install_stop_reason', 'voice_install_progress_percent'])
FIELDS_BY_AREA['TOOL'].update(['github_transport_kind', 'github_transport_wait_ms', 'read_range_count', 'read_invalid_window_count', 'argument_page_size'])
FIELDS_BY_AREA['PROVIDER'].update(['reasoning_requested_mode', 'reasoning_admission_basis'])
ENUMS['tool_protocol_frame_kind'] = {'MISSING_FUNCTION_NAME', 'UNFINISHED_STREAM', 'NON_OBJECT_ARGUMENTS', 'MALFORMED_JSON'}
ENUMS['tool_protocol_recovery_outcome'] = {'TEXT_FALLBACK_EXHAUSTED', 'TOOL_RETRY_EXHAUSTED', 'BACKEND_HANDLED_TOOLS', 'SINGLE_PASS_NOT_RETRYABLE', 'TEXT_ALREADY_EMITTED', 'NO_SAFE_REPAIR'}
FIELDS_BY_AREA['PROVIDER'].update(['tool_protocol_frame_kind', 'tool_protocol_recovery_outcome'])
FIELDS_BY_AREA['ACCEPTANCE'].update(['repository_input_blocked_count', 'workflow_input_blocked_count', 'live_evidence_blocked_count', 'acceptance_failed_operation_count'])

ENUMS.update({"reasoning_route_kind": {"GATEWAY", "DIRECT"}, "reasoning_control_state": {"VERIFIED", "UNSUPPORTED", "UNVERIFIED"}})
HASH_FIELDS.add("reasoning_template_hash")
FIELDS_BY_AREA["PROVIDER"].update({"reasoning_route_kind", "reasoning_control_state", "reasoning_template_hash"})

ENUM_LISTS["read_window_rejections"] = {"BAD_START", "CONFLICTING_LIMITS", "BAD_MAX_LINES", "BAD_END"}
FIELDS_BY_AREA["TOOL"].add("read_window_rejections")

COUNTS.add("acceptance_unresolved_claim_count")
ENUM_LISTS["acceptance_claim_failure_reasons"] = {'EXACT_RECEIPT_MATCH', 'CLAIM_VALUE_MISSING', 'OPERATION_FAILED', 'POINTER_NOT_IN_EVIDENCE', 'RECEIPT_NOT_FOUND', 'OBSERVATION_TIME_UNKNOWN', 'AUTHORIZATION_REVALIDATION_REQUIRED', 'UNTRUSTED_RECEIPT', 'VALUE_MISMATCH', 'FIELD_SEMANTICS_MISMATCH', 'HISTORICAL_OBSERVATION', 'MUTABLE_EVIDENCE_EXPIRED_OR_REVISION_CHANGED'}
FIELDS_BY_AREA["ACCEPTANCE"].update({"acceptance_unresolved_claim_count", "acceptance_claim_failure_reasons"})

# Protocol 2: source HTTP request starts (not adapter retries), separate probe and metadata ages.
REASONING_PROBE_FIELDS = {"reasoning_probe_outcome", "reasoning_probe_attempts", "reasoning_probe_age_ms", "reasoning_metadata_age_ms"}
ENUMS["reasoning_probe_outcome"] = {"VERIFIED", "IDENTICAL_PROMPTS", "MISSING_THINK_MARKER", "PREFIX_MISMATCH", "SUFFIX_MISMATCH", "INVALID_PROMPT", "METADATA_CHANGED", "PROBE_UNAVAILABLE", "PROBE_DEADLINE", "MIXED_NEGATIVE_RESULTS"}
DURATIONS.update({"reasoning_probe_age_ms", "reasoning_metadata_age_ms"})
FIELDS_BY_AREA["PROVIDER"].update(REASONING_PROBE_FIELDS)

# Bounded admission observations; raw requests and host paths are never accepted.
COUNTS.update({"reasoning_capability_age_ms", "admission_prompt_tokens", "admission_safety_tokens", "admission_output_tokens", "admission_capacity_tokens", "admission_dominant_tokens", "queue_active_leases", "queue_pending_deliveries", "queue_unsettled_submissions", "queue_legacy_runs", "queue_cached_active_jobs", "queue_concurrency_limit", "gateway_preflight_blocker_count", "gateway_preflight_candidate_count", "acceptance_approval_blocked_total", "acceptance_input_blocked_total", "acceptance_live_blocked_total", "acceptance_continuation_blocked_total"})
ENUMS["admission_measurement"] = {"SERVING_TOKENIZER", "UTF8_ESTIMATE"}
ENUMS["admission_dominant_component"] = {"tool_schemas", "system_instructions", "retained_history", "tool_results", "current_request", "response_schema", "serialization_and_template_estimate", "attachments"}
ENUMS["status_policy_recovery"] = {"CHECK_PROTECTION_READ_PERMISSION", "CHECK_RULES_READ_PERMISSION"}
ENUMS["reasoning_model_state"] = {"UNKNOWN", "UNAVAILABLE", "UNLOADED", "LOADING", "LOADED", "SLEEPING"}
ENUMS["reasoning_recovery_action"] = {"CHOOSE_AUTO_OR_QUALIFIED_MODEL"}
ENUMS["queue_blocker"] = {"ACTIVE_LEASE", "CHAT_BUSY", "DELIVERY_PENDING", "EXTERNAL_WORK", "GATEWAY_NOT_READY", "MODEL_PREPARATION", "CONFIGURATION", "NONE"}
ENUMS["gateway_validation_policy_state"] = {"NOT_CONFIGURED", "MISSING_OR_OVERSIZED", "REPOSITORY_UNCONFIGURED", "INVALID_DOCUMENT", "INVALID_ENTRY", "UNREADABLE", "VALIDATED"}
ENUMS["gateway_preflight_reason"] = {"VALIDATION_POLICY_REQUIRED", "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED", "VALIDATION_RUNTIME_UNAVAILABLE", "VALIDATION_REVISION_UNAVAILABLE", "SOURCE_REVISION_REQUIRED", "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED"}
ENUMS["gateway_preflight_discovery_state"] = {"REVIEW_REQUIRED", "DISCOVERY_UNAVAILABLE"}
ENUM_LISTS["gateway_preflight_blocker_codes"] = ENUMS["gateway_preflight_reason"]
ENUM_LISTS["gateway_preflight_recovery_actions"] = {"CONFIGURE_VALIDATION_POLICY", "DEFINE_ACCEPTANCE_REQUIREMENTS", "CONFIGURE_CI_CHECK_MAPPING", "CONFIGURE_ACCEPTANCE_MAPPING", "REFRESH_SOURCE_REVISION", "PROVISION_PINNED_MIRROR", "PROVISION_VALIDATION_RUNTIME"}
FIELDS_BY_AREA["PROVIDER"].update({"reasoning_capability_age_ms", "reasoning_model_state", "admission_prompt_tokens", "admission_safety_tokens", "admission_output_tokens", "admission_capacity_tokens", "admission_dominant_tokens", "admission_measurement", "admission_dominant_component", "reasoning_recovery_action"})
FIELDS_BY_AREA["GATEWAY"].update({"queue_active_leases", "queue_pending_deliveries", "queue_unsettled_submissions", "queue_legacy_runs", "queue_cached_active_jobs", "queue_concurrency_limit", "gateway_preflight_blocker_count", "gateway_preflight_candidate_count", "gateway_preflight_blocker_codes", "gateway_preflight_recovery_actions", "gateway_preflight_discovery_state", "queue_blocker", "gateway_validation_policy_state", "gateway_preflight_reason"})
FIELDS_BY_AREA["ACCEPTANCE"].update({"status_policy_recovery", "branch_protection_http_status", "branch_rules_http_status", "acceptance_approval_blocked_total", "acceptance_input_blocked_total", "acceptance_live_blocked_total", "acceptance_continuation_blocked_total"})

COUNTS.add("graph_input_blocked_count")
ENUMS["repository_graph_observation"] = {"NOT_INDEXED", "OBSERVED", "UNOBSERVED", "REVISION_MISMATCH", "UNAVAILABLE"}
ENUMS["workflow_dependency_observation"] = {"UNOBSERVED", "READ_FAILED", "OBSERVED_EXECUTED_JOB", "RUN_READ_PENDING", "MISSING_EXECUTED_JOB", "RUN_PAGE_AVAILABLE"}
FIELDS_BY_AREA["ACCEPTANCE"].update({"repository_graph_observation", "graph_input_blocked_count", "workflow_dependency_observation"})

# Bounded evidence from the owning request and the exact retained verdict.
ENUMS["provider_failure_code"].update({"CONVERSATION_CONFLICT", "CLIENT_TOOL_RESULTS_REQUIRED", "GITHUB_CIRCUIT_OPEN", "REASONING_OFF_UNSUPPORTED", "REASONING_ON_UNSUPPORTED"})
ENUMS["conflict_active_job_kind"] = {"generation", "engineering"}
ENUMS["github_cooldown_stop"] = {"WAIT_ALREADY_USED", "WAIT_LIMIT", "TASK_DEADLINE"}
HASH_FIELDS.add("conflict_conversation_hash")
TRANSPORT_FIELDS = {"github_transport_read_attempts", "github_transport_unanswered_reads", "github_transport_http_responses"}
GITHUB_SCHEMA_BUDGET_FIELDS = {"required_schema_tokens", "available_schema_tokens"}
ENUMS["provider_failure_code"].add("GITHUB_TOOL_CONTEXT_BUDGET")
COUNTS.update(GITHUB_SCHEMA_BUDGET_FIELDS)
FIELDS_BY_AREA["PROVIDER"].update(GITHUB_SCHEMA_BUDGET_FIELDS)
PROFILE_SELECTION_FLAGS = {"github_selection_profile_match", "github_selection_registered_profile", "github_selection_model_match"}
PROFILE_SELECTION_HASHES = {"github_selection_profile_hash", "github_selection_registered_model_hash"}
PROFILE_SELECTION_FIELDS = PROFILE_SELECTION_FLAGS | PROFILE_SELECTION_HASHES
FLAGS.update(PROFILE_SELECTION_FLAGS)
HASH_FIELDS.update(PROFILE_SELECTION_HASHES)
ENUMS["provider_failure_code"].add("GITHUB_PROFILE_LOCKED")
FIELDS_BY_AREA["PROVIDER"].update(PROFILE_SELECTION_FIELDS)
GITHUB_COOLDOWN_LONGS = {"github_cooldown_delay_ms", "github_cooldown_deadline_ms", "github_cooldown_observed_at_ms"}
GITHUB_COOLDOWN_FIELDS = GITHUB_COOLDOWN_LONGS | {"github_cooldown_resource", "github_cooldown_basis", "github_cooldown_remaining_requests"}
ENUMS["github_cooldown_resource"] = {"CORE", "SEARCH", "GRAPHQL", "OTHER"}
ENUMS["github_cooldown_basis"] = {"SECONDARY_DEADLINE", "RESOURCE_RESET", "RESOURCE_RETRY_AFTER", "RESOURCE_RESET_AND_RETRY_AFTER"}
ENUMS["provider_failure_code"].update({"GITHUB_COOLDOWN_ACTIVE", "GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED"})
ENUMS["tool_error_code"].add("GITHUB_COOLDOWN_ACTIVE")
COUNTS.add("github_cooldown_remaining_requests")
FIELDS_BY_AREA["PROVIDER"].update(GITHUB_COOLDOWN_FIELDS)
FIELDS_BY_AREA["TOOL"].update(GITHUB_COOLDOWN_FIELDS)
CAPACITY_DETAIL_FIELDS = {"admission_tool_schema_tokens", "admission_tool_result_reserve_tokens", "admission_attachment_reserve_tokens"}
COUNTS.update(TRANSPORT_FIELDS | CAPACITY_DETAIL_FIELDS | {"conflict_conversation_revision", "acceptance_pending_remote_continuations"})
FIELDS_BY_AREA["PROVIDER"].update({"conflict_active_job_kind", "conflict_conversation_revision", "conflict_pending_tool_count", "conflict_conversation_hash", "github_cooldown_stop"})
FIELDS_BY_AREA["PROVIDER"].update(CAPACITY_DETAIL_FIELDS)
FIELDS_BY_AREA["TOOL"].update(TRANSPORT_FIELDS)
FIELDS_BY_AREA["ACCEPTANCE"].add("acceptance_pending_remote_continuations")

ENUMS["provider_limit_source"] = {"upstream_provider_shared_pool", "openrouter_free_tier_per_minute", "openrouter_free_tier_daily"}
HASH_FIELDS.add("provider_cooldown_scope_hash")
FIELDS_BY_AREA["PROVIDER"].update({"provider_limit_source", "provider_cooldown_scope_hash"})

# Numeric request components and actual capacity provenance. No raw prompt,
# endpoint, model name or provider payload is part of this contract.
CAPACITY_CEILING_FIELDS = {
    "admission_route_context_tokens", "admission_configured_context_tokens", "admission_catalog_context_tokens",
    "admission_input_ceiling_tokens", "admission_output_ceiling_tokens",
}
CAPACITY_EVIDENCE_COUNTS = CAPACITY_CEILING_FIELDS | {
    "admission_system_tokens", "admission_history_tokens", "admission_current_request_tokens",
    "admission_tool_result_tokens", "admission_response_schema_tokens", "admission_envelope_tokens", "admission_capacity_age_ms",
}
COUNTS.update(CAPACITY_EVIDENCE_COUNTS)
ENUMS["admission_capacity_provenance"] = {"VERIFIED_ROUTE", "DECLARED", "CONSERVATIVE_POLICY", "UNKNOWN"}
FIELDS_BY_AREA["PROVIDER"].update(CAPACITY_EVIDENCE_COUNTS | {"admission_capacity_provenance"})

CONFLICT_EVIDENCE_FIELDS = {
    "conflict_active_job_provenance", "conflict_pending_tool_state", "conflict_pending_tool_ids_hash",
    "conflict_handoff_sequence", "conflict_stream_job_relation",
}
ENUMS["conflict_active_job_provenance"] = {"CONVERSATION_ACTIVE_JOB", "UNREPORTED"}
ENUMS["conflict_pending_tool_state"] = {"IDENTIFIED", "MISSING", "EMPTY", "INVALID", "UNAVAILABLE", "NOT_REPORTED"}
ENUMS["conflict_stream_job_relation"] = {"MATCHES_VERIFIED_STREAM_JOB", "DIFFERS_VERIFIED_STREAM_JOB", "NO_VERIFIED_STREAM_JOB", "ACTIVE_JOB_NOT_REPORTED"}
HASH_FIELDS.add("conflict_pending_tool_ids_hash")
FIELDS_BY_AREA["PROVIDER"].update(CONFLICT_EVIDENCE_FIELDS)

ENUMS["acceptance_capability_scope"] = {"FULL_CATALOG", "OBSERVED_CAPABILITIES"}
ENUMS["acceptance_pagination_mode"] = {"COMPLETE_INVENTORY_REQUESTED", "BOUNDED_SMOKE_SAMPLE"}
FIELDS_BY_AREA["ACCEPTANCE"].update({"acceptance_capability_scope", "acceptance_pagination_mode"})

VOICE_INSTALL_EVIDENCE_FIELDS = {
    "voice_install_pending_reason", "voice_install_elapsed_ms", "voice_install_pack", "voice_install_transfer_stage",
    "voice_install_failure_kind", "voice_install_http_status", "voice_install_downloaded_bytes", "voice_install_total_bytes",
}
ENUMS["voice_install_failure"].add("WORK_NOT_FOUND")
ENUMS["voice_install_pending_reason"] = {"UNDEFINED", "APP_STATE", "APP_STANDBY", "BACKGROUND_RESTRICTION", "CONNECTIVITY", "DEVICE_STATE", "SCHEDULER_OPTIMIZATION", "QUOTA", "USER", "OTHER_CONSTRAINT", "EXECUTING", "MISSING", "UNAVAILABLE"}
ENUMS["voice_install_pack"] = {"MOONSHINE", "KOKORO"}
ENUMS["voice_install_transfer_stage"] = {"CHECKING", "CONNECTING", "TRANSFERRING", "VERIFYING", "EXTRACTING", "READY", "FAILED"}
ENUMS["voice_install_failure_kind"] = {"DNS", "TIMEOUT", "TLS", "HTTP", "NETWORK_IO", "INTEGRITY", "STORAGE", "MODEL_IN_USE", "UNKNOWN"}
COUNTS.add("voice_install_elapsed_ms")
FIELDS_BY_AREA["VOICE"].update(VOICE_INSTALL_EVIDENCE_FIELDS)

VOICE_CAPTURE_PLAYBACK_FIELDS = {"voice_silenced_system_mic_muted", "voice_silenced_audio_mode", "voice_pronunciation_unknown_word_length", "voice_playback_audio_mode", "voice_playback_failure", "voice_playback_focus_attempts", "voice_capture_silenced_stage", "voice_caption_alignment", "voice_focus_last_transition", "voice_focus_transition_count", "voice_pronunciation_unknown_words", "voice_pronunciation_initialisms", "voice_pronunciation_failure_kind", "voice_pronunciation_audio_queued", "voice_pronunciation_first_audio_observed"}
ENUMS["voice_playback_failure"] = {"AUDIO_FOCUS_DENIED", "AUDIO_FOCUS_LOST", "AUDIO_FOCUS_TIMEOUT", "AUDIO_FOCUS_RESUME_FAILED", "TIMEOUT", "MEMORY_PRESSURE", "TEXT_QUEUE_FULL", "SYNTHESIS_FAILED", "ENGINE_FAILED", "AUDIO_FAILED", "PRONUNCIATION_UNAVAILABLE", "LEXICON_UNAVAILABLE"}
ENUMS["voice_pronunciation_failure_kind"] = {"UNKNOWN_WORD", "UNSUPPORTED_SCRIPT", "MISSING_LETTER"}
ENUMS["voice_playback_audio_mode"] = {"NORMAL", "RINGTONE", "IN_CALL", "IN_COMMUNICATION", "OTHER"}
FLAGS.add("voice_silenced_system_mic_muted")
ENUMS["voice_silenced_audio_mode"] = ENUMS["voice_playback_audio_mode"]
ENUMS["voice_capture_silenced_stage"] = {"CAPTURE_START", "CAPTURE_READ"}
ENUMS["voice_caption_alignment"] = {"NO_TIMING", "EXACT_RANGE", "TEXT_UNMAPPED"}
ENUMS["voice_focus_last_transition"] = {"RESTORE", "PAUSE", "DUCK", "STOP"}
COMPOSER_EVENT_FIELDS = {f"composer_event_{slot}" for slot in range(1, 9)}
COMPOSER_AGE_FIELDS = {f"composer_event_{slot}_age_ms" for slot in range(1, 9)}
COMPOSER_SLOT_FIELDS = COMPOSER_EVENT_FIELDS | COMPOSER_AGE_FIELDS
COMPOSER_TIMELINE_FIELDS = COMPOSER_SLOT_FIELDS | {"composer_generation", "composer_events_dropped", "composer_enabled", "composer_window_focused", "composer_lifecycle", "composer_input_mode"}
COMPOSER_EVIDENCE_FIELDS = COMPOSER_TIMELINE_FIELDS | {"composer_focus_transitions", "composer_ime_transitions", "composer_focused", "composer_ime_visible"}
COMPOSER_EVENTS = {"ATTACHED", "DETACHED", "EDITOR_FOCUSED", "EDITOR_UNFOCUSED", "IME_VISIBLE", "IME_HIDDEN", "WINDOW_FOCUSED", "WINDOW_UNFOCUSED", "CHAT_ENABLED", "CHAT_DISABLED", "INPUT_TOUCH", "INPUT_KEYBOARD", "LIFECYCLE_CREATE", "LIFECYCLE_START", "LIFECYCLE_RESUME", "LIFECYCLE_PAUSE", "LIFECYCLE_STOP", "LIFECYCLE_DESTROY", "RELEASE_SEND", "RELEASE_SHORTCUT", "RELEASE_BACKGROUND_TAP", "RELEASE_DISABLED", "RELEASE_LIFECYCLE_STOP", "RELEASE_DISPOSE"}
ENUMS.update({field: COMPOSER_EVENTS for field in COMPOSER_EVENT_FIELDS})
ENUMS["composer_lifecycle"] = {"INITIALIZED", "CREATED", "STARTED", "RESUMED", "DESTROYED"}
ENUMS["composer_input_mode"] = {"TOUCH", "KEYBOARD"}
FLAGS.update({"composer_focused", "composer_ime_visible", "composer_enabled", "composer_window_focused"})
SEARCH_ATTEMPT_FIELDS = {"search_attempt_count", "search_dispatched_attempt_count", "search_budget_rejected_attempt_count"}
REPLAY_BUDGET_COUNTS = {"replay_allowance", "replay_tokens", "replay_exchange_count"}
COUNTS.update(REPLAY_BUDGET_COUNTS)
ENUMS["replay_budget_state"] = {"NO_INITIAL_ALLOWANCE", "NO_CONTINUATION_ALLOWANCE", "COMPACTED_IDENTITIES_EXCEED_ALLOWANCE"}
FIELDS_BY_AREA["INCIDENT"].update(REPLAY_BUDGET_COUNTS | {"replay_budget_state"})
COUNTS.add("reconciliation_tracked_signature_count")
FLAGS.add("round_catalog_discovery_available")
ENUMS["reconciliation_source"] = {"SAVED_AND_TRACKED", "SAVED_UNSETTLED_CALL", "TRACKED_CALL_SIGNATURE"}
ENUMS["graph_transport_kind"] = {"DNS", "DISCONNECTED", "ABORTED", "TIMEOUT", "TLS", "UNKNOWN"}
ENUMS["url_reader_refusal"] = {"DOMAIN_SCOPE", "HOP_LIMIT", "LOCATION_MISSING", "LOOP", "LOCATION_INVALID", "HTTP_ACCESS_DENIED", "TURN_DENIAL_CACHE"}
FIELDS_BY_AREA["INCIDENT"].update({"reconciliation_source", "reconciliation_tracked_signature_count"})
FIELDS_BY_AREA["TOOL"].update({"url_reader_refusal", "round_catalog_discovery_available", "graph_transport_kind"})
COUNTS.update(SEARCH_ATTEMPT_FIELDS)
ENUMS["search_failure_observation"] = {"UPSTREAM_CHALLENGE", "NO_ENGINE_SUCCEEDED"}
ENUMS["catalog_selector_state"] = {"MIXED_INVENTORY_SELECTORS", "ACTION_NOT_STRING", "ACTION_WITHOUT_NAMES", "NAMES_NOT_ARRAY", "NAMES_NULL",
                                   "NAMES_CONTAIN_NON_STRING", "ACTION_REQUIRES_ONE_NAME", "OTHER_SCHEMA_CONSTRAINT"}
FIELDS_BY_AREA["TOOL"].update(SEARCH_ATTEMPT_FIELDS | {"search_failure_observation", "catalog_selector_state"})
COUNTS.add("tool_budget_bytes_available")
FIELDS_BY_AREA["TOOL"].update({"tool_budget_bytes_available", "graph_index_attempt_hash"})
HASH_FIELDS.add("graph_index_attempt_hash")
FIELDS_BY_AREA["APP"].update(COMPOSER_EVIDENCE_FIELDS)
COMPLETION_EVIDENCE_FIELDS = {"empty_completion_observed_attempts", "empty_completion_reasoning_only_attempts"}
ATTEMPTS.update(COMPLETION_EVIDENCE_FIELDS)
FIELDS_BY_AREA["PROVIDER"].update(COMPLETION_EVIDENCE_FIELDS)
ENUMS["provider_failure_code"].add("TOOL_PROTOCOL_INVALID_FRAME")
VOICE_PACK_STATUS_FIELDS = {"voice_install_recognition_status", "voice_install_playback_status"}
for field in VOICE_PACK_STATUS_FIELDS:
    ENUMS[field] = {"CHECKING", "NOT_INSTALLED", "DOWNLOADING", "READY", "FAILED"}
FIELDS_BY_AREA["VOICE"].update(VOICE_CAPTURE_PLAYBACK_FIELDS | VOICE_PACK_STATUS_FIELDS)

# Exact retained prerequisite and policy-read observations; no names, IDs or raw errors.
ACCEPTANCE_RECOVERY_FIELDS = {
    "acceptance_prerequisite_blockers", "acceptance_acquisition_allowed", "acceptance_cleanup_reserve", "acceptance_fixture_start_minimum",
    "task_deadline_check", "task_deadline_observation", "task_deadline_window_seconds", "task_deadline_overdue_seconds",
    "task_deadline_clock", "task_deadline_renewed", "task_deadline_elapsed_seconds", "task_deadline_remaining_seconds",
    "acceptance_preparation_state", "acceptance_ready_read_count", "acceptance_additional_ready_read_count",
    "acceptance_fixture_creation_admissible", "acceptance_blocked_operation_families", "acceptance_additional_blocked_family_count",
    "status_policy_protection_http_status", "status_policy_rules_http_status",
    "status_branch_protected", "status_policy_complete", "status_policy_recovery_hint", "status_missing_observations",
}
ENUMS["task_deadline_check"] = {"BEFORE_TOOL_RESTORE", "BEFORE_MODEL_LOOP", "MODEL_LOOP_TIMEOUT", "VERIFIED_SESSION_ENTRY"}
ENUMS["task_deadline_clock"] = {"WALL_CLOCK"}
FLAGS.add("task_deadline_renewed")
ENUMS["task_deadline_observation"] = {"CONTRACT_UNAVAILABLE", "NO_ACCEPTED_DEADLINE", "TIMING_UNAVAILABLE", "WINDOW_OPEN", "WINDOW_EXPIRED"}
ENUM_LISTS["acceptance_prerequisite_blockers"] = {"OUT_OF_SCOPE", "REPOSITORY_SCOPE_EXCLUDED", "OPERATION_IDENTITY_REQUIRED", "AUTHORIZATION_REQUIRED", "HISTORICAL_CONTRACT_CHANGED", "APPROVAL_REQUIRED", "FIXTURE_APPROVAL_STATE_UNKNOWN", "FIXTURE_APPROVAL_MODE_REQUIRED", "LIVE_EVIDENCE_REQUIRED", "CONTINUATION_REQUIRED", "PRECHECK_REJECTED", "OPERATION_FAILED", "USER_DECISION_REQUIRED", "CHECK_SUBSCRIPTION_REQUIRED", "OWNED_FIXTURE_REQUIRED", "ISSUE_ID_REQUIRED", "PULL_REQUEST_ID_REQUIRED", "WORKFLOW_RUN_OR_JOB_REQUIRED", "RELEASE_OR_TAG_REQUIRED", "REPOSITORY_INPUT_REQUIRED", "GRAPH_INDEX_REQUIRED", "GRAPH_INDEX_UNCONFIRMED", "GRAPH_INPUT_REQUIRED", "REPOSITORY_LIFECYCLE_REQUIRED", "EXECUTION_NOT_EXERCISED", "BLOCKER_DETAIL_NOT_RECORDED"}
COUNTS.update({"task_deadline_window_seconds", "task_deadline_overdue_seconds", "task_deadline_elapsed_seconds", "task_deadline_remaining_seconds", "acceptance_cleanup_reserve", "acceptance_fixture_start_minimum"})
FLAGS.add("acceptance_acquisition_allowed")
ENUMS["acceptance_preparation_state"] = {"CLEANUP_RESERVE_ACTIVE", "OWNED_FIXTURE_STEP_PREPARED", "BOUNDED_READS_PREPARED", "NO_PENDING_CAPABILITY_PREREQUISITES", "PREREQUISITES_REQUIRED"}
ENUMS["status_policy_recovery_hint"] = {"CHECK_AUTHENTICATION", "CHECK_PROTECTION_READ_PERMISSION", "CHECK_RULES_READ_PERMISSION", "CHECK_POLICY_READ_PERMISSIONS", "WAIT_RATE_LIMIT", "REFRESH_POLICY_OBSERVATIONS"}
ENUM_LISTS["status_missing_observations"] = {"BRANCH_HEAD", "COMMITS", "OPEN_PULLS", "COMMIT_CHECKS", "LATEST_WORKFLOW", "REQUIRED_POLICY", "RECENT_FAILURES", "REVIEW_DECISIONS"}
ENUM_LISTS["acceptance_blocked_operation_families"] = {"github_context", "github_read", "github_search", "github_pr", "github_issue", "github_ci", "github_watch_checks", "github_stage_patch", "github_commit", "github_write", "github_release", "github_decide", "github_review", "github_engineering", "github_repository", "repository_graph"}
COUNTS.update({"acceptance_ready_read_count", "acceptance_additional_ready_read_count", "acceptance_additional_blocked_family_count"})
FLAGS.update({"acceptance_fixture_creation_admissible", "status_branch_protected", "status_policy_complete"})
FIELDS_BY_AREA["ACCEPTANCE"].update(ACCEPTANCE_RECOVERY_FIELDS)

REASONING_PROBE_NEGATIVES = {"IDENTICAL_PROMPTS", "MISSING_THINK_MARKER", "PREFIX_MISMATCH", "SUFFIX_MISMATCH", "INVALID_PROMPT"}
ENUMS.update({
    "provider_wait_decision_state": {"NOT_RECORDED"} | capsule.EXECUTION_COMPLETION_ENUMS["provider_wait_decision_state"],
    "dispatch_gateway_selection_change": {"SELECTED", "CLEARED", "REPLACED"},
    "read_handle_kind": {"SAVED_CURSOR", "NO_HANDLE", "DIGEST_SHAPED", "VERIFICATION_RECEIPT", "NONCANONICAL_HANDLE"},
    "native_trace_format": {"text", "protobuf", "binary", "unknown"},
    "native_trace_reason": {"no_trace_stream", "empty_trace", "trace_read_failed", "trace_access_failed", "unrecognized_binary_tombstone", "no_recognized_tombstone_fields", "text_prefix_limit", "signal_and_library_frames", "malformed_protobuf", "trace_process_mismatch", "trace_limit_before_evidence", "no_recognized_protobuf_fields", "protobuf_prefix_limit", "protobuf_crashed_thread"},
    "native_signal": {"SIGABRT", "SIGSEGV", "SIGBUS", "SIGFPE", "SIGILL", "SIGTRAP", "SIGSYS", "SIGKILL", "SIGQUIT", "SIGTERM", "SIGPIPE", "SIGSTKFLT", "SIGXCPU", "SIGXFSZ"},
    "reasoning_probe_enable_thinking": REASONING_PROBE_NEGATIVES,
    "reasoning_probe_thinking": REASONING_PROBE_NEGATIVES,
})
FLAGS.update({"native_trace_truncated", "search_explicit_mode"})
HASH_FIELDS.update({"native_top_frame_hash", "acceptance_missing_pointer_hash", "acceptance_pointer_receipt_hash"})
COUNTS.update({"search_query_length", "acceptance_verified_claim_count", "acceptance_unmeasured_claim_count"})
REQUIRED_SCHEMA_DETAIL_FIELDS = {"required_schema_count", "required_catalog_schema_tokens", "required_recovery_schema_tokens", "required_github_operation_tokens"}
COUNTS.update(REQUIRED_SCHEMA_DETAIL_FIELDS)
FIELDS_BY_AREA["PROVIDER"].update(REQUIRED_SCHEMA_DETAIL_FIELDS)
FIELDS_BY_AREA["PROVIDER"].update({"reasoning_probe_enable_thinking", "reasoning_probe_thinking", "provider_wait_decision_state"})
FIELDS_BY_AREA["APP"].update({"native_trace_reason", "native_trace_format", "native_trace_frames", "native_trace_truncated", "native_signal", "native_top_frame_hash"})
for area in ("INCIDENT", "PROVIDER", "TOOL"):
    FIELDS_BY_AREA[area].add("dispatch_gateway_selection_change")
FIELDS_BY_AREA["TOOL"].update({"read_handle_kind", "search_query_length", "search_explicit_mode"})
FIELDS_BY_AREA["ACCEPTANCE"].update({"acceptance_verified_claim_count", "acceptance_unmeasured_claim_count", "acceptance_missing_pointer_hash", "acceptance_pointer_receipt_hash"})

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


REQUEST_BYTE_COUNTS = {"request_serialized_bytes", "request_tool_schema_bytes", "request_evidence_bytes", "provider_request_byte_limit"}
FIELDS_BY_AREA["PROVIDER"].update(REQUEST_BYTE_COUNTS | {"provider_request_limit_source", "payload_replan_allowed"})
ENUMS["provider_request_limit_source"] = {"UNREPORTED", "RESPONSE_HEADER"}
ENUMS["provider_failure_code"].add("REQUEST_BYTES_EXCEEDED")
FLAGS.add("payload_replan_allowed")

def _valid_value(key, value):
    if key in REQUEST_BYTE_COUNTS:
        return type(value) is int and 0 <= value <= 2_147_483_647
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
    if key == "native_trace_frames":
        return 0 <= value <= 16
    if key == "composer_generation":
        return 1 <= value <= 16_000_000
    if key == "composer_events_dropped":
        return 0 <= value <= 16_000_000
    if key in COMPOSER_AGE_FIELDS:
        return 0 <= value <= 600_000
    if key in {"composer_focus_transitions", "composer_ime_transitions"}:
        return 0 <= value <= 255
    if key == "voice_playback_focus_attempts":
        return 1 <= value <= 2
    if key == "voice_generation" or key in GITHUB_COOLDOWN_LONGS:
        return 0 <= value <= 9_223_372_036_854_775_807
    if key == "gateway_protocol_version":
        return 1 <= value <= 1000
    if key in {"backend_terminal_status", "gateway_http_status", "gateway_upstream_status", "gateway_reconciliation_status", "branch_protection_http_status", "branch_rules_http_status", "status_policy_protection_http_status", "status_policy_rules_http_status"}:
        return 100 <= value <= 599
    if key == "model_preparation_load_requests":
        return 0 <= value <= 1
    if key in SEARCH_ATTEMPT_FIELDS or key == "reconciliation_tracked_signature_count":
        return 0 <= value <= 1024
    if key == "conflict_pending_tool_count":
        return 0 <= value <= 128
    if key == "conflict_handoff_sequence":
        return 1 <= value <= 16_000_000
    if key in CAPACITY_CEILING_FIELDS:
        return 1 <= value <= 16_000_000
    if key == "reasoning_probe_attempts":
        return 0 <= value <= 4
    if key in ATTEMPTS:
        return 0 <= value <= 10_000
    if key in DURATIONS:
        return 0 <= value <= 86_400_000
    if key == "voice_install_progress_percent":
        return 0 <= value <= 100
    if key in {"voice_install_downloaded_bytes", "voice_install_total_bytes"}:
        return 0 <= value <= 1_000_000_000
    if key == "voice_install_http_status":
        return 100 <= value <= 599
    return key in COUNTS and 0 <= value <= 16_000_000


def _aligned_composer(values):
    slots = [slot for slot in range(1, 9) if f"composer_event_{slot}" in values or f"composer_event_{slot}_age_ms" in values]
    if not slots:
        return True
    if not {"composer_generation", "composer_events_dropped"} <= values.keys() or slots != list(range(1, len(slots) + 1)):
        return False
    if any(f"composer_event_{slot}" not in values or f"composer_event_{slot}_age_ms" not in values for slot in slots):
        return False
    ages = [values[f"composer_event_{slot}_age_ms"] for slot in slots]
    return ages[-1] == 0 and all(older >= newer for older, newer in zip(ages, ages[1:]))


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
    if GATEWAY_CAPABILITY_FIELDS & values.keys():
        kind = values.get("gateway_capability_failure_kind")
        expected_code = "GATEWAY_ISOLATION_UNAVAILABLE" if kind == "ISOLATION_UNVERIFIED" else "GATEWAY_CAPABILITY_UNAVAILABLE"
        if kind not in GATEWAY_CAPABILITY_FAILURE_KINDS or values.get("provider_failure_code") != expected_code:
            raise ValueError("invalid gateway capability evidence scope")
        if "gateway_capability_decode_reason" in values and kind != "DECODE_REJECTED":
            raise ValueError("invalid gateway capability decoder evidence")
    if not _aligned_composer(values):
        raise ValueError("unaligned composer observations")
    if area != "INCIDENT" and values.get("incident_capture_site") == "CHAT_FLOW_EMPTY_ANSWER":
        raise ValueError("invalid chat delivery diagnostic area")
    lengths = {len(values[key]) for key in CRITERION_FIELDS & values.keys()}
    if len(lengths) > 1:
        raise ValueError("unaligned criterion observations")
    if version == 3 and not capsule.aligned(values):
        raise ValueError("unaligned argument shape observations")
    unavailable, omitted = value["unavailableFields"], value.get("omittedFields", [])
    optional_unavailable = ((REASONING_PROBE_FIELDS | capsule.POLICY_RESPONSE_FIELDS | capsule.REPEAT_HISTORY_FIELDS) & allowed) - values.keys()
    if not _string_list(unavailable, core | optional_unavailable) or not core - values.keys() <= set(unavailable) or set(unavailable) & values.keys():
        raise ValueError("invalid diagnostic availability")
    if not _string_list(omitted, allowed) or set(omitted) & values.keys():
        raise ValueError("invalid diagnostic omissions")
    if COMPOSER_SLOT_FIELDS & values.keys() and COMPOSER_SLOT_FIELDS & set(omitted):
        raise ValueError("partially omitted composer observations")
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
        rendered = ("[]" if not item and key in capsule.POLICY_RESPONSE_HEADER_FIELDS | capsule.REPEAT_HISTORY_LIST_FIELDS else ", ".join(item)) if isinstance(item, list) else str(item).lower() if type(item) is bool else str(item)
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
