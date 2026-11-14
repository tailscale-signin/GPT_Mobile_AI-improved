"""Publish approved Android issue reports with durable GitHub write reconciliation."""

from __future__ import annotations

import base64
import hashlib
import json
import re
import sqlite3
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from contextlib import closing, contextmanager
from datetime import datetime, timezone

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from .github_broker import NoRedirect
from .engineering_report_contract import ENGINEERING_FIELDS, ENGINEERING_REASONS, MODEL_PREPARATION_REASONS
from .issue_report_diagnostics import diagnostics_body, validate_diagnostics


REPOSITORY = "tailscale-signin/GPT_Mobile_AI-improved"
REPORT_ID = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")
FEATURES = {"app": "App", "chat": "Chat", "voice": "Voice Chat", "gateway": "Gateway", "local": "Local models", "tools": "Tools"}
ERROR_CODES = {
    "APP_ERROR",
    "CHAT_FAILED",
    "COMBINED_REQUIREMENTS_UNMET",
    "REVIEW_INPUT_REQUIRED",
    "GITHUB_REVIEW_COMPARISON_REQUIRED",
    "PRIVACY_CONFIGURATION",
    "VOICE_MICROPHONE_FOCUS_DENIED",
    "VOICE_MICROPHONE_FOCUS_LOST",
    "VOICE_MICROPHONE_FORMAT_UNSUPPORTED",
    "VOICE_MICROPHONE_UNINITIALIZED",
    "VOICE_MICROPHONE_START_FAILED",
    "VOICE_MICROPHONE_NOT_RECORDING",
    "VOICE_MICROPHONE_DEAD_OBJECT",
    "VOICE_MICROPHONE_BAD_VALUE",
    "VOICE_MICROPHONE_INVALID_OPERATION",
    "VOICE_MICROPHONE_READ_FAILED",
    "VOICE_MICROPHONE_CAPTURE_FAILED",
    "SOURCE_PAYMENT_REQUIRED",
    "SOURCE_ACCESS_BLOCKED",
    "SOURCE_UNAVAILABLE",
    "SOURCE_SERVER_ERROR",
    "SOURCE_HTTP_ERROR",
    "READER_FAILED",
    "NO_VISIBLE_ANSWER",
    "VOICE_SESSION_FAILED",
    "VOICE_PLAYBACK_FAILED",
    "VOICE_NO_SPEECH",
    "VOICE_MICROPHONE_PERMISSION_REQUIRED",
    "VOICE_RECOGNIZER_UNAVAILABLE",
    "VOICE_AUDIO_QUEUE_OVERFLOW",
    "VOICE_RECOGNIZER_FAILED",
    "VOICE_RECOGNIZER_RATE_LIMITED",
    "VOICE_RECOGNIZER_NETWORK_FAILED",
    "VOICE_RECOGNIZER_TIMED_OUT",
    "VOICE_MODEL_REQUIRED",
    "VOICE_RECOGNIZER_LANGUAGE_UNAVAILABLE",
    "VOICE_MICROPHONE_BUSY",
    "VOICE_RECOGNIZER_MEMORY_PRESSURE",
    "LOCAL_PREFILL_TIMEOUT",
    "LOCAL_SERVICE_UNREACHABLE",
    "GATEWAY_UNREACHABLE",
    "MODEL_PREPARATION_INCOMPLETE",
    "MODEL_PREPARATION_REJECTED",
    "MODEL_PREPARATION_STORAGE_UNAVAILABLE",
    "MODEL_PREPARATION_CONNECTION_FAILED",
    "PROVIDER_RATE_LIMITED",
    "JVM_CRASH",
    "NATIVE_CRASH",
    "APP_NOT_RESPONDING",
    "STARTUP_FAILED",
    "TRANSPORT_EOF",
    "TRANSPORT_ERROR",
    "TRANSPORT_UNKNOWN",
    "DNS_RESOLUTION_FAILED",
    "NETWORK_DISCONNECTED",
    "CONNECTION_ABORTED",
    "NETWORK_TIMEOUT",
    "TLS_FAILURE",
    "AUTH_REQUIRED",
    "RATE_LIMITED",
    "READ_DEADLINE",
    "DISPATCH_OUTCOME_UNKNOWN",
    "TIMEOUT_OUTCOME_UNKNOWN",
    "TOOL_RESULT_ID_MISMATCH",
    "GATEWAY_TOOL_OUTCOME_UNKNOWN",
    "REMOTE_OUTCOME_UNKNOWN",
    "PARSING_ERROR",
    "TOOL_FAILURE",
    "OUTPUT_LIMIT",
    "INCOMPLETE_PROVIDER_STREAM",
    "CONNECTION_REFUSED",
    "CONNECTION_TIMEOUT",
    "READ_TIMEOUT",
    "INVALID_ARGUMENTS",
    "TOOL_UNAVAILABLE",
    "TOOL_OUTCOME_UNKNOWN",
    "RECONCILIATION_REQUIRED",
    "DISPATCH_POLICY_CHANGED",
    "POLICY_STALE",
    "GATEWAY_OUTCOME_UNKNOWN",
    "GITHUB_AUTHORIZATION_UNVERIFIED",
    "GITHUB_TASK_INCOMPLETE",
    "GITHUB_TOOL_CONTEXT_BUDGET",
    "GITHUB_EVIDENCE_REQUIRED",
    "GITHUB_CIRCUIT_OPEN",
    "GITHUB_TRANSIENT_NETWORK",
    "GITHUB_TRANSIENT_SERVER",
    "GITHUB_TLS_FAILURE",
    "GITHUB_PRIMARY_RATE_LIMITED",
    "GITHUB_SECONDARY_RATE_LIMITED",
    "GITHUB_SSO_REQUIRED",
    "GITHUB_BRANCH_RULES_BLOCKED",
    "GITHUB_ENDPOINT_PERMISSION_REQUIRED",
    "GITHUB_FORBIDDEN_UNKNOWN",
    "GITHUB_NOT_FOUND_OR_INACCESSIBLE",
    "GITHUB_CONFLICT",
    "GITHUB_VALIDATION_FAILED",
    "GITHUB_HTTP_ERROR",
    "GITHUB_OPERATION_FAILED",
    "GITHUB_LIVE_OBSERVATION_REQUIRED",
    "GITHUB_REF_UNAVAILABLE",
    "GITHUB_REPOSITORY_UNAVAILABLE",
    "GITHUB_PAGINATION_STALLED",
    "GITHUB_PAGINATION_INCOMPLETE",
    "PERMISSION_DENIED",
    "PERMISSION_REVOKED",
    "GRAPH_CONFIGURATION_REQUIRED",
    "REPOSITORY_GRAPH_UNAVAILABLE",
    "REPOSITORY_TARGET_REQUIRED",
    "REPOSITORY_TARGET_UNVERIFIED",
    "FIXTURE_RECONCILE_REQUIRED",
    "APPROVAL_PUBLICATION_UNCONFIRMED",
    "MCP_AUTHENTICATION_REQUIRED",
    "MCP_ENDPOINT_CONFIGURATION",
    "MCP_INITIALIZATION_SCHEMA_INVALID",
    "MCP_INITIALIZATION_TIMEOUT",
    "MCP_DISCOVERY_SCHEMA_INVALID",
    "MCP_DISCOVERY_CONFIGURATION",
    "MCP_PROTOCOL_VERSION_UNSUPPORTED",
    "MCP_PROTOCOL_ERROR",
    "MCP_CATALOG_TIMEOUT",
    "MCP_CONNECTION_FAILED",
    "MCP_DISCOVERY_FAILED",
    "MCP_EXECUTION_FAILED",
    "MCP_LOCAL_CONNECTION_INVALID",
    "MCP_LOCAL_ENDPOINT_INVALID",
    "MCP_LOCAL_AUTH_TYPE_INVALID",
    "MCP_LOCAL_CREDENTIAL_MISSING",
    "MCP_LOCAL_CREDENTIAL_INVALID",
    "MCP_LOCAL_CREDENTIAL_REFRESH_REQUIRED",
    "REQUIRED_TOOL_ACQUISITION_FAILED",
    "REQUIRED_TOOL_ACQUISITION_PENDING",
    "REQUIRED_TOOL_ACQUISITION_TIMED_OUT",
    "REQUIRED_TOOL_AMBIGUOUS_CONFIGURATION",
    "REQUIRED_TOOL_AUTHORIZATION_REQUIRED",
    "REQUIRED_TOOL_CAPABILITY_UNAVAILABLE",
    "SELECTED_MODEL_NOT_LOADED",
    "SELECTED_MODEL_LOADING",
    "SELECTED_MODEL_NOT_AVAILABLE",
    "REASONING_OFF_UNSUPPORTED",
    "REASONING_ON_UNSUPPORTED",
    "REASONING_CAPABILITY_UNVERIFIED",
    "REASONING_CAPABILITY_UNAVAILABLE",
    "REASONING_CAPABILITY_TIMEOUT",
    "SAVED_VIEW_UNAVAILABLE",
    "COMPLETION_REPAIR_BUDGET_EXHAUSTED",
    "GATEWAY_REMOTE_SURFACE_NOT_ADMITTED",
    "GATEWAY_MISSING_SCOPE",
    "GATEWAY_TRANSPORT_IDENTITY_REQUIRED",
    "GATEWAY_IDENTITY_MISMATCH",
    "GATEWAY_UNSUPPORTED_CONTRACT",
    "GATEWAY_COMMAND_RECEIPT_UNAVAILABLE",
    "GATEWAY_INVALID_RESPONSE",
    "GATEWAY_INVALID_CONNECTION",
    "GATEWAY_STORAGE_UNAVAILABLE",
    "GATEWAY_CURSOR_EXPIRED",
    "GATEWAY_TRANSPORT_INTERRUPTED",
    "GATEWAY_SOURCE_MISMATCH",
    "GATEWAY_HTTP_REJECTED",
    "GATEWAY_ENGINEERING_STORAGE_DISABLED",
    "GATEWAY_ENGINEERING_STORAGE_UNAVAILABLE",
    "GATEWAY_ENGINEERING_GITHUB_REQUIRED",
    "GATEWAY_ENGINEERING_DRAINING",
    "GATEWAY_ENGINEERING_WORKER_UNAVAILABLE",
    "GATEWAY_ENGINEERING_UNAVAILABLE",
    "GATEWAY_CAPABILITY_UNAVAILABLE",
    "GATEWAY_ISOLATION_UNAVAILABLE",
    "ACCESS_FORBIDDEN",
    "AUTHENTICATION_FAILED",
    "BACKEND_TEMPLATE_INVALID",
    "BUDGET_RESTRICTED",
    "CIRCUIT_BREAKER_OPEN",
    "CLIENT_TOOL_RESULTS_REQUIRED",
    "CLIENT_TOOL_RESULT_IDENTITY_MISMATCH",
    "COMPLETION_REPAIR_BUDGET_UNAVAILABLE",
    "COMPLETION_REPAIR_USAGE_UNAVAILABLE",
    "CONTEXT_BUDGET_EXCEEDED",
    "CAPACITY_UNVERIFIED",
    "INPUT_CAPACITY_EXCEEDED",
    "OUTPUT_CAPACITY_EXCEEDED",
    "CONTEXT_CAPACITY_EXCEEDED",
    "CONTEXT_WINDOW_EXCEEDED",
    "CONVERSATION_CONFLICT",
    "EMPTY_COMPLETION",
    "ENGINEERING_STATE_OR_INPUT_CHANGED",
    "EXECUTION_SELECTION_INVALID",
    "GATEWAY_JOB_FAILED",
    "GATEWAY_JOB_IN_PROGRESS",
    "GATEWAY_JOB_UNAVAILABLE",
    "GATEWAY_NEW_STAGE_BOUNDARY_REQUIRED",
    "INVALID_OUTPUT_CAP",
    "INVALID_TOOL_ARGUMENTS",
    "INVALID_TOOL_HISTORY",
    "INVALID_TOOL_OWNERSHIP",
    "INVALID_V14_REQUEST",
    "LOCAL_INITIALIZATION_FAILED",
    "MEMORY_STALE",
    "MISSING_REQUIRED_TOOL_CALL",
    "MIXED_TOOL_BATCH",
    "OUTPUT_PROTOCOL_INVALID",
    "PERMISSION_REQUIRED",
    "PROVIDER_PAYLOAD_ERROR",
    "PROVIDER_PRIVACY_RESTRICTED",
    "RATE_LIMIT_EXCEEDED",
    "REASONING_BUDGET_EXHAUSTED",
    "REQUEST_REJECTED",
    "RESOURCE_DEADLINE",
    "SAVED_POLICY_UNAVAILABLE",
    "SERIALIZATION_ERROR",
    "SERVICE_UNAVAILABLE",
    "TERMINAL_JOB_NOT_RESUMABLE",
    "TOOL_CHOICE_MISMATCH",
    "UNAUTHORIZED_TOOL_CALL",
    "UNSUPPORTED_INSTRUCTION_CONTENT",
}
SAFE_VERSION = re.compile(r"[a-zA-Z0-9._+-]{1,48}")
SAFE_DEVICE = re.compile(r"[a-zA-Z0-9 ._()+-]{1,80}")
SAFE_FRAME = re.compile(r"(?:dev\.(?:chungjungsoo|melo)\.gptmobile|android|com\.android|java|kotlin)\.[\w.$<>]{1,145}:[0-9]{1,6}", re.ASCII)
SAFE_EVENT = re.compile(r"-[0-9]{1,5}ms [DIWEF] (?:app|chat|voice|gateway|local|tools)(?: [A-Z][A-Z0-9_]{0,63})?")
SAFE_TYPE = re.compile(r"[A-Za-z][A-Za-z0-9_$]{0,63}")
SECRET = re.compile(
    r"(?i)\b(?:bearer\s+[^\s,;]+|(?:gh[pousr]_|github_pat_|sk-|hf_|xox[baprs]-)[a-z0-9_-]{8,}"
    r"|(?:authorization|api[_ -]?key|password|(?:access|refresh)[_ -]?token|client[_ -]?secret)\s*[:=]\s*[^\s,;]+)"
)
RETRY_LEASE = 120
ADMISSION_WINDOW = 600
CONTEXT_ENUMS = {
    "source": {"CHAT_FAILURE", "STRUCTURED_EVENT", "TOOL_RESULT", "VOICE_STATE", "EXCEPTION", "PROCESS_EXIT", "CODE_ONLY", "MANUAL_REPORT"},
    "stage": {"admission", "planning", "repository_admission", "provider_admission", "request_validation", "message_admission",
              "conversation_admission", "backend_admission", "binding_admission", "execution_admission", "connection_preflight",
              "dns_resolution", "dns", "connection", "response_stream", "tls", "cancelled", "transport", "provider_generation",
              "model_invocation", "provider_wait", "tool_precheck", "tool_readiness", "tool_admission", "task_budget", "inventory_pagination",
              "tool_dispatch", "tool_execution", "tool_continuation", "tool_reconciliation", "backend_template", "gateway_recovery",
              "gateway_dispatch", "review_compatibility", "answer_review", "engineering_guard", "claim_extraction", "claim_verification", "verification",
              "final_synthesis", "delivery", "lifecycle", "voice_capture", "audio_focus", "capture_configuration", "capture_initialization", "capture_start", "capture_read", "retrieval", "precheck", "redirect_precheck", "voice_recognition", "voice_playback"},
    "provider": {"OPENAI", "ANTHROPIC", "GOOGLE", "GROQ", "OLLAMA", "OPENROUTER", "LLAMA", "GATEWAY", "LOCAL", "CUSTOM", "LITERT_LM", "FREE", "NVIDIA"},
    "operation": {"github_context", "github_read", "github_search", "github_pr", "github_issue", "github_ci", "github_commit",
                  "github_review", "github_engineering", "repository_graph", "mcp", "web_search", "read_url", "voice_capture",
                  "voice_recognition", "voice_playback"},
    "remoteAcceptance": {"YES", "NO", "UNKNOWN"},
    "jobState": {"QUEUED", "RUNNING", "WAITING_FOR_CLIENT_TOOL", "WAITING_FOR_APPROVAL", "PAUSED", "CANCELLING", "CANCELLED", "COMPLETED", "FAILED"},
    "recoveryAction": {"OPEN_ACTIVE_JOB", "RETURN_CLIENT_TOOL_RESULTS", "WAIT_FOR_ACTIVE_JOB", "QUEUE_INSTRUCTION", "RESOLVE_ACTIVE_JOB",
                       "RECONCILE_ACTIVE_GATEWAY_JOB", "REFRESH_REPOSITORY_ACCESS", "CHANGE_REPOSITORY", "SELECT_REF", "RECONCILE_OUTCOME",
                       "REVIEW_AUTHORIZATION", "RETRY_AFTER_BACKOFF", "RETRY_AFTER_CONFIGURATION_CHANGE", "MICROPHONE_PERMISSION",
                       "RETRY_RECOGNITION", "RETRY_PLAYBACK", "SPEECH_SERVICE", "SPEECH_LANGUAGE", "LOCAL_MODEL"},
    "reasonCode": {"SELECTION_NOT_OBJECT", "SELECTION_SCHEMA_INVALID", "SELECTION_IDENTITY_INVALID", "SELECTION_CREDENTIAL_REFERENCE_INVALID"},
}
CONTEXT_ACCEPTANCE_REASONS = {
    "UNVERIFIED_ACCEPTANCE_CRITERIA", "ACCEPTED_TASK_DEADLINE_REACHED", "ACCEPTANCE_EVIDENCE_MISSING",
    "TOOL_BUDGET_EXHAUSTED", "VERIFICATION_RESERVE_REACHED", "FIXTURE_CLEANUP_UNVERIFIED",
    "AUTHORIZED_CATALOG_INCOMPLETE", "RECORDED_OPERATION_BLOCKERS", "BOUNDED_INVENTORY_SAMPLE_REACHED",
    "INVENTORY_CONTINUATION_BLOCKED", "CAPABILITY_PREREQUISITES_MISSING", "PRIMARY_EVIDENCE_INCOMPLETE",
    "SAVED_VIEW_PERSISTENCE_UNAVAILABLE", "RECONCILE_REMOTE_OUTCOME", "RECORDED_EXECUTION_FAILURE",
    "WAITING_FOR_APPROVAL", "APPROVAL_REJECTED", "GITHUB_EVIDENCE_INCOMPLETE", "STATUS_READ_PENDING", "STATUS_OBSERVATION_UNAVAILABLE", "STATUS_OBSERVATIONS_COMPLETE",
}
CONTEXT_ACCEPTANCE_RECOVERY = {
    "RESUME_UNFINISHED_WORK", "RESUME_VERIFICATION", "RECONCILE_REMOTE_OUTCOME", "RESUME_UNVERIFIED_REQUIREMENTS",
    "RECONCILE_CATALOG_CONTRACTS", "RESUME_ISSUED_CATALOG_CONTINUATION", "RECONCILE_CATALOG_SNAPSHOT",
    "RESOLVE_BOUND_APPROVAL", "RECHECK_OWNED_FIXTURE_PREFLIGHT", "RECONCILE_OWNED_FIXTURE",
    "REVIEW_REMAINING_SCOPE_AND_BUDGET", "RESOLVE_CAPABILITY_PREREQUISITE", "NEW_TASK_REQUIRED",
    "RESTORE_AUTHORIZED_CONFIGURATION", "INSPECT_EXECUTION_FAILURE", "INSPECT_EXISTING_WORKFLOW_AT_RECORDED_REVISION",
}
CONTEXT_REVIEW_STAGES = {"review_comparison", "approval"}
CONTEXT_REVIEW_RECOVERY = {"SELECT_REVIEW_BASELINE", "RESTORE_REVIEW_COMPARISON_EVIDENCE"}
CONTEXT_ENUMS_V2 = {
    **CONTEXT_ENUMS,
    "stage": CONTEXT_ENUMS["stage"] | {"acceptance_verification"},
    "reasonCode": CONTEXT_ENUMS["reasonCode"] | CONTEXT_ACCEPTANCE_REASONS,
    "recoveryAction": CONTEXT_ENUMS["recoveryAction"] | CONTEXT_ACCEPTANCE_RECOVERY,
}
CONTEXT_ENUMS_V3 = {
    **CONTEXT_ENUMS_V2,
    "stage": CONTEXT_ENUMS_V2["stage"] | CONTEXT_REVIEW_STAGES,
    "recoveryAction": CONTEXT_ENUMS_V2["recoveryAction"] | CONTEXT_REVIEW_RECOVERY,
}
CONTEXT_RECOVERY_REASONS = {
    "PROVIDER_WAIT_CHECKPOINT_CHANGED", "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", "PROVIDER_WAIT_OUTCOME_UNKNOWN",
    "PROVIDER_WAIT_TASK_CHANGED", "PROVIDER_WAIT_CHECKPOINT_INVALID", "PROVIDER_WAIT_INPUT_CHANGED",
    "PROVIDER_WAIT_PREFIX_CHANGED", "PROVIDER_WAIT_REVIEW_REQUIRED", "RESEARCH_RECOVERY_ACTION_CHANGED",
    "GITHUB_EXTRACTION_OUTPUT_LIMIT", "GITHUB_EXTRACTION_INVALID_RESPONSE", "GITHUB_EXTRACTION_TIMEOUT",
    "GITHUB_EXTRACTION_UNAVAILABLE", "GITHUB_EXTRACTION_FAILED",
}
CONTEXT_RECOVERY_ACTIONS = {
    "REVIEW_SAVED_RECOVERY", "RECONCILE_TOOL_RECEIPTS", "RESUME_THIS_BATCH_WITH_A_SMALLER_CLAIM_LIMIT",
    "RESUME_THIS_BATCH_WITH_STRICT_SCHEMA", "RESUME_UNFINISHED_EXTRACTION_BATCHES",
    "INSPECT_THE_RECORDED_PROVIDER_FAILURE", "RETRY_CLAIM_EXTRACTION_FROM_SAVED_RECEIPTS",
}
CONTEXT_TOOL_PRECHECK_REASONS = {
    "BUDGET_EXHAUSTED", "TOOL_BATCH_OVERSIZE", "DUPLICATE_INVOCATION", "REPEATED_FAILURE", "BUDGET_CHECKPOINT_UNAVAILABLE",
    "UNSUPPORTED_TOOL_CONTRACT", "UNSUPPORTED_OPERATION", "UNKNOWN_TOOL", "SAVED_VIEW_WRONG_OPERATION", "SAVED_VIEW_CURSOR_ALTERED",
}
CONTEXT_ENUMS_V4 = {
    **CONTEXT_ENUMS_V3,
    "reasonCode": CONTEXT_ENUMS_V3["reasonCode"] | CONTEXT_RECOVERY_REASONS | CONTEXT_TOOL_PRECHECK_REASONS,
    "recoveryAction": CONTEXT_ENUMS_V3["recoveryAction"] | CONTEXT_RECOVERY_ACTIONS,
    "originalFailureCode": ERROR_CODES,
}
CONTEXT_ENUMS_V6 = {**CONTEXT_ENUMS_V4, "reasonCode": CONTEXT_ENUMS_V4["reasonCode"] | ENGINEERING_REASONS | MODEL_PREPARATION_REASONS, "stage": CONTEXT_ENUMS_V4["stage"] | {"model_preparation"}}
CONTEXT_ENUMS_V7 = {**CONTEXT_ENUMS_V6, "stage": CONTEXT_ENUMS_V6["stage"] | {"voice_reply", "voice_response_wait"}}
CONTEXT_NUMBERS = {"occurredAtMillis": (1, 9_999_999_999_999), "httpStatus": (100, 599), "retryAfterMillis": (0, 86_400_000)}
CONTEXT_ACCEPTANCE_NUMBERS = {"requiredCriteria": (0, 100_000), "verifiedCriteria": (0, 100_000), "remainingCriteria": (0, 100_000)}
CONTEXT_RECOVERY_NUMBERS = {"originalHttpStatus": (100, 599), "ownershipFence": (0, 9_223_372_036_854_775_807)}
CONTEXT_FLAGS = {"dispatched", "retryable", "resumeAllowed", "requiresReconciliation"}
CONTEXT_HASHES = {"runIdHash", "invocationIdHash", "callIdHash", "jobIdHash", "activeJobIdHash", "exceptionFingerprint"}
CONTEXT_RECOVERY_HASHES = {"checkpointBindingHash", "checkpointDigestHash", "resumeClaimIdHash"}
CONTEXT_SELECTION_FIELDS = {"profile_uid", "profile_revision", "provider_connection_id", "provider_connection_revision", "provider_kind",
                            "configured_endpoint", "requested_model_id", "generation_parameters", "privacy_scope", "tool_owner",
                            "required_capability_policy", "credential_reference", "credential_account_identity", "approved_alias_mapping"}
