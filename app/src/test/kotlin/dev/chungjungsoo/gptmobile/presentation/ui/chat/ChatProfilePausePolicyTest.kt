package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatProfilePausePolicyTest {
    @Test
    fun lastParticipantCannotBePausedButPausedProfileCanResume() {
        assertFalse(canPauseChatProfile("a", setOf("a"), emptySet()))
        assertTrue(canPauseChatProfile("a", setOf("a", "b"), emptySet()))
        assertFalse(canPauseChatProfile("a", setOf("a", "b"), setOf("b")))
        assertTrue(canPauseChatProfile("b", setOf("a", "b"), setOf("b")))
        assertFalse(canPauseChatProfile("c", setOf("a", "b"), emptySet()))
    }
}
