package dev.chungjungsoo.gptmobile.data.benchmark

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkEngineeringRecoveryTest {
    private val fixture = BenchmarkEngineeringTestFixtures()
    private val test = fixture.cases.first()
    private val pending = fixture.sample(test, false).copy(
        outcome = BenchmarkOutcome.PARTIAL,
        inputTokens = 10,
        outputTokens = 20,
        logicalRequestId = "logical",
        physicalRequestIds = listOf("generation"),
        cleanupReceipt = "gateway:submission_outcome_unknown",
        engineeringAttempts = listOf(BenchmarkEngineeringAttemptReceipt(0, "original-request", benchmarkHash("candidate"), null, "VERIFICATION_PENDING", false, 10, 20, 100))
    )

    @Test fun pendingSubmissionRemainsQueuedAndResumableWithoutInventingSkippedCases() {
        val run = fixture.runs().first().copy(samples = listOf(pending), finished = false)
        val items = BenchmarkEngineeringRegistry.queue(fixture.profiles, fixture.plan).mapIndexed { index, item ->
            if (index == 0) item.copy(runId = run.id, state = BenchmarkQueueState.RUNNING) else item
        }
        val batch = BenchmarkBatch(fixture.plan.id, BenchmarkMode.GITHUB_INTEGRATION, 0, items, engineeringPlan = fixture.plan)
        val paused = benchmarkEngineeringPausedBatch(batch, run)
        assertEquals(BenchmarkBatchState.INTERRUPTED, paused.state)
        assertEquals(BenchmarkQueueState.QUEUED, paused.items.first().state)
        assertNull(paused.endedAt)
        assertTrue(paused.canResume)
        assertEquals(1, run.awaitingEngineeringReconciliation().samples.size)
        assertFalse(run.awaitingEngineeringReconciliation().finished)
        assertEquals(ENGINEERING_RECONCILIATION_REQUIRED, paused.items.first().failure?.code)
    }

    @Test fun terminalOrNeverSubmittedReadbackIsSafeButUnknownCleanupStaysBlocked() {
        assertTrue(pending.engineeringCleanupPending())
        assertTrue(pending.copy(cleanupReceipt = "gateway:shutdown_unconfirmed").engineeringCleanupPending())
        assertTrue(pending.copy(cleanupReceipt = null).engineeringCleanupPending())
        assertFalse(pending.copy(cleanupReceipt = "gateway:terminal_readback_confirmed").engineeringCleanupPending())
        assertFalse(pending.copy(cleanupReceipt = "gateway:no_submission_dispatched").engineeringCleanupPending())
    }

    @Test fun finalSampleReplacesPendingReceiptWithoutDiscardingOtherTasks() {
        val run = fixture.runs().first().copy(samples = listOf(pending, fixture.sample(fixture.cases[1], true)))
        val finished = fixture.sample(test, true).copy(logicalRequestId = pending.logicalRequestId, physicalRequestIds = pending.physicalRequestIds)
        val updated = run.withEngineeringSample(finished)
        assertEquals(2, updated.samples.size)
        assertEquals(BenchmarkOutcome.PASSED, updated.samples.single { it.testId == test.id }.outcome)
        assertTrue(updated.samples.any { it.testId == fixture.cases[1].id })
    }

    @Test fun reconciliationCannotReplaceTheCandidateOrEraseUsage() {
        assertTrue(benchmarkEngineeringReconciliationBound(pending, pending.copy(cleanupReceipt = "gateway:terminal_readback_confirmed")))
        assertFalse(benchmarkEngineeringReconciliationBound(pending, pending.copy(physicalRequestIds = listOf("another-generation"))))
        assertFalse(benchmarkEngineeringReconciliationBound(pending, pending.copy(inputTokens = 0, outputTokens = 0, engineeringAttempts = emptyList())))
        assertFalse(benchmarkEngineeringReconciliationBound(pending, pending.copy(engineeringAttempts = pending.engineeringAttempts.map { it.copy(candidateDigest = benchmarkHash("different")) })))
    }

    @Test fun processInterruptionRetainsUnknownGenerationInsteadOfReplayingOrScoringIt() {
        val run = fixture.runs().first().copy(samples = emptyList(), finished = false)
        val started = BenchmarkAttempt(
            "attempt", fixture.plan.id, 0, run.id, test.id, 0, 10, 100,
            physicalRequestIds = listOf("original-generation"), logicalRequestId = "logical"
        )
        val recovered = benchmarkEngineeringInterruptedRun(run, listOf(started))
        assertEquals(1, recovered.samples.size)
        assertEquals(listOf("original-generation"), recovered.samples.single().physicalRequestIds)
        assertEquals(BenchmarkOutcome.PARTIAL, recovered.samples.single().outcome)
        assertEquals("unknown", recovered.samples.single().inputTokenConfidence)
        assertNull(benchmarkEngineeringTaskResult(fixture.plan, test, recovered.samples.single()))
        assertEquals(recovered.samples, benchmarkEngineeringInterruptedRun(recovered, listOf(started)).samples)
    }

    @Test fun pendingOrAbortedRepairNeverBecomesTheFinalDeliveredModelFailure() {
        assertNull(benchmarkEngineeringTaskResult(fixture.plan, test, pending))
        assertNull(benchmarkEngineeringTaskResult(fixture.plan, test, pending.copy(cleanupReceipt = "gateway:terminal_readback_confirmed")))
        assertEquals(false, benchmarkEngineeringTaskResult(fixture.plan, test, fixture.sample(test, false)))
    }
}
