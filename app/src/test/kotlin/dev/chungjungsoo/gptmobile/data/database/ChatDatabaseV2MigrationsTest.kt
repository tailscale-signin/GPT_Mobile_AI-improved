package dev.chungjungsoo.gptmobile.data.database

import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatDatabaseV2MigrationsTest {

    @Test
    fun defaultFavoriteStateIsFalse() {
        val chatRoom = ChatRoomV2(title = "Test Room")
        assertFalse(chatRoom.isFavorite)
    }

    @Test
    fun migration11To12AddsFavoriteColumnStatement() {
        assertEquals(
            listOf("ALTER TABLE `chats_v2` ADD COLUMN `is_favorite` INTEGER NOT NULL DEFAULT 0"),
            ChatDatabaseV2Migrations.CHAT_FAVORITE_COLUMN_MIGRATIONS
        )
    }
}
