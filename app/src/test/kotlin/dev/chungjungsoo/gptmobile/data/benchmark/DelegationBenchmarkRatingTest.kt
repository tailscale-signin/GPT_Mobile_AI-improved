package dev.chungjungsoo.gptmobile.data.benchmark

import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationBenchmarkRatingTest {
    private fun rate(runs: List<BenchmarkRun>) = delegationBenchmarkRating(runs, 50.0, 100L, 1000L)
    private fun run(
        worker: String,
        toolPassed: Boolean = true,
        speed: Double = 50.0,
        duration: Long = 1000,
        key: String = "worker-config",
        reviewerScore: Int? = null
    ) = BenchmarkRun(
        worker, "primary", "Primary", "OPENAI", "primary-model", "primary-config", false, BenchmarkMode.DELEGATION, 1,
        suiteVersion = 2,
        delegationSettings = ModelDelegationSettings(targetProfileUid = worker),
        samples = delegationBenchmarkSuite().map { test ->
            BenchmarkSample(
                test.id,
                test.label,
                test.category,
                if (test.id.startsWith("delegation-tools") && !toolPassed) BenchmarkOutcome.FAILED else BenchmarkOutcome.PASSED,
                durationMs = duration,
                delegation = DelegationBenchmarkMetrics(
                    worker, worker, "LLAMA", 1, 100, 20, 0, 0, 0, 0, 100, 20,
                    if (test.id.startsWith("delegation-tools") && toolPassed) 1 else 0,
                    if (test.id.startsWith("delegation-tools") && toolPassed) 1 else 0,
                    workerModel = "model", workerConfigKey = key, workerFirstTextMs = 100,
                    workerDecodeTokensPerSecond = speed,
                    reviewerScore = reviewerScore,
                    reviewerEvaluations = if (reviewerScore == null) 0 else 1
                )
            )
        }
    )

    @Test fun `transport errors reduce reliability without reporting tool incapability`() {
        val complete = run("helper")
        val interrupted = complete.copy(
            samples = complete.samples.map { sample ->
                if (sample.testId.startsWith("delegation-tools")) sample.copy(outcome = BenchmarkOutcome.ERROR) else sample
            }
        )
        val rating = rate(listOf(interrupted))
        assertEquals(null, rating.toolTaskSuccessPercent)
        assertTrue(rating.score == null || rating.score < 100)
    }

    @Test fun `correct slower helper ranks above fast helper without tool usage`() {
        val rows = delegateRankings(listOf(run("fast", false, 1000.0), run("reliable", true, 40.0, 4000)), "primary-config")
        assertEquals("reliable", rows.first().run.id)
        assertTrue(rows.last().rating.score!! < 100)
        assertEquals(0.0, rows.last().rating.toolTaskSuccessPercent!!, .01)
    }

    @Test fun `incomplete suite cannot produce a rating`() {
        val full = run("helper")
        assertNull(rate(listOf(full.copy(samples = full.samples.take(1)))).score)
    }

    @Test fun `failed cases count against success and canceled runs are excluded`() {
        val failed = run("helper").copy(samples = run("helper").samples.map { it.copy(outcome = BenchmarkOutcome.TIMED_OUT) })
        val result = rate(listOf(failed, run("canceled").copy(canceled = true)))
        assertEquals(24, result.attempts)
        assertEquals(0, result.passed)
        assertNull(result.score)
        assertNull(result.medianDecodeSpeed)
    }

    @Test fun `rankings separate worker settings primary and legacy suites`() {
        val base = run("helper")
        val changed = base.copy(id = "new-config", samples = base.samples.map { it.copy(delegation = it.delegation!!.copy(workerConfigKey = "new")) })
        assertEquals(2, delegateRankings(listOf(base, changed, base.copy(suiteVersion = 1), base.copy(configKey = "other")), "primary-config").size)
        assertTrue(delegateRankings(listOf(base), "primary-config", ModelDelegationSettings(maxOutputTokens = 2048)).isEmpty())
    }

    @Test fun `delegation rating exposes tool call usability and diagnostic severity counts`() {
        val base = run("helper")
        val events = listOf(
            DelegationBenchmarkEvent(10, "FIRST_TEXT", "INFO", "fast"),
            DelegationBenchmarkEvent(20, "INSIGHT_LOW_THROUGHPUT", "WARN", "slow"),
            DelegationBenchmarkEvent(30, "WORKER_FAILURE", "ERROR", "failed")
        )
        val enriched = base.copy(
            samples = base.samples.map { sample ->
                sample.copy(delegation = sample.delegation!!.copy(diagnosticEvents = events))
            }
        )
        val result = rate(listOf(enriched))
        assertEquals(100.0, result.toolTaskSuccessPercent!!, .01)
        assertEquals(100.0, result.toolCallSuccessPercent!!, .01)
        assertEquals(72, result.diagnosticEvents)
        assertEquals(24, result.warningEvents)
        assertEquals(24, result.errorEvents)
        assertTrue(result.dimensions.any { it.label == "Token throughput" })
        assertTrue(result.dimensions.any { it.label == "First-response latency" })
        assertTrue(result.dimensions.any { it.label == "End-to-end latency" })
    }

    @Test fun `single chunk missing speed is not fabricated and tokens remain visible`() {
        val base = run("helper")
        val result = rate(listOf(base.copy(samples = base.samples.map { it.copy(delegation = it.delegation!!.copy(workerDecodeTokensPerSecond = null, workerEstimated = true)) })))
        assertNull(result.medianDecodeSpeed)
        assertEquals(2400L, result.workerInputTokens)
        assertEquals(480L, result.workerOutputTokens)
        assertTrue(result.estimated)
    }

    @Test fun `reviewer score is exposed but never changes delegation score`() {
        val strong = rate(listOf(run("strong-review", reviewerScore = 100)))
        val weak = rate(listOf(run("weak-review", reviewerScore = 0)))

        assertEquals(100, strong.reviewerScore)
        assertEquals(24, strong.reviewerEvaluations)
        assertEquals(0, weak.reviewerScore)
        assertEquals(24, weak.reviewerEvaluations)
        assertEquals(strong.score, weak.score)
        assertTrue(strong.dimensions.any { it.label == "Reviewer report (diagnostic)" && it.score == 100.0 })
    }

    @Test fun `reviewer token usage is aggregated separately from worker tokens`() {
        val base = run("review-usage", reviewerScore = 90)
        val enriched = base.copy(
            samples = base.samples.map { sample ->
                sample.copy(
                    delegation = sample.delegation!!.copy(
                        reviewerCalls = 1,
                        reviewerInputTokens = 120,
                        reviewerOutputTokens = 30,
                        reviewerEstimated = true
                    )
                )
            }
        )

        val result = rate(listOf(enriched))
        assertEquals(24, result.reviewerCalls)
        assertEquals(2880L, result.reviewerInputTokens)
        assertEquals(720L, result.reviewerOutputTokens)
        assertTrue(result.reviewerEstimated)
        assertEquals(2400L, result.workerInputTokens)
        assertEquals(480L, result.workerOutputTokens)
    }

    @Test fun `reviewer disabled leaves reviewer score unmeasured instead of treating it as zero`() {
        val result = rate(listOf(run("no-reviewer")))

        assertNull(result.reviewerScore)
        assertEquals(0, result.reviewerEvaluations)
        assertTrue(result.dimensions.any { it.label == "Reviewer report (diagnostic)" && it.score == null })
    }

    @Test fun `retry cost lowers scoreboard even when the final answer passes`() {
        val base = run("helper")
        fun withEfficiency(percent: Double, attempts: Int) = base.copy(
            samples = base.samples.map { sample ->
                sample.copy(delegation = sample.delegation!!.copy(timeEfficiencyPercent = percent, delegateAttempts = attempts, successfulRequests = 1))
            }
        )
        val efficient = rate(listOf(withEfficiency(100.0, 1)))
        val recovered = rate(listOf(withEfficiency(5.0, 6)))
        assertTrue(efficient.score!! > recovered.score!!)
        assertTrue(recovered.dimensions.any { it.label == "Time efficiency" && it.score == 5.0 })
    }
}
