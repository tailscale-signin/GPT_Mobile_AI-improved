package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource

/** A null dimension was not measured. Reservations and character estimates are never actual usage. */
internal data class RunTokenUsage(val inputTokens: Int?, val outputTokens: Int?, val unmeasuredInvocations: Int) {
    val measuredSubtotalTokens: Int? get() = if (inputTokens != null && outputTokens != null) (inputTokens.toLong() + outputTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null

    val totalTokens: Int? get() = measuredSubtotalTokens.takeIf { unmeasuredInvocations == 0 }

    companion object {
        fun from(records: List<ModelInvocation>): RunTokenUsage {
            val unique = records.associateBy { it.id }.values.filter { it.status != "NOT_DISPATCHED" }
            fun sum(select: (ModelInvocation) -> Long?): Int? {
                val measured = unique.mapNotNull(select)
                return measured.takeIf { it.isNotEmpty() }?.sum()?.coerceIn(0, Int.MAX_VALUE.toLong())?.toInt()
            }
            return RunTokenUsage(
                sum { it.observedInputTokens?.takeIf { _ -> measuredUsageSource(it.inputTokenSource) } },
                sum { it.observedOutputTokens?.takeIf { _ -> measuredUsageSource(it.outputTokenSource) } },
                unique.count { it.observedInputTokens == null || it.observedOutputTokens == null || !measuredUsageSource(it.inputTokenSource) || !measuredUsageSource(it.outputTokenSource) }
            )
        }
    }
}
