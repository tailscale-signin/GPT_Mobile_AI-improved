package dev.chungjungsoo.gptmobile.data.benchmark

import kotlin.math.ceil
import kotlin.math.roundToInt

internal data class WorkerBenchmarkTelemetry(
    val durationMs: Long = 0,
    val firstTextMs: Long? = null,
    val decodeTokensPerSecond: Double? = null,
    val estimated: Boolean = false,
    val outputCapViolations: Int = 0,
    val speedUsesReportedTokens: Boolean = false,
    val events: List<DelegationBenchmarkEvent> = emptyList()
)

data class DelegationBenchmarkRating(
    val score: Int?,
    val attempts: Int,
    val passed: Int,
    val toolTaskSuccessPercent: Double?,
    val toolCallSuccessPercent: Double?,
    val successfulToolCalls: Int,
    val toolCalls: Int,
    val medianLatencyMs: Long?,
    val p95LatencyMs: Long?,
    val medianFirstTextMs: Long?,
    val medianDecodeSpeed: Double?,
    val workerInputTokens: Long,
    val workerOutputTokens: Long,
    val primaryInputTokens: Long,
    val primaryOutputTokens: Long,
    val outputCapViolations: Int,
    val reviewerScore: Int?,
    val reviewerEvaluations: Int,
    val estimated: Boolean,
    val diagnosticEvents: Int,
    val warningEvents: Int,
    val errorEvents: Int,
    val dimensions: List<BenchmarkDimension>,
    val reviewerCalls: Int = 0,
    val reviewerInputTokens: Long = 0,
    val reviewerOutputTokens: Long = 0,
    val reviewerEstimated: Boolean = false
)

data class DelegateRanking(val run: BenchmarkRun, val runs: Int, val rating: DelegationBenchmarkRating)

