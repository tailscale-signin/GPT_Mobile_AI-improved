package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DynamicScoreEngineTest {
    private fun run(id: String, speed: Int, local: Boolean = false, failed: Boolean = false): BenchmarkRun {
        val suite = benchmarkSuite(BenchmarkMode.FULL)
        return BenchmarkRun(
            id, id, id, if (local) "LITERT_LM" else "OPENAI", id, "revision-$id", local, BenchmarkMode.FULL, 1000,
            samples = suite.map { case ->
                BenchmarkSample(
                    case.id, case.label, case.category, if (failed && case.category != "speed") BenchmarkOutcome.FAILED else BenchmarkOutcome.PASSED,
                    durationMs = 1100, firstTextMs = 100, lastTextMs = 1100, chunks = 2, outputTokens = speed, referenceTokens = speed + 1, referenceDecodeTokens = speed, tokenBasis = ReferenceTextTokenizer.VERSION, warmup = case.warmup, workload = case.workload
                )
            },
            suiteVersion = 2, measurementVersion = 2, plannedTrials = suite.size, device = "fixture-device"
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
    fun invalidValuesHaveNoScores() {
        assertNull(DynamicScoreEngine.higher(Double.NaN, 1.0))
        assertNull(DynamicScoreEngine.higher(Double.POSITIVE_INFINITY, 1.0))
        assertNull(DynamicScoreEngine.lower(-1.0, 1.0))
        assertEquals(50.0, DynamicScoreEngine.lower(2.0, 1.0)!!, .0001)
    }

    @Test
    fun standardPlansHaveDeclaredCoverage() {
        assertEquals(36, benchmarkSuite(BenchmarkMode.FULL).size)
        assertEquals(48, benchmarkSuite(BenchmarkMode.AGENT).size)
        assertEquals(6, benchmarkSuite(BenchmarkMode.QUICK).size)
        assertEquals(3, benchmarkSuite(BenchmarkMode.FULL).count { it.warmup })
        assertTrue(ReferenceTextTokenizer.starts("abcd efgh!").size == 3)
    }
}
