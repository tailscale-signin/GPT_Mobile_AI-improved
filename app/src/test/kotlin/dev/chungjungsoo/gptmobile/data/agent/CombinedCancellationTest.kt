package dev.chungjungsoo.gptmobile.data.agent

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedCancellationTest {
    @Test
    fun failedJournalStillAttemptsOtherRootsAndJoinsPartialPersistenceBeforeTerminalFallback() = runTest {
        val events = mutableListOf<String>()
        var retainedAnswer = ""
        val provider = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    yield()
                    retainedAnswer = "Retained partial answer"
                    events += "persist partial"
                }
            }
        }

        cancelCombinedRootsAndRuns(
            roots = listOf("first", "second", "first"),
            cancelRoot = { root ->
                events += "cancel $root"
                if (root == "first") throw IOException("Journal storage unavailable")
            },
            onRootFailure = { root, failure ->
                assertTrue(failure is IOException)
                events += "report $root"
            },
            cancelRuns = {
                cancelAndJoinAgentRun(provider) {
                    assertEquals("Retained partial answer", retainedAnswer)
                    events += "terminal fallback"
                }
            }
        )

        assertTrue(provider.isCancelled)
        assertTrue(provider.isCompleted)
        assertEquals(listOf("cancel first", "report first", "cancel second", "persist partial", "terminal fallback"), events)
    }

    @Test
    fun cancellingTheStopCallerDoesNotInterruptProviderCleanup() = runTest {
        val enteredJournal = CompletableDeferred<Unit>()
        val releaseJournal = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val provider = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    yield()
                    events += "persist partial"
                }
            }
        }
        val stop = launch(start = CoroutineStart.UNDISPATCHED) {
            cancelCombinedRootsAndRuns(
                roots = listOf("first", "second"),
                cancelRoot = { root ->
                    if (root == "first") {
                        enteredJournal.complete(Unit)
                        releaseJournal.await()
                    }
                    events += "cancel $root"
                },
                onRootFailure = { _, failure -> throw AssertionError("Unexpected journal failure", failure) },
                cancelRuns = { cancelAndJoinAgentRun(provider) { events += "terminal fallback" } }
            )
        }

        enteredJournal.await()
        stop.cancel()
        releaseJournal.complete(Unit)
        stop.join()

        assertTrue(stop.isCancelled)
        assertTrue(provider.isCancelled)
        assertTrue(provider.isCompleted)
        assertEquals(listOf("cancel first", "cancel second", "persist partial", "terminal fallback"), events)
    }

    @Test
    fun aFailingErrorReporterCannotPreventProviderCancellationAndTerminalFallback() = runTest {
        val events = mutableListOf<String>()
        val provider = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                withContext(NonCancellable) {
                    yield()
                    events += "persist partial"
                }
            }
        }

        val failure = runCatching {
            cancelCombinedRootsAndRuns(
                roots = listOf("first"),
                cancelRoot = { throw IOException("Journal storage unavailable") },
                onRootFailure = { _, _ -> throw IllegalStateException("Error reporter unavailable") },
                cancelRuns = { cancelAndJoinAgentRun(provider) { events += "terminal fallback" } }
            )
        }.exceptionOrNull()

        assertTrue(failure is IllegalStateException)
        assertEquals("Error reporter unavailable", failure?.message)
        assertTrue(provider.isCancelled)
        assertTrue(provider.isCompleted)
        assertEquals(listOf("persist partial", "terminal fallback"), events)
    }
}