/** Separate from the primary-model score: failed/no-call tool tasks cannot earn speed points. */
fun delegationBenchmarkRating(runs: List<BenchmarkRun>, speedAnchor: Double? = null, firstAnchor: Long? = null, durationAnchor: Long? = null): DelegationBenchmarkRating {
    val samples = runs.filter { it.mode == BenchmarkMode.DELEGATION && it.finished && !it.canceled }
        .flatMap { it.samples }.filter { it.outcome != BenchmarkOutcome.CANCELED }
    val passed = samples.count { it.outcome == BenchmarkOutcome.PASSED }
    val metrics = samples.mapNotNull { it.delegation }
    val successful = samples.filter { it.outcome == BenchmarkOutcome.PASSED }
    // Transport/runtime errors still reduce reliability, but are not evidence
    // that a model cannot select a tool. Only completed fixture trials measure that.
    val tools = samples.filter { it.testId.startsWith("delegation-tools") && it.outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED) }
    val toolTaskSuccess = tools.takeIf { it.isNotEmpty() }?.let {
        100.0 * it.count { sample ->
            sample.outcome == BenchmarkOutcome.PASSED && (sample.delegation?.successfulFixtureCalls ?: 0) > 0
        } / it.size
    }
    val fixtureCalls = metrics.sumOf { it.fixtureCalls }
    val successfulFixtureCalls = metrics.sumOf { it.successfulFixtureCalls }
    val toolCallSuccess = if (fixtureCalls > 0) 100.0 * successfulFixtureCalls / fixtureCalls else tools.takeIf { it.isNotEmpty() }?.let { 0.0 }
    val toolUsability = toolTaskSuccess?.let { taskRate ->
        taskRate * 0.7 + (toolCallSuccess ?: 0.0) * 0.3
    }
    val latency = median(successful.map { it.durationMs })
    val first = median(successful.mapNotNull { it.delegation?.workerFirstTextMs })
    val speed = median(successful.mapNotNull { it.delegation?.workerDecodeTokensPerSecond?.takeIf { value -> value > 0 && value.isFinite() } })
    fun accuracy(id: String) = samples.filter { it.testId.startsWith(id) }.takeIf { it.isNotEmpty() }
        ?.let { 100.0 * it.count { sample -> sample.outcome == BenchmarkOutcome.PASSED } / it.size }
    val successRate = samples.takeIf { it.isNotEmpty() }?.let { 100.0 * passed / it.size }
    val reviewerValues = metrics.mapNotNull { it.reviewerScore }
    val reviewerScore = reviewerValues.takeIf { it.isNotEmpty() }?.let {
        (it.average().coerceIn(0.0, 100.0) + 0.5).toInt()
    }
    val reviewerEvaluations = metrics.sumOf { it.reviewerEvaluations }
    val local = metrics.firstOrNull()?.workerProvider == "LITERT_LM"
    val quality = samples.filter { it.completed }.takeIf { it.isNotEmpty() }?.let { 100.0 * it.count { sample -> sample.outcome == BenchmarkOutcome.PASSED } / it.size }
    val budget = samples.takeIf { it.isNotEmpty() && metrics.size == it.size }?.let { trials ->
        val compliant = 100.0 * trials.count { sample -> sample.durationMs <= 180_000 && sample.delegation?.outputCapViolations == 0 } / trials.size
        minOf(compliant, metrics.mapNotNull { it.timeEfficiencyPercent }.takeIf { it.isNotEmpty() }?.average() ?: compliant)
    }
    val dimensions = listOf(
        BenchmarkDimension("Final evidence and answer quality", quality, if (local) 35 else 30, "Ground-truth final scenario validators; reviewer self-reports excluded"),
        BenchmarkDimension("Tool selection and handoff integrity", toolUsability, 20, "Correct fixture selection, arguments and outcome"),
        BenchmarkDimension("Task reliability", successRate, if (local) 25 else 20, "Passed scenarios / attempts; operational failures remain visible"),
        BenchmarkDimension("End-to-end latency", DynamicScoreEngine.lower(latency?.toDouble(), durationAnchor?.toDouble()), if (local) 10 else 20, "Compatible scenario pipeline scale"),
        BenchmarkDimension("Budget discipline", budget, 10, "Declared scenario deadline, output cap and measured waste"),
        BenchmarkDimension("Token throughput", speed, 0, "Worker diagnostic only; native tokenizers are not interchangeable"),
        BenchmarkDimension("First-response latency", first?.toDouble(), 0, "Worker first text in milliseconds"),
        BenchmarkDimension("Evidence accuracy", accuracy("delegation-compact"), 0, "Critical identifier, date, unit and negation retention"),
        BenchmarkDimension("Research and handoff", accuracy("delegation-research"), 0, "Final code and source retention"),
        BenchmarkDimension("Time efficiency", metrics.mapNotNull { it.timeEfficiencyPercent }.takeIf { it.isNotEmpty() }?.average(), 0, "Observed productive time"),
        BenchmarkDimension("Reviewer report (diagnostic)", reviewerScore?.toDouble(), 0, "Never contributes to pipeline quality score")
    )
    val mandatory = dimensions.filter { it.weight > 0 }
    val score = if (samples.size >= 24 && samples.map { it.testId }.containsAll(delegationBenchmarkSuite().map { it.id }) && mandatory.all { it.score != null }) {
        minOf(mandatory.sumOf { it.score!! * it.weight } / 100, quality!!, successRate!!).roundToInt()
    } else {
        null
    }
    val events = metrics.flatMap { it.diagnosticEvents }
    return DelegationBenchmarkRating(
        score = score,
        attempts = samples.size,
        passed = passed,
        toolTaskSuccessPercent = toolTaskSuccess,
        toolCallSuccessPercent = toolCallSuccess,
        successfulToolCalls = successfulFixtureCalls,
        toolCalls = fixtureCalls,
        medianLatencyMs = latency,
        p95LatencyMs = percentile(successful.map { it.durationMs }, .95).takeIf { successful.size >= 20 },
        medianFirstTextMs = first,
        medianDecodeSpeed = speed,
        workerInputTokens = metrics.sumOf { it.workerInputTokens },
        workerOutputTokens = metrics.sumOf { it.workerOutputTokens },
        primaryInputTokens = metrics.sumOf { it.primaryInputTokens },
        primaryOutputTokens = metrics.sumOf { it.primaryOutputTokens },
        outputCapViolations = metrics.sumOf { it.outputCapViolations },
        reviewerScore = reviewerScore,
        reviewerEvaluations = reviewerEvaluations,
        estimated = metrics.any { it.workerEstimated || it.primaryEstimated || it.reviewerEstimated },
        diagnosticEvents = events.size,
        warningEvents = events.count { it.level == "WARN" },
        errorEvents = events.count { it.level == "ERROR" },
        dimensions = dimensions,
        reviewerCalls = metrics.sumOf { it.reviewerCalls },
        reviewerInputTokens = metrics.sumOf { it.reviewerInputTokens },
        reviewerOutputTokens = metrics.sumOf { it.reviewerOutputTokens },
        reviewerEstimated = metrics.any { it.reviewerEstimated }
    )
}

