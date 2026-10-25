package dev.chungjungsoo.gptmobile.data.benchmark

import kotlin.math.ceil
import kotlin.math.sqrt
import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkPerformanceDimension(val label: String, val unit: String, val lowerIsBetter: Boolean) {
    COLD_START("Cold start", "ms", true),
    WARM_START("Warm start", "ms", true),
    PREFILL("Native prefill", "native input tokens/s", false),
    DECODE("Native decode", "native output tokens/s", false),
    FIRST_VISIBLE_TEXT("First visible text", "ms", true),
    SUSTAINED_THROUGHPUT("Sustained native decode", "native output tokens/s", false),
    CLEANUP("Cleanup", "ms", true)
}

@Serializable
data class BenchmarkPerformanceTrial(
    val dimension: BenchmarkPerformanceDimension,
    val repeatIndex: Int,
    val requestedLoadRegime: String = "observed",
    val minimumNativeOutputTokens: Int = 0,
    val acceptsOutputLimit: Boolean = false,
    val deadlineMs: Long = 120_000
)

internal object BenchmarkPerformanceSuite {
    const val VERSION = 1
    const val REPEATS = 3

    fun adapt(plan: List<BenchmarkCase>, contextTokens: Int?): List<BenchmarkCase> {
        if (contextTokens == null) return plan
        val records = ((BenchmarkWorkloadPlanner.contextTier(contextTokens) - 1024).coerceAtLeast(128) * 2 / 60).coerceIn(4, 256)
        return plan.map { test ->
            if (test.performanceTrial?.dimension != BenchmarkPerformanceDimension.PREFILL) test else test.copy(prompt = "Read these synthetic records in order:\n" + (1..records).joinToString("\n") { "Record $it: status retained; measurement fixture only." } + "\nReply with exactly PREFILL_READY and nothing else.")
        }
    }

    fun cases(): List<BenchmarkCase> = (0 until REPEATS).flatMap { repeat ->
        BenchmarkPerformanceDimension.entries.map { dimension ->
            val id = "performance-${dimension.name.lowercase()}-$repeat"
            val prompt = when (dimension) {
                BenchmarkPerformanceDimension.COLD_START, BenchmarkPerformanceDimension.WARM_START -> "Reply with exactly PERFORMANCE_READY and nothing else."
                BenchmarkPerformanceDimension.PREFILL -> "Read these synthetic records in order:\n" + (1..256).joinToString("\n") { "Record $it: status retained; measurement fixture only." } + "\nReply with exactly PREFILL_READY and nothing else."
                BenchmarkPerformanceDimension.DECODE -> "Print the integers from 1 to 300 in increasing order, separated by one space. Do not add a heading or explanation."
                BenchmarkPerformanceDimension.SUSTAINED_THROUGHPUT -> "Print the integers from 1 to 1200 in increasing order, separated by one space. Continue until the output budget is exhausted. Do not add a heading or explanation."
                BenchmarkPerformanceDimension.FIRST_VISIBLE_TEXT -> "Reply with exactly FIRST_READY and nothing else."
                BenchmarkPerformanceDimension.CLEANUP -> "Reply with exactly CLEANUP_READY and nothing else."
            }
            val expected = when (dimension) {
                BenchmarkPerformanceDimension.COLD_START, BenchmarkPerformanceDimension.WARM_START -> "PERFORMANCE_READY"
                BenchmarkPerformanceDimension.PREFILL -> "PREFILL_READY"
                BenchmarkPerformanceDimension.FIRST_VISIBLE_TEXT -> "FIRST_READY"
                BenchmarkPerformanceDimension.CLEANUP -> "CLEANUP_READY"
                else -> null
            }
            BenchmarkCase(
                id, "${dimension.label} ${repeat + 1}/$REPEATS", "performance", prompt, expected, workload = dimension.name.lowercase(),
                validatorVersion = "benchmark-performance-v1", repeatIndex = repeat,
                dependsOn = if (dimension == BenchmarkPerformanceDimension.WARM_START) setOf("performance-cold_start-$repeat") else emptySet(),
                performanceTrial = BenchmarkPerformanceTrial(
                    dimension,
                    repeat,
                    when (dimension) {
                        BenchmarkPerformanceDimension.COLD_START -> "cold"
                        BenchmarkPerformanceDimension.WARM_START -> "warm"
                        else -> "observed"
                    },
                    if (dimension == BenchmarkPerformanceDimension.DECODE) {
                        128
                    } else if (dimension == BenchmarkPerformanceDimension.SUSTAINED_THROUGHPUT) {
                        512
                    } else {
                        0
                    },
                    dimension in setOf(BenchmarkPerformanceDimension.DECODE, BenchmarkPerformanceDimension.SUSTAINED_THROUGHPUT),
                    if (dimension == BenchmarkPerformanceDimension.SUSTAINED_THROUGHPUT) 600_000 else 120_000
                )
            )
        }
    }
}