CONTEXT_FIELDS = (set(CONTEXT_ENUMS) | set(CONTEXT_NUMBERS) | CONTEXT_FLAGS | CONTEXT_HASHES | {"missingFields", "invalidFields"}) - {"source", "occurredAtMillis"}
CONTEXT_FIELDS_V2 = CONTEXT_FIELDS | set(CONTEXT_ACCEPTANCE_NUMBERS)
CONTEXT_FIELDS_V4 = CONTEXT_FIELDS_V2 | set(CONTEXT_RECOVERY_NUMBERS) | CONTEXT_RECOVERY_HASHES | {"originalFailureCode"}
CONTEXT_FIELDS_V5 = CONTEXT_FIELDS_V4 | {"diagnostics"}
CONTEXT_VERSION_FIELDS = {1: CONTEXT_FIELDS, 2: CONTEXT_FIELDS_V2, 3: CONTEXT_FIELDS_V2, 4: CONTEXT_FIELDS_V4, 5: CONTEXT_FIELDS_V5, 6: CONTEXT_FIELDS_V5, 7: CONTEXT_FIELDS_V5}
CONTEXT_LABELS = {
    "stage": "Failure stage", "provider": "Provider family", "operation": "Operation family", "httpStatus": "HTTP status",
    "retryAfterMillis": "Retry delay (ms)", "dispatched": "Dispatched", "retryable": "Retryable", "resumeAllowed": "Resume allowed",
    "requiresReconciliation": "Requires reconciliation", "remoteAcceptance": "Remote acceptance", "jobState": "Reported job state",
    "recoveryAction": "Recovery action", "reasonCode": "Reason code", "missingFields": "Missing selection fields",
    "invalidFields": "Invalid selection fields", "runIdHash": "Run identity SHA-256", "invocationIdHash": "Invocation identity SHA-256",
    "callIdHash": "Call identity SHA-256", "jobIdHash": "Reported job identity SHA-256", "activeJobIdHash": "Active job identity SHA-256",
    "exceptionFingerprint": "Exception type/frame fingerprint SHA-256",
    "requiredCriteria": "Required acceptance criteria", "verifiedCriteria": "Verified acceptance criteria",
    "remainingCriteria": "Remaining acceptance criteria",
    "originalFailureCode": "Original interruption code", "originalHttpStatus": "Original interruption HTTP status",
    "checkpointBindingHash": "Checkpoint binding SHA-256", "checkpointDigestHash": "Checkpoint digest SHA-256",
    "resumeClaimIdHash": "Resume claim SHA-256", "ownershipFence": "Recovery ownership fence",
}


