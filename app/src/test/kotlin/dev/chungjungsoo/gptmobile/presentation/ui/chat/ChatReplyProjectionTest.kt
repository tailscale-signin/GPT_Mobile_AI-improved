package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.chat.CompletedGeneration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class ChatReplyProjectionTest {
    @Test fun otherTurnsDoNotInvalidateAcknowledgementAndSameMessageRevisionsDo() {
        val projection = ChatReplyProjection()
        val reply = CompletedGeneration("run", 1, 11, 100, revision = 2, contentDigest = "old")
        val first = projection.select(1, setOf(11), listOf(reply))
        repeat(50) { tick ->
            assertSame(first, projection.select(1, setOf(11), listOf(reply, reply.copy(chatId = 2, revision = tick.toLong()), reply.copy(assistantMessageId = 22))))
        }
        val updated = projection.select(1, setOf(11), listOf(reply.copy(revision = 3, contentDigest = "new")))
        assertNotSame(first, updated)
        assertEquals("new", updated.single().contentDigest)
        assertEquals(emptyList<CompletedGeneration>(), projection.select(1, setOf(11), emptyList()))
    }
}
