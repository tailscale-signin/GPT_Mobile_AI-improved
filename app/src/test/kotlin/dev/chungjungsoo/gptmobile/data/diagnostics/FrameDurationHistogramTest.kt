package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameDurationHistogramTest {
    @Test fun quantilesStallsAndOverflowAreWindowScoped() {
        val histogram = FrameDurationHistogram()
        repeat(95) { histogram.add(6_000_000) }
        repeat(4) { histogram.add(150_000_000) }
        histogram.add(2_000_000_000)
        assertEquals(10, histogram.percentileUpperMs(95))
        assertEquals(155, histogram.percentileUpperMs(99))
        assertEquals(5, histogram.stallsOver100Ms)
        assertTrue(histogram.overflow)
        histogram.clear()
        assertEquals(0, histogram.percentileUpperMs(99))
        assertEquals(0, histogram.stallsOver100Ms)
        assertFalse(histogram.overflow)
    }
}