@Serializable
data class BenchmarkDistribution(val count: Int, val median: Double?, val p95: Double?, val minimum: Double?, val maximum: Double?, val variance: Double?, val standardDeviation: Double?, val percentileMethod: String = "nearest_rank_descriptive")

@Serializable
data class BenchmarkPerformanceMetric(
    val dimension: BenchmarkPerformanceDimension,
    val unit: String,
    val comparisonContextId: String,
    val raw: BenchmarkDistribution,
    val qualified: BenchmarkDistribution,
    val repeatCount: Int,
    val uniqueFixtureCount: Int,
    val sourceRunIds: List<String>,
    val sourceAttemptIds: List<String>,
    val exclusions: Map<String, Int>,
    val previousRunId: String? = null,
    val previousMedian: Double? = null,
    val changePercent: Double? = null,
    val policy: String = "benchmark-independent-performance-v1",
    val rawObservations: List<BenchmarkPerformanceObservation> = emptyList(),
    val measurementSources: Map<String, Int> = emptyMap()
)

@Serializable
data class BenchmarkPerformanceSummary(
    val runId: String,
    val executionPolicy: BenchmarkExecutionPolicy?,
    val metrics: List<BenchmarkPerformanceMetric>,
    val thermalStatuses: Set<Int>,
    val batteryBefore: Int?,
    val batteryAfter: Int?,
    val peakWorkerPssBytes: Long?,
    val peakClientPssKb: Long?,
    val runtimeVersions: Set<String>,
    val limitation: String = "Native tokens depend on the model tokenizer. Quantiles describe observed repeats; one observation is not a ranking. Battery percentage is context, not energy consumption."
)

internal fun benchmarkDistribution(input: List<Double>): BenchmarkDistribution {
    val values = input.filter { it.isFinite() && it >= 0 }.sorted()
    val mean = values.takeIf { it.isNotEmpty() }?.average()
    val variance = if (mean != null && values.size > 1) values.sumOf { (it - mean) * (it - mean) } / (values.size - 1) else null
    val p95 = values.takeIf { it.size >= 3 }?.let { observed -> observed[(ceil(observed.size * .95).toInt() - 1).coerceAtLeast(0)] }
    return BenchmarkDistribution(values.size, DynamicScoreEngine.median(values), p95, values.minOrNull(), values.maxOrNull(), variance, variance?.let(::sqrt))
}

@Serializable
data class BenchmarkPerformanceObservation(
    val testId: String,
    val attemptId: String?,
    val value: Double?,
    val source: String,
    val scope: String,
    val segmentCount: Int
)

