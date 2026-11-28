package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMetricResult
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkMode
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkOutcome
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkPreparationReceipt
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkRun
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkSample
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStopOrigin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEvidencePresentationTest {
    private val run = BenchmarkRun("run", "profile", "Profile", "OPENAI", "model", "revision", false, BenchmarkMode.STANDARD, 1)

    @Test fun admissionIdentityCannotBecomeAnHttpSendClaim() {
        val sample = BenchmarkSample("case", "Case", "task", BenchmarkOutcome.CANCELED, requestDispatched = false, physicalRequestIds = listOf("admitted"))
        val text = benchmarkDispatchDescription(sample)
        assertTrue(text.contains("Fixture dispatch: not recorded"))
        assertTrue(text.contains("HTTP response: not recorded"))
        assertTrue(text.contains("admission records: 1"))
        assertFalse(text.contains("sent"))
        assertTrue(benchmarkDispatchDescription(sample.copy(providerHttpStatus = 503)).contains("HTTP response: observed (503)"))
    }

    @Test fun preparationRetainsWaitLimitAndUserStopWithoutInventingFixtureFailure() {
        val stopped = run.copy(cancellationOrigin = BenchmarkStopOrigin.USER_STOP, preparation = BenchmarkPreparationReceipt("event", "admission", "CANCELED", 1, elapsedMs = 32_000, queueWaitMs = 32_000, queueDeadlineMs = 30_000, blockerLabels = listOf("INTERACTIVE:012345abcdef", "private owner at 192.0.2.3")))
        val lines = benchmarkPreparationLines(stopped).joinToString("\n")
        assertEquals("stopped by you", benchmarkAttemptStatus(stopped, "INTERRUPTED"))
        assertTrue(lines.contains("32s observed"))
        assertTrue(lines.contains("wait limit 30s"))
        assertTrue(lines.contains("INTERACTIVE:012345abcdef"))
        assertTrue(lines.contains("Admission waited for"))
        assertFalse(lines.contains("Waiting for"))
        assertFalse(lines.contains("192.0.2.3"))
        assertTrue(lines.contains("Readiness may have contacted the provider separately"))
        assertTrue(benchmarkPreparationLines(run).isEmpty())
    }

    @Test fun currentAndCompletedPreparationUseTheObservedWaitPhase() {
        val preparation = BenchmarkPreparationReceipt("event", "admission", "WAITING", 1, blockerLabels = listOf("Inference 012345abcdef"))
        assertTrue(benchmarkPreparationLines(run.copy(preparation = preparation)).contains("Waiting for Inference 012345abcdef"))
        assertTrue(benchmarkPreparationLines(run.copy(preparation = preparation.copy(status = "COMPLETED"))).contains("Admission waited for Inference 012345abcdef"))
        assertTrue(benchmarkPreparationLines(run.copy(preparation = preparation.copy(blockerLabels = listOf("Earlier queued measurement", "Earlier queued inference")))).contains("Waiting for Earlier queued measurement, Earlier queued inference"))
    }

    @Test fun unavailablePlanIsDifferentFromStoppedProgressAndEmptyPopulation() {
        val metric = BenchmarkMetricResult("planned_task_success", null, "%", 0, 0, 0, listOf("run"), emptyList(), plannedKnown = false)
        val unknown = benchmarkPlanCompletionDescription(metric)
        assertTrue(unknown.contains("planned population unavailable"))
        assertFalse(unknown.contains("incomplete"))
        assertFalse(unknown.contains("0/0"))
        val stopped = benchmarkPlanCompletionDescription(metric.copy(plannedKnown = true, denominator = 25, planned = 25))
        assertTrue(stopped.contains("0/25"))
        assertTrue(stopped.contains("run incomplete or stopped"))
        val empty = benchmarkPlanCompletionDescription(metric.copy(plannedKnown = true))
        assertTrue(empty.contains("no supported planned cases"))
        assertFalse(empty.contains("incomplete"))
    }

    @Test fun processLossRetainsTheLastObservationWithoutInventingFinalDuration() {
        val preparation = BenchmarkPreparationReceipt("event", "admission", "INTERRUPTED", 1, elapsedMs = null, queueWaitMs = 5_000, queueDeadlineMs = 30_000, lastObservedElapsedMs = 5_000)
        val lines = benchmarkPreparationLines(run.copy(preparation = preparation)).joinToString("\n")
        assertTrue(lines.contains("Last observed preparation: 5s · final duration unknown"))
        assertTrue(lines.contains("Last observed admission wait: 5s · wait limit 30s"))
        assertFalse(lines.contains("interrupted · 5s observed"))
        assertFalse(lines.contains("0s observed"))
    }
}
