package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Search is read-only, but a lost response is still different from a rejected preflight. */
internal data class SearchTransportFailure(val code: String, val phase: String, val requestSent: Boolean?, val message: String) {
    companion object {
        fun from(failure: Throwable): SearchTransportFailure = when (LocalServiceHealth.classify(failure)) {
            ConnectionHealth.DNS_FAILED -> SearchTransportFailure("DNS_FAILED", "DNS", false, "The search hostname could not be resolved. Retry after connectivity returns.")
            ConnectionHealth.DNS_RECOVERY -> SearchTransportFailure("DNS_RECOVERY", "CONNECTIVITY_PRECHECK", false, "Search is waiting for network recovery.")
            ConnectionHealth.CONNECTION_REFUSED, ConnectionHealth.ROUTE_UNAVAILABLE -> SearchTransportFailure("CONNECTION_FAILED", "CONNECT", false, "The search service could not be reached.")
            ConnectionHealth.CONNECT_TIMEOUT -> SearchTransportFailure("TIMEOUT", "CONNECT", null, "The connection to the search service timed out.")
            ConnectionHealth.REQUEST_TIMEOUT, ConnectionHealth.STREAM_IDLE_TIMEOUT -> SearchTransportFailure("TIMEOUT", "RESPONSE", null, "The search response timed out.")
            ConnectionHealth.STREAM_INTERRUPTED -> SearchTransportFailure("STREAM_INTERRUPTED", "RESPONSE", null, "The search connection ended before a complete response arrived.")
            else -> SearchTransportFailure("UNAVAILABLE", "TRANSPORT", null, "No usable search response was received.")
        }
    }
}

internal fun searchFailureRetryable(code: String): Boolean = code in setOf(
    "DNS_FAILED",
    "DNS_RECOVERY",
    "CONNECTION_FAILED",
    "STREAM_INTERRUPTED",
    "TIMEOUT",
    "RATE_LIMITED",
    "UNAVAILABLE",
    "SEARCH_PROVIDERS_UNAVAILABLE",
    "SOURCE_SERVER_ERROR"
)

internal fun searchOperationReceipt(
    callId: String,
    provider: String,
    phase: String,
    requestSent: Boolean?,
    bodyReceived: Boolean,
    code: String? = null,
    status: Int? = null,
    requestId: String? = null
): JsonObject = buildJsonObject {
    put("schema", "gptmobile.search-operation.v1")
    put("callId", callId)
    put("provider", provider)
    put("phase", phase)
    put("requestSent", requestSent)
    put("bodyReceived", bodyReceived)
    put(
        "state",
        when {
            code == null -> "COMPLETED"
            requestSent == false -> "PRECHECK_REJECTED"
            requestSent == null && !bodyReceived -> "UNKNOWN_OUTCOME"
            else -> "FAILED"
        }
    )
    put("retryable", code != null && searchFailureRetryable(code))
    code?.let { put("errorCode", it) }
    status?.let { put("httpStatus", it) }
    // Provider headers are untrusted. Never retain arbitrary diagnostic/header contents.
    requestId?.takeIf { it.matches(Regex("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}")) }?.let { put("requestId", it) }
}
