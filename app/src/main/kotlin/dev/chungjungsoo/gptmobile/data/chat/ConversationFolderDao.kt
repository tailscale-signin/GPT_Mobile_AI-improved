package dev.chungjungsoo.gptmobile.data.chat

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationFolderDao {
    @Query("SELECT * FROM conversation_folders ORDER BY createdAt, id")
    fun observeFolders(): Flow<List<ConversationFolder>>

    @Query("SELECT * FROM conversation_folder_members")
    fun observeMembers(): Flow<List<ConversationFolderMember>>

    @Query("SELECT * FROM conversation_folders ORDER BY createdAt, id")
    suspend fun folders(): List<ConversationFolder>

    @Query("SELECT * FROM conversation_folder_members")
    suspend fun members(): List<ConversationFolderMember>

    @Upsert
    suspend fun restoreFolder(folder: ConversationFolder)

    @Insert
    suspend fun insertFolder(folder: ConversationFolder)

    @Upsert
    suspend fun move(member: ConversationFolderMember)

    @Query("UPDATE conversation_folders SET name = :name, color = :color WHERE id = :id")
    suspend fun edit(id: String, name: String, color: Long)

    @Query("DELETE FROM conversation_folders WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM conversation_folder_members WHERE chatId = :chatId")
    suspend fun remove(chatId: Int)
}
