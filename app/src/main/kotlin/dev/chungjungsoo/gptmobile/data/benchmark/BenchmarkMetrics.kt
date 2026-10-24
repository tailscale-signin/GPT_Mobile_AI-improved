package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
data class BenchmarkMetricResult(
    val id: String,
    val value: Double?,
    val unit: String,
    val numerator: Int,
    val denominator: Int,
    val planned: Int,
    val sourceRunIds: List<String>,
    val sourceAttemptIds: List<String>,
    val exclusions: Map<String, Int> = emptyMap(),
    val policyVersion: String = "benchmark-metrics-v3",
    val comparisonContextId: String = "",
    val timestamp: Long = 0,
    val population: String = "observed trials",
    val aggregation: String = "ratio",
    val minimum: Double? = null,
    val maximum: Double? = null,
    val standardDeviation: Double? = null,
    val uniqueFixtureCount: Int = 0,
    val runCount: Int = sourceRunIds.size,
    val lowerQuartile: Double? = null,
    val upperQuartile: Double? = null
)

internal fun benchmarkMetricResults(runs: List<BenchmarkRun>): List<BenchmarkMetricResult> {
    val runIds = runs.map { it.id }
    val samples = runs.flatMap { it.samples }.filterNot { it.warmup }
    val planned = runs.sumOf { run -> maxOf(run.plannedTrials, run.samples.size) - run.samples.count { it.warmup } }
    fun ratio(id: String, numerator: Int, denominator: Int, source: List<BenchmarkSample>): BenchmarkMetricResult = BenchmarkMetricResult(
        id, if (denominator == 0) null else 100.0 * numerator / denominator, "%", numerator, denominator,
        planned, runIds, source.mapNotNull { it.attemptId },
        samples.filterNot { it in source }.groupingBy { it.failure?.code ?: it.failureCategory ?: it.outcome.name }.eachCount(),
        comparisonContextId = runs.firstOrNull()?.let(DynamicScoreEngine::cohort).orEmpty(),
        timestamp = runs.maxOfOrNull { it.endedAt ?: it.startedAt } ?: 0,
        uniqueFixtureCount = source.map { it.testId }.distinct().size,
        population = if (id == "profile_availability") {
            "profiles with known admission outcome; user stops excluded"
        } else if (id == "answer_correctness") {
            "evaluable answers only"
        } else if (id == "delivery_completion") {
            "requests known to have been dispatched"
        } else {
            "planned supported trials"
        }
    )
    val graded = samples.filter { it.completed && it.category != "speed" && it.category != IntelligenceBenchmark.CATEGORY && (it.outputCharacters > 0 || it.preview.isNotBlank()) && it.failureCategory != "FIXTURE_INVALID" }
    val dispatched = samples.filter { it.requestDispatched == true || it.requestDispatched == null && (it.outputCharacters > 0 || it.chunks > 0 || it.rounds.isNotEmpty()) }
    val eligibleProfiles = runs.filter { it.cancellationOrigin !in setOf(BenchmarkStopOrigin.USER_SKIP, BenchmarkStopOrigin.USER_STOP) && it.samples.any { sample -> sample.requestDispatched != null || sample.chunks > 0 || sample.rounds.isNotEmpty() } }
    val admitted = eligibleProfiles.count { run -> run.samples.any { it.requestDispatched == true || it.chunks > 0 || it.rounds.isNotEmpty() } }
    val speed = samples.filter { it.category == "speed" && it.outcome == BenchmarkOutcome.PASSED && it.timingEligible != false && it.referenceSpeed != null }
    fun timing(id: String, selected: List<BenchmarkSample>, values: List<Double>, unit: String): BenchmarkMetricResult {
        val mean = values.takeIf { it.isNotEmpty() }?.average()
        return ratio(id, selected.size, selected.size, selected).copy(value = DynamicScoreEngine.median(values), unit = unit, population = "successful timing trials with a measurable streaming interval", aggregation = "median", minimum = values.minOrNull(), maximum = values.maxOrNull(), standardDeviation = mean?.let { m -> kotlin.math.sqrt(values.map { (it - m) * (it - m) }.average()) }, lowerQuartile = values.sorted().takeIf { it.size >= 4 }?.let { DynamicScoreEngine.median(it.take(it.size / 2)) }, upperQuartile = values.sorted().takeIf { it.size >= 4 }?.let { DynamicScoreEngine.median(it.takeLast(it.size / 2)) })
    }
    val arrivals = samples.filter { it.category == "speed" && it.outcome == BenchmarkOutcome.PASSED && it.firstTextMs != null }
    val reasoning = samples.filter { it.category == IntelligenceBenchmark.CATEGORY && it.completed && it.intelligenceAnswers.isNotEmpty() }
    val reasoningRuns = runs.map { run -> IntelligenceBenchmark.scoreBatches(run.samples, run.mode == BenchmarkMode.QUICK, if (run.suiteVersion >= 7) 3 else 2) }
    val reasoningScores = reasoningRuns.mapNotNull { it.score }
    val reasoningMetric = ratio("reasoning_fixture_accuracy", reasoning.sumOf { it.intelligenceAnswers.values.count { answer -> answer } }, reasoning.sumOf { it.intelligenceAnswers.size }, reasoning).copy(value = DynamicScoreEngine.median(reasoningScores), aggregation = "median of complete run scores", population = "fixed reasoning questions; repeats do not add unique items", uniqueFixtureCount = reasoning.flatMap { sample -> sample.intelligenceAnswers.keys.map { domain -> "${sample.testId}:$domain" } }.distinct().size, minimum = reasoningScores.minOrNull(), maximum = reasoningScores.maxOrNull(), runCount = reasoningScores.size)
    return listOf(
        ratio("answer_correctness", graded.count { it.outcome == BenchmarkOutcome.PASSED }, graded.size, graded),
        ratio("delivery_completion", dispatched.count { it.completed && it.outputCharacters > 0 && it.terminalReceived != false && it.failureCategory != "OUTPUT_PROTOCOL_INVALID" }, dispatched.size, dispatched),
        ratio("profile_availability", admitted, eligibleProfiles.size, eligibleProfiles.flatMap { it.samples }),
        ratio("planned_task_success", samples.count { it.outcome == BenchmarkOutcome.PASSED }, (planned - samples.count { it.outcome == BenchmarkOutcome.UNSUPPORTED }).coerceAtLeast(0), samples),
        timing("reference_decode_rate", speed, speed.mapNotNull { it.referenceSpeed }, "reference text units/s"),
        timing("first_visible_text", arrivals, arrivals.mapNotNull { it.firstTextMs?.toDouble() }, "ms").copy(population = "successful timing answers; streaming and buffered delivery"),
        reasoningMetric
    )
}

internal fun benchmarkResultId(run: BenchmarkRun): String = benchmarkHash(listOf(run.profileUid, run.configKey, run.model, run.runtimeVersion, run.backend, run.accelerator, DynamicScoreEngine.cohort(run), run.scoringVersion, run.fixtureManifestHash).joinToString("|"))
