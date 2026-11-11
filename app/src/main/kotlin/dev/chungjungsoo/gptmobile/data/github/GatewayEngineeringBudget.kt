package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.EngineeringAdaptivePolicy
import dev.chungjungsoo.gptmobile.data.context.EngineeringMode
import dev.chungjungsoo.gptmobile.data.context.EngineeringProfileSettings
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Model invocations and their aggregate token allowance, separate from native tool-call limits. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class GatewayEngineeringBudget(
    @SerialName("model_calls") val modelCalls: Int,
    @SerialName("model_tokens") val modelTokens: Int,
    @EncodeDefault @SerialName("verification_calls") val verificationCalls: Int = 2,
    @EncodeDefault @SerialName("final_calls") val finalCalls: Int = 1,
    @EncodeDefault @SerialName("final_seconds") val finalSeconds: Int = 30
) {
    init {
        require(modelCalls in 1..512 && modelTokens in 1..8_388_608)
        require(verificationCalls in 1..256 && finalCalls in 1..256 && finalSeconds in 1..900)
    }
}

@Serializable
data class GatewayEngineeringBudgetSelection(
    val requestedDurationHours: Int,
    val effectiveDurationHours: Int,
    val requestedBudget: GatewayEngineeringBudget,
    val effectiveBudget: GatewayEngineeringBudget
) {
    val limitedByHost: Boolean get() = requestedDurationHours != effectiveDurationHours || requestedBudget != effectiveBudget
}

internal fun GatewayEngineeringSettings.resolveBudget(
    capability: GatewayEngineeringCapabilities,
    mode: EngineeringMode,
    objective: String,
    totalRunTokens: Int = Int.MAX_VALUE
): GatewayEngineeringBudgetSelection {
    val selected = EngineeringAdaptivePolicy.mode(EngineeringProfileSettings(mode = mode), objective)
    val calls = when (selected) {
        EngineeringMode.QUICK -> 32
        EngineeringMode.OFF, EngineeringMode.STANDARD -> 64
        EngineeringMode.DEEP -> 128
        EngineeringMode.ENGINEERING_MAX, EngineeringMode.ADAPTIVE -> 256
        EngineeringMode.ULTRA_MAX -> 512
    }
    val proposed = executionBudget ?: GatewayEngineeringBudget(calls, calls * 4096)
    val requested = proposed.copy(modelTokens = minOf(proposed.modelTokens, totalRunTokens.takeIf { it > 0 } ?: Int.MAX_VALUE))
    val limits = capability.executionBudgetLimits ?: GatewayEngineeringBudget(256, 8_388_608, 256, 256, 900)
    val effective = requested.copy(
        modelCalls = minOf(requested.modelCalls, limits.modelCalls),
        modelTokens = minOf(requested.modelTokens, limits.modelTokens),
        verificationCalls = minOf(requested.verificationCalls, limits.verificationCalls),
        finalCalls = minOf(requested.finalCalls, limits.finalCalls),
        finalSeconds = minOf(requested.finalSeconds, limits.finalSeconds)
    )
    val hours = if (durationHours == 24 && durationHours !in capability.durationHours) {
        capability.durationHours.filter { it in 1..24 }.maxOrNull()
            ?: error("The gateway does not advertise a bounded engineering duration. Update it before starting a job.")
    } else {
        check(durationHours in capability.durationHours) { "The gateway does not support the saved engineering duration. Review its capabilities before starting a job." }
        durationHours
    }
    return GatewayEngineeringBudgetSelection(durationHours, hours, requested, effective)
}

/** Report the observed server allowance when available; a local checkpoint is only a request. */
fun GatewayEngineeringBinding.budgetDescription(): String? {
    val accepted = job?.executionBudget
    val budget = accepted ?: budgetSelection?.effectiveBudget ?: request.executionBudget ?: return null
    val hours = job?.durationHours ?: budgetSelection?.effectiveDurationHours ?: request.durationHours
    val duration = if (hours == 0) "bounded until-complete" else "up to $hours hours"
    val origin = if (accepted != null) "Accepted" else "Requested"
    val limitation = if (budgetSelection?.limitedByHost == true) " This gateway supports a lower allowance than the selected preset." else ""
    return "$origin gateway allowance: $duration, ${budget.modelCalls} model calls and ${budget.modelTokens} model tokens.$limitation"
}
