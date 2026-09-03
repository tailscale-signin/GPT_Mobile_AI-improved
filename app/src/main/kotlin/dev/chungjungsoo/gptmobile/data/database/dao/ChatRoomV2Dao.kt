package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2

@Dao
interface ChatRoomV2Dao {

    @Query("SELECT * FROM chats_v2 ORDER BY is_favorite DESC, updated_at DESC")
    suspend fun getChatRooms(): List<ChatRoomV2>

    @Query("SELECT * FROM chats_v2 WHERE title LIKE '%' || :query || '%' ORDER BY is_favorite DESC, updated_at DESC")
    suspend fun searchChatRoomsByTitle(query: String): List<ChatRoomV2>

    @Insert
    suspend fun addChatRoom(chatRoom: ChatRoomV2): Long

    @Update
    suspend fun editChatRoom(chatRoom: ChatRoomV2)

    @Query("UPDATE chats_v2 SET is_favorite = :isFavorite WHERE chat_id = :chatId")
    suspend fun updateFavorite(chatId: Int, isFavorite: Boolean)

    @Delete
    suspend fun deleteChatRooms(vararg chatRooms: ChatRoomV2)
}
