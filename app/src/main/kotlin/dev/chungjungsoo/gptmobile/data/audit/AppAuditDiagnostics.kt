package dev.chungjungsoo.gptmobile.data.audit

import dev.chungjungsoo.gptmobile.data.agent.tool.McpLocalValidationRule

/** Shared display/export allowlists. Never echo a rejected value or a provider's free text. */
internal fun safeAuditFailurePhase(value: String?): String? = value?.takeIf { it in AUDIT_FAILURE_PHASES }
internal fun safeAuditFailureCategory(value: String?): String? = value?.takeIf { it in AUDIT_FAILURE_CATEGORIES }
internal fun safeAuditValidationRule(value: String?): String? = value?.takeIf { it in AUDIT_VALIDATION_RULES }
internal fun safeAuditDiagnosticField(value: String?): String? = value?.takeIf { it in AUDIT_DIAGNOSTIC_FIELDS }
internal fun safeAuditConfigurationRevision(value: String?): String? = value?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }

/** Only application-produced fingerprints become an exportable configuration binding. */
internal fun appAuditConfigurationRevision(fingerprint: String?): String? = fingerprint
    ?.takeIf { it.matches(Regex("[a-f0-9]{32}|[a-f0-9]{64}")) }
    ?.let { appAuditHash("app-audit-configuration-v1:$it") }

private val AUDIT_FAILURE_PHASES = setOf(
    "local_validation", "oauth_refresh", "dns", "tls", "connection", "initialization", "protocol_negotiation", "discovery", "catalogue", "execution",
    "mcp_discovery", "gateway_capabilities", "admission", "validation"
)
private val AUDIT_FAILURE_CATEGORIES = setOf(
    "LOCAL_VALIDATION", "AUTHENTICATION_REQUIRED", "PROTOCOL_CONFIGURATION", "ENDPOINT_CONFIGURATION", "TLS_FAILURE", "DNS_FAILURE",
    "RECONNECT_REQUIRED", "WAITING_RECOVERY", "NETWORK_UNAVAILABLE", "MCP_STATE_OR_CATALOG_INVALID", "DISCOVERY_FAILED"
)
private val AUDIT_VALIDATION_RULES = McpLocalValidationRule.entries.mapTo(mutableSetOf()) { it.name } + setOf(
    "OAUTH_REFRESH_UNAVAILABLE", "PROTOCOL_VERSIONS_ARRAY_REQUIRED", "PROTOCOL_VERSIONS_NONEMPTY_REQUIRED", "PROTOCOL_VERSIONS_LIMIT_EXCEEDED",
    "PROTOCOL_VERSION_FORMAT_REQUIRED", "SUPPORTED_PROTOCOL_REQUIRED", "REQUESTED_PROTOCOL_MATCH_REQUIRED", "LEGACY_PROTOCOL_ALTERNATIVE_REQUIRED",
    "REQUEST_METADATA_ACCEPTANCE_REQUIRED", "CLIENT_CAPABILITY_REQUIRED", "REQUEST_PARAMETERS_ACCEPTANCE_REQUIRED", "REQUEST_JSON_ACCEPTANCE_REQUIRED",
    "DISCOVERY_REQUEST_ACCEPTANCE_REQUIRED", "DISCOVERY_METHOD_SUPPORTED_REQUIRED", "ENDPOINT_ROUTE_REQUIRED", "INITIALIZE_REQUEST_ACCEPTANCE_REQUIRED", "RESPONSE_ID_MATCH_REQUIRED", "RESULT_OBJECT_REQUIRED",
    "RESPONSE_OBJECT_REQUIRED", "JSON_RPC_VERSION_REQUIRED", "RESPONSE_RESULT_OR_ERROR_REQUIRED", "ERROR_OBJECT_REQUIRED",
    "CAPABILITIES_OBJECT_REQUIRED", "DISCOVERY_COMPLETE_REQUIRED", "CATALOG_REQUEST_ACCEPTANCE_REQUIRED", "CATALOG_ROUTE_REQUIRED"
)
private val AUDIT_DIAGNOSTIC_FIELDS = McpLocalValidationRule.entries.mapTo(mutableSetOf()) { it.field } + setOf(
    "supportedVersions", "error.data.requested", "error.data.supported", "request_metadata", "client_capabilities", "params", "request", "server_discover",
    "initialize", "id", "result", "jsonrpc", "response", "error", "endpoint", "tools_list", "capabilities", "resultType"
)
