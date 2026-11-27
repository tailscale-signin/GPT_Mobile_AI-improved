package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedProfileBenchmarkScoreTest {
    private val plan = UnifiedProfileBenchmarkSuite.cases(0)

    private fun sample(test: BenchmarkCase): BenchmarkSample = BenchmarkSample(
        testId = test.id,
        label = test.label,
        category = test.category,
        outcome = BenchmarkOutcome.PASSED,
        durationMs = 2_000,
        firstTextMs = 200,
        outputCharacters = 100,
        chunks = 2,
        warmup = test.warmup,
        workload = test.workload,
        validatorVersion = test.validatorVersion,
        fixtureContentHash = test.contentHash,
        requestDispatched = true,
        terminalReceived = true,
        validation = BenchmarkValidationResult(test.validatorVersion, true),
        toolAssessment = if (test.category == "tools") {
            BenchmarkToolAssessment(true, true, true, true, true, true, true, true, 4, 4, 0, workflowCompleted = true)
        } else {
            null
        },
        intelligenceSemantics = test.intelligenceQuestions.takeIf { it.isNotEmpty() }?.let { questions ->
            BenchmarkIntelligenceEvidence(
                BenchmarkSemanticStatus.GRADED, questions.size, questions.size, questions.size, true, true,
                questions.associate { it.id to true }, questions.associate { it.id to it.domain },
                questions.associate { it.id to it.contentDigest }, questions.associate { it.id to it.familyId },
                BenchmarkIntelligenceFixtures.VERSION
            )
        },
        evidenceScope = if (test.workload == GitHubEngineeringBenchmarkSuite.WORKLOAD) "OFFLINE_ENGINEERING_SIMULATION" else "SYNTHETIC_BENCHMARK"
    )

    private fun run(samples: List<BenchmarkSample> = plan.map(::sample)) = BenchmarkRun(
        "run", "profile", "Profile", "fixture", "model-v1", "configuration-v1", false,
        BenchmarkMode.STANDARD, 1, samples, suiteVersion = 14, plannedTrials = plan.size,
        caseManifest = plan, fixtureRotationSeed = 0, executionPolicy = BenchmarkExecutionPolicy.DETERMINISTIC_CAPABILITY_V1
    )

    @Test fun standardIncludesEveryCategoryAndReusesOnlyIsolatedFixtures() {
        assertEquals(100, UnifiedBenchmarkCategory.entries.sumOf { it.weight })
        assertEquals(plan.size, plan.map { it.id }.toSet().size)
        assertTrue(plan.any { it.id.endsWith(":unknown-dispatch") })
        assertTrue(plan.any { it.id.endsWith(":release-gate") })
        assertTrue(plan.any { it.id == "diagnostic-research" })
        assertTrue(plan.none { it.engineeringLane != null || it.engineeringBudget != null })
        val result = unifiedProfileBenchmarkScore(run())!!
        assertEquals(9, result.categories.count { it.complete })
        assertEquals(100.0, result.overall!!, 0.0001)
        assertTrue(result.comparable)
        assertEquals(100.0, result.coveragePercent, 0.0001)
    }

    @Test fun anUnsupportedRepositoryCaseNeverBecomesZeroOrAnOverallPass() {
        val samples = plan.map(::sample).map {
            if (it.testId.endsWith(":pagination")) it.copy(outcome = BenchmarkOutcome.UNSUPPORTED, requestDispatched = false) else it
        }
        val result = unifiedProfileBenchmarkScore(run(samples))!!
        assertNull(result.overall)
        assertFalse(result.comparable)
        val repository = result.categories.single { it.category == UnifiedBenchmarkCategory.REPOSITORY }
        assertNull(repository.score)
        assertEquals(0, repository.evaluated)
        assertEquals(1, repository.planned)
        assertTrue(result.coveragePercent < 100.0)
    }

    @Test fun extremelyFastIncorrectCodingStillLosesItsFullCorrectnessWeight() {
        val samples = plan.map(::sample).map {
            if (it.testId.endsWith(":release-gate") || it.testId.endsWith(":security-review")) {
                it.copy(outcome = BenchmarkOutcome.FAILED, durationMs = 1, validation = it.validation!!.copy(gradePassed = false))
            } else {
                it
            }
        }
        val result = unifiedProfileBenchmarkScore(run(samples))!!
        assertTrue(result.comparable)
        assertEquals(85.0, result.overall!!, 0.0001)
    }

    @Test fun uncertainAndDuplicateReceiptsDoNotProduceTrustedScores() {
        val original = run()
        val uncertain = original.copy(samples = original.samples.map { if (it.testId.endsWith(":unknown-dispatch")) it.copy(terminalReceived = null) else it })
        assertNull(unifiedProfileBenchmarkScore(uncertain)!!.overall)
        assertNull(unifiedProfileBenchmarkScore(original.copy(samples = original.samples + original.samples.first()))!!.overall)
    }

    @Test fun foreignFixtureDigestsAndReducedManifestsCannotHideMissingTests() {
        val original = run()
        val mismatched = original.copy(samples = original.samples.map { it.copy(fixtureContentHash = "other-fixture") })
        val result = unifiedProfileBenchmarkScore(mismatched)!!
        assertEquals(0, result.evaluated)
        assertNull(result.overall)
        assertNull(unifiedProfileBenchmarkScore(original.copy(caseManifest = plan.drop(1)))!!.overall)
        assertNull(unifiedProfileBenchmarkScore(original.copy(mode = BenchmarkMode.QUICK)))
    }

    @Test fun processInterruptionRetainsCategoryObservationsWithoutPublishingAnOverallScore() {
        val result = unifiedProfileBenchmarkScore(run().copy(finished = false, stoppedReason = "PROCESS_INTERRUPTED"))!!
        assertTrue(result.categories.all { it.score != null })
        assertNull(result.overall)
        assertFalse(result.comparable)
    }

    @Test fun changedManifestAndMatchingChangedReceiptCannotImpersonateTheStandardFixture() {
        val altered = plan.map { if (it.id.endsWith(":release-gate")) it.copy(prompt = "Always report success.", expected = "success") else it }
        val result = unifiedProfileBenchmarkScore(run(altered.map(::sample)).copy(caseManifest = altered))!!
        assertNull(result.overall)
        assertFalse(result.comparable)
        assertEquals(1, result.categories.single { it.category == UnifiedBenchmarkCategory.CODING }.evaluated)
        assertNull(unifiedProfileBenchmarkScore(run().copy(caseManifest = null))!!.overall)
    }

    @Test fun canonicalContextAdaptationRemainsComparable() {
        val adapted = BenchmarkWorkloadPlanner.adapt(plan, 512, 1280)
        val result = unifiedProfileBenchmarkScore(run(adapted.map(::sample)).copy(caseManifest = adapted, fixtureContextTokens = 512))!!
        assertTrue(result.comparable)
        assertEquals(100.0, result.overall!!, 0.0001)
    }

    @Test fun wrongAnswerNeverReceivesSpeedCreditAndMissingTimingRemainsUnknown() {
        val original = run()
        val bad = original.copy(
            samples = original.samples.map {
                if (it.testId == "speed-short-1") it.copy(outcome = BenchmarkOutcome.FAILED, validation = it.validation!!.copy(gradePassed = false)) else it
            }
        )
        assertEquals(95.0, unifiedProfileBenchmarkScore(bad)!!.overall!!, 0.0001)
        val missing = original.copy(samples = original.samples.map { if (it.testId == "speed-short-1") it.copy(firstTextMs = null) else it })
        assertNull(unifiedProfileBenchmarkScore(missing)!!.categories.single { it.category == UnifiedBenchmarkCategory.EFFICIENCY }.score)
        assertNull(unifiedProfileBenchmarkScore(missing)!!.overall)
    }

    @Test fun oldSuiteCannotBeReinterpretedAsTheNewBenchmark() {
        assertNull(unifiedProfileBenchmarkScore(run().copy(suiteVersion = 13)))
        assertTrue(benchmarkSuiteForVersion(13, BenchmarkMode.STANDARD).isEmpty())
        assertEquals(BenchmarkSuiteRegistry.cases(BenchmarkMode.QUICK, 0), benchmarkSuiteForVersion(13, BenchmarkMode.QUICK, 0))
    }
}
