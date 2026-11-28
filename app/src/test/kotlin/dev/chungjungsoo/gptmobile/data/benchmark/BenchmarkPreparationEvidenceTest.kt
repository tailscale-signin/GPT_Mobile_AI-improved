package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BenchmarkPreparationEvidenceTest {
    @Test fun ownDeadlineAndNestedChildTimeoutHaveDifferentOwners() = runTest {
        val own = runCatching { benchmarkWithinDeadline("preflight", 10) { delay(20) } }.exceptionOrNull()!!
        assertTrue(own is BenchmarkDeadlineExceededException)
        val ownFailure = benchmarkTerminationFailure(own, "preflight", null, "own-event")
        assertEquals("PREFLIGHT_DEADLINE_EXCEEDED", ownFailure.code)
        assertEquals(BenchmarkOutcome.TIMED_OUT, ownFailure.outcome())
        val child = runCatching { benchmarkWithinDeadline("preflight", 100) { withTimeout(5) { awaitCancellation() } } }.exceptionOrNull()!!
        assertTrue(child is TimeoutCancellationException)
        assertEquals("CHILD_OPERATION_ABORTED", benchmarkTerminationFailure(child, "preflight", null, "child-event").code)
        assertNull(benchmarkWithinDeadline("preflight", 100) { null as Int? })
    }

    @Test fun explicitStopAndLifecycleOriginsNeverBecomeProviderFailures() {
        val error = CancellationException("LazyStandaloneCoroutine was cancelled")
        for (origin in listOf(BenchmarkStopOrigin.USER_STOP, BenchmarkStopOrigin.USER_SKIP, BenchmarkStopOrigin.SERVICE_INTERRUPTION, BenchmarkStopOrigin.PROCESS_INTERRUPTION)) {
            val failure = benchmarkTerminationFailure(error, "admission", origin, "root-event")
            assertEquals(origin.name, failure.code)
            assertEquals("root-event", failure.eventId)
            assertFalse(failure.retryable)
            assertFalse(failure.message.contains("Coroutine"))
            assertEquals(if (origin == BenchmarkStopOrigin.USER_SKIP) BenchmarkOutcome.SKIPPED else BenchmarkOutcome.CANCELED, failure.outcome())
            val sample = BenchmarkSample("case", "Case", "task", failure.outcome(), failure = failure).withFailureScope(true)
            if (origin in setOf(BenchmarkStopOrigin.SERVICE_INTERRUPTION, BenchmarkStopOrigin.PROCESS_INTERRUPTION)) {
                assertEquals(BenchmarkRecoveryAction.STOP_BATCH, sample.failure?.recoveryAction)
                assertEquals(BenchmarkFailureScope.BATCH, sample.failure?.failureScope)
                assertEquals("lifecycle", sample.failure?.owner)
            }
        }
        assertEquals("CHILD_OPERATION_ABORTED", benchmarkTerminationFailure(error, "admission", null, "unexpected-event").code)
    }

    @Test fun skippedFixtureRetainsManifestIdentityWithoutAnExecutionOrRootFailure() {
        val fixture = BenchmarkCase("second", "Second", "task", "Reply READY", "READY", validatorVersion = "benchmark-validator-v5", repeatIndex = 2)
        val root = benchmarkTerminationFailure(CancellationException(), "admission", BenchmarkStopOrigin.USER_STOP, "root-event")
        val sample = benchmarkUnattemptedDisposition(fixture, root)
        assertTrue(sample.dispositionOnly)
        assertEquals(BenchmarkOutcome.SKIPPED, sample.outcome)
        assertEquals("RUN_STOPPED_BY_USER", sample.failureCategory)
        assertEquals(sample.failureCategory, sample.failure?.code)
        assertNull(sample.failure?.eventId)
        assertEquals(root.eventId, sample.causedByEventId)
        assertEquals(fixture.contentHash, sample.fixtureContentHash)
        assertEquals(fixture.validatorVersion, sample.validatorVersion)
        assertEquals(fixture.repeatIndex, sample.repeatIndex)
        assertNull(sample.attemptId)
        assertNull(sample.validation)
        assertNull(sample.endToEndDurationMs)
        assertNull(sample.deadlineReceipt)
        assertEquals(false, sample.requestDispatched)
        assertEquals(false, sample.requestAdmitted)
    }

    @Test fun blockerLabelsContainOnlyBoundedRoleAndHash() {
        val owners = (0..20).map { "private-owner-$it@secret.invalid" }
        val labels = benchmarkAdmissionBlockerLabels(owners + owners, false)
        assertEquals(8, labels.size)
        assertTrue(labels.all { it.matches(Regex("Inference [a-f0-9]{12}")) })
        assertFalse(labels.any { it.contains("private") || it.contains("@") })
        assertEquals(labels, benchmarkAdmissionBlockerLabels(owners, false))
    }

    @Test fun recoveryClosesPreparationWithoutInventingItsFinalTimeOrChangingCompletedEvidence() {
        val pending = BenchmarkRun("run", "profile", "Profile", "OPENAI", "model", "config", false, BenchmarkMode.STANDARD, 1, finished = false, preflightDurationMs = 15, preparation = BenchmarkPreparationReceipt("setup", "admission", "WAITING", 1, elapsedMs = 15, queueWaitMs = 10))
        val recovered = pending.withInterruptedPreparation(BenchmarkStopOrigin.PROCESS_INTERRUPTION)
        assertEquals("INTERRUPTED", recovered.preparation?.status)
        assertEquals("PROCESS_INTERRUPTION", recovered.preparation?.failure?.code)
        assertNull(recovered.preparation?.endedAt)
        assertNull(recovered.preparation?.elapsedMs)
        assertNull(recovered.preflightDurationMs)
        assertEquals(15L, recovered.preparation?.lastObservedElapsedMs)
        assertEquals("USER_STOP", pending.withInterruptedPreparation(BenchmarkStopOrigin.USER_STOP).preparation?.failure?.code)
        val completed = pending.copy(preparation = pending.preparation!!.copy(status = "COMPLETED", endedAt = 16))
        assertEquals(completed, completed.withInterruptedPreparation(BenchmarkStopOrigin.PROCESS_INTERRUPTION))
    }
}