class ReportError(Exception):
    def __init__(self, status, code, retry_after=0):
        self.status, self.code, self.retry_after = status, code, retry_after
        super().__init__(code)


def _field(values, key, rule, default=None):
    value = values.get(key, default)
    if not isinstance(value, str) or not rule.fullmatch(value):
        raise ReportError(422, "INVALID_REPORT")
    return value


def _text(values, key, limit):
    value = values.get(key)
    if not isinstance(value, str) or not 0 < len(value.strip()) <= limit:
        raise ReportError(422, "INVALID_REPORT")
    value = "".join(c for c in value if c in "\n\t" or c.isprintable()).strip()
    if not value:
        raise ReportError(422, "INVALID_REPORT")
    return SECRET.sub("[redacted]", value)


def _lines(values, key, limit, valid):
    value = values.get(key, [])
    if not isinstance(value, list) or len(value) > limit or any(not isinstance(line, str) or not valid(line) for line in value):
        raise ReportError(422, "INVALID_REPORT")
    return value


def marker(report_id):
    return f"<!-- gpt-mobile-report:{report_id} -->"


def _report_context(value):
    required = {"schemaVersion", "occurredAtMillis", "source", "unavailableFields"}
    if not isinstance(value, dict) or not required <= value.keys():
        raise ReportError(422, "INVALID_REPORT")
    version = value["schemaVersion"]
    if type(version) is not int or version not in CONTEXT_VERSION_FIELDS:
        raise ReportError(422, "INVALID_REPORT")
    fields = CONTEXT_VERSION_FIELDS[version]
    if set(value) - required - fields:
        raise ReportError(422, "INVALID_REPORT")
    # A released client used v2 for review extensions. Accept those exact approved
    # payloads as well as v3; clients require readiness for both 2 and 3 before sending them.
    enums = CONTEXT_ENUMS if version == 1 else CONTEXT_ENUMS_V7 if version >= 7 else CONTEXT_ENUMS_V6 if version >= 6 else CONTEXT_ENUMS_V4 if version in (4, 5) else CONTEXT_ENUMS_V3
    for key, choices in enums.items():
        if key in value and (not isinstance(value[key], str) or value[key] not in choices):
            raise ReportError(422, "INVALID_REPORT")
    if value.get("reasonCode") in MODEL_PREPARATION_REASONS and value.get("stage") != "model_preparation":
        raise ReportError(422, "INVALID_REPORT")
    if value.get("reasonCode") in CONTEXT_TOOL_PRECHECK_REASONS and not (
        version in (4, 5, 6, 7) and value.get("source") == "TOOL_RESULT" and value.get("stage") == "tool_precheck" and value.get("dispatched") is False and value.get("requiresReconciliation") is not True
    ):
        raise ReportError(422, "INVALID_REPORT")
    numbers = CONTEXT_NUMBERS if version == 1 else {**CONTEXT_NUMBERS, **CONTEXT_ACCEPTANCE_NUMBERS}
    if version in (4, 5, 6, 7):
        numbers = {**numbers, **CONTEXT_RECOVERY_NUMBERS}
    for key, (low, high) in numbers.items():
        if key in value and (type(value[key]) is not int or not low <= value[key] <= high):
            raise ReportError(422, "INVALID_REPORT")
    if version in (2, 3, 4, 5, 6, 7):
        required_count = value.get("requiredCriteria")
        verified_count = value.get("verifiedCriteria")
        remaining_count = value.get("remainingCriteria")
        if required_count is not None and (
            verified_count is not None and verified_count > required_count
            or remaining_count is not None and remaining_count > required_count
            or verified_count is not None and remaining_count is not None and verified_count + remaining_count != required_count
        ):
            raise ReportError(422, "INVALID_REPORT")
    for key in CONTEXT_FLAGS:
        if key in value and type(value[key]) is not bool:
            raise ReportError(422, "INVALID_REPORT")
    for key in CONTEXT_HASHES | (CONTEXT_RECOVERY_HASHES if version in (4, 5, 6, 7) else set()):
        if key in value and (not isinstance(value[key], str) or not re.fullmatch(r"[a-f0-9]{64}", value[key])):
            raise ReportError(422, "INVALID_REPORT")
    for key in ("missingFields", "invalidFields"):
        if key in value:
            allowed_names = CONTEXT_SELECTION_FIELDS | (ENGINEERING_FIELDS if version >= 6 and value.get("reasonCode") in ENGINEERING_REASONS else set())
            names = _lines(value, key, len(allowed_names), lambda name: name in allowed_names)
            if len(names) != len(set(names)):
                raise ReportError(422, "INVALID_REPORT")
    unavailable = _lines(value, "unavailableFields", len(fields), lambda name: name in fields)
    # Omitted observations remain explicit. An absent dispatch fact cannot be
    # presented as false, and an unknown field cannot carry arbitrary metadata.
    if len(unavailable) != len(set(unavailable)) or set(unavailable) != fields - value.keys():
        raise ReportError(422, "INVALID_REPORT")
    if version in (5, 7) or version == 6 and "diagnostics" in value:
        try:
            diagnostic = validate_diagnostics(value.get("diagnostics"))
            if version == 5 and diagnostic["schemaVersion"] != 1:
                raise ValueError("diagnostic extension requires context version six")
            if version == 6 and diagnostic["schemaVersion"] == 3 or version == 7 and diagnostic["schemaVersion"] != 3:
                raise ValueError("incident capsule requires context version seven")
        except ValueError as error:
            raise ReportError(422, "INVALID_REPORT") from error
    return value


