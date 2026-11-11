package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPromptAnchorTest {
    @Test fun everyDragStopsAutomaticMovementWithoutConsumingFingerMotion() {
        val anchor = GitHubPromptAnchor(8f, 8f)
        anchor.beginGesture()
        assertTrue(anchor.dragging)
        assertEquals(0f, anchor.move(20f, 0f, true, 0).consumed)
        assertEquals(GitHubScrollMode.PIN_PROMPT, anchor.mode)
        anchor.endGesture(300f, 48f, 96f)
        assertFalse(anchor.dragging)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
    }

    @Test fun continuousUpwardDragReleasesAtExactlyAQuarterSecondAndStaysInHistory() {
        val anchor = GitHubPromptAnchor(8f, 8f)
        for (time in listOf(0L, 50L, 100L, 150L, 200L, 249L)) {
            assertEquals(0f, anchor.move(4f, 0f, true, time).consumed)
            assertEquals(GitHubScrollMode.PIN_PROMPT, anchor.mode)
        }
        anchor.move(4f, 0f, true, 250)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
        anchor.endGesture(300f, 48f, 96f)
        anchor.move(-100f, 0f, false, 500)
        anchor.fling(-2000f, 1000f)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
    }

    @Test fun durationWithoutTouchSlopAndSeparatedSwipesDoNotReleaseHistory() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        for (time in 0L..1200L step 100) anchor.move(1f, 0f, true, time)
        assertEquals(GitHubScrollMode.PIN_PROMPT, anchor.mode)
        anchor.endGesture(300f, 48f, 96f)
        anchor.beginGesture()
        anchor.move(100f, 0f, true, 1300)
        anchor.move(100f, 0f, true, 2600)
        assertEquals(GitHubScrollMode.PIN_PROMPT, anchor.mode)
    }

    @Test fun downwardReturnStaysInHistoryUntilItReachesTheMeasuredBottom() {
        val anchor = GitHubPromptAnchor(8f, 8f)
        anchor.detach()
        anchor.beginGesture()
        anchor.move(-20f, 0f, true, 0)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
        assertTrue(anchor.dragging)
        anchor.endGesture(200f, 48f, 96f)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
        anchor.beginGesture()
        anchor.move(-20f, 0f, true, 10)
        anchor.endGesture(30f, 48f, 96f)
        assertEquals(GitHubScrollMode.FOLLOW_BOTTOM, anchor.mode)
    }

    @Test fun measuredBottomDistanceUsesHysteresisAndJumpAlwaysFollows() {
        val anchor = GitHubPromptAnchor(8f, 8f)
        anchor.observeBottom(40f, 48f, 96f)
        anchor.detach()
        anchor.move(-20f, 0f, true, 0)
        anchor.endGesture(70f, 48f, 96f)
        assertEquals(GitHubScrollMode.FOLLOW_BOTTOM, anchor.mode)
        anchor.observeBottom(100f, 48f, 96f)
        anchor.detach()
        anchor.move(-20f, 0f, true, 10)
        anchor.endGesture(70f, 48f, 96f)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
        anchor.detach()
        anchor.followBottom()
        assertEquals(GitHubScrollMode.FOLLOW_BOTTOM, anchor.mode)
    }

    @Test fun reversedOrCancelledGesturesDoNotAccumulateHistoryDuration() {
        val anchor = GitHubPromptAnchor(8f, 8f)
        for (time in 0L..200L step 50) anchor.move(20f, 0f, true, time)
        anchor.move(-1f, 0f, true, 225)
        anchor.move(100f, 0f, true, 250)
        assertEquals(GitHubScrollMode.PIN_PROMPT, anchor.mode)
        anchor.endGesture(300f, 48f, 96f, cancelled = true)
        anchor.beginGesture()
        for (time in 500L..700L step 50) anchor.move(20f, 0f, true, time)
        assertEquals(GitHubScrollMode.PIN_PROMPT, anchor.mode)
        anchor.move(20f, 0f, true, 750)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
    }
}
