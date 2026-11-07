package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRunCoverageTest {
    @Test fun completingARunBeforeThePeriodicReportDoesNotEraseItsSampleCoverage() {
        val coverage = FrameRunCoverage()
        val active = mutableMapOf("run-1" to "streaming")
        coverage.observe(active)
        active.clear()
        coverage.observe(active)
        assertEquals(mapOf("run-1" to "streaming"), coverage.snapshot())
        coverage.clear()
        assertTrue(coverage.snapshot().isEmpty())
    }

    @Test fun sampledRunsAreBoundedAndUnobservedRunsAreNotInvented() {
        val coverage = FrameRunCoverage(maximumRuns = 2)
        assertTrue(coverage.snapshot().isEmpty())
        coverage.observe(linkedMapOf("a" to "preparing", "b" to "streaming", "c" to "streaming"))
        assertEquals(setOf("a", "b"), coverage.snapshot().keys)
        assertTrue(coverage.truncated)
        coverage.observe(mapOf("a" to "export"))
        assertEquals("export", coverage.snapshot()["a"])
        coverage.clear()
        assertFalse(coverage.truncated)
    }
}
