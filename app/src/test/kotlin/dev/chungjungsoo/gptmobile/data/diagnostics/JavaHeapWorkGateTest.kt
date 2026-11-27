package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JavaHeapWorkGateTest {
    @Test fun waitsForAnInFlightCollectorBeforeDeferringBoundedWork() = runBlocking {
        var samples = 0
        val admitted = awaitJavaHeapHeadroom(
            requiredBytes = 32,
            waitMillis = 200,
            pollMillis = 25
        ) {
            samples++
            if (samples < 4) 8 else 64
        }

        assertTrue(admitted)
        assertEquals(4, samples)
    }

    @Test fun stillDefersWhenHeapCapacityDoesNotRecoverWithinTheBound() = runBlocking {
        var samples = 0
        val started = System.nanoTime()
        val admitted = awaitJavaHeapHeadroom(
            requiredBytes = 32,
            waitMillis = 50,
            pollMillis = 25
        ) {
            samples++
            8
        }
        val elapsedMillis = (System.nanoTime() - started) / 1_000_000

        assertFalse(admitted)
        assertEquals(3, samples)
        assertTrue(elapsedMillis < 500)
    }
}