def _context_body(context):
    occurred = datetime.fromtimestamp(context["occurredAtMillis"] / 1000, timezone.utc).isoformat(timespec="milliseconds")
    rows = [("Context schema", str(context["schemaVersion"])), ("Client occurrence time", occurred), ("Capture source", context["source"])]
    for key, label in CONTEXT_LABELS.items():
        if key not in context:
            continue
        value = context[key]
        if isinstance(value, list):
            rendered = ", ".join(sorted(value)) if value else "[]"
        elif type(value) is bool:
            rendered = "true" if value else "false"
        else:
            rendered = str(value)
        if context["schemaVersion"] >= 6 and key in {"missingFields", "invalidFields"}:
            label = "Missing request fields" if key == "missingFields" else "Invalid request fields"
        rows.append((label, rendered))
    body = "\n\n### Diagnostic context\n\n| Field | Recorded value |\n| --- | --- |\n"
    body += "\n".join(f"| {label} | `{value}` |" for label, value in rows)
    unavailable = sorted(context["unavailableFields"])
    if unavailable:
        body += "\n\nUnavailable fields: " + ", ".join(f"`{name}`" for name in unavailable) + "."
        body += " These observations were not retained; absence does not establish success, non-dispatch, or permission to retry."
    else:
        body += "\n\nUnavailable fields: none declared by this capture."
    if context["schemaVersion"] in (5, 6, 7) and "diagnostics" in context:
        body += diagnostics_body(context["diagnostics"])
    return body


