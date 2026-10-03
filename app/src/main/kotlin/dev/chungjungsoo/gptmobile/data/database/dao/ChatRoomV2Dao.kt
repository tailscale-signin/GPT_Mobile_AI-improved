package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2

@Dao
interface ChatRoomV2Dao {

    @Query("SELECT * FROM chats_v2 WHERE is_archived = 0 ORDER BY is_favorite DESC, updated_at DESC LIMIT 200")
    fun observeNavigationChats(): kotlinx.coroutines.flow.Flow<List<ChatRoomV2>>

    @Query("SELECT * FROM chats_v2 WHERE is_archived = 0 ORDER BY updated_at DESC")
    suspend fun getChatRooms(): List<ChatRoomV2>

    @Query("SELECT * FROM chats_v2 WHERE is_archived = 0 ORDER BY is_favorite DESC, updated_at DESC")
    suspend fun getChatRoomsWithFavorites(): List<ChatRoomV2>

    @Query("SELECT * FROM chats_v2 WHERE is_archived = 0 AND title LIKE '%' || :query || '%' ORDER BY updated_at DESC")
    suspend fun searchChatRoomsByTitle(query: String): List<ChatRoomV2>

    @Query("SELECT * FROM chats_v2 WHERE is_archived = 1 ORDER BY updated_at DESC")
    suspend fun getArchivedChatRooms(): List<ChatRoomV2>

    @Query("SELECT * FROM chats_v2 WHERE chat_id IN (:ids) ORDER BY updated_at DESC")
    suspend fun getChatRoomsByIds(ids: List<Int>): List<ChatRoomV2>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun addChatRoom(chatRoom: ChatRoomV2): Long

    @Query("UPDATE chats_v2 SET enabled_platform = :history, active_platform = :active, updated_at = :updatedAt WHERE chat_id = :chatId")
    suspend fun updatePlatforms(chatId: Int, history: List<String>, active: List<String>, updatedAt: Long)

    @Query("UPDATE chats_v2 SET is_temporary = :enabled WHERE chat_id = :chatId")
    suspend fun updateTemporary(chatId: Int, enabled: Boolean)

    @Update
    suspend fun editChatRoom(chatRoom: ChatRoomV2)

    @Query("UPDATE chats_v2 SET is_favorite = :isFavorite WHERE chat_id = :chatId")
    suspend fun updateFavorite(chatId: Int, isFavorite: Boolean)

    @Query("UPDATE chats_v2 SET is_archived = :isArchived WHERE chat_id = :chatId")
    suspend fun updateArchived(chatId: Int, isArchived: Boolean)

    @Query("UPDATE chats_v2 SET draft_text = :draftText, draft_updated_at = :timestamp WHERE chat_id = :chatId")
    suspend fun updateDraft(chatId: Int, draftText: String?, timestamp: Long?)

    @Query("UPDATE chats_v2 SET draft_text = :text, draft_attachments = :attachments, draft_updated_at = :timestamp WHERE chat_id = :chatId")
    suspend fun saveComposerDraft(chatId: Int, text: String?, attachments: String, timestamp: Long?)

    @Query("UPDATE chats_v2 SET last_share_token = :token WHERE chat_id = :chatId")
    suspend fun markShareDelivered(chatId: Int, token: String)

    @Query("UPDATE chats_v2 SET title = :title, is_title_customized = :isCustomized, updated_at = :updatedAt WHERE chat_id = :chatId")
    suspend fun updateTitle(chatId: Int, title: String, isCustomized: Boolean, updatedAt: Long = System.currentTimeMillis() / 1000)

    @Query("SELECT * FROM chats_v2 WHERE is_temporary = 1")
    suspend fun temporaryChats(): List<ChatRoomV2>

    @Delete
    suspend fun deleteChatRooms(vararg chatRooms: ChatRoomV2)
}
