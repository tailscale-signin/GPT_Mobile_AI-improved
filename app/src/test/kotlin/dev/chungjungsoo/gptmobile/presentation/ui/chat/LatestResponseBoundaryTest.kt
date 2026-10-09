package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class LatestResponseBoundaryTest {
    @Test fun `boundary consumes overshoot and the same held gesture never releases it`() {
        val boundary = LatestResponseBoundary()
        boundary.rearm()
        boundary.beginGesture(0)
        assertEquals(0f, boundary.consume(20f, 100f, 0, true))
        assertEquals(30f, boundary.consume(50f, 20f, 10, true))
        assertEquals(100f, boundary.consume(100f, 0f, 5000, true))
        assertEquals(100f, boundary.consume(100f, 0f, 5000, false))
    }

    @Test fun `fresh upward gesture after a second releases until returning to newest response`() {
        val boundary = LatestResponseBoundary()
        boundary.rearm()
        boundary.beginGesture(0)
        boundary.consume(50f, 0f, 10, true)
        boundary.beginGesture(1009)
        assertEquals(50f, boundary.consume(50f, 0f, 1009, true))
        boundary.beginGesture(1010)
        assertEquals(0f, boundary.consume(50f, 0f, 1010, true))
        boundary.returningToResponse()
        assertEquals(50f, boundary.consume(50f, 0f, 1011, true))
    }

    @Test fun `downward scroll and an unarmed favourite entry are never blocked`() {
        val boundary = LatestResponseBoundary()
        assertEquals(0f, boundary.consume(50f, 0f, 0, true))
        boundary.rearm()
        assertEquals(0f, boundary.consume(-50f, 0f, 0, true))
    }
}