def issue_from_report(values):
    allowed = {"reportId", "source", "title", "description", "version", "build", "commit", "android", "sdk", "device",
               "feature", "errorCode", "errorType", "frames", "events", "context"}
    if not isinstance(values, dict) or set(values) - allowed:
        raise ReportError(422, "INVALID_REPORT")
    report_id = _field(values, "reportId", REPORT_ID)
    version = _field(values, "version", SAFE_VERSION)
    commit = _field(values, "commit", re.compile(r"(?:[a-f0-9]{7,40}|unknown)"))
    android = _field(values, "android", SAFE_VERSION)
    device = _field(values, "device", SAFE_DEVICE)
    build, sdk = values.get("build"), values.get("sdk")
    if type(build) is not int or not 0 < build <= 2_147_483_647 or type(sdk) is not int or not 1 <= sdk <= 999:
        raise ReportError(422, "INVALID_REPORT")
    events = _lines(values, "events", 16, SAFE_EVENT.fullmatch)
    if any(len(event.split()) == 4 and event.split()[3] not in ERROR_CODES for event in events):
        raise ReportError(422, "INVALID_REPORT")
    context = _report_context(values["context"]) if "context" in values else None
    source = values.get("source")
    if source == "manual" and not ({"feature", "errorCode", "errorType", "frames"} & values.keys()):
        title = " ".join(_text(values, "title", 120).split()).replace("@", "＠")
        description = _text(values, "description", 1200)
        # Indented code keeps links, HTML, mentions and hidden report markers in user text inert.
        body = "### What happened\n\n" + "\n".join("    " + line for line in description.split("\n"))
    elif source == "debug" and not ({"title", "description"} & values.keys()):
        feature, code = values.get("feature"), values.get("errorCode")
        if not isinstance(feature, str) or feature not in FEATURES or not isinstance(code, str) or code not in ERROR_CODES:
            raise ReportError(422, "INVALID_REPORT")
        title = f"[Debug] {FEATURES[feature]} · {code}"
        body = f"### Error\n\n{FEATURES[feature]} · `{code}`"
        if "errorType" in values:
            body += "\n\nException type · `" + _field(values, "errorType", SAFE_TYPE) + "`"
        frames = _lines(values, "frames", 8, SAFE_FRAME.fullmatch)
        if frames:
            body += "\n\n### Stack frames\n\n" + "\n".join("    " + frame for frame in frames)
    else:
        raise ReportError(422, "INVALID_REPORT")
    if context is not None:
        body += _context_body(context)
    body += f"\n\n### App and device\n\n- App {version} (build {build}, commit `{commit}`)\n- Android {android} (API {sdk}) · {device}"
    if events:
        body += "\n\n### Recent app events\n\n" + "\n".join("    " + event for event in events)
    return report_id, {"title": title, "body": f"{body}\n\n{marker(report_id)}"}


