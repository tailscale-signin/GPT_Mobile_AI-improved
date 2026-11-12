package dev.chungjungsoo.gptmobile.data.network.gateway

/** Repair hints are fixed vocabulary; they never grant permission to replay an operation. */
internal fun gatewayDiagnosticRecovery(reason: String?, terminal: String? = null, execution: String? = null): String = when {
    execution == "UNKNOWN_OUTCOME" -> "reconcile"
    reason == "TRANSPORT_EOF" -> "reconcile"
    reason in setOf("COMPLETION_REPAIR_BUDGET_EXHAUSTED", "INCOMPLETE", "TASK_INCOMPLETE", "GITHUB_TASK_INCOMPLETE") -> "resume_checkpoint"
    reason in setOf("ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED", "VALIDATION_POLICY_REQUIRED", "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED", "VALIDATION_RUNTIME_UNAVAILABLE", "VALIDATION_REVISION_UNAVAILABLE") -> "repair_configuration"
    reason in setOf("SOURCE_REVISION_REQUIRED", "SOURCE_REVISION_CHANGED") -> "repair_request"
    reason == "ENGINEERING_REPOSITORY_ACCESS_REQUIRED" -> "authorize"
    reason in setOf("SELECTED_MODEL_LOADING", "EXECUTION_MODEL_LOADING") -> "wait_for_model"
    reason in setOf("SELECTED_MODEL_NOT_LOADED", "EXECUTION_MODEL_NOT_LOADED") -> "load_selected_model"
    reason in setOf("SELECTED_MODEL_NOT_AVAILABLE", "EXECUTION_MODEL_UNAVAILABLE") -> "select_available_model"
    reason in setOf("DURABLE_STORAGE_UNAVAILABLE", "DURABLE_STORAGE_WRITE_FAILED", "conversation_storage_unavailable") -> "restore_storage"
    reason == "GATEWAY_DRAINING" -> "wait_for_gateway"
    reason in setOf("BACKEND_METADATA_DENIED", "EXECUTION_METADATA_DENIED") -> "check_backend_access"
    reason in setOf("BACKEND_CONFIGURATION_INVALID", "BACKEND_ROUTE_UNSUPPORTED", "EXECUTION_ENDPOINT_INVALID") -> "repair_configuration"
    reason in setOf("BACKEND_CONNECTION_REFUSED", "BACKEND_CONNECT_TIMEOUT", "BACKEND_READ_TIMEOUT", "BACKEND_DNS_FAILED", "BACKEND_TLS_FAILED", "BACKEND_CONNECTION_CLOSED", "BACKEND_CONNECTION_FAILED", "EXECUTION_BACKEND_UNAVAILABLE") -> "restore_backend"
    reason in setOf("MCP_AUTH_REQUIRED", "MCP_AUTHENTICATION_REQUIRED", "AUTHORIZATION_REQUIRED", "ENGINEERING_AUTHENTICATION_REQUIRED", "ENGINEERING_ACCESS_DENIED", "GATEWAY_ENGINEERING_GITHUB_REQUIRED", "HOST_GITHUB_CONNECTION_REQUIRED") -> "authorize"
    reason in setOf("MCP_DISCOVERY_FAILED", "MCP_CATALOG_TIMEOUT") -> "retry_discovery"
    reason in setOf("MCP_CONNECTION_FAILED", "MCP_UNAVAILABLE") -> "reconnect_server"
    reason in setOf("REASONING_OFF_UNSUPPORTED", "REASONING_ON_UNSUPPORTED", "GATEWAY_UNSUPPORTED_CONTRACT", "GATEWAY_INVALID_CONNECTION", "GATEWAY_SOURCE_MISMATCH") -> "repair_configuration"
    reason in setOf("GATEWAY_STORAGE_UNAVAILABLE", "GATEWAY_ENGINEERING_STORAGE_DISABLED", "GATEWAY_ENGINEERING_STORAGE_UNAVAILABLE") -> "restore_storage"
    reason == "GATEWAY_ENGINEERING_WORKER_UNAVAILABLE" -> "restore_backend"
    reason in setOf("ENGINEERING_VALIDATION_REJECTED", "OPERATION_PRECHECK_REJECTED", "INVALID_ARGUMENTS", "INVALID_REQUEST", "REQUEST_REJECTED", "GITHUB_VALIDATION_FAILED") -> "repair_request"
    terminal in setOf("PARTIAL", "EVIDENCE_MISSING") -> "verify_acceptance"
    terminal == "BLOCKED" || execution == "PRECHECK_REJECTED" -> "recheck_prerequisite"
    terminal in setOf("COMPLETED", "CANCELLED", "CANCELED") -> "none"
    else -> "inspect_failure"
}

internal fun gatewayRecoveryCondition(action: String): String = when (action) {
    "none" -> "none"
    "restore_backend", "wait_for_gateway", "retry_read", "reconnect_server" -> "backend_reachable"
    "wait_for_model", "load_selected_model" -> "selected_model_loaded"
    "select_available_model" -> "selected_model_available"
    "authorize", "check_backend_access" -> "required_scope_granted"
    "restore_storage" -> "storage_writable"
    "repair_configuration" -> "configuration_changed"
    "repair_request" -> "valid_request"
    "refresh_capabilities", "retry_discovery" -> "capabilities_refreshed"
    "verify_acceptance" -> "acceptance_evidence_available"
    "reconcile" -> "original_outcome_reconciled"
    else -> "dependency_changed"
}

/** Fixed cause-to-dependency mapping for admission failures that have no dispatched job. */
internal fun gatewayDiagnosticDependency(reason: String?): String? = when (reason) {
    "ACCEPTANCE_CONTRACT_REQUIRED", "ACCEPTANCE_PRODUCER_REQUIRED" -> "acceptance"
    "VALIDATION_POLICY_REQUIRED" -> "validation_policy"
    "VALIDATION_CHECKS_REQUIRED", "VALIDATION_CHECK_UNSUPPORTED" -> "ci_checks"
    "SOURCE_REVISION_REQUIRED", "SOURCE_REVISION_CHANGED", "VALIDATION_REVISION_UNAVAILABLE" -> "source_revision"
    "VALIDATION_RUNTIME_UNAVAILABLE" -> "validation_runtime"
    "ENGINEERING_REPOSITORY_ACCESS_REQUIRED" -> "repository_access"
    else -> null
}
