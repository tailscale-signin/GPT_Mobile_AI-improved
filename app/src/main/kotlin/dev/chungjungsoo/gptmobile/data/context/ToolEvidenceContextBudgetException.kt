package dev.chungjungsoo.gptmobile.data.context

/** Retains the allocation that failed preparation without recomputing or enlarging it. */
internal class ToolEvidenceContextBudgetException private constructor(
    val allocation: ReplayAllowance.Allocation,
    val resolvedContextTokens: Int,
    val requestedOutputTokens: Int,
    val code: String
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
        if (resolvedContextTokens in 1 until Int.MAX_VALUE) CapacityProvenance.UNKNOWN else CapacityProvenance.CONSERVATIVE_POLICY

    companion object {
        fun requireAllocation(allocation: ReplayAllowance.Allocation, resolvedContextTokens: Int, requestedOutputTokens: Int): ReplayAllowance.Allocation {
            if (allocation.output > 0 && allocation.replay > 0) return allocation
            val code = if (requestedOutputTokens <= 0) "OUTPUT_CAPACITY_EXCEEDED" else "CONTEXT_CAPACITY_EXCEEDED"
            throw ToolEvidenceContextBudgetException(allocation, resolvedContextTokens, requestedOutputTokens, code)
        }
    }
}
