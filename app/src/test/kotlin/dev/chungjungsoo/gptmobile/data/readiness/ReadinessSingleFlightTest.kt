package dev.chungjungsoo.gptmobile.data.readiness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ReadinessSingleFlightTest {
    @Test fun cancelledSpeculatorDoesNotCancelRequiredConsumer() = runTest {
        val flights = ReadinessSingleFlight<String, String>(backgroundScope)
        val release = CompletableDeferred<Unit>()
        var calls = 0
        val producer: suspend () -> String = {
            calls++
            release.await()
            "catalogue"
        }
        val warm = async { flights.acquire("same", producer) }
        runCurrent()
        val required = async { flights.acquire("same", producer) }
        runCurrent()
        warm.cancelAndJoin()
        assertFalse(required.isCompleted)
        release.complete(Unit)
        assertEquals("catalogue", required.await())
        assertEquals(1, calls)
    }

    @Test fun lastConsumerDetachesWithoutJoiningProducerCleanup() = runTest {
        val flights = ReadinessSingleFlight<String, String>(backgroundScope)
        var stopped = false
        val waiter = async {
            flights.acquire("key") {
                try {
                    awaitCancellation()
                } finally {
                    stopped = true
                }
            }
        }
        runCurrent()
        waiter.cancelAndJoin()
        runCurrent()
        assertTrue(stopped)
        assertEquals("new", flights.acquire("key") { "new" })
    }

    @Test fun invalidationCancelsOldIdentityAndAllowsFreshAcquisition() = runTest {
        val flights = ReadinessSingleFlight<String, String>(backgroundScope)
        val old = async { flights.acquire("key") { awaitCancellation() } }
        runCurrent()
        flights.invalidate()
        runCurrent()
        assertTrue(old.isCancelled)
        assertEquals("new", flights.acquire("key") { "new" })
    }
}
