package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

internal val benchmarkOperationalMetricIds = setOf("profile_availability", "delivery_completion", "planned_task_success", "operational_delivery_including_warmups", "physical_generation_terminal_receipt")

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
    val upperQuartile: Double? = null,
    val sourceCohortIds: List<String> = emptyList(),
    val plannedKnown: Boolean = true,
    /** Affected cases are not incident counts; legacy failures without causal IDs remain unknown. */
    val rootIncidentCount: Int? = null,
    val rootFailureEventIds: List<String> = emptyList(),
    val affectedCaseCount: Int = 0
)

internal fun benchmarkMetricResults(runs: List<BenchmarkRun>, operationalContexts: Boolean = false): List<BenchmarkMetricResult> {
    val domains = runs.groupBy { run -> if (operationalContexts) listOf(run.profileUid, run.configKey, run.mode, run.suiteVersion, run.measurementVersion, run.scoringVersion, run.executionPolicy, run.structuredOutputMode, run.structuredSchemaModes.toSortedMap()).joinToString("|") else DynamicScoreEngine.cohort(run) }
    if (domains.size > 1) return domains.values.flatMap { benchmarkMetricResults(it, operationalContexts) }
    val runIds = runs.map { it.id }
    val cohorts = runs.map(DynamicScoreEngine::cohort).distinct()
    val samples = runs.flatMap { it.samples }.filterNot { it.warmup }
    val plannedCounts = runs.map(::benchmarkPlannedMeasuredTrials)
    val planned = plannedCounts.filterNotNull().sum()
    val plannedKnown = plannedCounts.all { it != null }
    val incidentCounts = benchmarkIncidentCounts(runs)
    fun ratio(id: String, numerator: Int, denominator: Int, source: List<BenchmarkSample>): BenchmarkMetricResult = BenchmarkMetricResult(
        id, if (denominator == 0) null else 100.0 * numerator / denominator, "%", numerator, denominator,
        planned, runIds, source.mapNotNull { it.attemptId },
        samples.filterNot { it in source }.groupingBy { benchmarkMetricExclusion(id, it) }.eachCount(),
        policyVersion = if (runs.all { it.measurementVersion >= 4 }) "benchmark-metrics-v5" else "benchmark-metrics-v3",
        comparisonContextId = if (operationalContexts) "operational:" + domains.keys.firstOrNull().orEmpty() else cohorts.singleOrNull().orEmpty(),
        timestamp = runs.maxOfOrNull { it.endedAt ?: it.startedAt } ?: 0,
        uniqueFixtureCount = source.map { it.fixtureContentHash ?: it.testId }.distinct().size,
        plannedKnown = plannedKnown,
        rootIncidentCount = incidentCounts.count,
        rootFailureEventIds = incidentCounts.eventIds,
        affectedCaseCount = incidentCounts.affectedCases,
        sourceCohortIds = cohorts,
        population = if (id == "profile_availability") {
            "profiles with known admission outcome; user stops excluded"
        } else if (id == "answer_correctness") {
            "evaluable answers only"
        } else if (id == "delivery_completion") {
            "requests that reached the physical dispatch boundary or produced a provider response; not proof of model execution"
        } else {
            "planned supported non-warmup trials; progress is not answer accuracy"
        }
    )
    val graded = samples.filter { it.completed && it.category !in setOf("speed", "performance", IntelligenceBenchmark.CATEGORY) && (it.outputCharacters > 0 || it.preview.isNotBlank()) && it.failureCategory != "FIXTURE_INVALID" }
    val dispatched = samples.filter { it.requestDispatched == true || it.providerHttpStatus != null || it.knownExecuted() }
    val eligibleProfiles = runs.filter { it.cancellationOrigin !in setOf(BenchmarkStopOrigin.USER_SKIP, BenchmarkStopOrigin.USER_STOP) && it.samples.any { sample -> sample.requestAdmitted != null || sample.requestDispatched == false || sample.knownExecuted() } }
    val admitted = eligibleProfiles.count { run -> run.samples.any { it.requestAdmitted == true || it.knownExecuted() } }
    val qualifiedSamples = runs.filter { benchmarkTimingExecutionExclusion(it) == null }.flatMap { it.samples }.filterNot { it.warmup }
    val contextExclusions = runs.mapNotNull(::benchmarkTimingExecutionExclusion).groupingBy { it }.eachCount()
    val speed = qualifiedSamples.filter { it.category == "speed" && it.outcome == BenchmarkOutcome.PASSED && it.timingEligible != false && it.referenceSpeed != null }
    fun timing(id: String, selected: List<BenchmarkSample>, values: List<Double>, unit: String): BenchmarkMetricResult {
        val mean = values.takeIf { it.isNotEmpty() }?.average()
        return ratio(id, selected.size, selected.size, selected).copy(value = DynamicScoreEngine.median(values), unit = unit, population = "successful timing trials with a measurable streaming interval", aggregation = "median", minimum = values.minOrNull(), maximum = values.maxOrNull(), standardDeviation = mean?.let { m -> kotlin.math.sqrt(values.map { (it - m) * (it - m) }.average()) }, lowerQuartile = values.sorted().takeIf { it.size >= 4 }?.let { DynamicScoreEngine.median(it.take(it.size / 2)) }, upperQuartile = values.sorted().takeIf { it.size >= 4 }?.let { DynamicScoreEngine.median(it.takeLast(it.size / 2)) })
    }
    val arrivals = qualifiedSamples.filter { it.category == "speed" && it.outcome == BenchmarkOutcome.PASSED && it.firstTextMs != null }
    val reasoning = samples.filter { it.category == IntelligenceBenchmark.CATEGORY }
    val reasoningSummaries = runs.map(::benchmarkIntelligenceSummary)
    val reasoningEvidence = runs.flatMap(::benchmarkIntelligenceEvidence)
    val reasoningScores = reasoningSummaries.mapNotNull { it.accuracyPercent }
    val reasoningMetric = ratio("reasoning_fixture_accuracy", reasoningSummaries.sumOf { it.correctQuestions ?: 0 }, reasoningSummaries.sumOf { it.gradedQuestions }, reasoning).copy(
        value = DynamicScoreEngine.median(reasoningScores), aggregation = "median of semantically complete run scores", population = "per-question semantic intelligence; unparseable or incomplete answers remain ungraded; repeats do not add unique questions", uniqueFixtureCount = reasoningEvidence.flatMap { it.questionDigests.values }.distinct().size, minimum = reasoningScores.minOrNull(), maximum = reasoningScores.maxOrNull(), runCount = reasoningScores.size,
        planned = reasoningSummaries.sumOf { it.plannedQuestions }, plannedKnown = reasoningSummaries.all { it.plannedQuestions > 0 },
        policyVersion = "benchmark-semantic-accuracy-v1", exclusions = mapOf("SEMANTIC_QUESTIONS_UNGRADED" to reasoningSummaries.sumOf { it.unknownQuestions })
    )
    val formatMetric = ratio("intelligence_format_compliance", reasoningSummaries.sumOf { it.formatPassed }, reasoningSummaries.sumOf { it.formatObserved }, reasoning).copy(population = "intelligence response formatting; separate from semantic correctness", policyVersion = "benchmark-semantic-accuracy-v1")
    val parseMetric = ratio("intelligence_parseability", reasoningSummaries.sumOf { it.parseableCases }, reasoningSummaries.sumOf { it.parseabilityObserved }, reasoning).copy(population = "observed complete intelligence response parseability; malformed text has no invented semantic grade", policyVersion = "benchmark-semantic-accuracy-v1")
    val all = runs.flatMap { it.samples }
    val operational = all.filter { it.knownExecuted() }
    val nativeExecutions = all.flatMap { it.runtimeExecutions }.filter { it.knownDispatched }.distinctBy { listOf(it.workerGenerationId, it.runtimeRequestId, it.nativeOperationId, it.nativeGenerationId).joinToString("|") }
    val rawArrival = samples.filter { it.category == "speed" && it.firstTextMs != null }
    val rawReference = samples.filter { it.category == "speed" && it.rawReferenceSpeed != null }
    val reportedBackend = samples.filter { it.category == "speed" && it.providerDecodeTokensPerSecond?.let { rate -> rate.isFinite() && rate > 0 } == true }
    val rawNative = samples.filter { it.category == "speed" }.flatMap { sample -> sample.localSegments.mapNotNull { segment -> segment.native?.takeIf { it.decodeTokens >= 0 && (it.decodeDurationMs ?: 0) > 0 }?.let { sample to it } } }
    val reportedSegments = samples.filter { it.category in setOf("speed", "performance") }.flatMap { sample -> sample.localSegments.mapNotNull { segment -> segment.native?.let { sample to it } } }
    fun reportedNativeRate(id: String, prefill: Boolean): BenchmarkMetricResult {
        val selected = reportedSegments.mapNotNull { (sample, metric) -> (if (prefill) metric.prefillTokensPerSecond else metric.decodeTokensPerSecond).takeIf { it.isFinite() && it > 0 }?.let { sample to it } }
        return ratio(id, selected.size, selected.size, selected.map { it.first }.distinct()).copy(value = DynamicScoreEngine.median(selected.map { it.second }), unit = if (prefill) "native input tokens/s" else "native output tokens/s", aggregation = "median_of_reported_segment_rates", population = "reported native per-generation segment rates; no derived durations or processed-input counts; raw diagnostics independent of answer grade", policyVersion = "benchmark-reported-native-rates-v1", planned = 0, plannedKnown = false)
    }
    val nativeDuration = rawNative.sumOf { it.second.decodeDurationMs ?: 0 }
    val nativeTokens = rawNative.sumOf { it.second.decodeTokens.toLong() }
    return listOf(
        ratio("answer_correctness", graded.count { it.outcome == BenchmarkOutcome.PASSED }, graded.size, graded),
        ratio("delivery_completion", dispatched.count { it.completed && it.outputCharacters > 0 && it.terminalReceived != false && it.failureCategory != "OUTPUT_PROTOCOL_INVALID" }, dispatched.size, dispatched),
        ratio("profile_availability", admitted, eligibleProfiles.size, eligibleProfiles.flatMap { it.samples }),
        ratio("planned_task_success", samples.count { it.outcome == BenchmarkOutcome.PASSED }, (planned - samples.count { it.outcome == BenchmarkOutcome.UNSUPPORTED }).coerceAtLeast(0), samples).let { result ->
            when {
                !result.plannedKnown -> result.copy(value = null, denominator = 0, population = "planned supported trials; non-warmup plan is unavailable", exclusions = result.exclusions + ("PLANNED_POPULATION_UNKNOWN" to plannedCounts.count { it == null }))
                runs.any { !it.finished || it.canceled || it.stoppedReason != null } -> result.copy(value = null, population = "planned task progress; run incomplete or stopped; numerator and denominator are progress only")
                else -> result
            }
        },
        timing("reference_decode_rate", speed, speed.mapNotNull { it.referenceSpeed }, "reference text units/s").let { it.copy(exclusions = it.exclusions + contextExclusions) },
        timing("first_visible_text", arrivals, arrivals.mapNotNull { it.firstTextMs?.toDouble() }, "ms").let { it.copy(population = "successful timing answers; streaming and buffered delivery", exclusions = it.exclusions + contextExclusions) },
        reasoningMetric,
        formatMetric,
        parseMetric,
        ratio("diagnostic_scenario_quality", graded.count { it.outcome == BenchmarkOutcome.PASSED }, graded.size, graded).copy(population = "non-intelligence evaluable scenarios; not per-question intelligence accuracy"),
        ratio("operational_delivery_including_warmups", operational.count { it.completed && it.outputCharacters > 0 && it.terminalReceived != false && it.failureCategory != "OUTPUT_PROTOCOL_INVALID" }, operational.size, operational).copy(policyVersion = "benchmark-operational-v2", planned = runs.sumOf { benchmarkCoverage(it).planned }, plannedKnown = runs.all { benchmarkCoverage(it).plannedKnown }, population = "logical cases with observed execution, including warmups; complete delivered answer, independent of correctness and native cleanup", exclusions = all.filterNot { it in operational }.groupingBy { benchmarkMetricExclusion("operational_delivery_including_warmups", it) }.eachCount()),
        ratio("physical_generation_terminal_receipt", nativeExecutions.count { it.hasNativeTerminal }, nativeExecutions.size, all.filter { it.runtimeExecutions.any { receipt -> receipt.knownDispatched } }).copy(policyVersion = "benchmark-native-receipts-v1", population = "distinct native generations with confirmed native entry; all native terminal kinds count as returned, not as correct answers; physical generation count is not planned in advance", planned = 0, plannedKnown = false),
        timing("raw_first_visible_text", rawArrival, rawArrival.mapNotNull { it.firstTextMs?.toDouble() }, "ms").copy(policyVersion = "benchmark-raw-timing-v1", population = "observed non-warmup timing answers, including failed validation and partial delivery; diagnostic only"),
        timing("raw_reference_decode_rate", rawReference, rawReference.mapNotNull { it.rawReferenceSpeed }, "reference text units/s").copy(policyVersion = "benchmark-raw-timing-v1", population = "observed non-warmup streaming intervals, including failed validation and partial delivery; diagnostic only"),
        timing("raw_reported_provider_decode_rate", reportedBackend, reportedBackend.mapNotNull { it.providerDecodeTokensPerSecond }, "provider tokens/s")
            .copy(policyVersion = "benchmark-provider-timing-v1", population = "provider-reported rates; separate from observed streaming delivery and excluded from normalized speed scoring"),
        reportedNativeRate("raw_reported_native_prefill_rate", true),
        reportedNativeRate("raw_reported_native_decode_rate", false),
        ratio("raw_native_decode_rate", rawNative.size, rawNative.size, rawNative.map { it.first }.distinct()).copy(policyVersion = "benchmark-raw-timing-v1", value = if (nativeDuration > 0) nativeTokens * 1000.0 / nativeDuration else null, unit = "native tokens/s", aggregation = "sum of segment tokens / sum of measured decode durations", population = "captured non-warmup native timing segments, including failed validation; model tokenizer dependent and not a normalized model score")
    ) + benchmarkEngineeringMetricResults(runs)
}

private data class BenchmarkIncidentCounts(val count: Int?, val eventIds: List<String>, val affectedCases: Int)

private fun benchmarkIncidentCounts(runs: List<BenchmarkRun>): BenchmarkIncidentCounts {
    val affected = runs.flatMap { it.samples }.filter { it.failure != null || it.failureCategory != null }
    val events = (
        runs.mapNotNull { run -> run.preparation?.takeIf { it.failure != null }?.let { it.failure?.eventId ?: it.eventId } } +
            affected.mapNotNull { it.causedByEventId ?: it.failure?.eventId }
        ).distinct().sorted()
    val unknown = affected.any { it.causedByEventId == null && it.failure?.eventId == null }
    return BenchmarkIncidentCounts(events.size.takeUnless { unknown }, events, affected.size)
}

internal fun benchmarkResultId(run: BenchmarkRun): String = benchmarkHash(listOf(run.profileUid, run.configKey, run.model, run.runtimeVersion, run.backend, run.accelerator, DynamicScoreEngine.cohort(run), run.scoringVersion, run.fixtureManifestHash).joinToString("|"))
