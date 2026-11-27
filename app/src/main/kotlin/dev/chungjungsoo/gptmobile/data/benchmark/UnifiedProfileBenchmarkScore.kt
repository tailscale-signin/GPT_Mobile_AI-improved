package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.Serializable

@Serializable
enum class UnifiedBenchmarkCategory(val label: String, val weight: Int) {
    REASONING("General reasoning", 15),
    INSTRUCTIONS("Instruction following", 10),
    TOOLS("Tool selection and execution", 10),
    RESEARCH("Research and factual accuracy", 10),
    REPOSITORY("GitHub repository understanding", 10),
    CODING("GitHub coding and repair", 15),
    AUTONOMY("GitHub autonomous execution", 10),
    RECOVERY("Verification and recovery", 15),
    EFFICIENCY("Speed and efficiency", 5)
}

@Serializable
data class UnifiedBenchmarkCategoryScore(
    val category: UnifiedBenchmarkCategory,
    val score: Double?,
    val evaluated: Int,
    val planned: Int
) {
    val complete: Boolean get() = planned > 0 && evaluated == planned
}

@Serializable
data class UnifiedProfileBenchmarkScore(
    val runId: String,
    val categories: List<UnifiedBenchmarkCategoryScore>,
    val overall: Double?,
    val evaluated: Int,
    val planned: Int,
    val comparable: Boolean,
    val coveragePercent: Double,
    val policy: String = "unified-profile-score-v1",
    val evidenceScope: String = UnifiedProfileBenchmarkSuite.SCOPE,
    val limitation: String = UnifiedProfileBenchmarkSuite.LIMITATION
)

/**
 * Derive scores only from the exact run's retained validator observations. Unsupported,
 * interrupted, missing and duplicate samples stay unknown. No best-of history selection.
 */
