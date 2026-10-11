package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IntelligenceBenchmarkTest {
    private fun answers(): List<BenchmarkSample> = IntelligenceBenchmark.suite.map { test ->
        BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED, workload = test.workload)
    }

    @Test
    fun balancedDomainsUseAbsoluteAccuracyWithoutSpeedWeighting() {
        val samples = answers().mapIndexed { index, sample -> if (index < 3) sample.copy(outcome = BenchmarkOutcome.FAILED) else sample }
        val score = IntelligenceBenchmark.score(samples)
        assertEquals(36, score.answered)
        assertEquals(6, score.domains.size)
        assertEquals(50.0, score.domains.getValue("Math"), .0001)
        assertEquals(100.0 * 33 / 36, score.score!!, .0001)
        assertEquals(score, IntelligenceBenchmark.score(samples.map { it.copy(durationMs = 999999, outputTokens = 1) }))
    }

    @Test
    fun missingOrFailedInfrastructureNeverProducesAFalselyCompleteScore() {
        val complete = answers()
        assertNull(IntelligenceBenchmark.score(complete.dropLast(1)).score)
        assertNull(IntelligenceBenchmark.score(complete.dropLast(1) + complete.last().copy(outcome = BenchmarkOutcome.ERROR)).score)
        assertNull(IntelligenceBenchmark.score(complete + complete.first()).score)
        assertEquals(35, IntelligenceBenchmark.score(complete.dropLast(1)).answered)
        assertEquals(0.0, IntelligenceBenchmark.score(complete.map { it.copy(outcome = BenchmarkOutcome.FAILED) }).score!!, .0001)
    }

    @Test
    fun answerCheckerAllowsLabelsButRejectsExplanationsAndSubstringGuesses() {
        assertTrue(IntelligenceBenchmark.matches("Answer: mercury.", "Mercury"))
        assertTrue(IntelligenceBenchmark.matches(" 138 ", "138"))
        assertFalse(IntelligenceBenchmark.matches("Not 138, it is 140", "138"))
        assertFalse(IntelligenceBenchmark.matches("YES or NO", "NO"))
        assertFalse(IntelligenceBenchmark.matches("Answer: 138\nAnother answer: 140", "138"))
    }

    @Test
    fun uniqueCompactFixturesHaveSixQuestionsPerDomain() {
        val suite = IntelligenceBenchmark.suite
        assertEquals(36, suite.map { it.id }.toSet().size)
        assertTrue(suite.groupBy { it.workload }.values.all { it.size == 6 })
        assertTrue(suite.all { it.prompt.toByteArray().size < 700 && !it.expected.isNullOrBlank() })
        assertEquals(6, benchmarkSuite(BenchmarkMode.FULL).count { it.category == IntelligenceBenchmark.CATEGORY })
        assertEquals(6, benchmarkSuite(BenchmarkMode.AGENT).count { it.category == IntelligenceBenchmark.CATEGORY })
        assertEquals(1, benchmarkSuite(BenchmarkMode.QUICK).count { it.category == IntelligenceBenchmark.CATEGORY })
    }
    private fun batchAnswers(): List<BenchmarkSample> = IntelligenceBenchmark.batches.map { test ->
        BenchmarkSample(test.id, test.label, test.category, BenchmarkOutcome.PASSED, workload = test.workload, intelligenceAnswers = IntelligenceBenchmark.gradeBatch(test, test.expected!!))
    }

    @Test
    fun fullKeepsAll36QuestionsAndQuickSamplesEveryDomainOnce() {
        val full = IntelligenceBenchmark.scoreBatches(batchAnswers(), false)
        assertEquals(36, full.answered)
        assertEquals(36, full.planned)
        assertEquals(100.0, full.score!!, .0001)
        assertFalse(full.provisional)
        val quick = IntelligenceBenchmark.scoreBatches(batchAnswers().take(1), true)
        assertEquals(6, quick.answered)
        assertEquals(6, quick.domains.size)
        assertTrue(quick.provisional)
        assertNull(IntelligenceBenchmark.scoreBatches(batchAnswers().dropLast(1), false).score)
        assertNull(IntelligenceBenchmark.scoreBatches(batchAnswers() + batchAnswers().first(), false).score)
    }

    @Test
    fun batchGradingGivesPartialCreditAndRejectsMalformedOrMissingAnswers() {
        val test = IntelligenceBenchmark.batches.first()
        val grades = IntelligenceBenchmark.gradeBatch(test, test.expected!!.replace("138", "999"))
        assertEquals(5, grades.values.count { it })
        assertTrue(IntelligenceBenchmark.gradeBatch(test, "not json").values.none { it })
        assertTrue(IntelligenceBenchmark.gradeBatch(test, "{}").values.none { it })
        val bad = batchAnswers().mapIndexed { index, sample -> if (index == 0) sample.copy(intelligenceAnswers = grades, outcome = BenchmarkOutcome.FAILED) else sample }
        assertEquals(100.0 * 35 / 36, IntelligenceBenchmark.scoreBatches(bad, false).score!!, .0001)
    }
}
