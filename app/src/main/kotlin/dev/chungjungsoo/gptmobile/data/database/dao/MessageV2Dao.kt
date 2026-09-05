package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageV2Dao {
    @Query("SELECT * FROM messages_v2 WHERE chat_room_id = :chatRoomId ORDER BY created_at ASC")
    fun getMessages(chatRoomId: Int): Flow<List<MessageV2>>

    @Query("SELECT * FROM messages_v2 WHERE chat_room_id = :chatRoomId ORDER BY created_at ASC")
    suspend fun getMessagesSync(chatRoomId: Int): List<MessageV2>

    @Query("SELECT * FROM messages_v2 WHERE message_id = :messageId")
    suspend fun getMessage(messageId: Int): MessageV2?

    @Query("SELECT * FROM messages_v2 WHERE chat_room_id = :chatRoomId ORDER BY created_at DESC LIMIT 1")
    suspend fun getLatestMessage(chatRoomId: Int): MessageV2?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageV2): Long

    @Update
    suspend fun updateMessage(message: MessageV2)

    @Query("DELETE FROM messages_v2 WHERE message_id = :messageId")
    suspend fun deleteMessage(messageId: Int)

    @Query("DELETE FROM messages_v2 WHERE chat_room_id = :chatRoomId")
    suspend fun deleteMessages(chatRoomId: Int)

    @Query("SELECT * FROM messages_v2 WHERE content LIKE '%' || :query || '%' ORDER BY created_at DESC")
    suspend fun searchMessages(query: String): List<MessageV2>

    @Query("UPDATE messages_v2 SET is_favorite = :isFavorite WHERE message_id = :messageId")
    suspend fun updateFavorite(messageId: Int, isFavorite: Boolean)

    @Query("SELECT * FROM messages_v2 WHERE is_favorite = 1 AND platform_type IS NOT NULL ORDER BY created_at DESC")
    fun observeFavoriteMessages(): Flow<List<MessageV2>>

    @Query("SELECT * FROM messages_v2 WHERE is_favorite = 1 AND platform_type IS NOT NULL ORDER BY created_at DESC")
    suspend fun getFavoriteMessages(): List<MessageV2>

    @Query("SELECT * FROM messages_v2 WHERE is_favorite = 1 AND platform_type IS NOT NULL AND (content LIKE '%' || :query || '%' OR revisions LIKE '%' || :query || '%') ORDER BY created_at DESC")
    suspend fun searchFavoriteMessages(query: String): List<MessageV2>
}
