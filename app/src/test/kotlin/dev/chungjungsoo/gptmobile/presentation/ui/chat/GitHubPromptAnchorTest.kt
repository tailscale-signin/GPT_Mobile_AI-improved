package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPromptAnchorTest {
    @Test fun continuousUpwardDragReleasesAtExactlyAQuarterSecond() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        for (time in listOf(0L, 50L, 100L, 150L, 200L, 249L)) {
            assertEquals(20f, anchor.move(20f, 0f, true, time).consumed)
            assertTrue(anchor.attached)
        }
        assertEquals(0f, anchor.move(20f, 0f, true, 250).consumed)
        assertFalse(anchor.attached)
        assertEquals(0f, anchor.move(80f, 0f, true, 300).consumed)
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
        for (time in 0L..200L step 50) anchor.move(20f, 0f, true, time)
        anchor.move(-1f, 0f, true, 225)
        assertEquals(100f, anchor.move(100f, 0f, true, 250).consumed)
        assertTrue(anchor.attached)
        anchor.detach()
        assertFalse(anchor.move(-10f, 0f, true, 1100).reattach)
        assertTrue(anchor.move(-24f, 0f, true, 1200).reattach)
        assertTrue(anchor.attached)
    }

    @Test fun holdingStillOrCancellingAGestureCannotAccumulateReleaseTime() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        anchor.move(100f, 0f, true, 0)
        // Two isolated movements a quarter second apart are not a continuous drag.
        assertEquals(100f, anchor.move(100f, 0f, true, 250).consumed)
        assertTrue(anchor.attached)
        anchor.move(20f, 0f, true, 350)
        anchor.beginGesture()
        assertEquals(100f, anchor.move(100f, 0f, true, 500).consumed)
        assertTrue(anchor.attached)
        for (time in 550L..700L step 50) anchor.move(20f, 0f, true, time)
        assertEquals(0f, anchor.move(20f, 0f, true, 750).consumed)
        assertFalse(anchor.attached)
    }

    @Test fun explicitReattachmentStartsAFreshReleaseGestureAndIgnoresLayoutMotion() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        for (time in 0L..250L step 50) anchor.move(20f, 0f, true, time)
        assertFalse(anchor.attached)
        anchor.attach()
        anchor.move(100f, 0f, false, 500)
        assertEquals(100f, anchor.move(100f, 0f, true, 750).consumed)
        assertTrue(anchor.attached)
        anchor.detach()
        anchor.beginGesture()
        assertEquals(0f, anchor.move(100f, 0f, false, 1000).consumed)
        assertFalse(anchor.attached)
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
