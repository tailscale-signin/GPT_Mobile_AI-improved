package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class VoiceSpeechBenchmarkOwnershipTest {
    @Test fun aFreshObserverRemainsBlockedUntilTheCancelledOwnersTeardownCompletes() = runTest {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val oldLease = requireNotNull(ownership.acquire())
        val teardownStarted = CompletableDeferred<Unit>()
        val finishTeardown = CompletableDeferred<Unit>()
        val worker = launch {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    teardownStarted.complete(Unit)
                    finishTeardown.await()
                }
            }
        }
        worker.invokeOnCompletion { oldLease.release() }
        runCurrent()
        worker.cancel()
        teardownStarted.await()

        // A newly opened voice route observes process ownership, independently
        // of its own ViewModel's initial running=false and local cancel action.
        assertSame(oldLease, ownership.state.first())
        assertFalse(worker.isCompleted)
        assertNull(ownership.acquire())
        runCurrent()
        assertSame(oldLease, ownership.state.value)

        finishTeardown.complete(Unit)
        worker.join()
        assertTrue(worker.isCompleted)
        assertNull(ownership.state.value)
        val nextLease = requireNotNull(ownership.acquire())
        assertSame(nextLease, ownership.state.first())
        assertTrue(nextLease.release())
    }

    @Test fun aLateOldCompletionCannotReleaseOrCancelTheNewOwner() {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val first = requireNotNull(ownership.acquire())
        assertTrue(first.release())
        val second = requireNotNull(ownership.acquire())

        assertFalse(first.release())
        assertSame(second, ownership.state.value)
        assertNull(ownership.acquire())
        assertTrue(second.release())
        assertNull(ownership.state.value)
        assertFalse(second.release())
    }
}
