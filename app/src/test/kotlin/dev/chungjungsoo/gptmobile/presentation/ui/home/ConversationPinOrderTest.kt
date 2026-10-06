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
        assertEquals(0, conversationPinDrop(false, 170f, 1000, 160f, 40f, emptyList(), -100f))
        assertNull(conversationPinDrop(false, 400f, 1000, 160f, 40f, emptyList(), -100f))
    }

    @Test
    fun draggingIntoLowerHalfUnpinsAndPinnedRowsCanReorder() {
        assertEquals(-1, conversationPinDrop(true, 500f, 1000, 160f, 40f, listOf(1 to 200f), 100f))
        assertEquals(1, conversationPinDrop(true, 230f, 1000, 160f, 40f, listOf(1 to 200f, 2 to 300f), 80f))
    }

    @Test
    fun upwardDragInLowerHalfDoesNotUnpin() {
        assertNull(conversationPinDrop(true, 600f, 1000, 160f, 40f, listOf(1 to 200f), -100f))
    }

    @Test
    fun stationaryLongPressOrSmallMovementDoesNotChangePin() {
        assertNull(conversationPinDrop(false, 170f, 1000, 160f, 40f, emptyList(), 0f))
        assertNull(conversationPinDrop(true, 600f, 1000, 160f, 40f, emptyList(), 10f))
    }

    @Test
    fun downwardDragDoesNotPinUnpinnedChatOrUnpinBeforeLowerHalf() {
        assertNull(conversationPinDrop(false, 170f, 1000, 160f, 40f, emptyList(), 100f))
        assertNull(conversationPinDrop(true, 499f, 1000, 160f, 40f, emptyList(), 100f))
    }
}
