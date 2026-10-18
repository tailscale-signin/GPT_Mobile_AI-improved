package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufDeadlineReceiptTest {
    @Test fun timeoutRetainsNativeCountersAndRejectsLateOutput() = runBlocking {
        val cancel = CompletableDeferred<Unit>()
        val result = withGgufDeadlineReceipt(25, { cancel.complete(Unit) }) { accept ->
            cancel.await()
            assertFalse(accept())
            longArrayOf(42, 0, 20, 0, 2, 0, 42)
        }
        assertTrue(result.timedOut)
        assertEquals(42L, result.receipt!![6])
    }

    @Test fun acceptedOutputWinsAgainstLaterDeadline() = runBlocking {
        var cancelled = false
        val result = withGgufDeadlineReceipt(100, { cancelled = true }) { accept ->
            assertTrue(accept())
            delay(150)
            "complete"
        }
        assertFalse(result.timedOut)
        assertFalse(cancelled)
        assertEquals("complete", result.receipt)
    }

    @Test fun userStopSurvivesExpiredDeadline() = runBlocking {
        val cancel = CompletableDeferred<Unit>()
        val failure = runCatching {
            withGgufDeadlineReceipt(25, { cancel.complete(Unit) }) {
                cancel.await()
                throw CancellationException("User stopped")
            }
        }.exceptionOrNull()
        assertTrue(failure is CancellationException)
    }

    @Test fun productiveOutputStillHasAnIndependentNativeStallDeadline() = runBlocking {
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        val cancel = CompletableDeferred<Unit>()
        val result = withGgufDeadlineReceipt(1000, { cancel.complete(Unit) }, expirationCause = { if (expired.get()) "GGUF_DECODE_STALL" else null }) { accept ->
            assertTrue(accept())
            expired.set(true)
            cancel.await()
            assertFalse(accept())
            "partial native receipt"
        }
        assertTrue(result.timedOut)
        assertEquals("GGUF_DECODE_STALL", result.terminalCode)
        assertEquals("partial native receipt", result.receipt)
    }
}
