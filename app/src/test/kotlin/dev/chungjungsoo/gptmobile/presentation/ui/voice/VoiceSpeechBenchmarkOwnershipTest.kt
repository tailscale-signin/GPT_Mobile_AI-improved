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
import org.junit.Assert.assertEquals
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
        assertFalse(first.quarantine())
        assertNull(ownership.quarantineReason.value)
        assertSame(second, ownership.state.value)
        assertNull(ownership.acquire())
        assertTrue(second.release())
        assertNull(ownership.state.value)
        assertFalse(second.release())
    }

    @Test fun aFreshViewModelStateShowsTheSharedRestartRequirementAndCannotReleaseQuarantine() = runTest {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val failedOwner = requireNotNull(ownership.acquire())
        assertTrue(failedOwner.quarantine())

        val freshState = VoiceSpeechBenchmarkUiState().withQuarantine(ownership.quarantineReason.first())
        assertTrue(freshState.nativeCleanupPending)
        assertFalse(freshState.running)
        assertEquals(VoiceSpeechBenchmarkOwnership.QuarantineReason.NATIVE_CLEANUP_UNCONFIRMED.notice, freshState.notice)
        assertTrue(freshState.notice.orEmpty().contains("Restart the app"))
        assertTrue(freshState.copy(loaded = true).nativeCleanupPending)
        assertFalse(failedOwner.release())
        assertSame(failedOwner, ownership.state.value)
        assertNull(ownership.acquire())
    }

    @Test fun anAlreadyOpenObserverReceivesLateFailedCleanupAndKeepsSpeechBlocked() = runTest {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val failedOwner = requireNotNull(ownership.acquire())
        var state = VoiceSpeechBenchmarkUiState(loaded = true)
        val observer = launch {
            state = state.withQuarantine(ownership.quarantineReason.first { it != null })
        }
        runCurrent()
        assertFalse(state.nativeCleanupPending)

        assertTrue(failedOwner.quarantine())
        observer.join()
        assertTrue(state.nativeCleanupPending)
        assertEquals(VoiceSpeechBenchmarkOwnership.QuarantineReason.NATIVE_CLEANUP_UNCONFIRMED.notice, state.notice)
        assertFalse(failedOwner.release())
        assertNull(ownership.acquire())
    }

    @Test fun executorRetirementCannotHideADestructorFailureBeforeOrDuringCompletion() = runTest {
        listOf(false, true).forEach { alreadyRetired ->
            val ownership = VoiceSpeechBenchmarkOwnership()
            val lease = requireNotNull(ownership.acquire())
            val retired = CompletableDeferred<Unit>()
            var cleanupFailed = false
            var failureChecked = false
            val failedNativeOwner = Any()
            var retained: Any? = null
            var released: Boolean? = null
            if (alreadyRetired) {
                cleanupFailed = true
                retired.complete(Unit)
            }
            val completion = launch {
                released = lease.releaseAfterRetirement(
                    retired::await,
                    {
                        assertTrue(retired.isCompleted)
                        failureChecked = true
                        cleanupFailed
                    },
                    { retained = failedNativeOwner }
                )
            }
            runCurrent()
            if (!alreadyRetired) {
                assertFalse(failureChecked)
                assertNull(released)
                assertSame(lease, ownership.state.value)
                cleanupFailed = true
                retired.complete(Unit)
            }
            completion.join()
            assertEquals(false, released)
            assertSame(failedNativeOwner, retained)
            assertSame(lease, ownership.state.value)
            assertEquals(VoiceSpeechBenchmarkOwnership.QuarantineReason.NATIVE_CLEANUP_UNCONFIRMED, ownership.quarantineReason.value)
            assertFalse(lease.release())
            assertNull(ownership.acquire())
        }
    }

    @Test fun confirmedSuccessfulRetirementReleasesOnlyItsCurrentOwner() = runTest {
        val ownership = VoiceSpeechBenchmarkOwnership()
        val lease = requireNotNull(ownership.acquire())
        val retired = CompletableDeferred<Unit>()
        val completion = launch {
            assertTrue(lease.releaseAfterRetirement(retired::await, { false }) { error("No failed owner exists") })
        }
        runCurrent()
        assertSame(lease, ownership.state.value)
        assertNull(ownership.acquire())
        retired.complete(Unit)
        completion.join()
        assertNull(ownership.state.value)
        assertNull(ownership.quarantineReason.value)
        val next = requireNotNull(ownership.acquire())
        assertFalse(lease.releaseAfterRetirement({}, { false }) { error("No failed owner exists") })
        assertSame(next, ownership.state.value)
        assertTrue(next.release())
    }
}
