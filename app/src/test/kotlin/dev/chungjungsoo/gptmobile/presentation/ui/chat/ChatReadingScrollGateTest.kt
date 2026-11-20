package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReadingScrollGateTest {
    @Test fun readingSpeedNeverRevealsArrow() {
        val gate = ChatReadingScrollGate()
        assertFalse(gate.recordDelta(3f, 0L))
        assertFalse(gate.recordDelta(3f, 100L))
        assertFalse(gate.recordDelta(3f, 200L))
    }

    @Test fun fasterGestureRevealsAndSlowingDownHidesArrow() {
        val gate = ChatReadingScrollGate()
        assertFalse(gate.recordDelta(30f, 0L))
        assertTrue(gate.recordDelta(40f, 100L))
        assertFalse(gate.recordDelta(1f, 200L))
    }

    @Test fun fastFlingRevealsAndStopOrInvalidMotionAlwaysClears() {
        val gate = ChatReadingScrollGate()
        assertTrue(gate.recordFling(450f))
        gate.stop()
        assertFalse(gate.fasterThanReading)
        assertFalse(gate.recordFling(Float.NaN))
        assertFalse(gate.recordFling(80f))
    }

    @Test fun slightlySlowerButDeliberateScrollRevealsWhileReadingDoesNot() {
        val gate = ChatReadingScrollGate()
        assertFalse(gate.recordDelta(10f, 0L))
        assertTrue(gate.recordDelta(4f, 100L)) // 140 dp/s
        gate.stop()
        assertFalse(gate.recordDelta(8f, 200L))
        assertFalse(gate.recordDelta(4f, 300L)) // 120 dp/s
    }

    @Test fun staleGestureSamplesCannotTransferSpeedToLaterScroll() {
        val gate = ChatReadingScrollGate()
        gate.recordDelta(80f, 10L)
        assertTrue(gate.recordDelta(60f, 110L))
        assertFalse(gate.recordDelta(2f, 800L))
        assertFalse(gate.recordDelta(2f, 900L))
    }
}
