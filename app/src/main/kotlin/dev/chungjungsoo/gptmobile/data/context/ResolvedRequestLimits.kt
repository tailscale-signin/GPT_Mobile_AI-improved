package dev.chungjungsoo.gptmobile.data.context

import kotlinx.serialization.Serializable

@Serializable
enum class OutputLimitSource { USER_PROFILE, REQUEST_RESTRICTION, PROVIDER_CEILING, APPLICATION_TARGET }

@Serializable
data class OutputLimitDecision(val tokens: Int?, val sources: Set<OutputLimitSource>, val applicationTarget: Int?)

/** A default is a target, never evidence that a user explicitly selected a cap. */
object RequestLimitResolver {
    fun output(profile: Int?, restriction: Int?, provider: Int? = null, applicationTarget: Int? = null): OutputLimitDecision {
        val ceilings = listOfNotNull(
            profile?.takeIf { it > 0 }?.let { it to OutputLimitSource.USER_PROFILE },
            restriction?.takeIf { it > 0 }?.let { it to OutputLimitSource.REQUEST_RESTRICTION },
            provider?.takeIf { it > 0 }?.let { it to OutputLimitSource.PROVIDER_CEILING }
        )
        val cap = ceilings.minOfOrNull { it.first }
        val target = applicationTarget?.takeIf { it > 0 }
        val effective = listOfNotNull(cap, target).minOrNull()
        val sources = ceilings.filter { it.first == effective }.map { it.second }.toMutableSet()
        if (effective != null && effective == target) sources += OutputLimitSource.APPLICATION_TARGET
        return OutputLimitDecision(effective, sources, target)
    }
}
