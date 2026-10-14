package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

@Serializable
enum class ReasoningMode { AUTO, ON, OFF }

@Serializable
enum class ReasoningTemplateControl(val parameter: String) { ENABLE_THINKING("enable_thinking"), THINKING("thinking") }

@Serializable
data class ReasoningDecision(
    val requested: ReasoningMode,
    val effective: ReasoningMode,
    val transmittedControl: String? = null,
    val templateProbeVerified: Boolean = false,
    val observedComputationEnabled: Boolean? = null
)

/** A template prefill check verifies a control, not the model's observed computation or answer. */
object ReasoningPolicy {
    fun resolve(requested: ReasoningMode, allowed: Boolean, control: ReasoningTemplateControl?): ReasoningDecision {
        val effective = if (allowed) requested else ReasoningMode.OFF
        if (effective == ReasoningMode.AUTO) return ReasoningDecision(requested, effective)
        check(control != null) { "REASONING_${effective}_UNSUPPORTED: this route has no verified template control. Choose Auto or a qualified model; no inference was sent." }
        return ReasoningDecision(requested, effective, control.parameter, templateProbeVerified = true)
    }

    fun apply(root: JsonObject, decision: ReasoningDecision): JsonObject {
        val kwargs = (root["chat_template_kwargs"] as? JsonObject).orEmpty().toMutableMap()
        kwargs.remove("thinking")
        kwargs.remove("enable_thinking")
        decision.transmittedControl?.let { kwargs[it] = JsonPrimitive(decision.effective == ReasoningMode.ON) }
        return JsonObject(
            root.toMutableMap().apply {
                remove("reasoning_effort")
                if (kwargs.isEmpty()) remove("chat_template_kwargs") else put("chat_template_kwargs", JsonObject(kwargs))
            }
        )
    }

    fun verifiesThinkingToggle(enabledPrompt: String, disabledPrompt: String): Boolean {
        val on = enabledPrompt.lastIndexOf("<think>")
        val off = disabledPrompt.lastIndexOf("<think>")
        if (on < 0 || on != off || enabledPrompt.substring(0, on) != disabledPrompt.substring(0, off)) return false
        return enabledPrompt.substring(on + 7).isBlank() && disabledPrompt.substring(off + 7).trim() == "</think>"
    }
}
