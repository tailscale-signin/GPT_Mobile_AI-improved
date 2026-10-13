package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationTimingTest {
    @Test fun cancellationRemainsCancellationAndRecordsIncompletePhase() = runTest {
        var clock = 0L
        val events = mutableListOf<Pair<Long, Boolean>>()
        val outcome = runCatching {
            measurePreparation("run", PreparationPhase.TOOL_DISCOVERY, clockNanos = { clock }, record = { elapsed, successful -> events += elapsed to successful }) {
                clock = 25_000_000
                throw CancellationException("parent ended")
            }
        }
        assertTrue(outcome.exceptionOrNull() is CancellationException)
        assertEquals(listOf(25L to false), events)
    }

    @Test fun brokenDiagnosticSinkCannotFailPreparedRequest() = runTest {
        val prepared = measurePreparation("run", PreparationPhase.CONTEXT_ASSEMBLY, record = { _, _ -> error("storage unavailable") }) { "complete request" }
        assertEquals("complete request", prepared)
    }
}
