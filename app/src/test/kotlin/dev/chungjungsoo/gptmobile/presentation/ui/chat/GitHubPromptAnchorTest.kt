package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPromptAnchorTest {
    @Test fun intensiveDragNeedsOneSecondBeforeHistoryReleases() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        for (time in 0L..900L step 100) {
            assertEquals(20f, anchor.move(20f, 0f, true, time).consumed)
            assertTrue(anchor.attached)
        }
        assertEquals(0f, anchor.move(20f, 0f, true, 1000).consumed)
        assertFalse(anchor.attached)
    }

    @Test fun durationAloneOrSeparateSwipesCannotReleaseHistory() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        for (time in 0L..1200L step 100) anchor.move(1f, 0f, true, time)
        assertTrue(anchor.attached)
        anchor.beginGesture()
        anchor.move(100f, 0f, true, 1300)
        anchor.move(100f, 0f, true, 2600)
        assertTrue(anchor.attached)
        anchor.fling(2000f, 1000f)
        assertTrue(anchor.attached)
    }

    @Test fun reverseDirectionResetsHistoryClockAndDownwardMotionReattaches() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        for (time in 0L..900L step 100) anchor.move(20f, 0f, true, time)
        anchor.move(-1f, 0f, true, 950)
        assertEquals(100f, anchor.move(100f, 0f, true, 1000).consumed)
        assertTrue(anchor.attached)
        anchor.detach()
        assertFalse(anchor.move(-10f, 0f, true, 1100).reattach)
        assertTrue(anchor.move(-24f, 0f, true, 1200).reattach)
        assertTrue(anchor.attached)
    }

    @Test fun readingResponseIsUnrestrictedAndInertiaCannotChangeIntent() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        assertEquals(0f, anchor.move(100f, 200f, true, 0).consumed)
        assertEquals(0f, anchor.move(-100f, 0f, true, 100).consumed)
        assertEquals(1000f, anchor.move(1000f, 0f, false, 2000).consumed)
        assertTrue(anchor.attached)
        anchor.detach()
        assertEquals(0f, anchor.move(100f, 0f, false, 3000).consumed)
        assertTrue(anchor.fling(-1200f, 1000f).reattach)
    }
}
