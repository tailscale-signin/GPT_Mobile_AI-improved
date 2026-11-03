package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubPromptAnchorTest {
    @Test fun shortUpwardGestureKeepsPromptAtTopButDeliberateDragReleasesHistory() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        assertEquals(20f, anchor.move(20f, 0f, true).consumed)
        assertTrue(anchor.attached)
        assertEquals(0f, anchor.move(50f, 0f, true).consumed)
        assertFalse(anchor.attached)
    }

    @Test fun downwardGestureReattachesAndNewPromptResetsIntent() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        anchor.detach()
        assertFalse(anchor.move(-10f, 0f, true).reattach)
        assertTrue(anchor.move(-24f, 0f, true).reattach)
        assertTrue(anchor.attached)
        anchor.detach()
        anchor.attach()
        assertTrue(anchor.attached)
    }

    @Test fun fastFlickReleasesAndReverseFlickRestoresWhileWeakInertiaStopsAtPrompt() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        anchor.fling(400f, 1000f)
        assertTrue(anchor.attached)
        assertEquals(80f, anchor.move(100f, 20f, false).consumed)
        anchor.fling(1200f, 1000f)
        assertFalse(anchor.attached)
        assertEquals(0f, anchor.move(100f, 0f, false).consumed)
        assertTrue(anchor.fling(-1200f, 1000f).reattach)
        assertTrue(anchor.attached)
    }

    @Test fun readingResponseIsUnrestrictedAndProgrammaticMotionCannotChangeIntent() {
        val anchor = GitHubPromptAnchor(64f, 32f)
        assertEquals(0f, anchor.move(100f, 200f, true).consumed)
        assertEquals(0f, anchor.move(-100f, 0f, true).consumed)
        assertEquals(1000f, anchor.move(1000f, 0f, false).consumed)
        assertTrue(anchor.attached)
        anchor.move(40f, 0f, true)
        anchor.beginGesture()
        assertEquals(40f, anchor.move(40f, 0f, true).consumed)
        assertTrue(anchor.attached)
    }
}
