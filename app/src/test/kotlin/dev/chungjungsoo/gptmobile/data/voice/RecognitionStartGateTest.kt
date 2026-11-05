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

    @Test fun repeatedBindingFailuresBackOffWithoutReleasingAnotherSession() {
        var time = 0L
        val gate = RecognitionStartGate { time }
        assertTrue(gate.acquire("first"))
        gate.bindingFailed("other")
        assertFalse(gate.acquire("second"))
        gate.bindingFailed("first")
        org.junit.Assert.assertEquals(5000L, gate.remainingMillis())
        time += 5000
        assertTrue(gate.acquire("second"))
        gate.bindingFailed("second")
        org.junit.Assert.assertEquals(15000L, gate.remainingMillis())
        time += 15000
        assertTrue(gate.acquire("third"))
        gate.bindingFailed("third")
        org.junit.Assert.assertEquals(30000L, gate.remainingMillis())
        time += 30000
        assertTrue(gate.acquire("recovered"))
        gate.succeeded("recovered")
        time += 500
        assertTrue(gate.acquire("later"))
        gate.bindingFailed("later")
        org.junit.Assert.assertEquals(5000L, gate.remainingMillis())
    }
}
