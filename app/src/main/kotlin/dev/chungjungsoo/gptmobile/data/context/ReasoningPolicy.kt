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
    /** Missing enum preferences migrate the old off switch to OFF; only an explicit AUTO keeps the template default. */
    fun preference(legacyEnabled: Boolean, explicit: ReasoningMode?, conversation: Boolean? = null): ReasoningMode = when (conversation) {
        false -> ReasoningMode.OFF
        true -> ReasoningMode.ON
        null -> explicit ?: if (legacyEnabled) ReasoningMode.AUTO else ReasoningMode.OFF
    }

    fun forRequest(requested: ReasoningMode, allowed: Boolean, maximize: Boolean, helper: Boolean, control: ReasoningTemplateControl?): ReasoningDecision = when {
        requested == ReasoningMode.OFF -> resolve(requested, true, control)
        !allowed -> resolve(requested, false, control)
        requested != ReasoningMode.AUTO -> resolve(requested, allowed, control)
        helper -> boundedHelper(control).copy(requested = requested)
        maximize && allowed -> strongestSupported(true, control).copy(requested = requested)
        else -> resolve(requested, allowed, control)
    }

    fun resolve(requested: ReasoningMode, allowed: Boolean, control: ReasoningTemplateControl?): ReasoningDecision {
        val effective = if (allowed) requested else ReasoningMode.OFF
        if (effective == ReasoningMode.AUTO) return ReasoningDecision(requested, effective)
        if (control == null) throw ReasoningAdmissionException(effective)
        return ReasoningDecision(requested, effective, control.parameter, templateProbeVerified = true)
    }

    fun strongestSupported(allowed: Boolean, control: ReasoningTemplateControl?): ReasoningDecision =
        resolve(if (allowed && control != null) ReasoningMode.ON else ReasoningMode.AUTO, allowed, control)

    /** When reasoning is permitted, helpers prefer verified OFF and otherwise preserve AUTO. */
    fun boundedHelper(control: ReasoningTemplateControl?): ReasoningDecision =
        resolve(if (control != null) ReasoningMode.OFF else ReasoningMode.AUTO, true, control)

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
