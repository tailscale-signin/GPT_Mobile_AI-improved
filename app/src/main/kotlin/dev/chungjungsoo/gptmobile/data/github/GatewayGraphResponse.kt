package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonObject

/** Display identity belongs to the frozen route that produced this response, never a later selection. */
internal data class GatewayGraphResponse(
    val result: JsonObject,
    val operation: String,
    val gatewayProfileUid: String,
    val gatewayName: String,
    val indexOutcomeUnknown: Boolean = false
)

internal class GatewayGraphOperationException(
    cause: Exception,
    val gatewayProfileUid: String?,
    val gatewayName: String?,
    val repairAction: String?,
    val indexOutcomeUnknown: Boolean
) : IllegalStateException(cause.message ?: "Repository graph unavailable.", cause)
