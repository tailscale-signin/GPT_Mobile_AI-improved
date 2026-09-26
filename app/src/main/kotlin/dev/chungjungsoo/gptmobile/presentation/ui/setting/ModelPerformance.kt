package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import kotlin.math.ceil

data class ModelPerformance(
    val provider: String,
    val model: String,
    val requests: Int,
    val completed: Int,
    val inputTokens: Long,
    val outputTokens: Long,
    val estimatedRequests: Int,
    val medianLatencyMs: Long?,
    val p95LatencyMs: Long?,
    val medianFirstTokenMs: Long?,
    val p95FirstTokenMs: Long?,
    val outputTokensPerSecond: Double?
)

internal fun modelPerformance(invocations: List<ModelInvocation>): List<ModelPerformance> = invocations
    .filter { it.status != "RUNNING" }
    .groupBy { it.provider to it.model }
    .map { (key, items) ->
        val finished = items.filter { it.status == "COMPLETED" }
        val durations = finished.map { it.durationMs.coerceAtLeast(0) }.sorted()
        val first = finished.mapNotNull { it.firstTokenMs?.coerceAtLeast(0) }.sorted()
        val measured = finished.filter { !it.estimated && it.durationMs > 0 }
        ModelPerformance(
            key.first, key.second, items.size, finished.size,
            items.sumOf { it.inputTokens.coerceAtLeast(0).toLong() },
            items.sumOf { it.outputTokens.coerceAtLeast(0).toLong() },
            items.count { it.estimated },
            percentile(durations, .5), percentile(durations, .95),
            percentile(first, .5), percentile(first, .95),
            measured.takeIf { it.isNotEmpty() }?.let { rows -> rows.sumOf { it.outputTokens.toLong() }.toDouble() * 1000 / rows.sumOf { it.durationMs } }
        )
    }.sortedByDescending { it.requests }

private fun percentile(sorted: List<Long>, fraction: Double): Long? = sorted.takeIf { it.isNotEmpty() }
    ?.get((ceil(sorted.size * fraction).toInt() - 1).coerceAtLeast(0))
