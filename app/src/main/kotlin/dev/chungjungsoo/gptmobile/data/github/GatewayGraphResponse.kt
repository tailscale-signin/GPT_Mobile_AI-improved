package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject

/** Display identity belongs to the frozen route that produced this response, never a later selection. */
internal data class GatewayGraphResponse(
    val result: JsonObject,
    val operation: String,
    val gatewayProfileUid: String,
    val gatewayName: String,
    val indexOutcomeUnknown: Boolean = false,
    val durableIndexJobs: Boolean = false,
    val modelAccess: String = "NOT_CHECKED"
)

internal class GatewayGraphOperationException(
    cause: Exception,
    val gatewayProfileUid: String?,
    val gatewayName: String?,
    val repairAction: String?,
    val indexOutcomeUnknown: Boolean,
    val failureCode: String = graphFailureCode(cause)
) : IllegalStateException(cause.message ?: "Repository graph unavailable.", cause)

/** Keeps a bad response or rejected identity distinct from a disconnected host. */
internal class GatewayGraphIntegrityException(val code: String, message: String) : IllegalStateException(message)

internal class GatewayGraphResultException(val code: String, message: String, val repairAction: String) : IllegalStateException(message)

internal fun graphFailureCode(failure: Exception): String = when (failure) {
    is GatewayGraphIntegrityException -> failure.code
    is GatewayGraphResultException -> failure.code
    is GatewayGraphAdmissionException -> failure.code
    is javax.net.ssl.SSLException -> "GRAPH_TLS_REJECTED"
    is GatewayAdmissionException -> "GRAPH_${failure.reason.name}"
    is GatewayGraphUnavailableException -> failure.prerequisites.firstOrNull()?.name ?: "GRAPH_UNAVAILABLE"
    is GatewayEngineeringRequestRejected -> failure.errorCode?.takeIf { it.matches(Regex("[A-Z][A-Z0-9_]{1,100}")) } ?: "GRAPH_REQUEST_REJECTED"
    is java.io.IOException -> "GRAPH_TRANSPORT_UNAVAILABLE"
    else -> "GRAPH_OPERATION_FAILED"
}
