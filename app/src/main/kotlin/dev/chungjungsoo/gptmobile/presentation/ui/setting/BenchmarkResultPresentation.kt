package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkBatch
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkCleanupMemoryTrend
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkDiagnosticScenarioSummary
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkExecutionPolicy
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkFailure
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkIntelligenceSummary
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMetricResult
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkPerformanceSummary
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkQueueItem
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkScoreRow
import dev.chungjungsoo.gptmobile.data.benchmark.DynamicScoreEngine
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkCleanupMemoryTrend
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkDiagnosticScenarioSummary
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkIntelligenceSummary
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkMetricResults
import dev.chungjungsoo.gptmobile.data.benchmark.benchmarkPerformanceSummary
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import java.text.DateFormat
import java.util.Date

internal data class BenchmarkResultPresentation(
    val profileUid: String,
    val profileName: String,
    val mode: BenchmarkMode,
    val latestRun: BenchmarkRun?,
    val latestQueueItem: BenchmarkQueueItem?,
    val attemptScore: BenchmarkScoreRow?,
    val attemptMetrics: List<BenchmarkMetricResult>,
    val retainedScore: BenchmarkScoreRow?,
    val lastVerified: BenchmarkScoreRow?,
    val verifiedAt: Long?,
    val executionPolicy: BenchmarkExecutionPolicy? = latestRun?.executionPolicy,
    val intelligence: BenchmarkIntelligenceSummary? = latestRun?.let(::benchmarkIntelligenceSummary),
    val scenarios: BenchmarkDiagnosticScenarioSummary? = latestRun?.let(::benchmarkDiagnosticScenarioSummary),
    val performance: BenchmarkPerformanceSummary? = latestRun?.let { benchmarkPerformanceSummary(it) },
    val cleanupMemory: List<BenchmarkCleanupMemoryTrend> = latestRun?.takeIf { it.local }?.let(::benchmarkCleanupMemoryTrend).orEmpty()
) {
    val identity: String get() = "$profileUid:${mode.name}:${executionPolicy?.name ?: "UNKNOWN"}"
    val failure: BenchmarkFailure? get() = latestQueueItem?.failure ?: latestRun?.samples?.lastOrNull { it.failure != null }?.failure
    val status: String get() = latestQueueItem?.state?.name ?: latestRun?.let { run ->
        run.state.takeUnless { it == "LEGACY_IMPORTED" } ?: when {
            !run.finished -> "RUNNING"
            run.canceled -> "CANCELED"
            run.stoppedReason != null -> "FAILED"
            else -> "COMPLETED"
        }
    } ?: "NOT_ATTEMPTED"
}

/** Current failure-only/blocked attempts do not disappear merely because no score exists. */
internal fun benchmarkResultPresentations(
    profiles: List<PlatformV2>,
    runs: List<BenchmarkRun>,
    batches: List<BenchmarkBatch>,
    rows: List<BenchmarkScoreRow>
): List<BenchmarkResultPresentation> = BenchmarkMode.entries.filter { it !in setOf(BenchmarkMode.DELEGATION, BenchmarkMode.AGENT) }.flatMap { mode ->
    val relevantRuns = runs.filter { it.mode == mode }
    val queueEntries = batches.filter { it.mode == mode }.flatMap { batch -> batch.items.map { Triple(it.startedAt ?: batch.createdAt, it, batch.executionPolicy) } }
    val runById = relevantRuns.associateBy { it.id }
    val attemptScores = mutableMapOf<String, BenchmarkScoreRow?>()
    fun singleAttemptScore(run: BenchmarkRun): BenchmarkScoreRow? = attemptScores.getOrPut(run.id) {
        DynamicScoreEngine.snapshot(listOf(run), 0, run.startedAt).rows.singleOrNull()
    }
    val relevantRows = rows.filter { it.cohort.startsWith(mode.name + "|") }
    val ids = (profiles.map { it.uid } + relevantRuns.map { it.profileUid } + queueEntries.map { it.second.profile.uid }).distinct()
    fun scoreTime(row: BenchmarkScoreRow) = row.sourceRunIds.mapNotNull { runById[it]?.startedAt }.maxOrNull() ?: 0L
    ids.flatMap { uid ->
        val profileRuns = relevantRuns.filter { it.profileUid == uid }
        val profileQueue = queueEntries.filter { it.second.profile.uid == uid }
        val policies = (profileRuns.map { it.executionPolicy } + profileQueue.map { it.third }).distinct().ifEmpty { listOf(BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1) }
        policies.map { policy ->
            val policyRuns = profileRuns.filter { it.executionPolicy == policy }
            val policyQueue = profileQueue.filter { it.third == policy }
            val newestRun = policyRuns.maxByOrNull { it.startedAt }
            val newestQueue = policyQueue.maxByOrNull { it.first }
            val queue = if (newestQueue != null && newestQueue.first >= (newestRun?.startedAt ?: 0)) newestQueue.second else policyQueue.firstOrNull { it.second.runId == newestRun?.id }?.second
            val run = if (queue != null) runById[queue.runId]?.takeIf { it.executionPolicy == policy } else newestRun
            // Every retained source must have the same recorded request policy.
            // An absent legacy policy cannot stand in for a chosen current policy.
            val currentRows = relevantRows.filter { row -> row.profileUid == uid && row.executionPolicy == policy && row.sourceRunIds.isNotEmpty() && row.sourceRunIds.all { source -> source in runById && runById[source]?.executionPolicy == policy } }
            val retained = currentRows.firstOrNull { it.latestAttemptRunId == run?.id }
                ?: currentRows.filter { it.revision == (run?.configKey ?: queue?.configKey) }.maxByOrNull(::scoreTime)
                ?: currentRows.maxByOrNull(::scoreTime)
            val historicalVerified = policyRuns.sortedByDescending { it.startedAt }
                .firstNotNullOfOrNull { candidate -> singleAttemptScore(candidate)?.takeIf { it.verified } }
            val verified = (currentRows.filter { it.verified } + listOfNotNull(historicalVerified)).maxByOrNull(::scoreTime)
            // Current-attempt qualification never comes from a retained older score.
            val attemptScore = run?.let(::singleAttemptScore)
            BenchmarkResultPresentation(
                uid,
                profiles.firstOrNull { it.uid == uid }?.name ?: run?.profileName ?: queue?.profile?.name ?: "Retained profile",
                mode, run, queue, attemptScore, run?.let { benchmarkMetricResults(listOf(it)) }.orEmpty(), retained, verified,
                verified?.let(::scoreTime)?.takeIf { it > 0 }, executionPolicy = policy,
                performance = run?.let { benchmarkPerformanceSummary(it, runs) }
            )
        }
    }
}

internal fun benchmarkResultDate(at: Long?): String = at?.takeIf { it > 0 }?.let { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(it)) } ?: "Date not recorded"
