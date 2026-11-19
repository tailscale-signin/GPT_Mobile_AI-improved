package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPromptAnchorTest {
    @Test fun eachNewHistoryGestureReportsOneDetachAfterTheButtonRestoresFollow() {
        val input = GitHubPromptAnchor(8f, GitHubScrollMode.FOLLOW_BOTTOM)
        var following = true
        repeat(2) {
            assertFalse(input.move(3f))
            if (input.move(5f)) following = false
            assertFalse(following)
            assertFalse(input.move(20f))
            input.finishScroll()
            // The chat's jump button restores following outside the input detector.
            following = true
        }
    }

    @Test fun followStopsOnceHistoryDragPassesTouchSlop() {
        val anchor = GitHubPromptAnchor(8f, GitHubScrollMode.FOLLOW_BOTTOM)
        anchor.move(4f)
        assertTrue(anchor.dragging)
        assertEquals(GitHubScrollMode.FOLLOW_BOTTOM, anchor.mode)
        anchor.move(4f)
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
        anchor.endGesture()
        assertFalse(anchor.dragging)
        assertFalse(anchor.shouldFollowAtBottom(true))
    }

    @Test fun returnIntentSurvivesFingerUpUntilFlingReachesTheBottom() {
        val anchor = GitHubPromptAnchor(8f, GitHubScrollMode.USER_HISTORY)
        anchor.move(-20f)
        anchor.endGesture()
        assertFalse(anchor.shouldFollowAtBottom(false))
        assertTrue(anchor.shouldFollowAtBottom(true))
        anchor.followBottom()
        assertEquals(GitHubScrollMode.FOLLOW_BOTTOM, anchor.mode)
        assertFalse(anchor.shouldFollowAtBottom(true))
    }

    @Test fun stoppedFlingsAndLayoutOnlyChangesDoNotAttach() {
        val anchor = GitHubPromptAnchor(8f, GitHubScrollMode.USER_HISTORY)
        assertFalse(anchor.shouldFollowAtBottom(true))
        anchor.move(-20f)
        anchor.endGesture()
        anchor.finishScroll()
        assertFalse(anchor.shouldFollowAtBottom(true))
        assertEquals(GitHubScrollMode.USER_HISTORY, anchor.mode)
    }

    @Test fun reversedOrCancelledGesturesClearReturnIntent() {
        val anchor = GitHubPromptAnchor(8f, GitHubScrollMode.USER_HISTORY)
        anchor.move(-20f)
        anchor.move(3f)
        assertFalse(anchor.shouldFollowAtBottom(true))
        anchor.move(-20f)
        anchor.endGesture(cancelled = true)
        assertFalse(anchor.shouldFollowAtBottom(true))
    }
}
