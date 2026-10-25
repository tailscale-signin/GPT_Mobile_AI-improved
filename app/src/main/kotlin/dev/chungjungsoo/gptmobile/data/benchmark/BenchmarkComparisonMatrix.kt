package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
enum class BenchmarkComparisonStatus { MEASURED, UNSUPPORTED, UNMEASURED, IDENTITY_UNKNOWN, FALLBACK_OBSERVED }

@Serializable
data class BenchmarkComparisonCell(
    val backend: String,
    val status: BenchmarkComparisonStatus,
    val sourceRunIds: List<String>,
    val profileUids: Set<String>,
    val raw: BenchmarkDistribution,
    val qualified: BenchmarkDistribution,
    val comparable: Boolean,
    val dimensionScore: Double?,
    val reasons: List<String>,
    val runtimeVersions: Set<String>,
    val qualifiedTrialCount: Int,
    val aggregation: String = "median_of_eligible_run_medians; distribution count is runs, qualifiedTrialCount is underlying trials"
)

@Serializable
data class BenchmarkComparisonRow(
    val id: String,
    val dimension: BenchmarkPerformanceDimension,
    val unit: String,
    val artifactDigest: String?,
    val modelFamily: String?,
    val quantization: String?,
    val contextTokens: Int?,
    val fixtureDigest: String?,
    val executionPolicy: BenchmarkExecutionPolicy?,
    val cells: List<BenchmarkComparisonCell>,
    val compatibility: String,
    val explanation: String
)

/** Different compiled artifacts never become equivalent from a friendly profile/model name. */
internal fun benchmarkComparisonMatrix(runs: List<BenchmarkRun>): List<BenchmarkComparisonRow> {
    data class Observed(val run: BenchmarkRun, val sample: BenchmarkSample, val metric: BenchmarkPerformanceMetric)
    val observations = runs.flatMap { run ->
        benchmarkPerformanceSummary(run).metrics.mapNotNull { metric ->
            run.samples.firstOrNull { sample -> sample.attemptId in metric.sourceAttemptIds || metric.sourceAttemptIds.isEmpty() && (sample.performanceTrial?.dimension == metric.dimension || sample.category == "speed") && (benchmarkPerformanceComparisonContext(run, sample, metric.dimension) ?: "unknown:${sample.fixtureContentHash ?: sample.testId}:${benchmarkPerformanceObservation(sample, metric.dimension).source}") == metric.comparisonContextId }?.let { Observed(run, it, metric) }
        }
    }
    return observations.groupBy { observation ->
        val context = benchmarkPerformanceComparisonContext(observation.run, observation.sample, observation.metric.dimension, includeBackend = false)
        "${observation.metric.dimension}:" + (context ?: "unknown:${observation.run.id}:${observation.metric.comparisonContextId}")
    }.map { (id, group) ->
        val first = group.first()
        val receipt = first.sample.runtimeExecutions.lastOrNull()
        val identityKnown = benchmarkPerformanceComparisonContext(first.run, first.sample, first.metric.dimension, includeBackend = false) != null
        val backends = listOf("cpu", "gpu", "opencl", "npu")
        val cells = backends.map { backend ->
            val matching = group.filter { observation -> observation.sample.runtimeExecutions.map { benchmarkBackendKind(it.engineLoad?.actualBackend) }.distinct().singleOrNull() == backend }
            val requested = group.filter { benchmarkBackendKind(it.run.requestedAccelerator) == backend }
            val qualified = matching.flatMap { it.metric.qualified.let { statistics -> listOfNotNull(statistics.median).takeIf { statistics.count >= 3 }.orEmpty() } }
            val raw = matching.flatMap { it.metric.raw.let { statistics -> listOfNotNull(statistics.median).takeIf { statistics.count > 0 }.orEmpty() } }
            val nativeCount = matching.sumOf { it.metric.qualified.count }
            val status = when {
                matching.isNotEmpty() && identityKnown -> BenchmarkComparisonStatus.MEASURED
                matching.isNotEmpty() -> BenchmarkComparisonStatus.IDENTITY_UNKNOWN
                requested.any { it.sample.outcome == BenchmarkOutcome.UNSUPPORTED } -> BenchmarkComparisonStatus.UNSUPPORTED
                requested.any { it.sample.runtimeExecutions.any { execution -> execution.knownDispatched && benchmarkBackendKind(execution.engineLoad?.actualBackend) != null && benchmarkBackendKind(execution.engineLoad?.actualBackend) != backend } } -> BenchmarkComparisonStatus.FALLBACK_OBSERVED
                requested.isNotEmpty() -> BenchmarkComparisonStatus.IDENTITY_UNKNOWN
                else -> BenchmarkComparisonStatus.UNMEASURED
            }
            BenchmarkComparisonCell(
                backend, status, matching.map { it.run.id }.distinct(), matching.map { it.run.profileUid }.toSet(), benchmarkDistribution(raw), benchmarkDistribution(qualified), identityKnown && qualified.isNotEmpty() && nativeCount >= 3, null,
                buildList {
                    if (!identityKnown) add("EXACT_ARTIFACT_CONTEXT_PROMPT_AND_EFFECTIVE_SETTINGS_NOT_PROVEN")
                    if (nativeCount < 3) add("AT_LEAST_THREE_QUALIFIED_REPEATS_REQUIRED")
                    if (status == BenchmarkComparisonStatus.UNMEASURED) add("NO_MEASUREMENT_FOR_THIS_EXACT_ARTIFACT_AND_COHORT")
                    if (status == BenchmarkComparisonStatus.FALLBACK_OBSERVED) add("REQUESTED_BACKEND_DID_NOT_EXECUTE")
                    addAll((matching + requested).flatMap { it.metric.exclusions.keys }.distinct())
                },
                matching.flatMap { it.sample.runtimeExecutions.mapNotNull { execution -> execution.engineLoad?.runtimeRevision } }.toSet(), nativeCount
            )
        }
        val comparable = cells.filter { it.comparable }
        val anchor = if (first.metric.dimension.lowerIsBetter) comparable.mapNotNull { it.qualified.median }.minOrNull() else comparable.mapNotNull { it.qualified.median }.maxOrNull()
        val ranked = if (comparable.size >= 2) {
            cells.map { cell ->
                cell.copy(
                    dimensionScore = if (!cell.comparable) {
                        null
                    } else if (first.metric.dimension.lowerIsBetter) {
                        DynamicScoreEngine.lower(cell.qualified.median, anchor)
                    } else {
                        DynamicScoreEngine.higher(cell.qualified.median, anchor)
                    }
                )
            }
        } else {
            cells
        }
        BenchmarkComparisonRow(id, first.metric.dimension, first.metric.unit, receipt?.engineLoad?.artifactDigest, null, null, receipt?.configuration?.contextTokens ?: receipt?.engineLoad?.contextTokens, first.sample.fixtureContentHash, first.run.executionPolicy, ranked, if (identityKnown) "EXACT_ARTIFACT_ONLY" else "UNKNOWN", "Only matching observed artifact, context, prompt, template, effective sampler/output budget, policy and device build are comparable. Family and quantization metadata are unknown when not reported; separately compiled NPU artifacts cannot be silently equated. Scores compare this dimension only and need repeated evidence for at least two backends.")
    }
}
