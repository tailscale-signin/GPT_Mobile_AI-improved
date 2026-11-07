package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Request intent and the actual admitted wire control are independent of observed behavior. */
data class ReasoningControlReceipt(
    val disableRequested: Boolean,
    val transmittedControl: String?,
    val transmitsDisable: Boolean,
    val providerSupport: String,
    val profileRevision: String? = null,
    val configuredProfileReasoning: Boolean? = null,
    val requestedMode: String? = null
) {
    /** Immutable request evidence; an emitted disable control does not prove backend compliance. */
    fun evidence(): JsonObject = buildJsonObject {
        put("profile_revision", profileRevision?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let(::JsonPrimitive) ?: JsonNull)
        put("configured_profile_reasoning", configuredProfileReasoning?.let(::JsonPrimitive) ?: JsonNull)
        put("requested_mode", requestedMode?.takeIf { it in setOf("AUTO", "ON", "OFF") }?.let(::JsonPrimitive) ?: JsonNull)
        put("disable_requested", disableRequested)
        put("serialized_control", transmittedControl?.let(::JsonPrimitive) ?: JsonNull)
        put("serialized_disable", transmitsDisable)
        put("control_support", providerSupport)
        put("effective_reasoning", JsonNull)
        put("effective_settings_source", "NOT_MEASURED")
    }

    companion object {
        internal fun fromPayload(payload: String, allowReasoning: Boolean, requestedMode: dev.chungjungsoo.gptmobile.data.context.ReasoningMode = dev.chungjungsoo.gptmobile.data.context.ReasoningMode.AUTO, profileRevision: String? = null, configuredProfileReasoning: Boolean? = null): ReasoningControlReceipt {
            val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            val enabled = ((root?.get("reasoning") as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull
            val template = root?.get("chat_template_kwargs") as? JsonObject
            val thinkingKey = listOf("enable_thinking", "thinking").firstOrNull { (template?.get(it) as? JsonPrimitive)?.booleanOrNull != null }
            val thinking = (template?.get(thinkingKey) as? JsonPrimitive)?.booleanOrNull
            val effort = (root?.get("reasoning_effort") as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh") }
            val control = when {
                enabled != null -> "reasoning.enabled=$enabled"
                thinking != null -> "chat_template_kwargs.$thinkingKey=$thinking"
                effort != null -> "reasoning_effort=$effort"
                else -> null
            }
            val disables = enabled == false || thinking == false || effort == "none"
            val support = when {
                control == null -> "NOT_TRANSMITTED"
                !disables && effort != null -> "EFFORT_ONLY"
                else -> "UNVERIFIED"
            }
            return ReasoningControlReceipt(!allowReasoning || requestedMode == dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, control, disables, support, profileRevision, configuredProfileReasoning, requestedMode.name)
        }
    }
}
