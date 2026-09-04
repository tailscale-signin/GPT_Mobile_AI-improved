package dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomV2Dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatRoomV2Dao {
    @Query("SELECT * FROM chats_v2 ORDER BY is_favorite DESC, updated_at DESC")
    fun getAllChats(): Flow<List<ChatRoomV2>>

    @Query("SELECT * FROM chats_v2 WHERE chat_id = :chatId")
    suspend fun getChatById(chatId: Int): ChatRoomV2?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChat(chat: ChatRoomV2): Long

    @Update
    suspend fun updateChat(chat: ChatRoomV2)

    @Delete
    suspend fun deleteChat(chat: ChatRoomV2)

    @Query("DELETE FROM chats_v2 WHERE chat_id = :chatId")
    suspend fun deleteChatById(chatId: Int)

    @Query("UPDATE chats_v2 SET is_favorite = :isFavorite WHERE chat_id = :chatId")
    suspend fun updateFavorite(chatId: Int, isFavorite: Boolean)
}
