package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDeadlineBudgetTest {
    @Test fun unknownAndroidDeadlinesDoNotHideMissedFrames() {
        val guard = FrameEffectsGuard()
        val observation = FrameEffectsObservation(guard, 0)
        val missingDeadline = Long.MAX_VALUE - 726_340_000_000L
        assertNull(FrameDeadlineBudget.observed(missingDeadline))
        val deadline = FrameDeadlineBudget.observed(missingDeadline) ?: FrameDeadlineBudget.fromRefreshRate(120f)
        repeat(120) { observation.observe(30_000_000, deadline, firstDraw = false, nowMs = 5000) }
        repeat(120) { observation.observe(30_000_000, deadline, firstDraw = false, nowMs = 10000) }
        assertTrue(guard.reduced.value)
    }

    @Test fun realDeadlinesKeepTheirBudgetAndMissingMetricsUseAValidFallback() {
        assertEquals(25_000_000L, FrameDeadlineBudget.observed(25_000_000))
        assertEquals(1_000_000_000L, FrameDeadlineBudget.observed(1_000_000_000))
        listOf(-1L, 0L, Long.MAX_VALUE, Long.MAX_VALUE - 1).forEach { assertNull(FrameDeadlineBudget.observed(it)) }
        assertEquals(8_333_333L, FrameDeadlineBudget.fromRefreshRate(120f))
        listOf(null, 0f, -1f, Float.NaN, Float.POSITIVE_INFINITY, Float.MIN_VALUE).forEach {
            assertEquals(16_666_666L, FrameDeadlineBudget.fromRefreshRate(it))
        }
    }
}
