package dev.chungjungsoo.gptmobile.data.llama

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Metadata failures expose fixed guidance and allowlisted fields, never arbitrary server text. */
internal data class LlamaMetadataFailure(
    val httpStatus: Int,
    val code: String,
    val message: String,
    val serverCode: String? = null,
    val serverType: String? = null,
    val parameter: String? = null,
    val requestId: String? = null
) {
    fun diagnosticFields(): Map<String, Any?> = mapOf(
        "httpStatus" to httpStatus,
        "failureCode" to code,
        "safeMessage" to message,
        "serverErrorCode" to serverCode,
        "serverErrorType" to serverType,
        "rejectedParameter" to parameter,
        "requestId" to requestId,
        "metadataAdapter" to "llama_selected_model_v1",
        "rawResponseRetained" to false
    )
}

private val metadataErrorCodes = setOf(
    "invalid_request_error", "bad_request", "invalid_request", "invalid_argument", "invalid_parameter",
    "unsupported_parameter", "unknown_parameter", "model_not_found", "model_not_loaded", "model_unavailable",
    "invalid_model", "remote_surface_not_admitted", "unauthorized", "forbidden", "not_found", "server_error"
)
private val metadataErrorTypes = setOf("invalid_request_error", "authentication_error", "permission_error", "not_found_error", "server_error", "unavailable_error")
private val metadataParameters = setOf("model", "autoload", "chat_template", "chat_template_kwargs", "enable_thinking", "thinking", "n_ctx")

internal fun llamaMetadataFailure(status: Int, body: JsonObject?, requestId: String? = null): LlamaMetadataFailure {
    val error = body?.get("error") as? JsonObject ?: body
    fun text(key: String) = (error?.get(key) as? JsonPrimitive)?.contentOrNull
    val serverCode = text("code")?.takeIf { it in metadataErrorCodes || it == status.toString() }
    val serverType = text("type")?.takeIf { it in metadataErrorTypes }
    val parameter = (text("param") ?: text("parameter"))?.takeIf { it in metadataParameters }
    val code = when (status) {
        400, 422 -> "METADATA_REQUEST_REJECTED"
        401 -> "METADATA_AUTHENTICATION_REQUIRED"
        403 -> "METADATA_ACCESS_DENIED"
        404, 405, 501 -> "METADATA_UNSUPPORTED_CONTRACT"
        408, 429 -> "METADATA_TEMPORARILY_UNAVAILABLE"
        in 500..599 -> "METADATA_SERVER_UNAVAILABLE"
        else -> "METADATA_HTTP_REJECTED"
    }
    val message = when {
        serverCode in setOf("model_not_found", "invalid_model") -> "The server did not recognize the selected model. Check its exact model identifier on this connection."
        serverCode in setOf("model_not_loaded", "model_unavailable") -> "The selected model is unavailable on this server. Load it on the host, then recheck this connection."
        parameter != null -> "The server rejected the metadata parameter '$parameter'. Check the server version and metadata contract; the selected model and settings are unchanged."
        status in setOf(400, 422) -> "The server rejected the metadata request. Check the selected model identifier and server metadata compatibility; the saved reasoning setting is unchanged."
        status == 401 -> "The server did not authorize this metadata read. Check the credentials for the selected connection."
        status == 403 -> "The server does not permit this metadata read. Use its supported capability contract or repair the selected connection's access."
        status in setOf(404, 405, 501) -> "This address does not expose the required metadata contract. Verify the selected service and its supported server version."
        else -> "The server could not provide metadata. Recheck the selected connection after it becomes available."
    }
    val safeRequestId = requestId?.takeIf { it.matches(Regex("[a-fA-F0-9]{32}|[a-fA-F0-9]{8}(?:-[a-fA-F0-9]{4}){3}-[a-fA-F0-9]{12}")) }
    return LlamaMetadataFailure(status, code, message, serverCode, serverType, parameter, safeRequestId)
}
