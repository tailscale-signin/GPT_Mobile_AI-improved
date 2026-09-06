package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.melo.gptmobile.improved.data.database.entity.Message
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY message_id")
    fun getAll(): Flow<List<Message>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: Message)

    @Update
    suspend fun update(message: Message)

    @Delete
    suspend fun delete(message: Message)

    @Query("SELECT * FROM messages WHERE chat_id = :id ORDER BY message_id")
    fun getMessages(id: Int): Flow<List<Message>>

    @Query("SELECT * FROM messages WHERE chat_id = :id ORDER BY message_id")
    suspend fun getMessagesDirect(id: Int): List<Message>

    @Query("SELECT * FROM messages WHERE chat_id = :chatId AND message_id = :messageId")
    suspend fun getMessage(chatId: Int, messageId: Int): Message?

    @Query("SELECT * FROM messages WHERE chat_id = :id ORDER BY message_id DESC LIMIT 1")
    suspend fun getLatestMessage(id: Int): Message?
}
