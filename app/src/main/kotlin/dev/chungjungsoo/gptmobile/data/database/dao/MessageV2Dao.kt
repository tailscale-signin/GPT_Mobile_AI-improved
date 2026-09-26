package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageV2Dao {

    @Query("SELECT * FROM messages_v2 WHERE attachments != '[]' ORDER BY created_at DESC, message_id DESC")
    fun observeAttachments(): Flow<List<MessageV2>>

    @Query("SELECT * FROM messages_v2 WHERE chat_id=:chatInt")
    suspend fun loadMessages(chatInt: Int): List<MessageV2>

    @Query("SELECT * FROM messages_v2 ORDER BY created_at, message_id")
    suspend fun getMessageList(): List<MessageV2>

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId ORDER BY created_at, message_id")
    fun observeMessages(chatId: Int): Flow<List<MessageV2>>

    @Query("SELECT DISTINCT m.chat_id FROM messages_v2 m JOIN messages_search s ON s.rowid=m.message_id WHERE messages_search MATCH :query")
    suspend fun searchMessagesFts(query: String): List<Int>

    suspend fun searchMessagesByContent(query: String): List<Int> {
        val escaped = dev.chungjungsoo.gptmobile.data.database.entity.messageSearchQuery(query)
        return if (escaped.isBlank()) emptyList() else searchMessagesFts(escaped)
    }

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId AND message_id >= COALESCE((SELECT message_id FROM messages_v2 WHERE chat_id = :chatId AND platform_type IS NULL ORDER BY message_id DESC LIMIT 1 OFFSET :offset), 0) ORDER BY created_at, message_id")
    fun observeWindow(chatId: Int, offset: Int): Flow<List<MessageV2>>

    @Query("SELECT COUNT(*) FROM messages_v2 WHERE chat_id = :chatId AND platform_type IS NULL")
    fun observeTurnCount(chatId: Int): Flow<Int>

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

    @Transaction
    @Insert
    suspend fun addMessages(vararg messages: MessageV2)

    @Transaction
    @Insert
    suspend fun insertMessageList(messages: List<MessageV2>)

    @Update
    suspend fun editMessages(vararg message: MessageV2)

    @Delete
    suspend fun deleteMessages(vararg message: MessageV2)
}