/** A reported SDK/native rate remains a rate; no duration or processed-token count is fabricated. */
internal fun benchmarkPerformanceObservation(sample: BenchmarkSample, dimension: BenchmarkPerformanceDimension): BenchmarkPerformanceObservation {
    val native = sample.localSegments.mapNotNull { it.native }
    var source = "client_monotonic_observation"
    var scope = "logical_case"
    val value = when (dimension) {
        BenchmarkPerformanceDimension.COLD_START, BenchmarkPerformanceDimension.WARM_START -> sample.durationMs.takeIf { sample.knownExecuted() && it > 0 }?.toDouble()
        BenchmarkPerformanceDimension.FIRST_VISIBLE_TEXT -> sample.firstTextMs?.takeIf { it >= 0 }?.toDouble()
        BenchmarkPerformanceDimension.CLEANUP -> sample.cleanupDurationMs?.takeIf { it >= 0 }?.toDouble()
        else -> {
            val prefill = dimension == BenchmarkPerformanceDimension.PREFILL
            val complete = native.isNotEmpty() && native.size == sample.localSegments.size
            val measured = complete && native.all { metric -> if (prefill) metric.processedPromptTokens != null && metric.processedPromptTokens >= 0 && (metric.prefillDurationMs ?: 0) > 0 else metric.decodeTokens >= 0 && (metric.decodeDurationMs ?: 0) > 0 }
            when {
                measured -> {
                    source = if (prefill) "measured_processed_input_count_over_duration" else "measured_native_output_count_over_duration"
                    scope = "sum_of_complete_native_segments"
                    val count = native.sumOf { if (prefill) requireNotNull(it.processedPromptTokens).toLong() else it.decodeTokens.toLong() }
                    val duration = native.sumOf { if (prefill) requireNotNull(it.prefillDurationMs) else requireNotNull(it.decodeDurationMs) }
                    count * 1000.0 / duration
                }
                complete && native.size == 1 -> {
                    source = "native_reported_rate"
                    scope = if (prefill) "single_native_segment; processed_input_basis_unreported" else "single_native_segment; duration_unreported"
                    (if (prefill) native.single().prefillTokensPerSecond else native.single().decodeTokensPerSecond).takeIf { it.isFinite() && it > 0 }
                }
                else -> {
                    source = "unknown"
                    scope = "aggregate_unavailable_without_complete_coherent_segment_counts_and_durations; raw_segment_rates_retained"
                    null
                }
            }
        }
    }?.takeIf { it.isFinite() && it >= 0 }
    return BenchmarkPerformanceObservation(sample.testId, sample.attemptId, value, source, scope, native.size)
}

internal fun benchmarkPerformanceComparisonContext(run: BenchmarkRun, sample: BenchmarkSample, dimension: BenchmarkPerformanceDimension, includeBackend: Boolean = true): String? =
    benchmarkPerformanceContext(run, sample, includeBackend)?.let { benchmarkHash(it + "|" + benchmarkPerformanceObservation(sample, dimension).source) }

internal fun benchmarkPerformanceContext(run: BenchmarkRun, sample: BenchmarkSample, includeBackend: Boolean = true): String? {
    val entered = sample.runtimeExecutions.filter { it.knownDispatched }
    if (entered.isEmpty()) return null
    val identities = entered.map { execution ->
        val load = execution.engineLoad ?: return null
        val config = execution.configuration ?: return null
        val artifact = load.artifactDigest.takeIf { it.isNotBlank() } ?: return null
        val context = config.contextTokens ?: load.contextTokens ?: return null
        val sampler = config.effectiveSampler ?: return null
        if ("sampler.temperature" !in config.nativeAcknowledgedFields || "effectiveOutputTokens" !in config.nativeAcknowledgedFields) return null
        val samplerIdentity = if (sampler.temperature == 0f) {
            "greedy:0"
        } else {
            if (!config.nativeAcknowledgedFields.containsAll(setOf("sampler.topK", "sampler.topP"))) return null
            "temperature:${sampler.temperature}|topK:${sampler.topK}|topP:${sampler.topP}"
        }
        val cap = config.effectiveOutputTokens ?: return null
        val template = config.templateIdentity ?: load.templateIdentity ?: return null
        val runtime = load.runtimeRevision.takeIf { it.isNotBlank() } ?: return null
        val backend = benchmarkBackendKind(load.actualBackend) ?: return null
        val fixture = sample.fixtureContentHash ?: return null
        val renderedPrompt = config.renderedPromptDigest?.takeIf { it.isNotBlank() } ?: return null
        if ("renderedPromptDigest" !in config.nativeAcknowledgedFields) return null
        if (sample.cacheRegime !in setOf("observed_uncached", "observed_cached_prefix")) return null
        val policy = run.executionPolicy ?: return null
        val deviceBuild = run.deviceBuild ?: return null
        listOf("native-metrics-requested-v1", run.measurementVersion, run.deadlinePolicy, sample.deadlineReceipt?.totalRequestMs, sample.validatorVersion, run.fixtureSetVersion, run.mode, artifact, context, samplerIdentity, cap, template, renderedPrompt, fixture, policy, run.structuredOutputMode, run.structuredSchemaModes.toSortedMap(), deviceBuild, sample.cacheRegime, sample.performanceTrial?.requestedLoadRegime ?: sample.loadRegime) + if (includeBackend) listOf(runtime, backend, load.driverIdentity ?: "unknown_driver") else emptyList()
    }.map { it.joinToString("\u0000") }.distinct()
    return identities.singleOrNull()?.let(::benchmarkHash)
}

