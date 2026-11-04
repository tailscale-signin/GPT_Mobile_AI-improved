package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource

/** A null dimension was not measured. Reservations and character estimates are never actual usage. */
internal data class RunTokenUsage(val inputTokens: Int?, val outputTokens: Int?, val unmeasuredInvocations: Int) {
    val measuredSubtotalTokens: Int? get() = if (inputTokens != null && outputTokens != null) (inputTokens.toLong() + outputTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null

    val totalTokens: Int? get() = measuredSubtotalTokens.takeIf { unmeasuredInvocations == 0 }

    companion object {
        fun from(records: List<ModelInvocation>): RunTokenUsage {
            val unique = records.associateBy { it.id }.values.filter { it.status != "NOT_DISPATCHED" }
            fun measured(value: Long?, source: String): Long? = value?.takeIf { it >= 0 && measuredUsageSource(source) }
            fun sum(select: (ModelInvocation) -> Long?): Int? {
                val measurements = unique.mapNotNull(select)
                if (measurements.isEmpty()) return null
                val limit = Int.MAX_VALUE.toLong()
                return measurements.fold(0L) { total, value -> (total + value.coerceAtMost(limit)).coerceAtMost(limit) }.toInt()
            }
            return RunTokenUsage(
                sum { measured(it.observedInputTokens, it.inputTokenSource) },
                sum { measured(it.observedOutputTokens, it.outputTokenSource) },
                unique.count { measured(it.observedInputTokens, it.inputTokenSource) == null || measured(it.observedOutputTokens, it.outputTokenSource) == null }
            )
        }
    }
}
