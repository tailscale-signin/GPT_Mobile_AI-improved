package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLinePacingQueueTest {
    @Test fun aSinglePreviewLineTakesExactlyOneAndAHalfSeconds() {
        val queue = GitHubLinePacingQueue()
        queue.append(1, 30f)
        assertEquals(1500L, queue.remainingMillis)
        assertEquals(20f, queue.advance(1000), 0.001f)
        assertEquals(500L, queue.remainingMillis)
        assertEquals(10f, queue.advance(500), 0.001f)
        assertTrue(queue.isEmpty)
    }

    @Test fun threeVisualLinesTakeExactlyThreeSeconds() {
        val queue = GitHubLinePacingQueue()
        queue.append(3, 90f)
        assertEquals(3000L, queue.remainingMillis)
        assertEquals(30f, queue.advance(1000), 0.001f)
        assertEquals(2000L, queue.remainingMillis)
        assertEquals(60f, queue.advance(2000), 0.001f)
        assertTrue(queue.isEmpty)
    }

    @Test fun arrivalsAppendTimeWithoutCompressingTheRunningSegment() {
        val queue = GitHubLinePacingQueue()
        queue.append(3, 90f)
        assertEquals(30f, queue.advance(1000), 0.001f)
        queue.append(2, 80f)
        assertEquals(4000L, queue.remainingMillis)
        assertEquals(60f, queue.advance(2000), 0.001f)
        assertEquals(2000L, queue.remainingMillis)
        assertEquals(40f, queue.advance(1000), 0.001f)
        assertEquals(40f, queue.advance(1000), 0.001f)
        assertTrue(queue.isEmpty)
    }

    @Test fun previewArrivalKeepsItsOwnTimingWhileAMultilineSummaryIsScrolling() {
        val queue = GitHubLinePacingQueue()
        queue.append(3, 90f)
        assertEquals(30f, queue.advance(1000), 0.001f)
        queue.append(1, 30f)
        assertEquals(3500L, queue.remainingMillis)
        assertEquals(60f, queue.advance(2000), 0.001f)
        assertEquals(1500L, queue.remainingMillis)
        assertEquals(30f, queue.advance(1500), 0.001f)
        assertTrue(queue.isEmpty)
    }

    @Test fun geometryCorrectionChangesDistanceWithoutChargingOrDiscardingReadingTime() {
        val queue = GitHubLinePacingQueue()
        queue.append(2, 60f)
        queue.advance(500)
        queue.reconcileDistance(100f)
        assertEquals(1500L, queue.remainingMillis)
        queue.append(1, 30f)
        assertEquals(3000L, queue.remainingMillis)
        assertEquals(100f, queue.advance(1500), 0.001f)
        assertEquals(30f, queue.advance(1500), 0.001f)
    }

    @Test fun fittingContentDoesNotCreateMotionAndDetachDiscardsTheBacklog() {
        val queue = GitHubLinePacingQueue()
        queue.append(3, 0f)
        assertTrue(queue.isEmpty)
        queue.append(100, 3000f)
        assertEquals(100000L, queue.remainingMillis)
        queue.clear()
        assertEquals(0L, queue.remainingMillis)
        assertEquals(0f, queue.advance(10000), 0f)
    }
}
