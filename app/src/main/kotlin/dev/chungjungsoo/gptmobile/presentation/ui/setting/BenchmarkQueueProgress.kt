package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueItem
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkCoverage

internal data class BenchmarkQueueProgress(
    val completed: Int,
    val planned: Int?,
    val remaining: Int?,
    val currentCase: String?,
    val executed: Int?,
    val passed: Int?,
    val failed: Int?,
    val skipped: Int?
)

/** Only the accepted run's plan can supply its total; today's suite is a different population. */
internal fun benchmarkQueueProgress(item: BenchmarkQueueItem, run: BenchmarkRun?): BenchmarkQueueProgress {
    val plan = run?.caseManifest?.takeIf { it.isNotEmpty() }
    val total = plan?.size ?: run?.plannedTrials?.takeIf { it > 0 }
    val recorded = run?.samples?.let { samples ->
        if (plan == null) samples.distinctBy { it.testId }.size else samples.map { it.testId }.toSet().intersect(plan.map { it.id }.toSet()).size
    } ?: 0
    val completed = if (run != null) recorded else item.completedCases.coerceAtLeast(0)
    val coverage = run?.let(::benchmarkCoverage)
    return BenchmarkQueueProgress(
        completed,
        total,
        total?.let { (it - completed).coerceAtLeast(0) },
        item.activeCaseId?.let { id -> plan?.firstOrNull { it.id == id }?.label ?: id },
        coverage?.executed,
        coverage?.passed,
        coverage?.failed,
        coverage?.skipped
    )
}
