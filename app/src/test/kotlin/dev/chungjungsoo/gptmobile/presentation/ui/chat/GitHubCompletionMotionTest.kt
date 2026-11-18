package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCompletionMotionTest {
    @Test fun onlyASeenLiveSuccessfulAnswerTriggersReveal() {
        assertTrue(shouldBeginGitHubFinalReveal(true, true, false, true, false))
        assertFalse(shouldBeginGitHubFinalReveal(true, false, false, true, false))
        assertFalse(shouldBeginGitHubFinalReveal(true, true, true, true, false))
        assertFalse(shouldBeginGitHubFinalReveal(true, true, false, false, false))
        assertFalse(shouldBeginGitHubFinalReveal(true, true, false, true, true))
        assertFalse(shouldBeginGitHubFinalReveal(false, true, false, true, false))
    }

    @Test fun completedAnswerNeverStealsBottomFollowingOrReadingHistory() {
        assertFalse(mayRepositionCompletedPrompt(GitHubScrollMode.FOLLOW_BOTTOM, false))
        assertFalse(mayRepositionCompletedPrompt(GitHubScrollMode.USER_HISTORY, false))
        assertFalse(mayRepositionCompletedPrompt(GitHubScrollMode.PIN_PROMPT, true))
        assertTrue(mayRepositionCompletedPrompt(GitHubScrollMode.PIN_PROMPT, false))
    }

    @Test fun finalRevealAndEdgeGlowUseSeparatePreciseDurations() {
        assertEquals(1000, GITHUB_COMPLETION_PROGRESS_FADE_MILLIS)
        assertEquals(2000, GITHUB_COMPLETION_ANSWER_FADE_MILLIS)
        assertEquals(2000, GITHUB_COMPLETION_PROMPT_SCROLL_MILLIS)
        assertEquals(3000, GITHUB_COMPLETION_GLOW_HOLD_MILLIS)
        assertEquals(2000, GITHUB_COMPLETION_GLOW_FADE_MILLIS)
    }
}
