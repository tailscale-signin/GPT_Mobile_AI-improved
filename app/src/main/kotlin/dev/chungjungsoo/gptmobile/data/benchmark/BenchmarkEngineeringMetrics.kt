package dev.chungjungsoo.gptmobile.data.benchmark

/** A named eligibility reason replaces the meaningless exclusion of a sample because it PASSED. */
internal fun benchmarkMetricExclusion(metric: String, sample: BenchmarkSample): String = when {
    // A skipped case is affected by its root cause; it is not another occurrence of it.
    sample.dispositionOnly || sample.outcome == BenchmarkOutcome.SKIPPED -> sample.failureCategory ?: "SKIPPED"
    metric.startsWith("intelligence_") || metric == "reasoning_fixture_accuracy" ->
        if (sample.category != IntelligenceBenchmark.CATEGORY) "NOT_AN_INTELLIGENCE_TASK" else sample.failureCategory ?: "SEMANTIC_EVIDENCE_UNAVAILABLE"
    metric.contains("decode_rate") || metric.contains("prefill_rate") || metric.contains("first_visible_text") -> when {
        sample.category !in setOf("speed", "performance") -> "NOT_A_TIMING_WORKLOAD"
        sample.failureCategory != null -> sample.failureCategory
        metric.contains("native") && sample.localSegments.isEmpty() -> "NATIVE_TIMING_NOT_REPORTED"
        metric.contains("decode") && sample.deliveryMode == "buffered" -> "BUFFERED_NO_DECODE_INTERVAL"
        sample.timingExclusionReason != null -> requireNotNull(sample.timingExclusionReason)
        else -> "TIMING_EVIDENCE_UNAVAILABLE"
    }
    metric == "physical_generation_terminal_receipt" && sample.runtimeExecutions.isEmpty() -> "NATIVE_EXECUTION_NOT_REPORTED"
    else -> sample.failure?.code ?: sample.failureCategory ?: if (sample.outcome == BenchmarkOutcome.PASSED) "OUTSIDE_METRIC_POPULATION" else sample.outcome.name
}

internal fun benchmarkEngineeringMetricResults(runs: List<BenchmarkRun>): List<BenchmarkMetricResult> {
    if (runs.isEmpty() || runs.any { it.engineeringPlan == null }) return emptyList()
    val plans = runs.mapNotNull { it.engineeringPlan?.copy(externalSetupMs = null, externalSetupRequestCount = null) }.distinct()
    if (plans.size != 1) return emptyList()
    val plan = plans.single()
    val observations = runs.flatMap { run ->
        val cases = run.caseManifest.orEmpty().associateBy { it.id }
        run.samples.mapNotNull { sample -> cases[sample.testId]?.let { Triple(run, it, sample) } }
    }
    val eligible = observations.mapNotNull { (run, test, sample) -> benchmarkEngineeringTaskResult(plan, test, sample)?.let { Triple(run, sample, it) } }
    val id = when (plan.lane) {
        BenchmarkEngineeringLane.TOOL_CONTRACT -> "tool_contract_task_success"
        BenchmarkEngineeringLane.WORKFLOW_SIMULATION -> "workflow_simulation_task_success"
        BenchmarkEngineeringLane.EXECUTABLE_CODING, BenchmarkEngineeringLane.LIVE_GITHUB -> "verified_engineering_task_completion"
    }
    val exclusions = observations.filter { (_, test, sample) -> benchmarkEngineeringTaskResult(plan, test, sample) == null }
        .groupingBy { (_, _, sample) -> benchmarkEngineeringFailureDomain(sample).name.takeUnless { it == "MODEL" } ?: "VERIFICATION_EVIDENCE_MISSING" }.eachCount()
    val metric = BenchmarkMetricResult(
        id, eligible.takeIf { it.isNotEmpty() }?.let { 100.0 * it.count { value -> value.third } / it.size }, "%",
        eligible.count { it.third }, eligible.size, runs.sumOf { it.plannedTrials }, runs.map { it.id },
        eligible.mapNotNull { it.second.attemptId }, exclusions, "benchmark-engineering-metrics-v1", plan.cohortId,
        runs.maxOfOrNull { it.endedAt ?: it.startedAt } ?: 0,
        "${plan.lane.label}; only this lane's bound, independently evaluable task outcomes",
        uniqueFixtureCount = observations.map { it.second.engineeringFamilyId ?: it.second.id }.distinct().size,
        sourceCohortIds = listOf(plan.cohortId), plannedKnown = runs.all { it.caseManifest != null }
    )
    val measured = observations.map { it.third }.filter { it.endToEndDurationMs != null }
    val durations = measured.mapNotNull { it.endToEndDurationMs?.toDouble() }
    return listOf(
        metric,
        metric.copy(
            id = "engineering_task_end_to_end", value = DynamicScoreEngine.median(durations), unit = "ms",
            numerator = durations.size, denominator = durations.size, sourceAttemptIds = measured.mapNotNull { it.attemptId },
            population = "all measured tasks, including failed tasks; duration is not correctness", aggregation = "median",
            minimum = durations.minOrNull(), maximum = durations.maxOrNull(),
            exclusions = observations.filter { it.third.endToEndDurationMs == null }.groupingBy { "TASK_DURATION_UNAVAILABLE" }.eachCount()
        )
    )
}

internal fun benchmarkGitHubScoreExplanation(run: BenchmarkRun): String {
    val classes = run.samples.groupingBy { it.evidenceScope }.eachCount()
    return if (run.engineeringPlan != null) {
        "${run.engineeringPlan.lane.label}: use verified task completion and the paired experiment comparison. " +
            "Timing bands and a general-purpose overall score are not applicable. " +
            "Evidence: " + classes.entries.joinToString { "${it.value} ${it.key}" }
    } else {
        "GitHub audit evidence: " + classes.entries.joinToString { "${it.value} ${it.key}" } +
            ". Contract and simulated results do not establish executable coding or live GitHub completion. No general-purpose overall score applies."
    }
}