class GitHubRequestError(Exception):
    def __init__(self, status=0, retry_after=0):
        self.status, self.retry_after = status, retry_after


class GitHubIssuePublisher:
    def __init__(self, app_id, installation_id, app_slug, private_key_file, clock=time.time):
        if not (app_id.isdecimal() and installation_id.isdecimal() and re.fullmatch(r"[a-z0-9-]{1,100}", app_slug)):
            raise ValueError("Configure the reporting App ID, installation ID and slug")
        self.app_id = app_id
        self.installation_id = installation_id
        self.bot_login = app_slug + "[bot]"
        self.key = serialization.load_pem_private_key(private_key_file.read_bytes(), password=None)
        if not isinstance(self.key, rsa.RSAPrivateKey):
            raise ValueError("The reporting GitHub App requires an RSA private key")
        self.clock = clock
        self.opener = urllib.request.build_opener(NoRedirect)
        self.token_lock = threading.Lock()
        self.token = ""
        self.token_expiry = 0

    def _request(self, path, token, payload=None):
        request = urllib.request.Request(
            "https://api.github.com" + path,
            data=json.dumps(payload).encode() if payload is not None else None,
            headers={"Authorization": f"Bearer {token}", "Accept": "application/vnd.github+json",
                     "Content-Type": "application/json", "User-Agent": "GPT-Mobile-Issue-Reporter/1",
                     "X-GitHub-Api-Version": "2022-11-28"},
        )
        try:
            with self.opener.open(request, timeout=15) as response:
                data = response.read(2_000_001)
                if len(data) > 2_000_000:
                    raise GitHubRequestError()
                return json.loads(data)
        except urllib.error.HTTPError as error:
            with closing(error):
                retry = error.headers.get("Retry-After", "")
                reset = error.headers.get("X-RateLimit-Reset", "")
                seconds = int(retry) if retry.isdecimal() and len(retry) <= 8 else 0
                if error.headers.get("X-RateLimit-Remaining") == "0" and reset.isdecimal() and len(reset) <= 12:
                    seconds = max(seconds, int(reset) - int(self.clock()), 60)
                raise GitHubRequestError(error.code, seconds) from None
        except (OSError, ValueError, UnicodeError):
            raise GitHubRequestError() from None

    def _installation_token(self):
        def encoded(value):
            return base64.urlsafe_b64encode(value).rstrip(b"=")

        with self.token_lock:
            now = int(self.clock())
            if self.token_expiry > now + 60:
                return self.token
            claims = json.dumps({"iat": now - 60, "exp": now + 540, "iss": self.app_id}).encode()
            data = encoded(b'{"alg":"RS256","typ":"JWT"}') + b"." + encoded(claims)
            signature = self.key.sign(data, padding.PKCS1v15(), hashes.SHA256())
            jwt = (data + b"." + encoded(signature)).decode("ascii")
            try:
                result = self._request(
                    f"/app/installations/{self.installation_id}/access_tokens", jwt,
                    {"repositories": [REPOSITORY.split("/")[1]], "permissions": {"issues": "write"}},
                )
                expiry = datetime.fromisoformat(result["expires_at"].replace("Z", "+00:00")).timestamp()
                token = result["token"]
                if not isinstance(token, str) or not re.fullmatch(r"[!-~]{1,4096}", token) or expiry <= now + 60:
                    raise ValueError()
            except GitHubRequestError as error:
                # The request never reached issue creation.
                raise ReportError(503, "GITHUB_UNAVAILABLE", max(60, error.retry_after)) from None
            except (KeyError, TypeError, ValueError, AttributeError):
                raise ReportError(503, "GITHUB_UNAVAILABLE", 60) from None
            self.token, self.token_expiry = token, expiry
            return token

    def create(self, issue):
        token = self._installation_token()
        try:
            result = self._request(f"/repos/{REPOSITORY}/issues", token, issue)
        except GitHubRequestError as error:
            if error.status == 401:
                with self.token_lock:
                    if self.token == token:
                        self.token_expiry = 0
            if error.status in (401, 403, 404, 410, 429):
                raise ReportError(503, "GITHUB_UNAVAILABLE", max(60, error.retry_after)) from None
            if error.status == 422:
                raise ReportError(422, "GITHUB_REJECTED") from None
            raise
        number = result.get("number") if isinstance(result, dict) else None
        if type(number) is not int or not 0 < number <= 2_147_483_647:
            raise GitHubRequestError()
        return number

    def find(self, report_id, created):
        since = datetime.fromtimestamp(created - 300, timezone.utc).isoformat()
        token = self._installation_token()
        for page in range(1, 4):
            query = urllib.parse.urlencode({"state": "all", "sort": "created", "direction": "desc", "since": since, "per_page": 100, "page": page})
            results = self._request(f"/repos/{REPOSITORY}/issues?{query}", token)
            if not isinstance(results, list):
                raise GitHubRequestError()
            for issue in results:
                if not isinstance(issue, dict) or "pull_request" in issue:
                    continue
                user = issue.get("user")
                if not isinstance(user, dict) or user.get("login") != self.bot_login:
                    continue
                number, body = issue.get("number"), issue.get("body")
                if type(number) is int and 0 < number <= 2_147_483_647 and isinstance(body, str) and body.rstrip().endswith("\n" + marker(report_id)):
                    return number
            if len(results) < 100:
                break
        return None