internal fun unifiedProfileBenchmarkScore(run: BenchmarkRun): UnifiedProfileBenchmarkScore? {
    if (run.mode != BenchmarkMode.STANDARD || run.suiteVersion != 14) return null
    val expected = BenchmarkWorkloadPlanner.adapt(UnifiedProfileBenchmarkSuite.cases(run.fixtureRotationSeed ?: 0), run.fixtureContextTokens, 1280)
    val manifest = run.caseManifest.orEmpty()
    val validManifest = manifest.size == expected.size && manifest.associateBy { it.id } == expected.associateBy { it.id }
    val byId = manifest.groupBy { it.id }
    val samples = run.samples.groupBy { it.testId }
    val observations = UnifiedBenchmarkCategory.entries.associateWith { mutableListOf<Double?>() }

    fun sample(test: BenchmarkCase): BenchmarkSample? {
        val accepted = byId[test.id]?.singleOrNull() ?: return null
        // The retained plan and receipt must both match the canonical versioned
        // fixture. Matching a changed receipt to a changed imported plan is insufficient.
        if (accepted != test) return null
        return samples[test.id]?.singleOrNull()?.takeIf {
            it.fixtureContentHash == accepted.contentHash && it.validatorVersion == accepted.validatorVersion &&
                it.outcome in setOf(BenchmarkOutcome.PASSED, BenchmarkOutcome.FAILED) &&
                it.terminalReceived == true && it.knownExecuted() && !it.warmup &&
                it.category == accepted.category && it.workload == accepted.workload &&
                it.evidenceScope == if (test.workload == GitHubEngineeringBenchmarkSuite.WORKLOAD) {
                    "OFFLINE_ENGINEERING_SIMULATION"
                } else {
                    "SYNTHETIC_BENCHMARK"
                }
        }
    }
    fun Boolean?.points(): Double? = this?.let { if (it) 100.0 else 0.0 }
    fun grade(test: BenchmarkCase, observed: BenchmarkSample?): Double? = observed?.validation
        ?.takeIf { it.validatorVersion == test.validatorVersion }
        ?.gradePassed.points()
    fun add(category: UnifiedBenchmarkCategory, value: Double?) {
        observations.getValue(category).add(value)
    }

    expected.filterNot { it.warmup }.forEach { test ->
        val observed = sample(test)
        when {
            test.category == IntelligenceBenchmark.CATEGORY -> {
                val evidence = observed?.intelligenceSemantics?.takeIf { it.fixtureVersion == BenchmarkIntelligenceFixtures.VERSION }
                test.intelligenceQuestions.forEach { question ->
                    val correct = evidence?.takeIf {
                        it.questionDigests[question.id] == question.contentDigest && it.questionDomains[question.id] == question.domain
                    }?.perQuestion?.get(question.id)
                    add(
                        if (question.domain in setOf("Knowledge", "Grounded reasoning")) UnifiedBenchmarkCategory.RESEARCH else UnifiedBenchmarkCategory.REASONING,
                        correct.points()
                    )
                }
                add(UnifiedBenchmarkCategory.INSTRUCTIONS, evidence?.formatCompliant.points())
            }
            test.category in setOf("task", "json") -> add(UnifiedBenchmarkCategory.INSTRUCTIONS, grade(test, observed))
            test.id in UnifiedProfileBenchmarkSuite.toolCaseIds -> add(UnifiedBenchmarkCategory.TOOLS, grade(test, observed?.takeIf { it.toolAssessment != null }))
            test.id == "diagnostic-research" -> add(UnifiedBenchmarkCategory.RESEARCH, grade(test, observed?.takeIf { it.toolAssessment != null }))
            test.id in UnifiedProfileBenchmarkSuite.recoveryCaseIds -> add(UnifiedBenchmarkCategory.RECOVERY, grade(test, observed?.takeIf { it.toolAssessment != null }))
            test.workload == GitHubEngineeringBenchmarkSuite.WORKLOAD -> {
                val category = when (test.id.substringAfterLast(':')) {
                    "release-gate", "security-review" -> UnifiedBenchmarkCategory.CODING
                    "pagination" -> UnifiedBenchmarkCategory.REPOSITORY
                    "copilot-handshake", "agent-merge" -> UnifiedBenchmarkCategory.AUTONOMY
                    else -> UnifiedBenchmarkCategory.RECOVERY
                }
                add(category, grade(test, observed?.takeIf { it.toolAssessment?.workflowCompleted != null }))
            }
            test.category == "speed" -> {
                // Fixed, disclosed anchors; no changing leaderboard population. Invalid answers
                // receive no speed reward. Missing execution/latency is not a zero score.
                val valid = grade(test, observed)
                val first = observed?.firstTextMs?.takeIf { it >= 0 }
                val duration = observed?.durationMs?.takeIf { it > 0 && first != null && it >= first }
                val efficiency = when {
                    valid == null || first == null || duration == null -> null
                    valid == 0.0 -> 0.0
                    else -> 50.0 * (1_000.0 / maxOf(1_000L, first)) + 50.0 * (20_000.0 / maxOf(20_000L, duration))
                }
                add(UnifiedBenchmarkCategory.EFFICIENCY, efficiency)
            }
        }
    }
    val categories = observations.map { (category, values) ->
        val known = values.filterNotNull()
        UnifiedBenchmarkCategoryScore(category, known.takeIf { it.isNotEmpty() }?.average(), known.size, values.size)
    }
    val evaluated = categories.sumOf { it.evaluated }
    val planned = categories.sumOf { it.planned }
    val complete = validManifest && run.finished && !run.canceled && run.stoppedReason == null && categories.all { it.complete }
    return UnifiedProfileBenchmarkScore(
        run.id, categories,
        if (complete) categories.sumOf { requireNotNull(it.score) * it.category.weight } / 100.0 else null,
        evaluated, planned, complete, if (planned == 0) 0.0 else evaluated * 100.0 / planned
    )
}
