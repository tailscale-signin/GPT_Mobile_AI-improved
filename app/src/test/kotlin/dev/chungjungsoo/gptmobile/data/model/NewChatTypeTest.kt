package dev.chungjungsoo.gptmobile.data.model

import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NewChatTypeTest {
    @Test
    fun singleRequiresOneProfile() {
        assertFalse(NewChatType.SINGLE.isCountValid(0))
        assertTrue(NewChatType.SINGLE.isCountValid(1))
        assertFalse(NewChatType.SINGLE.isCountValid(2))
        assertEquals(ConversationMode.STANDARD, NewChatType.SINGLE.executionMode(1))
    }

    @Test
    fun researchAllowsOneAndCombinesMultiple() {
        assertFalse(NewChatType.RESEARCH.isCountValid(0))
        assertTrue(NewChatType.RESEARCH.isCountValid(1))
        assertTrue(NewChatType.RESEARCH.isCountValid(3))
        assertEquals(ConversationMode.STANDARD, NewChatType.RESEARCH.executionMode(1))
        assertEquals(ConversationMode.COMBINED, NewChatType.RESEARCH.executionMode(2))
    }

    @Test
    fun sharedInputMultiChatKeepsSeparateModelResponses() {
        assertFalse(NewChatType.MULTI_CHAT.isCountValid(1))
        assertTrue(NewChatType.MULTI_CHAT.isCountValid(2))
        assertEquals(ConversationMode.STANDARD, NewChatType.MULTI_CHAT.executionMode(3))
    }

    @Test
    fun combinedRequiresMultipleProfiles() {
        assertFalse(NewChatType.COMBINED.isCountValid(1))
        assertTrue(NewChatType.COMBINED.isCountValid(2))
        assertEquals(ConversationMode.COMBINED, NewChatType.COMBINED.executionMode(2))
    }
}
