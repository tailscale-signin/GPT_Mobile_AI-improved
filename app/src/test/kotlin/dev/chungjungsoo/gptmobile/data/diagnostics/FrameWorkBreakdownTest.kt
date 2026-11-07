package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameWorkBreakdownTest {
    @Test fun slowFrameBreakdownDoesNotAttributeNormalFramesOrInventUnavailableMeasurements() {
        val work = FrameWorkBreakdown()
        work.observe(FrameWorkBreakdown.Phase.DRAW, 10, slowFrame = false)
        work.observe(FrameWorkBreakdown.Phase.DRAW, 8, slowFrame = true)
        work.observe(FrameWorkBreakdown.Phase.DRAW, 7, slowFrame = true)
        work.observe(FrameWorkBreakdown.Phase.LAYOUT_MEASURE, 20, slowFrame = true)
        work.observe(FrameWorkBreakdown.Phase.SYNC, -1, slowFrame = true)
        assertEquals(10L, work.maximumNanos()["DRAW"])
        assertEquals(15L, work.slowFrameNanos()["DRAW"])
        assertEquals(20L, work.slowFrameNanos()["LAYOUT_MEASURE"])
        assertNull(work.maximumNanos()["SYNC"])
        assertNull(work.slowFrameNanos()["SYNC"])
        assertNull(work.slowFrameNanos()["INPUT"])
    }

    @Test fun reportingWindowResetCannotCarryOldPhaseValuesIntoTheNextWindow() {
        val work = FrameWorkBreakdown()
        work.observe(FrameWorkBreakdown.Phase.INPUT, 0, slowFrame = false)
        assertEquals(0L, work.maximumNanos()["INPUT"])
        work.clear()
        assertTrue(work.maximumNanos().values.all { it == null })
        assertTrue(work.slowFrameNanos().values.all { it == null })
    }
}
