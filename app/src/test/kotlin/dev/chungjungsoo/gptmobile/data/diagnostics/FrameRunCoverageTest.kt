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

    @Test fun privacyTransitionAndClearDiscardThePreviousWindowBeforePublication() {
        val coverage = FrameRunCoverage()
        coverage.observe(mapOf("public-before" to "streaming"), 1)
        coverage.observe(mapOf("private-run" to "streaming"), null)
        assertTrue(coverage.snapshot(2).isEmpty())
        coverage.observe(mapOf("public-after" to "streaming"), 2)
        assertEquals(setOf("public-after"), coverage.snapshot(2).keys)
        // A clear can occur without an intervening frame; publication still checks its epoch.
        assertTrue(coverage.snapshot(3).isEmpty())
    }

    @Test fun privateAndStalePhaseProducersCannotEnterANewCapture() {
        val phases = FrameRunPhases(maximumRuns = 2)
        phases.observe("private-run", "streaming", null)
        assertTrue(phases.snapshot(1).isEmpty())
        phases.observe("public-run", "preparing", 1)
        phases.clear()
        phases.observe("public-run", "streaming", 2)
        phases.observe("public-run", "old-phase", 1)
        phases.observe("stale-run", "streaming", 1)
        assertEquals(mapOf("public-run" to "streaming"), phases.snapshot(2))
        phases.finished("public-run")
        assertTrue(phases.snapshot(2).isEmpty())
    }
}
