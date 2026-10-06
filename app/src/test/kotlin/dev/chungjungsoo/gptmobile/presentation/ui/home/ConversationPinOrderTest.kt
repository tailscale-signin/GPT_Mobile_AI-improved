package dev.chungjungsoo.gptmobile.presentation.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationPinOrderTest {
    @Test
    fun movingExistingPinDoesNotDuplicateOrLoseOtherPins() {
        assertEquals(listOf(3, 1, 2), movePinnedConversation(listOf(1, 2, 3), 3, 0))
        assertEquals(listOf(2, 3, 1), movePinnedConversation(listOf(1, 2, 3), 1, 2))
    }

    @Test
    fun onlyDropAtTopPinsUnpinnedConversation() {
        assertEquals(0, conversationPinDrop(false, 170f, 1000, 160f, 40f, emptyList()))
        assertNull(conversationPinDrop(false, 400f, 1000, 160f, 40f, emptyList()))
    }

    @Test
    fun droppingBelowSeventyPercentUnpinsAndPinnedRowsCanReorder() {
        assertEquals(-1, conversationPinDrop(true, 700f, 1000, 160f, 40f, listOf(1 to 200f)))
        assertEquals(1, conversationPinDrop(true, 230f, 1000, 160f, 40f, listOf(1 to 200f, 2 to 300f)))
    }
}
