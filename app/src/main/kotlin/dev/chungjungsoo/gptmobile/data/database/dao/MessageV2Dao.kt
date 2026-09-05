package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageV2Dao {

    @Query("SELECT * FROM messages_v2 WHERE chat_id=:chatInt")
    suspend fun loadMessages(chatInt: Int): List<MessageV2>

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId ORDER BY created_at, message_id")
    fun observeMessages(chatId: Int): Flow<List<MessageV2>>

    @Query(
        "SELECT DISTINCT chat_id FROM messages_v2 " +
            "WHERE content LIKE '%' || :query || '%' OR " +
            "revisions LIKE '%' || :query || '%'"
    )
    suspend fun searchMessagesByContent(query: String): List<Int>

    @Query("UPDATE messages_v2 SET is_favorite = :isFavorite WHERE message_id = :messageId")
    suspend fun updateFavorite(messageId: Int, isFavorite: Boolean)

    @Query(
        "SELECT * FROM messages_v2 " +
            "WHERE is_favorite = 1 AND platform_type IS NOT NULL " +
            "ORDER BY created_at DESC, message_id DESC"
    )
    fun observeFavoriteAssistantMessages(): Flow<List<MessageV2>>

    @Query(
        "SELECT * FROM messages_v2 " +
            "WHERE is_favorite = 1 AND platform_type IS NOT NULL " +
            "AND (content LIKE '%' || :query || '%' OR revisions LIKE '%' || :query || '%') " +
            "ORDER BY created_at DESC, message_id DESC"
    )
    fun searchFavoriteAssistantMessages(query: String): Flow<List<MessageV2>>

    @Insert
    suspend fun addMessages(vararg messages: MessageV2)

    @Update
    suspend fun editMessages(vararg message: MessageV2)

    @Delete
    suspend fun deleteMessages(vararg message: MessageV2)
}
