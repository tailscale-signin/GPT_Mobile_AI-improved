package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicScoreEngineTest {
    @Test fun estimatedProviderCountsNeverReplaceReferenceDeliveryMeasurements() {
        val original = run("profile", 150)
        val estimates = original.copy(samples = original.samples.map { it.copy(outputTokens = 999999, inputTokenSource = "unknown", outputTokenSource = "estimated", outputTokenConfidence = "estimated", estimatedTokens = true) })
        val before = DynamicScoreEngine.snapshot(listOf(original), 1, 2000).rows.single()
        val after = DynamicScoreEngine.snapshot(listOf(estimates), 2, 2000).rows.single()
        assertEquals(before.speed, after.speed)
        assertEquals(before.overall, after.overall)
        assertEquals(before.metrics.single { it.id == "reference_decode_rate" }.value, after.metrics.single { it.id == "reference_decode_rate" }.value)
    }

    private fun run(id: String, speed: Int, local: Boolean = false, failed: Boolean = false): BenchmarkRun {
        // Preserve the accepted suite-nine / measurement-v2 fixture contract.
        val suite = benchmarkSuiteForVersion(9, BenchmarkMode.FULL)
        return BenchmarkRun(
            id, id, id, if (local) "LITERT_LM" else "OPENAI", id, "revision-$id", local, BenchmarkMode.FULL, 1000,
            samples = suite.map { case ->
                BenchmarkSample(
                    case.id, case.label, case.category, if (failed && case.category != "speed") BenchmarkOutcome.FAILED else BenchmarkOutcome.PASSED,
                    durationMs = 1100, firstTextMs = 100, lastTextMs = 1100, chunks = 2, outputTokens = speed, outputCharacters = speed * 4, requestDispatched = true, referenceTokens = speed + 1, referenceDecodeTokens = speed, tokenBasis = ReferenceTextTokenizer.VERSION, warmup = case.warmup, workload = case.workload
                )
            },
            suiteVersion = 9, measurementVersion = 2, plannedTrials = suite.size, device = "fixture-device"
        )
    }

    @Test
    fun newLeaderRescalesRemoteAndLeavesLocalUntouched() {
        val history = listOf(run("a", 150), run("b", 75), run("local", 30, true))
        val before = DynamicScoreEngine.snapshot(history, 1, 2000)
        val after = DynamicScoreEngine.snapshot(history + run("c", 300), 2, 2000)
        fun score(snapshot: BenchmarkScoreSnapshot, id: String) = snapshot.rows.first { it.profileUid == id }.speedScore!!
        assertEquals(100.0, score(before, "a"), .0001)
        assertEquals(50.0, score(after, "a"), .0001)
        assertEquals(25.0, score(after, "b"), .0001)
        assertEquals(100.0, score(after, "c"), .0001)
        assertEquals(score(before, "local"), score(after, "local"), .0001)
    }

    @Test
    fun incorrectAnswersCannotEarnHighOverall() {
        val rows = DynamicScoreEngine.snapshot(listOf(run("fast", 300, failed = true), run("good", 150)), 1, 2000).rows
        assertEquals(0.0, rows.first { it.profileUid == "fast" }.overall!!, .0001)
    }

    @Test
    fun provisionalAndMissingTrialsCannotSetAnchor() {
        val partial = run("partial", 600).let { it.copy(samples = it.samples.dropLast(1)) }
        val quick = run("quick", 900).copy(mode = BenchmarkMode.QUICK)
        val rows = DynamicScoreEngine.snapshot(listOf(run("good", 150), partial, quick), 1, 2000).rows
        assertEquals(100.0, rows.first { it.profileUid == "good" }.speedScore!!, .0001)
        assertNull(rows.first { it.profileUid == "partial" }.overall)
        assertNull(rows.first { it.profileUid == "quick" }.overall)
    }

    @Test
    fun deliveryScoresBalanceBandsBeforeAggregation() {
        val varied = run("varied", 100).let { block ->
            block.copy(
                samples = block.samples.map { sample ->
                    sample.copy(referenceDecodeTokens = if (sample.workload == "short") 400 else 100)
                }
            )
        }
        val steady = run("steady", 200)
        val rows = DynamicScoreEngine.snapshot(listOf(varied, steady), 1, 2000).rows
        assertEquals((100.0 + 50.0 + 50.0) / 3, rows.first { it.profileUid == "varied" }.speedScore!!, .0001)
        assertEquals((50.0 + 100.0 + 100.0) / 3, rows.first { it.profileUid == "steady" }.speedScore!!, .0001)
    }

    @Test
    fun duplicateTrialsAndInactiveProfilesCannotEstablishAnchors() {
        val valid = run("good", 150)
        val duplicate = run("duplicate", 900).let { it.copy(samples = it.samples.dropLast(1) + it.samples.first()) }
        val rows = DynamicScoreEngine.snapshot(listOf(valid, duplicate), 1, 2000).rows
        assertEquals(100.0, rows.first { it.profileUid == "good" }.speedScore!!, .0001)
        assertNull(rows.first { it.profileUid == "duplicate" }.overall)
        assertTrue(DynamicScoreEngine.snapshot(listOf(valid), 2, 2000, mapOf("good" to "changed-revision")).rows.isEmpty())
    }

    @Test
    fun invalidValuesHaveNoScores() {
        assertNull(DynamicScoreEngine.higher(Double.NaN, 1.0))
        assertNull(DynamicScoreEngine.higher(Double.POSITIVE_INFINITY, 1.0))
        assertNull(DynamicScoreEngine.lower(-1.0, 1.0))
        assertEquals(50.0, DynamicScoreEngine.lower(2.0, 1.0)!!, .0001)
    }

    @Test
    fun unsupportedAgentToolsKeepTextScoresAndFullCoverage() {
        val base = run("local", 100, true)
        val samples = base.samples.map { if (it.category == "tools") it.copy(outcome = BenchmarkOutcome.UNSUPPORTED, requestDispatched = false, chunks = 0, outputCharacters = 0) else it }
        val row = DynamicScoreEngine.snapshot(listOf(base.copy(samples = samples)), 1, 2000).rows.single()
        assertTrue(row.verified)
        assertEquals(100.0, row.reliability!!, .0001)
        assertNull(row.overall)
        assertEquals(setOf("tools"), row.unsupportedCategories)
        assertEquals(setOf("task", "json"), row.accuracyByCategory.keys)
    }

    @Test
    fun executionFailuresHaveUnknownAccuracyAndContextTiersStaySeparate() {
        val failed = run("failed", 100).let { block -> block.copy(samples = block.samples.map { if (it.category == "speed") it else it.copy(outcome = BenchmarkOutcome.ERROR) }) }
        val row = DynamicScoreEngine.snapshot(listOf(failed), 1, 2000).rows.single()
        assertNull(row.quality)
        assertNull(row.overall)
        assertTrue(DynamicScoreEngine.cohort(run("a", 100, true).copy(fixtureContextTokens = 4096)) != DynamicScoreEngine.cohort(run("b", 100, true).copy(fixtureContextTokens = 8192)))
        assertEquals(100, benchmarkScoreDimensions(row, false).sumOf { it.weight })
    }

    @Test
    fun standardPlansHaveDeclaredCoverage() {
        assertEquals(50, benchmarkSuite(BenchmarkMode.FULL).size)
        assertEquals(50, benchmarkSuite(BenchmarkMode.AGENT).size)
        assertEquals(5, benchmarkSuite(BenchmarkMode.QUICK).size)
        assertEquals(3, benchmarkSuite(BenchmarkMode.FULL).count { it.warmup })
        assertTrue(ReferenceTextTokenizer.starts("abcd efgh!").size == 3)
    }

    @Test
    fun historicalTextMeasurementsKeepTheirOwnScoresWithoutInventedIntelligence() {
        val historical = run("old", 100).let { block ->
            val manifest = BenchmarkSuiteRegistry.legacyStandard(false).filter { it.category != IntelligenceBenchmark.CATEGORY }
            block.copy(suiteVersion = 3, plannedTrials = 36, samples = manifest.map { test -> block.samples.first().copy(testId = test.id, category = test.category, workload = test.workload, warmup = test.warmup) })
        }
        val row = DynamicScoreEngine.snapshot(listOf(historical), 1, 2000).rows.single()
        assertEquals(100.0, row.speedScore!!, .0001)
        assertEquals(100.0, row.latencyScore!!, .0001)
        assertEquals(100.0, row.overall!!, .0001)
        assertNull(row.intelligence)
    }

    @Test
    fun newPlansCoverAllFiveCategoriesAndPreserveOlderManifests() {
        val quick = benchmarkSuite(BenchmarkMode.QUICK)
        assertEquals(setOf("speed", "task", "json", "tools", "intelligence"), quick.map { it.category }.toSet())
        assertEquals(quick.size, quick.map { it.id }.toSet().size)
        val full = benchmarkSuite(BenchmarkMode.FULL)
        assertEquals(mapOf("speed" to 18, "task" to 10, "json" to 6, "tools" to 10, "intelligence" to 6), full.groupingBy { it.category }.eachCount())
        assertEquals(50, full.map { it.id }.toSet().size)
        val base = run("old", 100)
        assertEquals(72, benchmarkSuiteForRun(base.copy(suiteVersion = 5)).size)
        assertEquals(84, benchmarkSuiteForRun(base.copy(suiteVersion = 5, mode = BenchmarkMode.AGENT)).size)
        assertEquals(6, benchmarkSuiteForRun(base.copy(suiteVersion = 5, mode = BenchmarkMode.QUICK)).size)
    }

    @Test
    fun comparisonUsesTheFirstModelAsBaselineAndRejectsDifferentScales() {
        val rows = DynamicScoreEngine.snapshot(listOf(run("a", 100), run("b", 200)), 1, 2000).rows
        val first = rows.first { it.profileUid == "a" }
        val second = rows.first { it.profileUid == "b" }
        val speed = benchmarkComparisonMetrics(first, second).first { it.label == "Speed score" }
        assertEquals(50.0, speed.difference!!, .0001)
        assertEquals(-50.0, benchmarkComparisonMetrics(second, first).first { it.label == "Speed score" }.difference!!, .0001)
        assertTrue(benchmarkComparisonMetrics(first, second.copy(cohort = "different")).all { it.difference == null })
        assertNull(benchmarkComparisonMetrics(first.copy(overall = null), second).first().difference)
    }

    @Test
    fun olderInsufficientBlockDoesNotPoisonLaterEligibleTiming() {
        val good = run("same", 150).copy(id = "new", startedAt = 1500)
        val old = good.copy(
            id = "old",
            startedAt = 1000,
            samples = good.samples.map {
                if (it.category == "speed" && it.workload == "medium") it.copy(outcome = BenchmarkOutcome.FAILED, timingEligible = false, failureCategory = "INSUFFICIENT_TIMING_SAMPLE") else it
            }
        )
        val row = DynamicScoreEngine.snapshot(listOf(old, good), 1, 2000).rows.single()
        assertTrue(row.verified)
        assertEquals(100.0, row.speedScore!!, .0001)
        assertTrue(row.sourceRunIds.containsAll(listOf("old", "new")))
    }
}