/** Rankings compare helpers under the same primary, worker configuration, settings and suite. */
fun delegateRankings(
    history: List<BenchmarkRun>,
    primaryConfigKey: String,
    settings: dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings? = null
): List<DelegateRanking> = history
    .filter { it.mode == BenchmarkMode.DELEGATION && it.suiteVersion == dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSuiteRegistry.VERSION && it.configKey == primaryConfigKey && it.finished && !it.canceled }
    .filter { settings == null || it.delegationSettings?.copy(targetProfileUid = "", fallbackToAnotherProfile = false) == settings.normalized().copy(targetProfileUid = "", fallbackToAnotherProfile = false) }
    .groupBy { run ->
        val worker = run.samples.mapNotNull { it.delegation }.firstOrNull()
        listOf(worker?.workerUid, worker?.workerConfigKey, run.delegationSettings?.toString())
    }
    .values.mapNotNull { group ->
        val runs = group.sortedByDescending { it.startedAt }.take(5)
        if (runs.first().samples.none { it.delegation != null }) return@mapNotNull null
        DelegateRanking(runs.first(), runs.size, delegationBenchmarkRating(runs))
    }
    .let { rows ->
        rows.map { row ->
            val worker = row.run.samples.mapNotNull { it.delegation }.firstOrNull()
            val local = worker?.workerProvider == "LITERT_LM"
            val cohort = rows.filter { candidate ->
                val provider = candidate.run.samples.mapNotNull { it.delegation }.firstOrNull()?.workerProvider
                (provider == "LITERT_LM") == local && (!local || candidate.run.device == row.run.device) && (provider in setOf("LLAMA", "OLLAMA")) == (worker?.workerProvider in setOf("LLAMA", "OLLAMA"))
            }
            val speed = cohort.mapNotNull { it.rating.medianDecodeSpeed }.maxOrNull()
            val first = cohort.mapNotNull { it.rating.medianFirstTextMs }.filter { it > 0 }.minOrNull()
            val duration = cohort.mapNotNull { it.rating.medianLatencyMs }.filter { it > 0 }.minOrNull()
            val sourceRuns = history.filter { run -> run.mode == BenchmarkMode.DELEGATION && run.configKey == row.run.configKey && run.samples.firstOrNull()?.delegation?.workerConfigKey == worker?.workerConfigKey && run.delegationSettings == row.run.delegationSettings }.sortedByDescending { it.startedAt }.take(5)
            row.copy(rating = delegationBenchmarkRating(sourceRuns, speed, first, duration))
        }
    }
    .sortedWith(compareByDescending<DelegateRanking> { it.rating.score ?: -1 }.thenBy { it.rating.medianLatencyMs ?: Long.MAX_VALUE })

private fun <T : Comparable<T>> median(values: List<T>): T? = percentile(values, .5)

private fun <T : Comparable<T>> percentile(values: List<T>, fraction: Double): T? = values.sorted().takeIf { it.isNotEmpty() }
    ?.get((ceil(values.size * fraction).toInt() - 1).coerceAtLeast(0))
