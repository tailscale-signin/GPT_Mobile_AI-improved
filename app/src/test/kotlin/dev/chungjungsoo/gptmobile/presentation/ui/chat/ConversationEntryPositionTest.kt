package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationEntryPositionTest {
    @Test
    fun headerAndComposerAreExcludedFromResponseCenter() {
        // A 1000px list: 100px header and 200px composer. Content coordinates start after the header.
        assertEquals(350, conversationEntryCenter(viewportStart = -100, viewportEnd = 900, topInset = 100, bottomInset = 200))
    }

    @Test
    fun keyboardResizeKeepsResponseAboveComposer() {
        assertEquals(150, conversationEntryCenter(viewportStart = -100, viewportEnd = 500, topInset = 100, bottomInset = 200))
    }

    @Test
    fun hiddenComposerUsesFullRemainingViewport() {
        assertEquals(450, conversationEntryCenter(viewportStart = -100, viewportEnd = 900, topInset = 100, bottomInset = 0))
    }

    @Test
    fun compressedViewportDoesNotPlaceResponseBehindHeader() {
        assertEquals(0, conversationEntryCenter(viewportStart = -100, viewportEnd = 150, topInset = 100, bottomInset = 200))
    }
}
