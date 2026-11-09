package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.diagnostics.diagnosticMessageForStorage
import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import java.io.IOException
import kotlinx.coroutines.CancellationException

/** The resolver and connection screen must describe the same retained recovery policy. */
internal data class McpFailurePresentation(
    val outcome: String,
    val errorCode: String?,
    val retryable: Boolean?,
    val message: String,
    val phase: String? = null,
    val dispatchEvidence: String? = null,
    val retryDecision: String? = null,
    val httpStatus: Int? = null,
    val validationRule: String? = null,
    val validationField: String? = null,
    val observedAtEpochMs: Long? = null,
    val configurationRevision: String? = null
) {
    val waitingForRecovery: Boolean get() = outcome == "WAITING_RECOVERY"
    val authenticationRequired: Boolean get() = outcome == "AUTHENTICATION_REQUIRED" || validationRule == "CREDENTIAL_REAUTHORIZATION_REQUIRED"
    val diagnosticSummary: String get() = "outcome=$outcome · errorCode=${errorCode ?: "unknown"} · retryable=${retryable ?: "unknown"}"
}

internal fun mcpFailurePresentation(error: Throwable, health: McpConnectionHealth): McpFailurePresentation {
    val causes = generateSequence(error) { it.cause?.takeUnless { cause -> cause === it } }.take(8).toList()
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    val protocolFailure = causes.filterIsInstance<McpProtocolConfigurationException>().firstOrNull()
    val localFailure = causes.filterIsInstance<McpLocalValidationException>().firstOrNull()
    val endpointFailure = causes.filterIsInstance<McpEndpointConfigurationException>().firstOrNull()
    val refreshFailure = causes.filterIsInstance<McpDiscoveryOAuthRefreshException>().firstOrNull()
    val backoff = causes.filterIsInstance<McpBackoffException>().firstOrNull()
    val observedFailure = health.takeUnless { it.state == McpConnectionHealthState.CONNECTED }
    val directOutcome = when {
        localFailure != null -> "LOCAL_VALIDATION"
        error.isMcpUnauthorized() -> "AUTHENTICATION_REQUIRED"
        protocolFailure != null -> "PROTOCOL_CONFIGURATION"
        causes.any { it is McpEndpointConfigurationException } -> "ENDPOINT_CONFIGURATION"
        LocalServiceHealth.classify(error) == ConnectionHealth.TLS_FAILED -> "TLS_FAILURE"
        LocalServiceHealth.classify(error) == ConnectionHealth.DNS_FAILED -> "DNS_FAILURE"
        else -> null
    }
    val directCode = when (directOutcome) {
        "LOCAL_VALIDATION" -> localFailure?.diagnosticCode
        "AUTHENTICATION_REQUIRED" -> "MCP_AUTHENTICATION_REQUIRED"
        "PROTOCOL_CONFIGURATION" -> protocolFailure?.diagnosticCode
        "ENDPOINT_CONFIGURATION" -> "MCP_ENDPOINT_CONFIGURATION"
        "TLS_FAILURE" -> "TLS_FAILURE"
        "DNS_FAILURE" -> "DNS_FAILED"
        else -> null
    }
    val errorCode = (directCode ?: observedFailure?.lastErrorCode)?.takeIf {
        it.length in 1..80 && it.matches(Regex("[A-Z][A-Z0-9_]*"))
    }
    // A permanent gate also throws McpBackoffException. Its Long.MAX_VALUE
    // sentinel is not a timer that the user can wait out.
    val reconnectRequired = observedFailure?.failureRetryable == false || backoff?.retryAtMs == Long.MAX_VALUE
    val outcome = directOutcome ?: when {
        observedFailure?.state == McpConnectionHealthState.AUTHENTICATION_REQUIRED || errorCode == "MCP_AUTHENTICATION_REQUIRED" -> "AUTHENTICATION_REQUIRED"
        observedFailure?.connectionHealth == ConnectionHealth.TLS_FAILED || errorCode == "TLS_FAILURE" -> "TLS_FAILURE"
        errorCode in MCP_PROTOCOL_FAILURE_CODES -> "PROTOCOL_CONFIGURATION"
        errorCode == "MCP_ENDPOINT_CONFIGURATION" -> "ENDPOINT_CONFIGURATION"
        errorCode in MCP_LOCAL_FAILURE_CODES -> "LOCAL_VALIDATION"
        reconnectRequired -> "RECONNECT_REQUIRED"
        backoff != null -> "WAITING_RECOVERY"
        causes.any { it is IOException } -> "NETWORK_UNAVAILABLE"
        error is IllegalStateException -> "MCP_STATE_OR_CATALOG_INVALID"
        else -> "DISCOVERY_FAILED"
    }
    val retryable = when (outcome) {
        "AUTHENTICATION_REQUIRED", "TLS_FAILURE", "PROTOCOL_CONFIGURATION", "ENDPOINT_CONFIGURATION", "LOCAL_VALIDATION", "RECONNECT_REQUIRED" -> false
        "WAITING_RECOVERY", "DNS_FAILURE" -> true
        else -> observedFailure?.failureRetryable
    }
    val guidance = when (outcome) {
        "AUTHENTICATION_REQUIRED" -> "Authentication is required. Update this server's credentials or sign in again, then reconnect. Automatic discovery is paused."
        "TLS_FAILURE" -> "The secure connection to the MCP server failed. Check its certificate and TLS configuration, then reconnect. Automatic discovery is paused."
        "DNS_FAILURE" -> "This MCP server's hostname could not be resolved. Check its configured address, DNS and VPN connection, then retry. Other connections and the saved catalogue remain available."
        "PROTOCOL_CONFIGURATION" -> "The MCP server's protocol response is invalid or unsupported. Check the server or bridge configuration, then reconnect. Automatic discovery is paused."
        "ENDPOINT_CONFIGURATION" -> "The MCP endpoint path or server configuration is invalid. Check the configured URL and running service, then reconnect. Automatic discovery is paused."
        "LOCAL_VALIDATION" -> if (localFailure?.validationRule == "CREDENTIAL_REAUTHORIZATION_REQUIRED" || observedFailure?.lastValidationRule == "CREDENTIAL_REAUTHORIZATION_REQUIRED") {
            "Sign in again from Manage connection to renew this saved OAuth authorization, then reconnect. No MCP request was sent; retry alone cannot repair the credential."
        } else {
            "The saved MCP connection failed local validation. Correct the indicated connection setting, then retry. No MCP request was sent."
        }
        "RECONNECT_REQUIRED" -> "MCP discovery is paused. Check the connection configuration, then reconnect."
        "WAITING_RECOVERY" -> "Waiting for the MCP connection backoff to expire. The saved catalogue is retained and can be refreshed when recovery is available."
        "NETWORK_UNAVAILABLE" -> "Network access to this MCP server is unavailable. The saved catalogue is retained for retry after connectivity returns."
        "MCP_STATE_OR_CATALOG_INVALID" -> "The MCP connection or tool catalogue changed during discovery. Refresh the catalogue after the connection settles."
        else -> "Tool discovery failed. Check connection diagnostics and retry catalogue discovery."
    }
    // Preserve the original failure detail, not the later gate exception's
    // misleading timestamp. Redact complete input before limiting displayed text.
    val currentHealth = observedFailure?.takeIf {
        localFailure == null &&
            (directCode == null || directCode == it.lastErrorCode) &&
            (protocolFailure == null || (it.lastValidationRule == protocolFailure.validationRule && it.lastValidationField == protocolFailure.validationField && it.lastHttpStatus == protocolFailure.httpStatus))
    }
    val rawDetail = localFailure?.message
        ?: refreshFailure?.message
        ?: currentHealth?.lastError?.takeIf(String::isNotBlank)
        ?: protocolFailure?.message
        ?: error.takeIf { backoff == null }?.message
    val detail = rawDetail?.let { diagnosticMessageForStorage(it).take(1024) }?.takeIf(String::isNotBlank)
    val diagnosticDetail = when {
        errorCode == null -> detail
        detail == null -> errorCode
        errorCode in detail -> detail
        else -> "$errorCode: $detail"
    }
    val httpStatus = if (localFailure != null) {
        null
    } else {
        refreshFailure?.httpStatus ?: protocolFailure?.httpStatus ?: causes.firstNotNullOfOrNull {
            when (it) {
                is ModernMcpError -> it.status
                is io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpError -> it.code
                is McpEndpointConfigurationException -> it.httpStatus
                else -> null
            }
        } ?: currentHealth?.lastHttpStatus
    }
    return McpFailurePresentation(
        outcome,
        errorCode,
        retryable,
        listOfNotNull(guidance, diagnosticDetail).joinToString("\n"),
        phase = localFailure?.phase ?: if (refreshFailure != null) "oauth_refresh" else currentHealth?.failedStage ?: if (protocolFailure != null) "protocol_negotiation" else null,
        dispatchEvidence = when {
            localFailure != null -> localFailure.dispatchEvidence
            backoff != null || causes.any { it is McpAuthenticationRequiredException } || (endpointFailure != null && endpointFailure.httpStatus == null) -> "NOT_DISPATCHED"
            httpStatus != null -> "CONFIRMED"
            else -> currentHealth?.lastDispatchEvidence
        },
        retryDecision = localFailure?.retryDecision ?: currentHealth?.lastRetryDecision ?: if (retryable == false) "AFTER_CONFIGURATION_CHANGE" else null,
        httpStatus = httpStatus?.takeIf { it in 100..599 },
        validationRule = localFailure?.validationRule ?: refreshFailure?.validationRule ?: protocolFailure?.validationRule ?: currentHealth?.lastValidationRule,
        validationField = localFailure?.validationField ?: refreshFailure?.validationField ?: protocolFailure?.validationField ?: currentHealth?.lastValidationField,
        observedAtEpochMs = currentHealth?.lastFailureAtMs,
        configurationRevision = currentHealth?.failureConfigurationRevision?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }
    )
}

private val MCP_PROTOCOL_FAILURE_CODES = setOf(
    "MCP_PROTOCOL_VERSION_UNSUPPORTED",
    "MCP_INITIALIZATION_SCHEMA_INVALID",
    "MCP_DISCOVERY_SCHEMA_INVALID",
    "MCP_DISCOVERY_CONFIGURATION",
    "MCP_CATALOG_SCHEMA_INVALID",
    "MCP_CATALOG_LIMIT_EXCEEDED",
    "MCP_RESPONSE_ID_MISMATCH",
    "MCP_RESPONSE_SCHEMA_INVALID"
)

private val MCP_LOCAL_FAILURE_CODES = McpLocalValidationRule.entries.mapTo(mutableSetOf()) { it.diagnosticCode }
