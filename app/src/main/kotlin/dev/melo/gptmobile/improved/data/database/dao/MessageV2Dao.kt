package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.melo.gptmobile.improved.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageV2Dao {

    @Query("SELECT * FROM messages_v2 ORDER BY message_id")
    fun getAll(): Flow<List<MessageV2>>

    @Insert
    suspend fun insert(message: MessageV2): Long

    @Upsert
    suspend fun upsert(message: MessageV2)

    @Update
    suspend fun update(message: MessageV2)

    @Delete
    suspend fun delete(message: MessageV2)

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :id ORDER BY created_at, message_id")
    fun getMessages(id: Int): Flow<List<MessageV2>>

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :id ORDER BY created_at, message_id")
    suspend fun getMessagesDirect(id: Int): List<MessageV2>

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :chatId AND message_id = :messageId")
    suspend fun getMessage(chatId: Int, messageId: Int): MessageV2?

    @Query("SELECT * FROM messages_v2 WHERE chat_id = :id ORDER BY created_at DESC, message_id DESC LIMIT 1")
    suspend fun getLatestMessage(id: Int): MessageV2?
}
