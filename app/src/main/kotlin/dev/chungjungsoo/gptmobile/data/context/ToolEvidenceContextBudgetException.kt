package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.provider.ProviderRouteCapacity
import dev.chungjungsoo.gptmobile.data.research.researchHash

/** Retains the allocation that failed preparation without recomputing or enlarging it. */
internal class ToolEvidenceContextBudgetException private constructor(
    val allocation: ReplayAllowance.Allocation,
    val resolvedContextTokens: Int,
    val requestedOutputTokens: Int,
    val code: String,
    val observation: Observation?
) : IllegalStateException(
    "$code: This request needs positive reply and tool evidence allowances. " +
        "Resolved context=$resolvedContextTokens; allowance=${allocation.ceiling}; input=${allocation.input}; " +
        "safety=${allocation.safety}; requested reply=$requestedOutputTokens; allocated reply=${allocation.output}; " +
        "tool evidence=${allocation.replay} tokens. " +
        "Review the selected route's context and output limits or explicitly reduce input, then start a new request. " +
        "Automatic retry is not allowed."
) {
    val input: Int get() = allocation.input
    val safety: Int get() = allocation.safety
    val ceiling: Int get() = allocation.ceiling
    val output: Int get() = allocation.output
    val replay: Int get() = allocation.replay
    val capacityProvenance: CapacityProvenance =
        if (resolvedContextTokens in 1 until Int.MAX_VALUE) {
            observation?.capacity?.takeIf { it.tokens == resolvedContextTokens }?.provenance ?: CapacityProvenance.UNKNOWN
        } else {
            CapacityProvenance.CONSERVATIVE_POLICY
        }

    /** Only the owning preparation can supply these immutable observations; no settings lookup occurs here. */
    data class Observation(
        val profileHash: String?,
        val modelHash: String?,
        val configuredOutputTokens: Int?,
        val capacity: ProviderRouteCapacity.PrimaryContextDecision,
        val planningContextTokens: Int
    ) {
        internal fun matches(context: Int, allocation: ReplayAllowance.Allocation): Boolean =
            planningContextTokens == context &&
                context in 1..capacity.tokens &&
                if (context < Int.MAX_VALUE) allocation.ceiling == context else allocation.ceiling in 0..4096

        companion object {
            fun capture(profileUid: String, model: String, configuredOutputTokens: Int?, capacity: ProviderRouteCapacity.PrimaryContextDecision, planningContextTokens: Int) =
                Observation(
                    profileUid.takeIf(String::isNotBlank)?.let(::researchHash),
                    model.takeIf(String::isNotBlank)?.let(::researchHash),
                    configuredOutputTokens,
                    capacity,
                    planningContextTokens
                )
        }
    }

    companion object {
        fun requireAllocation(allocation: ReplayAllowance.Allocation, resolvedContextTokens: Int, requestedOutputTokens: Int, observation: Observation? = null): ReplayAllowance.Allocation {
            if (allocation.output > 0 && allocation.replay > 0) return allocation
            val code = if (requestedOutputTokens <= 0) "OUTPUT_CAPACITY_EXCEEDED" else "CONTEXT_CAPACITY_EXCEEDED"
            throw ToolEvidenceContextBudgetException(allocation, resolvedContextTokens, requestedOutputTokens, code, observation?.takeIf { it.matches(resolvedContextTokens, allocation) })
        }
    }
}
