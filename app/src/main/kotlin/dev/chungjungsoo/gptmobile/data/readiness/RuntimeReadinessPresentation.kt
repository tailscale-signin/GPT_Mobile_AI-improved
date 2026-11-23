package dev.chungjungsoo.gptmobile.data.readiness

import java.util.Locale

internal data class RuntimeReadinessPresentation(val status: String, val configured: String?, val observed: String?)

/** Loaded configuration and native processor evidence are separate; neither measures performance. */
internal fun runtimeReadinessPresentation(
    hasLoadedModel: Boolean,
    engine: String?,
    requestedProcessor: String?,
    requestedContext: Int?,
    lifecycle: String,
    engineHealth: String,
    loadOutcome: String? = null,
    observedProcessor: String? = null,
    processorAcknowledged: Boolean = false
): RuntimeReadinessPresentation {
    val phase = lifecycle.uppercase(Locale.ROOT)
    val health = engineHealth.uppercase(Locale.ROOT)
    val status = when {
        phase in setOf("CANCELLING", "RECOVERING") -> "Recovering runtime"
        phase in setOf("FAILED", "QUARANTINED") || health in setOf("RESTART_REQUIRED", "RESET_REQUIRED") -> "Runtime needs recovery"
        phase == "LOADING" -> "Preparing model"
        !hasLoadedModel -> "No model loaded"
        health != "HEALTHY" || engine.isNullOrBlank() -> "Runtime status unverified"
        phase == "READY" -> "Loaded · ready for a request"
        phase in setOf("PREFILLING", "DECODING", "WAITING_FOR_TOOL") -> "Model in use"
        else -> "Runtime status unverified"
    }
    val configuration = if (hasLoadedModel) {
        listOfNotNull(
            engine,
            requestedProcessor?.takeIf(String::isNotBlank)?.let { "Requested ${it.uppercase(Locale.ROOT)}" },
            requestedContext?.takeIf { it > 0 }?.let { "$it context requested" }
        ).joinToString(" · ").takeIf(String::isNotBlank)
    } else {
        null
    }
    val current = hasLoadedModel && health == "HEALTHY" && phase in setOf("READY", "PREFILLING", "DECODING", "WAITING_FOR_TOOL")
    val processor = observedProcessor?.trim()?.takeIf {
        it.isNotEmpty() &&
            it.length <= 64 &&
            it.none(Char::isISOControl) &&
            it.lowercase(Locale.ROOT) !in setOf("unknown", "unreported", "unavailable", "not reported")
    }
    val observation = when {
        !current -> null
        processorAcknowledged && loadOutcome?.uppercase(Locale.ROOT) in setOf("READY", "INITIALIZED") && processor != null -> "Processor reported: ${processor.uppercase(Locale.ROOT)}"
        else -> "Processor use not reported by this engine"
    }
    return RuntimeReadinessPresentation(status, configuration, observation)
}