class IssueReporter:
    def __init__(self, path, publisher, clock=time.time):
        self.path, self.publisher, self.clock = path, publisher, clock
        path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        with self._db() as db:
            db.execute("CREATE TABLE IF NOT EXISTS reports (id TEXT PRIMARY KEY, digest TEXT, phase TEXT, created INTEGER, retry_at INTEGER, number INTEGER)")
            db.execute("CREATE TABLE IF NOT EXISTS report_admission (client TEXT, created INTEGER)")
            db.execute("CREATE TABLE IF NOT EXISTS github_cooldown (id INTEGER PRIMARY KEY, retry_at INTEGER)")
        path.chmod(0o600)

    @contextmanager
    def _db(self):
        with closing(sqlite3.connect(self.path, timeout=5)) as db, db:
            yield db

    def submit(self, values, client):
        report_id, issue = issue_from_report(values)
        digest = hashlib.sha256(json.dumps(issue, sort_keys=True).encode()).hexdigest()
        now = int(self.clock())
        with self._db() as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT digest, phase, created, retry_at, number FROM reports WHERE id = ?", (report_id,)).fetchone()
            if row is None:
                db.execute("DELETE FROM report_admission WHERE created <= ?", (now - ADMISSION_WINDOW,))
                count, caller = db.execute("SELECT COUNT(*), COUNT(CASE WHEN client = ? THEN 1 END) FROM report_admission", (client,)).fetchone()
                if count >= 30 or caller >= 4:
                    raise ReportError(429, "RATE_LIMITED", ADMISSION_WINDOW)
                db.execute("INSERT INTO report_admission VALUES (?, ?)", (client, now))
                db.execute("INSERT INTO reports VALUES (?, ?, 'ready', ?, 0, NULL)", (report_id, digest, now))
                row = (digest, "ready", now, 0, None)
            original, phase, created, retry_at, number = row
            if original != digest:
                raise ReportError(409, "REPORT_ID_CONFLICT")
            if phase == "done":
                return number
            if phase == "rejected":
                raise ReportError(422, "GITHUB_REJECTED")
            upstream = db.execute("SELECT retry_at FROM github_cooldown WHERE id = 1").fetchone()
            wait = max(retry_at, upstream[0] if upstream else 0) - now
            if wait > 0:
                raise ReportError(503, "RETRY_LATER", wait)
            # Commit the dispatch lease before making a network call. Expired or
            # ambiguous leases can only reconcile the original write.
            lease = now + RETRY_LEASE
            db.execute("UPDATE reports SET phase = 'dispatching', retry_at = ? WHERE id = ?", (lease, report_id))

        try:
            number = self.publisher.create(issue) if phase == "ready" else self.publisher.find(report_id, created)
        except ReportError as error:
            retry = int(self.clock()) + error.retry_after
            with self._db() as db:
                next_phase = phase if error.status != 422 else "rejected"
                db.execute("UPDATE reports SET phase = ?, retry_at = ? WHERE id = ? AND phase = 'dispatching' AND retry_at = ?",
                           (next_phase, retry, report_id, lease))
                if error.retry_after:
                    self._cooldown(db, retry)
            raise
        except GitHubRequestError as error:
            if error.retry_after:
                with self._db() as db:
                    self._cooldown(db, int(self.clock()) + error.retry_after)
            raise ReportError(503, "AWAITING_GITHUB_RECEIPT", max(RETRY_LEASE, error.retry_after)) from None

        if number is None:
            raise ReportError(503, "AWAITING_GITHUB_RECEIPT", RETRY_LEASE)
        with self._db() as db:
            db.execute("UPDATE reports SET phase = 'done', number = ?, retry_at = 0 WHERE id = ? AND phase != 'done'", (number, report_id))
        return number

    @staticmethod
    def _cooldown(db, retry_at):
        db.execute("INSERT INTO github_cooldown VALUES (1, ?) ON CONFLICT(id) DO UPDATE SET retry_at = MAX(retry_at, excluded.retry_at)", (retry_at,))
