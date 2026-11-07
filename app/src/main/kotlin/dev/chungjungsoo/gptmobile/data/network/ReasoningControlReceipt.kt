package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** Request intent and the actual admitted wire control are independent of observed behavior. */
data class ReasoningControlReceipt(
    val disableRequested: Boolean,
    val transmittedControl: String?,
    val transmitsDisable: Boolean,
    val providerSupport: String,
    val profileRevision: String? = null,
    val configuredProfileReasoning: Boolean? = null,
    val requestedMode: String? = null,
    val requestedAllowReasoning: Boolean? = null,
    val requestedMaximizeReasoning: Boolean? = null,
    val serializedControls: JsonObject? = null
) {
    /** Immutable request evidence; an emitted disable control does not prove backend compliance. */
    fun evidence(): JsonObject = buildJsonObject {
        put("profile_revision", profileRevision?.takeIf { it.matches(Regex("[a-f0-9]{64}")) }?.let(::JsonPrimitive) ?: JsonNull)
        put("configured_profile_reasoning", configuredProfileReasoning?.let(::JsonPrimitive) ?: JsonNull)
        put("requested_mode", requestedMode?.takeIf { it in setOf("AUTO", "ON", "OFF") }?.let(::JsonPrimitive) ?: JsonNull)
        put("requested_allow_reasoning", requestedAllowReasoning?.let(::JsonPrimitive) ?: JsonNull)
        put("requested_maximize_reasoning", requestedMaximizeReasoning?.let(::JsonPrimitive) ?: JsonNull)
        put("disable_requested", disableRequested)
        put("serialized_control", transmittedControl?.let(::JsonPrimitive) ?: JsonNull)
        put("serialized_controls", serializedControls ?: JsonNull)
        put("serialized_disable", transmitsDisable)
        put("control_support", providerSupport)
        put("effective_reasoning", JsonNull)
        put("effective_settings_source", "NOT_MEASURED")
    }

    companion object {
        internal fun fromPayload(payload: String, config: ProviderRequestConfig): ReasoningControlReceipt =
            fromPayload(payload, config.allowReasoning, config.reasoningMode, config.profileRevision, config.configuredProfileReasoning, config.maximizeReasoning)

        internal fun fromPayload(payload: String, allowReasoning: Boolean, requestedMode: dev.chungjungsoo.gptmobile.data.context.ReasoningMode = dev.chungjungsoo.gptmobile.data.context.ReasoningMode.AUTO, profileRevision: String? = null, configuredProfileReasoning: Boolean? = null, maximizeReasoning: Boolean? = null): ReasoningControlReceipt {
            val root = runCatching { Json.parseToJsonElement(payload) as? JsonObject }.getOrNull()
            fun JsonObject?.booleanSetting(key: String) = (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.booleanOrNull
            fun JsonObject?.integerSetting(key: String) = (this?.get(key) as? JsonPrimitive)?.takeUnless { it.isString }?.intOrNull
            fun JsonObject?.enumSetting(key: String, allowed: Set<String>) = (this?.get(key) as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it in allowed }
            val reasoning = root?.get("reasoning") as? JsonObject
            val enabled = reasoning.booleanSetting("enabled")
            val template = root?.get("chat_template_kwargs") as? JsonObject
            val templateControls = listOf("enable_thinking", "thinking").mapNotNull { key -> template.booleanSetting(key)?.let { key to it } }
            val efforts = setOf("none", "minimal", "low", "medium", "high", "xhigh")
            val effort = root.enumSetting("reasoning_effort", efforts)
            val responseEffort = reasoning.enumSetting("effort", efforts)
            val anthropic = root?.get("thinking") as? JsonObject
            val anthropicType = anthropic.enumSetting("type", setOf("enabled", "adaptive", "disabled"))
            val anthropicBudget = anthropic.integerSetting("budget_tokens")?.takeIf { it >= 0 }
            val google = (root?.get("generationConfig") as? JsonObject)?.get("thinkingConfig") as? JsonObject
            val googleBudget = google.integerSetting("thinkingBudget")?.takeIf { it >= -1 }
            val includeThoughts = google.booleanSetting("includeThoughts")
            // Retain only typed, bounded settings. Prompt text, tokens, endpoint and arbitrary
            // provider extensions never become part of this durable request evidence.
            val controls = buildJsonObject {
                enabled?.let { put("reasoning.enabled", it) }
                templateControls.forEach { (key, value) -> put("chat_template_kwargs.$key", value) }
                effort?.let { put("reasoning_effort", it) }
                responseEffort?.let { put("reasoning.effort", it) }
                anthropicType?.let { put("thinking.type", it) }
                anthropicBudget?.let { put("thinking.budget_tokens", it) }
                googleBudget?.let { put("generationConfig.thinkingConfig.thinkingBudget", it) }
                includeThoughts?.let { put("generationConfig.thinkingConfig.includeThoughts", it) }
            }
            val control = controls.entries.joinToString(";") { (key, value) -> "$key=${(value as JsonPrimitive).content}" }.takeIf { it.isNotEmpty() }
            val disables = enabled == false || templateControls.any { !it.second } || effort == "none" || responseEffort == "none" || anthropicType == "disabled" || googleBudget == 0
            val support = when {
                control == null -> "NOT_TRANSMITTED"
                !disables && controls.keys.all { it in setOf("reasoning_effort", "reasoning.effort", "thinking.budget_tokens", "generationConfig.thinkingConfig.thinkingBudget") } -> "EFFORT_ONLY"
                controls.keys == setOf("generationConfig.thinkingConfig.includeThoughts") -> "VISIBILITY_ONLY"
                else -> "UNVERIFIED"
            }
            return ReasoningControlReceipt(!allowReasoning || requestedMode == dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF, control, disables, support, profileRevision, configuredProfileReasoning, requestedMode.name, allowReasoning, maximizeReasoning, controls)
        }
    }
}