private fun performanceExclusions(run: BenchmarkRun, sample: BenchmarkSample, dimension: BenchmarkPerformanceDimension): List<String> = buildList {
    if (sample.outcome != BenchmarkOutcome.PASSED) add("TASK_OR_EXECUTION_NOT_PASSED")
    if (sample.runtimeExecutions.isEmpty() || sample.runtimeExecutions.any { !it.knownDispatched || !it.hasNativeTerminal }) add("NATIVE_EXECUTION_OR_TERMINAL_UNKNOWN")
    if (sample.runtimeCleanup?.safeForSuccessor != true) add("CLEANUP_NOT_CONFIRMED")
    benchmarkReadinessExclusion(run)?.let(::add)
    if (benchmarkPerformanceContext(run, sample) == null) add("COMPARISON_IDENTITY_OR_EFFECTIVE_SETTINGS_UNKNOWN")
    if (dimension == BenchmarkPerformanceDimension.PREFILL && benchmarkPerformanceObservation(sample, dimension).source == "native_reported_rate") add("PROCESSED_INPUT_BASIS_NOT_REPORTED")
    if (dimension == BenchmarkPerformanceDimension.COLD_START && sample.loadRegime != "cold") add("COLD_ENGINE_NOT_OBSERVED")
    if (dimension == BenchmarkPerformanceDimension.WARM_START && sample.residentMatch?.exactMatch != true) add("EXACT_WARM_ENGINE_NOT_OBSERVED")
    val minimum = sample.performanceTrial?.minimumNativeOutputTokens ?: 0
    if (minimum > 0 && sample.localSegments.sumOf { it.native?.decodeTokens?.coerceAtLeast(0) ?: 0 } < minimum) add("INSUFFICIENT_NATIVE_OUTPUT")
    val requested = benchmarkBackendKind(run.requestedAccelerator)
    if (requested != null && sample.runtimeExecutions.any { benchmarkBackendKind(it.engineLoad?.actualBackend) != requested }) add("REQUESTED_BACKEND_NOT_EXECUTED")
    if (sample.runtimeExecutions.any { benchmarkBackendKind(it.engineLoad?.actualBackend) in setOf("gpu", "opencl") && (it.engineLoad?.actualOffloadLayers ?: 0) <= 0 }) add("GPU_OFFLOAD_NOT_PROVEN")
}

internal fun benchmarkPerformanceSummary(run: BenchmarkRun, history: List<BenchmarkRun> = emptyList()): BenchmarkPerformanceSummary {
    val candidates = run.samples.filter { it.performanceTrial != null || it.category == "speed" && !it.warmup }
    val observations = candidates.flatMap { sample ->
        val dimensions = sample.performanceTrial?.let { listOf(it.dimension) } ?: listOf(BenchmarkPerformanceDimension.FIRST_VISIBLE_TEXT, BenchmarkPerformanceDimension.PREFILL, BenchmarkPerformanceDimension.DECODE, BenchmarkPerformanceDimension.CLEANUP)
        dimensions.map { dimension -> Triple(sample, dimension, benchmarkPerformanceObservation(sample, dimension)) }
    }
    val metrics = observations.groupBy { (sample, dimension, _) -> dimension to (benchmarkPerformanceComparisonContext(run, sample, dimension) ?: "unknown:${sample.fixtureContentHash ?: sample.testId}:${benchmarkPerformanceObservation(sample, dimension).source}") }.map { (key, observations) ->
        val (dimension, cohort) = key
        val exclusions = observations.flatMap { (sample, _, value) -> performanceExclusions(run, sample, dimension) + if (value.value == null) listOf("MEASUREMENT_UNAVAILABLE") else emptyList() }.groupingBy { it }.eachCount()
        val raw = benchmarkDistribution(observations.mapNotNull { it.third.value })
        val qualifiedSamples = observations.filter { (sample, _, value) -> value.value != null && performanceExclusions(run, sample, dimension).isEmpty() }
        val qualified = benchmarkDistribution(qualifiedSamples.mapNotNull { it.third.value })
        val prior = history.filter { it.id != run.id && it.profileUid == run.profileUid && it.startedAt < run.startedAt }.sortedByDescending { it.startedAt }.firstNotNullOfOrNull { earlier ->
            benchmarkPerformanceSummary(earlier).metrics.firstOrNull { it.dimension == dimension && it.comparisonContextId == cohort && it.qualified.count >= 3 }?.let { earlier.id to it.qualified.median }
        }
        BenchmarkPerformanceMetric(dimension, dimension.unit, cohort, raw, qualified, observations.mapNotNull { it.first.repeatIndex }.distinct().size, observations.mapNotNull { it.first.fixtureContentHash }.distinct().size, listOf(run.id), observations.mapNotNull { it.first.attemptId }, exclusions, prior?.first, prior?.second, if (qualified.count >= 3 && qualified.median != null && prior?.second?.let { it > 0 } == true) 100.0 * (qualified.median - requireNotNull(prior.second)) / requireNotNull(prior.second) else null, rawObservations = observations.map { it.third }, measurementSources = observations.groupingBy { it.third.source }.eachCount())
    }
    return BenchmarkPerformanceSummary(run.id, run.executionPolicy, metrics, (run.resources.mapNotNull { it.thermalStatus } + listOfNotNull(run.thermalBefore, run.thermalAfter)).toSet(), run.batteryBefore, run.batteryAfter, run.peakExecutionWorkerPssBytes, run.peakExecutionClientPssKb ?: run.peakClientPssKb, run.samples.flatMap { it.runtimeExecutions.mapNotNull { receipt -> receipt.engineLoad?.runtimeRevision } }.toSet())
}

