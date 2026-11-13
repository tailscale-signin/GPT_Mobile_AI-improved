"""Public, application-owned engineering vocabulary; no request values or exception text."""

ENGINEERING_REASON_FIELDS = {
    "ENGINEERING_REQUEST_FIELDS_INVALID": {"request"},
    "ENGINEERING_BUDGET_INVALID": {"execution_budget"},
    "ENGINEERING_BRANCH_INVALID": {"branch"},
    "ENGINEERING_REPOSITORY_INVALID": {"repository"},
    "ENGINEERING_REVISION_INVALID": {"expected_revision"},
    "ENGINEERING_REVISION_REQUIRED": {"revision"},
    "ENGINEERING_TASK_SCOPE_INVALID": {"task_scope"},
    "ENGINEERING_TASK_SCOPE_REQUIRED": {"task_scope"},
    "ENGINEERING_SOURCE_REVISION_REQUIRED": {"source_sha"},
    "ENGINEERING_SOURCE_REVISION_INVALID": {"source_sha"},
    "ENGINEERING_PROFILE_INVALID": {"profile_id"},
    "ENGINEERING_MEMORY_MODE_INVALID": {"memory_mode"},
    "ENGINEERING_MODE_INVALID": {"execution_mode"},
    "ENGINEERING_BATCH_POLICY_INVALID": {"batch_preferences", "batch_policy_version"},
    "ENGINEERING_REQUEST_FORMAT_INVALID": {"request"},
    "ENGINEERING_REQUEST_TOO_LARGE": {"request"},
    "ENGINEERING_INPUT_OR_STATE_REJECTED": set(),
}
ENGINEERING_REASONS = set(ENGINEERING_REASON_FIELDS)
ENGINEERING_FIELDS = set().union(*ENGINEERING_REASON_FIELDS.values())
GATEWAY_ADMISSION_FIELDS = {"gateway_operation_id_hash", "gateway_manifest_hash", "gateway_version", "gateway_protocol_version", "gateway_admission_ready", "gateway_failure_phase", "gateway_http_status", "gateway_reason_code"}
GATEWAY_ADMISSION_CORE = {"gateway_manifest_hash", "gateway_protocol_version", "gateway_admission_ready"}
GATEWAY_ADMISSION_PHASES = {"model_preparation", "connection_preflight", "provider_admission", "request_validation", "engineering_guard", "binding_admission", "message_admission", "conversation_admission", "execution_admission", "gateway_recovery", "transport"}
