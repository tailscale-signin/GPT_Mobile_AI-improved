package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.melo.gptmobile.improved.data.database.entity.ChatRoom
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatRoomDao {
    @Query("SELECT * FROM chats ORDER BY created_at")
    fun getAll(): Flow<List<ChatRoom>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chatRoom: ChatRoom)

    @Update
    suspend fun update(chatRoom: ChatRoom)

    @Delete
    suspend fun delete(chatRoom: ChatRoom)

    @Query("SELECT * FROM chats WHERE chat_id = :id")
    suspend fun get(id: Int): ChatRoom?

    @Query("SELECT * FROM chats ORDER BY chat_id DESC LIMIT 1")
    suspend fun getLatest(): ChatRoom?
}
