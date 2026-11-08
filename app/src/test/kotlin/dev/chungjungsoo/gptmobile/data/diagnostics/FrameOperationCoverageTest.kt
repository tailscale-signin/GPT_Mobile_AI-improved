package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameOperationCoverageTest {
    @Test fun endedWorkRemainsVisibleInTheMatchingFrameWindow() {
        val coverage = FrameOperationCoverage()
        coverage.observe(FrameOperationCoverage.Sample("text_measure", 1, 10, 30, "run-1"))
        coverage.observe(FrameOperationCoverage.Sample("checkpoint", 2, 40, 80, "run-2"))
        assertEquals(listOf(1), coverage.snapshot(0, 40).samples.map { it.traceId })
        assertEquals(listOf(2), coverage.snapshot(40, 90).samples.map { it.traceId })
        assertTrue(coverage.snapshot(90, 100).samples.isEmpty())
    }

    @Test fun overlappingWorkIsRetainedWithoutInventingAnOperationForAnEmptyWindow() {
        val coverage = FrameOperationCoverage()
        coverage.observe(FrameOperationCoverage.Sample("checkpoint", 1, 10, 90, null))
        assertEquals(1, coverage.snapshot(30, 60).samples.size)
        assertTrue(coverage.snapshot(100, 120).samples.isEmpty())
        coverage.observe(FrameOperationCoverage.Sample("invalid", 2, 70, 60, null))
        assertEquals(1, coverage.snapshot(0, 100).samples.size)
    }

    @Test fun evictionAndClearCannotClaimCompleteCoverage() {
        val coverage = FrameOperationCoverage(1)
        coverage.observe(FrameOperationCoverage.Sample("first", 1, 10, 20, null))
        coverage.observe(FrameOperationCoverage.Sample("second", 2, 30, 40, null))
        assertTrue(coverage.snapshot(0, 50).truncated)
        assertFalse(coverage.snapshot(21, 50).truncated)
        coverage.clear()
        assertFalse(coverage.snapshot(0, 50).truncated)
        assertTrue(coverage.snapshot(0, 50).samples.isEmpty())
    }

    @Test fun clearingHistoryRejectsAnOperationThatStartedBeforeTheClear() {
        val coverage = FrameOperationCoverage()
        val oldGeneration = coverage.generation()
        assertTrue(coverage.observe(FrameOperationCoverage.Sample("first", 1, 10, 20, "old-run"), oldGeneration))
        coverage.clear()
        assertFalse(coverage.observe(FrameOperationCoverage.Sample("in_flight", 2, 15, 40, "old-run"), oldGeneration))
        assertTrue(coverage.snapshot(0, 50).samples.isEmpty())
        assertTrue(coverage.observe(FrameOperationCoverage.Sample("new", 3, 30, 40, "new-run"), coverage.generation()))
        assertEquals(listOf("new-run"), coverage.snapshot(0, 50).samples.map { it.runId })
    }
}
