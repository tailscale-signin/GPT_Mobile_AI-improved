package dev.chungjungsoo.gptmobile.data.agent.tool

import java.net.URI

/** These rules contain app-owned labels only; invalid values never enter a failure receipt. */
internal enum class McpLocalValidationRule(val diagnosticCode: String, val field: String, val guidance: String) {
    CONNECTION_ID_REQUIRED("MCP_LOCAL_CONNECTION_INVALID", "connection_id", "Select a saved MCP connection before retrying."),
    CONNECTION_CONFIGURATION_CHANGED("MCP_LOCAL_CONNECTION_INVALID", "connection_configuration", "The saved MCP connection changed. Refresh the connection list and start again with its current settings."),
    ENDPOINT_TOO_LONG("MCP_LOCAL_ENDPOINT_INVALID", "endpoint", "The MCP endpoint exceeds the supported URL length. Correct the saved endpoint."),
    ENDPOINT_SYNTAX_INVALID("MCP_LOCAL_ENDPOINT_INVALID", "endpoint", "Enter a valid MCP endpoint URL."),
    ENDPOINT_SCHEME_UNSUPPORTED("MCP_LOCAL_ENDPOINT_INVALID", "endpoint_scheme", "The MCP endpoint must use HTTP or HTTPS."),
    ENDPOINT_HOST_REQUIRED("MCP_LOCAL_ENDPOINT_INVALID", "endpoint_host", "The MCP endpoint must include a valid hostname."),
    ENDPOINT_USER_INFO_FORBIDDEN("MCP_LOCAL_ENDPOINT_INVALID", "endpoint_user_info", "Use the credential field instead of credentials in the endpoint authority."),
    ENDPOINT_FRAGMENT_FORBIDDEN("MCP_LOCAL_ENDPOINT_INVALID", "endpoint_fragment", "Remove the fragment from the saved MCP endpoint."),
    ENDPOINT_PORT_INVALID("MCP_LOCAL_ENDPOINT_INVALID", "endpoint_port", "The MCP endpoint port must be between 1 and 65535."),
    CLEARTEXT_NOT_ALLOWED("MCP_LOCAL_ENDPOINT_INVALID", "allow_cleartext", "Use HTTPS or explicitly allow cleartext for this MCP connection."),
    AUTH_TYPE_UNSUPPORTED("MCP_LOCAL_AUTH_TYPE_INVALID", "auth_type", "Select a supported MCP authentication type."),
    CREDENTIAL_REQUIRED("MCP_LOCAL_CREDENTIAL_MISSING", "credential", "Save the required credential or reconnect this service in Plugins/Tools."),
    CREDENTIAL_UNAVAILABLE("MCP_LOCAL_CREDENTIAL_INVALID", "credential", "The saved credential cannot be read. Update it or reconnect this service in Plugins/Tools."),
    CREDENTIAL_REAUTHORIZATION_REQUIRED("MCP_LOCAL_CREDENTIAL_INVALID", "credential", "The saved OAuth grant requires reauthorization. Reconnect this service in Plugins/Tools."),
    CREDENTIAL_REFRESH_REQUIRED("MCP_LOCAL_CREDENTIAL_REFRESH_REQUIRED", "credential", "The saved OAuth access token needs renewal. Reconnect this exact connection, then start a new audit."),
    CREDENTIAL_FORMAT_INVALID("MCP_LOCAL_CREDENTIAL_INVALID", "credential", "The saved credential contains unsupported characters. Update it in Plugins/Tools."),
    AUTHORIZATION_REQUIRED("MCP_LOCAL_CREDENTIAL_MISSING", "authorization", "The MCP authorization header must not be blank. Update the saved credential."),
    AUTHORIZATION_FORMAT_INVALID("MCP_LOCAL_CREDENTIAL_INVALID", "authorization", "The MCP authorization header contains unsupported characters. Update the saved credential."),
    AUTHORIZATION_TOO_LONG("MCP_LOCAL_CREDENTIAL_INVALID", "authorization", "The MCP authorization header exceeds the supported length. Update the saved credential."),
    GOOGLE_API_KEY_CONFIGURATION_INVALID("MCP_LOCAL_CREDENTIAL_INVALID", "google_api_key", "A Google MCP API key requires its official HTTPS endpoint and a valid credential."),
    ENDPOINT_CREDENTIAL_UNAVAILABLE("MCP_LOCAL_CREDENTIAL_INVALID", "endpoint_credential", "Re-enter the saved endpoint credential for this MCP connection.")
}

