package dev.chungjungsoo.gptmobile.presentation.ui.home

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class HomeArchivePolicyTest {
    private fun chat(id: Int, updatedAt: Long = id.toLong(), pinned: Boolean = false) =
        ChatRoomV2(id = id, title = "Chat $id", updatedAt = updatedAt, isFavorite = pinned)

    @Test
    fun `twenty visible chats do not auto archive`() {
        val chats = (1..20).map(::chat)

        assertEquals(emptyList<ChatRoomV2>(), automaticArchiveTargets(chats, emptySet()))
    }

    @Test
    fun `offline gateway chats neither consume the ordinary quota nor become archive targets`() {
        val gateway = (100..130).map { chat(it).copy(conversationMode = dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.GATEWAY) }
        assertEquals(emptyList<ChatRoomV2>(), automaticArchiveTargets((1..20).map(::chat) + gateway, emptySet()))
        val overflow = automaticArchiveTargets((1..24).map(::chat) + gateway, emptySet())
        assertEquals(listOf(1, 3, 5, 7), overflow.map { it.id })
        assertFalse(overflow.any { it.id >= 100 })
    }

    @Test
    fun `overflow archives alternating oldest chats`() {
        val chats = (1..24).map(::chat)

        val archived = automaticArchiveTargets(chats, emptySet())

        assertEquals(listOf(1, 3, 5, 7), archived.map { it.id })
    }

    @Test
    fun `pinned and active chats are never auto archived`() {
        val chats = (1..24).map { id ->
            chat(id, pinned = id == 1 || id == 3)
        }

        val archived = automaticArchiveTargets(chats, activeIds = setOf(2, 6))

        assertEquals(4, archived.size)
        assertFalse(archived.any { it.isFavorite })
        assertFalse(archived.any { it.id in setOf(2, 6) })
        assertEquals(listOf(4, 7, 9, 11), archived.map { it.id })
    }
}
