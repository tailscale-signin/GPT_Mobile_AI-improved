package dev.melo.gptmobile.improved.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.melo.gptmobile.improved.data.database.entity.ChatRoomV2
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatRoomV2Dao {

    @Query("SELECT * FROM chats_v2 ORDER BY updated_at DESC")
    fun getAll(): Flow<List<ChatRoomV2>>

    @Insert
    suspend fun insert(chatRoom: ChatRoomV2): Long

    @Upsert
    suspend fun upsert(chatRoom: ChatRoomV2)

    @Update
    suspend fun update(chatRoom: ChatRoomV2)

    @Delete
    suspend fun delete(chatRoom: ChatRoomV2)

    @Query("SELECT * FROM chats_v2 WHERE chat_id = :id")
    suspend fun get(id: Int): ChatRoomV2?

    @Query("SELECT * FROM chats_v2 ORDER BY chat_id DESC LIMIT 1")
    suspend fun getLatest(): ChatRoomV2?
}
