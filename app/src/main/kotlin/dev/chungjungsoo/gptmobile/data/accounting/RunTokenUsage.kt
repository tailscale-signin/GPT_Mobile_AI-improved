package dev.chungjungsoo.gptmobile.data.accounting

import dev.chungjungsoo.gptmobile.data.agent.measuredUsageSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A null dimension was not measured. Reservations and character estimates are never actual usage. */
internal data class RunTokenUsage(val inputTokens: Int?, val outputTokens: Int?, val unmeasuredInvocations: Int, val basis: String = "PROVIDER_OBSERVATIONS") {
    val measuredSubtotalTokens: Int? get() = if (inputTokens != null && outputTokens != null) (inputTokens.toLong() + outputTokens).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() else null

    val totalTokens: Int? get() = measuredSubtotalTokens.takeIf { unmeasuredInvocations == 0 }

    companion object {
        fun from(records: List<ModelInvocation>): RunTokenUsage {
            val distinct = records.associateBy { it.id }.values
            val unique = distinct.filterNot {
                it.status == "NOT_DISPATCHED" &&
                    it.inputTokenSource == "not_dispatched" &&
                    it.outputTokenSource == "not_dispatched" &&
                    it.inputTokens == 0 &&
                    it.outputTokens == 0 &&
                    (it.observedInputTokens ?: 0L) == 0L &&
                    (it.observedOutputTokens ?: 0L) == 0L
            }
            // A retained no-dispatch receipt proves zero provider consumption. An
            // empty ledger or an unmeasured dispatched call does not prove zero.
            if (distinct.isNotEmpty() && unique.isEmpty()) return RunTokenUsage(0, 0, 0, "NOT_DISPATCHED")
            fun measured(value: Long?, source: String): Long? = value?.takeIf { it >= 0 && measuredUsageSource(source) }
            val observations = unique.associate { record ->
                record.id to record.usageEvidenceJson?.let { runCatching { Json.parseToJsonElement(it) as? JsonObject }.getOrNull() }
                    ?.takeIf { it["invocation_id"] == JsonPrimitive(record.id) }
            }
            fun source(record: ModelInvocation, dimension: String, fallback: String): String =
                ((observations[record.id]?.get(dimension) as? JsonObject)?.get("source") as? JsonPrimitive)?.content ?: fallback
            fun known(value: Long?, source: String): Long? = value?.takeIf {
                it >= 0 && (measuredUsageSource(source) || source == "gateway_reported_partial")
            }
            fun sum(select: (ModelInvocation) -> Long?): Int? {
                val measurements = unique.mapNotNull(select)
                if (measurements.isEmpty()) return null
                val limit = Int.MAX_VALUE.toLong()
                return measurements.fold(0L) { total, value -> (total + value.coerceAtMost(limit)).coerceAtMost(limit) }.toInt()
            }
            return RunTokenUsage(
                sum { known(it.observedInputTokens, source(it, "input", it.inputTokenSource)) },
                sum { known(it.observedOutputTokens, source(it, "output", it.outputTokenSource)) },
                unique.count { observations[it.id]?.get("recovery_usage_complete") == JsonPrimitive(false) || measured(it.observedInputTokens, source(it, "input", it.inputTokenSource)) == null || measured(it.observedOutputTokens, source(it, "output", it.outputTokenSource)) == null }
            )
        }
    }
}
