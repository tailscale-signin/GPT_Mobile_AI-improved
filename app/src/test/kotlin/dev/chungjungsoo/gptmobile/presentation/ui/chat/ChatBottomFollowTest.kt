package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatBottomFollowTest {
    @Test fun downwardBottomArrivalUsesTheSameFollowActionAsTheButton() {
        val intent = GitHubPromptAnchor(8f, GitHubScrollMode.USER_HISTORY)
        intent.move(-20f)
        intent.endGesture()
        assertFalse(intent.shouldFollowAtBottom(false))
        assertTrue(intent.shouldFollowAtBottom(true))
        intent.followBottom()
        assertTrue(intent.mode == GitHubScrollMode.FOLLOW_BOTTOM)
    }

    @Test fun upwardInputDetachesEvenIfTheBottomSentinelIsStillVisible() {
        val intent = GitHubPromptAnchor(8f, GitHubScrollMode.FOLLOW_BOTTOM)
        intent.move(9f)
        assertTrue(intent.mode == GitHubScrollMode.USER_HISTORY)
        assertFalse(intent.shouldFollowAtBottom(true))
        intent.endGesture()
        assertFalse(intent.shouldFollowAtBottom(true))
    }

    @Test fun keyboardHeaderAndIdleGeometryChangesNeverReattachHistory() {
        val intent = GitHubPromptAnchor(8f, GitHubScrollMode.USER_HISTORY)
        assertFalse(intent.shouldFollowAtBottom(true))
        intent.move(-20f)
        intent.endGesture()
        intent.finishScroll()
        assertFalse(intent.shouldFollowAtBottom(true))
    }

    @Test
    fun backwardFlingInProgress_blocksAutoScroll() {
        assertFalse(
            shouldAutoScrollToBottom(
                isFollowing = true,
                isUserDragging = false,
                isScrollInProgress = true,
                isScrollingAway = true
            )
        )
    }

    @Test
    fun idleContentGrowthWhileFollowing_allowsAutoScroll() {
        assertTrue(
            shouldAutoScrollToBottom(
                isFollowing = true,
                isUserDragging = false,
                isScrollInProgress = false,
                isScrollingAway = false
            )
        )
    }
}
