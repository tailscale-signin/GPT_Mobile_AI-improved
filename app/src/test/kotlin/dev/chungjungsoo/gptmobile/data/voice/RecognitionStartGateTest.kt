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
        repeat(9) {
            time += 100
            assertFalse(gate.acquire())
        }
        time = 1_000
        assertTrue(gate.acquire())
    }

    @Test fun genericFailureCooldownIsBounded() {
        var time = 0L
        val gate = RecognitionStartGate { time }
        gate.acquire()
        gate.failed(false)
        time = 499
        assertFalse(gate.acquire())
        time = 500
        assertTrue(gate.acquire())
    }

    @Test fun activeRecognitionOwnsAdmissionBeyondTheTapDebounce() {
        var time = 0L
        val gate = RecognitionStartGate { time }
        assertTrue(gate.acquire("first"))
        time = 30_000
        assertFalse(gate.acquire("second"))
        gate.release("second")
        assertFalse(gate.acquire("second"))
        gate.release("first")
        assertTrue(gate.acquire("second"))
        gate.failed(true, "second")
        assertFalse(gate.acquire("third"))
        time += 1_000
        assertTrue(gate.acquire("third"))
    }
}