/** Emitted before an MCP request can be sent, with no source exception or rejected value attached. */
internal class McpLocalValidationException(val rule: McpLocalValidationRule) : IllegalArgumentException("${rule.diagnosticCode}: ${rule.guidance}") {
    val diagnosticCode: String get() = rule.diagnosticCode
    val validationRule: String get() = rule.name
    val validationField: String get() = rule.field
    val phase: String get() = "local_validation"
    val dispatchEvidence: String get() = "NOT_DISPATCHED"
    val retryable: Boolean get() = false
    val retryDecision: String get() = "AFTER_CONFIGURATION_CHANGE"
}

internal fun McpConnectionConfig.validateLocalConfiguration() {
    fun requireRule(accepted: Boolean, rule: McpLocalValidationRule) {
        if (!accepted) throw McpLocalValidationException(rule)
    }
    requireRule(connectionUid.isNotBlank(), McpLocalValidationRule.CONNECTION_ID_REQUIRED)
    requireRule(endpointUrl.length <= 32 * 1024, McpLocalValidationRule.ENDPOINT_TOO_LONG)
    val uri = runCatching { URI(endpointUrl) }.getOrNull()
        ?: throw McpLocalValidationException(McpLocalValidationRule.ENDPOINT_SYNTAX_INVALID)
    val scheme = uri.scheme?.lowercase()
    requireRule(scheme == "https" || scheme == "http", McpLocalValidationRule.ENDPOINT_SCHEME_UNSUPPORTED)
    requireRule(!uri.host.isNullOrBlank(), McpLocalValidationRule.ENDPOINT_HOST_REQUIRED)
    requireRule(uri.userInfo == null, McpLocalValidationRule.ENDPOINT_USER_INFO_FORBIDDEN)
    requireRule(uri.fragment == null, McpLocalValidationRule.ENDPOINT_FRAGMENT_FORBIDDEN)
    requireRule(uri.port == -1 || uri.port in 1..65535, McpLocalValidationRule.ENDPOINT_PORT_INVALID)
    requireRule(scheme != "http" || allowCleartext, McpLocalValidationRule.CLEARTEXT_NOT_ALLOWED)
    requireRule(authorizationHeader == null || authorizationHeader.isNotBlank(), McpLocalValidationRule.AUTHORIZATION_REQUIRED)
    requireRule(authorizationHeader == null || authorizationHeader.length <= 128 * 1024, McpLocalValidationRule.AUTHORIZATION_TOO_LONG)
    requireRule(authorizationHeader == null || authorizationHeader.all { it.code in 32..126 }, McpLocalValidationRule.AUTHORIZATION_FORMAT_INVALID)
    requireRule(
        googleApiKey == null || (uri.host == "mapstools.googleapis.com" && scheme == "https" && uri.port == -1 && googleApiKey.isNotBlank() && googleApiKey.length <= 4096 && googleApiKey.all { it.code in 33..126 }),
        McpLocalValidationRule.GOOGLE_API_KEY_CONFIGURATION_INVALID
    )
}

/** The vault buffer is cleared on success and on every local rejection. */
internal fun mcpBearerHeader(bytes: ByteArray): String = try {
    val stored = bytes.decodeToString()
    val token = stored.trim()
    if (token.isEmpty()) throw McpLocalValidationException(McpLocalValidationRule.CREDENTIAL_REQUIRED)
    if (stored.any(Char::isISOControl) || token.any { it.code !in 33..126 }) {
        throw McpLocalValidationException(McpLocalValidationRule.CREDENTIAL_FORMAT_INVALID)
    }
    if (token.length > 128 * 1024 - "Bearer ".length) throw McpLocalValidationException(McpLocalValidationRule.AUTHORIZATION_TOO_LONG)
    "Bearer $token"
} finally {
    bytes.fill(0)
}
