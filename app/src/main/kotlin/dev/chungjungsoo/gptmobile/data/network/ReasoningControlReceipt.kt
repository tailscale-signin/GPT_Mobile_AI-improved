package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** Request intent and the actual admitted wire control are independent of observed behavior. */
data class ReasoningControlReceipt(
    val disableRequested: Boolean,
    val transmittedControl: String?,
    val transmitsDisable: Boolean,
    val providerSupport: String
) {
    companion object {
        internal fun fromPayload(payload: String, allowReasoning: Boolean): ReasoningControlReceipt {
            val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            val enabled = ((root?.get("reasoning") as? JsonObject)?.get("enabled") as? JsonPrimitive)?.booleanOrNull
            val thinking = ((root?.get("chat_template_kwargs") as? JsonObject)?.get("enable_thinking") as? JsonPrimitive)?.booleanOrNull
            val effort = (root?.get("reasoning_effort") as? JsonPrimitive)?.contentOrNull?.takeIf { it in setOf("none", "minimal", "low", "medium", "high", "xhigh") }
            val control = when {
                enabled != null -> "reasoning.enabled=$enabled"
                thinking != null -> "chat_template_kwargs.enable_thinking=$thinking"
                effort != null -> "reasoning_effort=$effort"
                else -> null
            }
            val disables = enabled == false || thinking == false || effort == "none"
            val support = when {
                control == null -> "NOT_TRANSMITTED"
                !disables && effort != null -> "EFFORT_ONLY"
                else -> "UNVERIFIED"
            }
            return ReasoningControlReceipt(!allowReasoning, control, disables, support)
        }
    }
}
