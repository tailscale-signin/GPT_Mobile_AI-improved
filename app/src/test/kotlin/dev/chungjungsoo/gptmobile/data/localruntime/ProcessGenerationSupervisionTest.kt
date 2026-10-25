package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProcessGenerationSupervisionTest {
    private val policy = RuntimeProcessDeadlinePolicy.forGeneration(LocalRuntimeBackend.LITERT_LM, 2).copy(
        absoluteMs = 500,
        prefillMs = 50,
        decodeStallMs = 25
    )

    @Test fun stalledCollectorStillReceivesTypedDeadlineAndRunsOwningCleanup() = runTest {
        val deadline = ProcessGenerationDeadline(0, policy)
        val collectorEntered = CompletableDeferred<Unit>()
        var cleaned = false
        val outcome = async {
            runCatching {
                flow {
                    try {
                        withProcessGenerationWatchdog(
                            deadline,
                            terminalAcknowledged = { false },
                            onDeadline = { throw LocalProcessDeadlineException(it) },
                            nowMs = { testScheduler.currentTime },
                            monitorContext = StandardTestDispatcher(testScheduler)
                        ) {
                            deadline.observe(LocalRuntimeEvent.TextDelta("retained prefix"), testScheduler.currentTime)
                            emit("retained prefix")
                            awaitCancellation()
                        }
                    } finally {
                        cleaned = true
                    }
                }.collect {
                    collectorEntered.complete(Unit)
                    awaitCancellation()
                }
            }
        }
        runCurrent()
        assertTrue(collectorEntered.isCompleted)
        assertFalse(outcome.isCompleted)
        advanceTimeBy(25)
        runCurrent()
        val failure = outcome.await().exceptionOrNull()
        assertTrue(failure is LocalProcessDeadlineException)
        assertEquals("LOCAL_PROCESS_DECODE_STALL", (failure as LocalProcessDeadlineException).kind)
        assertTrue(cleaned)
    }

    @Test fun commandTerminalCancelsAndJoinsMonitorWithoutInventingDeadline() = runTest {
        var terminal = false
        var expired = false
        val value = withProcessGenerationWatchdog(
            ProcessGenerationDeadline(0, policy),
            terminalAcknowledged = { terminal },
            onDeadline = {
                expired = true
                throw LocalProcessDeadlineException(it)
            },
            nowMs = { testScheduler.currentTime },
            monitorContext = StandardTestDispatcher(testScheduler)
        ) {
            terminal = true
            "done"
        }
        advanceTimeBy(1000)
        runCurrent()
        assertEquals("done", value)
        assertFalse(expired)
    }

    @Test fun ordinaryFailureAndCallerCancellationDoNotLeaveAChildMonitor() = runTest {
        var expired = false
        var cleaned = false
        val error = IllegalArgumentException("original failure")
        val failure = runCatching {
            withProcessGenerationWatchdog(
                ProcessGenerationDeadline(0, policy),
                terminalAcknowledged = { false },
                onDeadline = {
                    expired = true
                    throw LocalProcessDeadlineException(it)
                },
                nowMs = { testScheduler.currentTime },
                monitorContext = StandardTestDispatcher(testScheduler)
            ) { throw error }
        }.exceptionOrNull()
        // Coroutine debug stack recovery may copy the exception at a suspend boundary.
        // Its type/message and the exact original cause must survive either mode.
        assertEquals(error.javaClass, failure?.javaClass)
        assertEquals(error.message, failure?.message)
        assertSame(error, generateSequence(failure) { it.cause }.last())
        val owner = launch {
            try {
                withProcessGenerationWatchdog(
                    ProcessGenerationDeadline(0, policy),
                    terminalAcknowledged = { false },
                    onDeadline = {
                        expired = true
                        throw LocalProcessDeadlineException(it)
                    },
                    nowMs = { testScheduler.currentTime },
                    monitorContext = StandardTestDispatcher(testScheduler)
                ) { awaitCancellation() }
            } finally {
                cleaned = true
            }
        }
        runCurrent()
        owner.cancelAndJoin()
        advanceTimeBy(1000)
        runCurrent()
        assertTrue(cleaned)
        assertFalse(expired)
    }
}
