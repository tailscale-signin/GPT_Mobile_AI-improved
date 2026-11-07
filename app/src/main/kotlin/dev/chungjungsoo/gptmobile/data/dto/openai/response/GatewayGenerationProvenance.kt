package dev.chungjungsoo.gptmobile.data.dto.openai.response

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** A verified gateway can acknowledge dispatch and backend model reporting, not unmeasured hardware. */
@Serializable
data class GatewayGenerationProvenance(
    @SerialName("schema_version") val schemaVersion: Int? = null,
    val origin: String? = null,
    @SerialName("requested_model") val requestedModel: String? = null,
    @SerialName("reported_model") val reportedModel: String? = null,
    @SerialName("model_identity") val modelIdentity: String? = null,
    @SerialName("http_status") val httpStatus: Int? = null,
    @SerialName("backend_endpoint_digest") val backendEndpointDigest: String? = null,
    @SerialName("serialized_controls") val serializedControls: JsonObject? = null
) {
    /** Only call after the response headers have verified the gateway transport identity. */
    fun evidence(expectedModel: String): JsonObject? {
        if (schemaVersion != 1 || origin != "BACKEND_RESPONSE" || httpStatus != 200 || requestedModel != expectedModel) return null
        val reported = reportedModel?.takeIf { it.isNotEmpty() && it.length <= 512 && it.none(Char::isISOControl) }
        val identity = when {
            reported == null -> "UNAVAILABLE"
            reported == expectedModel -> "MATCHED"
            else -> "DIFFERENT"
        }
        if (modelIdentity != identity) return null
        return buildJsonObject {
            put("schema_version", 1)
            put("origin", "BACKEND_RESPONSE")
            put("requested_model", expectedModel)
            put("reported_model", reported?.let(::JsonPrimitive) ?: JsonNull)
            put("model_identity", identity)
            put("http_status", 200)
            put("backend_endpoint_digest", backendEndpointDigest?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let(::JsonPrimitive) ?: JsonNull)
            put(
                "serialized_controls",
                buildJsonObject {
                    for (key in listOf("reasoning.enabled", "chat_template_kwargs.enable_thinking", "chat_template_kwargs.thinking")) {
                        (serializedControls?.get(key) as? JsonPrimitive)?.booleanOrNull?.let { put(key, it) }
                    }
                    (serializedControls?.get("reasoning_effort") as? JsonPrimitive)?.contentOrNull
                        ?.takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh") }?.let { put("reasoning_effort", it) }
                }
            )
            put("effective_controls", JsonNull)
            put("runtime_identity", JsonNull)
            put("accelerator", JsonNull)
            put("effective_settings_source", "NOT_MEASURED")
        }
    }
}