@Serializable
data class BenchmarkCleanupMemoryTrend(
    val status: String,
    val observations: Int,
    val sourceAttemptIds: List<String>,
    val workerGenerationId: String?,
    val engineInstanceId: String?,
    val firstPssBytes: Long?,
    val lastPssBytes: Long?,
    val growthBytes: Long?,
    val explanation: String
)

internal fun benchmarkCleanupMemoryTrend(run: BenchmarkRun): List<BenchmarkCleanupMemoryTrend> {
    val grouped = run.samples.mapNotNull { sample ->
        val cleanup = sample.runtimeCleanup ?: return@mapNotNull null
        val pss = run.resources.lastOrNull { it.testId == sample.testId && it.scope == "after_cleanup" }?.workerPssBytes ?: return@mapNotNull null
        if (!cleanup.safeForSuccessor || cleanup.engineHealthy != true || cleanup.engineReleased == true || cleanup.workerGenerationId == null || cleanup.engineInstanceId == null) return@mapNotNull null
        Triple(sample, cleanup, pss)
    }.groupBy { (_, cleanup, _) -> cleanup.workerGenerationId to cleanup.engineInstanceId }
    if (grouped.isEmpty()) return listOf(BenchmarkCleanupMemoryTrend("UNKNOWN", 0, emptyList(), null, null, null, null, null, "No comparable positive cleanup receipts with post-cleanup worker PSS; no leak conclusion is possible."))
    return grouped.map { (identity, observations) ->
        val values = observations.map { it.third }
        val growth = values.last() - values.first()
        val sustained = values.size >= 4 && values.zipWithNext().all { (a, b) -> b > a } && growth > 32L * 1024 * 1024
        BenchmarkCleanupMemoryTrend(
            if (sustained) {
                "SUSTAINED_GROWTH_OBSERVED"
            } else if (values.size < 4) {
                "INSUFFICIENT_REPEATS"
            } else {
                "NO_SUSTAINED_GROWTH_OBSERVED"
            },
            values.size, observations.mapNotNull { it.first.attemptId }, identity.first, identity.second, values.first(), values.last(), growth, "Same observed worker and healthy resident engine after confirmed cleanup. Growth is an observation requiring investigation, not proof of a memory leak."
        )
    }
}

/** Counted performance still retains instruction failures beside raw timing measurements. */
internal fun benchmarkPerformanceAnswerValid(test: BenchmarkCase, answer: String): Boolean {
    test.expected?.let { return answer == it }
    if (test.performanceTrial?.dimension !in setOf(BenchmarkPerformanceDimension.DECODE, BenchmarkPerformanceDimension.SUSTAINED_THROUGHPUT)) return answer.isNotBlank()
    val tokens = answer.trim().split(Regex("\\s+"))
    return tokens.isNotEmpty() && tokens.withIndex().all { (index, value) -> value == (index + 1).toString() }
}
