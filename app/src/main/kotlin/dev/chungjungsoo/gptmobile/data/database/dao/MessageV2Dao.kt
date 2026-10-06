package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.chat.decodedArchiveText
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Dao
interface MessageV2Dao {
    @Query("SELECT m.message_id FROM messages_v2 m LEFT JOIN agent_runs r ON r.run_id=m.current_run_id WHERE m.chat_id=:chatId AND m.platform_type IS NOT NULL AND LENGTH(TRIM(m.content)) > 0 AND (m.current_run_id IS NULL OR r.status='COMPLETED') ORDER BY m.created_at DESC, LENGTH(m.combined_sources) DESC, m.message_id DESC LIMIT 1")
    suspend fun newestAssistantMessageId(chatId: Int): Int?

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId ORDER BY created_at DESC, message_id DESC LIMIT 120")
    suspend fun rawComparisonHistory(chatId: Int): List<MessageV2>

    @Query("SELECT * FROM messages_v2 WHERE attachments != '[]' ORDER BY created_at DESC, message_id DESC")
    fun rawObserveAttachments(): Flow<List<MessageV2>>

    @Query("SELECT * FROM messages_v2 WHERE message_id = :id")
    suspend fun rawMessage(id: Int): MessageV2?

    @Query("SELECT * FROM messages_v2 WHERE chat_id=:chatInt")
    suspend fun rawLoadMessages(chatInt: Int): List<MessageV2>

    @Query("SELECT * FROM messages_v2 ORDER BY created_at, message_id")
    suspend fun rawGetMessageList(): List<MessageV2>

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId ORDER BY created_at, message_id")
    fun rawObserveMessages(chatId: Int): Flow<List<MessageV2>>

    @Query("SELECT DISTINCT m.chat_id FROM messages_v2 m JOIN messages_search s ON s.rowid=m.message_id JOIN chats_v2 c ON c.chat_id=m.chat_id WHERE c.is_archived=0 AND messages_search MATCH :query")
    suspend fun searchMessagesFts(query: String): List<Int>

    suspend fun searchMessagesByContent(query: String): List<Int> {
        val escaped = dev.chungjungsoo.gptmobile.data.database.entity.messageSearchQuery(query)
        if (escaped.isBlank()) return emptyList()
        val terms = query.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        val archived = rawArchivedSearchMessages().map { it.decodedArchiveText() }.filter { message ->
            val text = message.content + " " + message.revisions.joinToString(" ") { it.content }
            terms.all { text.contains(it, ignoreCase = true) }
        }.map { it.chatId }
        return (searchMessagesFts(escaped) + archived).distinct()
    }

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId AND message_id >= COALESCE((SELECT message_id FROM messages_v2 WHERE chat_id = :chatId AND platform_type IS NULL ORDER BY message_id DESC LIMIT 1 OFFSET :offset), 0) ORDER BY created_at, message_id")
    fun rawObserveWindow(chatId: Int, offset: Int): Flow<List<MessageV2>>

    @Query("SELECT COUNT(*) FROM messages_v2 WHERE chat_id = :chatId AND platform_type IS NULL")
    fun observeTurnCount(chatId: Int): Flow<Int>

    @Query("UPDATE messages_v2 SET is_favorite = :isFavorite WHERE message_id = :messageId")
    suspend fun updateFavorite(messageId: Int, isFavorite: Boolean)

    @Query(
        "SELECT * FROM messages_v2 " +
            "WHERE is_favorite = 1 AND platform_type IS NOT NULL " +
            "ORDER BY created_at DESC, message_id DESC"
    )
    fun rawObserveFavoriteAssistantMessages(): Flow<List<MessageV2>>

    fun searchFavoriteAssistantMessages(query: String): Flow<List<MessageV2>> = observeFavoriteAssistantMessages().map { messages ->
        messages.filter { it.content.contains(query, ignoreCase = true) || it.revisions.any { revision -> revision.content.contains(query, ignoreCase = true) } }
    }

    @Query("SELECT m.* FROM messages_v2 m JOIN chats_v2 c ON c.chat_id=m.chat_id WHERE c.is_archived=1")
    suspend fun rawArchivedSearchMessages(): List<MessageV2>

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

    suspend fun comparisonHistory(chatId: Int): List<MessageV2> = rawComparisonHistory(chatId).map { it.decodedArchiveText() }

    fun observeAttachments(): Flow<List<MessageV2>> = rawObserveAttachments().map { messages -> messages.map { it.decodedArchiveText() } }

    suspend fun message(id: Int): MessageV2? = rawMessage(id)?.decodedArchiveText()

    suspend fun loadMessages(chatInt: Int): List<MessageV2> = rawLoadMessages(chatInt).map { it.decodedArchiveText() }

    suspend fun getMessageList(): List<MessageV2> = rawGetMessageList().map { it.decodedArchiveText() }

    fun observeMessages(chatId: Int): Flow<List<MessageV2>> = rawObserveMessages(chatId).map { messages -> messages.map { it.decodedArchiveText() } }

    fun observeWindow(chatId: Int, offset: Int): Flow<List<MessageV2>> = rawObserveWindow(chatId, offset).map { messages -> messages.map { it.decodedArchiveText() } }

    fun observeFavoriteAssistantMessages(): Flow<List<MessageV2>> = rawObserveFavoriteAssistantMessages().map { messages -> messages.map { it.decodedArchiveText() } }
}
