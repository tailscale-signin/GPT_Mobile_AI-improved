package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionStartGateTest {
    @Test fun rateLimitedServiceCannotBeRestartedByRapidTaps() {
        var time = 0L
        val gate = RecognitionStartGate { time }
        assertTrue(gate.acquire())
        assertFalse(gate.acquire())
        gate.failed(true)
        repeat(7) {
            time += 500
            assertFalse(gate.acquire())
        }
        time = 10_000
        assertTrue(gate.acquire())
    }

    @Test fun genericFailureCooldownIsBounded() {
        var time = 0L
        val gate = RecognitionStartGate { time }
        gate.acquire()
        gate.failed(false)
        time = 1999
        assertFalse(gate.acquire())
        time = 2000
        assertTrue(gate.acquire())
    }
}
