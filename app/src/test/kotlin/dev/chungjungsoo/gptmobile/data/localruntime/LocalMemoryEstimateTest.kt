package dev.chungjungsoo.gptmobile.data.localruntime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalMemoryEstimateTest {
    @Test fun unknownPackagesRetainConservativeHeadroomAndNeverOverflow() {
        val estimate = LocalMemoryEstimate.estimate(Long.MAX_VALUE, Int.MAX_VALUE, false, true, true)
        assertEquals(Long.MAX_VALUE, estimate.upperBytes)
        assertTrue(estimate.lowerBytes <= estimate.upperBytes)
        assertEquals("conservative_unmeasured", estimate.confidence)
    }

    @Test fun warmReuseAndVisionDemandRemainDistinct() {
        val warm = LocalMemoryEstimate.estimate(2_000_000_000, 1024, true, false, false)
        val cold = LocalMemoryEstimate.estimate(2_000_000_000, 1024, false, false, false)
        val vision = LocalMemoryEstimate.estimate(2_000_000_000, 1024, false, false, true)
        assertEquals(128L * 1024 * 1024, warm.upperBytes)
        assertTrue(cold.upperBytes > warm.upperBytes)
        assertEquals(256L * 1024 * 1024, vision.upperBytes - cold.upperBytes)
    }
}
