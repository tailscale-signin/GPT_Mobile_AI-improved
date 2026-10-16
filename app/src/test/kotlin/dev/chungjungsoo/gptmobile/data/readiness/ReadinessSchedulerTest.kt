package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadinessSchedulerTest {
    @Test fun cancelledGenerationCannotConsumeReplacementAdmissionSlots() = runTest {
        val scheduler = ReadinessScheduler(backgroundScope)
        repeat(12) { scheduler.request("old-$it", { true }) { awaitCancellation() } }
        runCurrent()
        scheduler.cancelSpeculation()
        var warmed = 0
        // Submit before cancellation handlers get a dispatcher turn, as the configuration collector does.
        repeat(4) { scheduler.request("new-$it", { true }) { warmed++ } }
        runCurrent()
        assertEquals(4, warmed)
    }

    @Test fun oldCompletionCannotRemoveReplacementWithSameKey() = runTest {
        val scheduler = ReadinessScheduler(backgroundScope)
        scheduler.request("profile", { true }) { awaitCancellation() }
        runCurrent()
        scheduler.cancelSpeculation()
        var started = 0
        scheduler.request("profile", { true }) {
            started++
            awaitCancellation()
        }
        runCurrent()
        scheduler.request("profile", { true }) { started++ }
        runCurrent()
        assertEquals(1, started)
        scheduler.cancelSpeculation()
    }

    @Test fun duplicateHintsCoalesceAndNetworkConcurrencyIsBounded() = runTest {
        val scheduler = ReadinessScheduler(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var started = 0
        repeat(16) {
            scheduler.request("same", { true }) {
                started++
                release.await()
            }
        }
        runCurrent()
        assertEquals(1, started)
        repeat(16) { key ->
            scheduler.request("other-$key", { true }) {
                started++
                release.await()
            }
        }
        runCurrent()
        assertEquals(2, started)
        scheduler.cancelSpeculation()
        runCurrent()
        release.complete(Unit)
    }

    @Test fun noNewSpeculationAfterPriorityChangesAndFailuresDoNotCancelSiblings() = runTest {
        val scheduler = ReadinessScheduler(backgroundScope)
        var permitted = true
        var stopped = false
        scheduler.request("active", { permitted }) {
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
        runCurrent()
        permitted = false
        scheduler.cancelSpeculation()
        scheduler.request("forbidden", { permitted }) { error("Must not run") }
        runCurrent()
        assertTrue(stopped)
        val succeeded = CompletableDeferred<Unit>()
        scheduler.request("failure", { true }) { error("isolated") }
        scheduler.request("sibling", { true }) { succeeded.complete(Unit) }
        runCurrent()
        assertTrue(succeeded.isCompleted)
    }
}
