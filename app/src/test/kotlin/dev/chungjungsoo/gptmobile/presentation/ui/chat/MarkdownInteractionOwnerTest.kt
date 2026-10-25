package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MarkdownInteractionOwnerTest {
    @Test fun replacedBodyAndDisposedRendererRejectTheirRetainedCallbacks() {
        val owner = MarkdownInteractionOwner()
        owner.update("draft")
        val oldCallback = { owner.accepts("draft") }
        assertTrue(oldCallback())
        owner.update("final")
        assertFalse(oldCallback())
        assertTrue(owner.accepts("final"))
        owner.dispose()
        assertFalse(owner.accepts("final"))
        owner.update("final")
        assertFalse(owner.active)
        assertFalse(owner.accepts("final"))
    }

    @Test fun documentPromotionAndNewRevisionHaveDifferentRendererOwnership() {
        val live = AssistantRendererIdentity("message-3", "run", 4, "revision", "inline")
        assertEquals(live, live.copy())
        assertNotEquals(live, live.copy(renderer = "document:manifest"))
        assertNotEquals(live, live.copy(revisionId = "next"))
        assertNotEquals(live, live.copy(fence = 5))
        assertNotEquals(live, live.copy(message = "message-4"))
    }
}
