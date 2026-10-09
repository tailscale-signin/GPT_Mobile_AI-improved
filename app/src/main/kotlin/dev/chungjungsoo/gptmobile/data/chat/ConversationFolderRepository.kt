package dev.chungjungsoo.gptmobile.data.chat

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationFolderRepository @Inject constructor(private val database: ChatDatabaseV2) {
    private val dao get() = database.conversationFolderDao()
    val folders get() = dao.observeFolders()
    val members get() = dao.observeMembers()

    suspend fun create(chatId: Int, name: String, color: Long): String {
        val folder = ConversationFolder(UUID.randomUUID().toString(), ConversationFolderStyle.name(name), ConversationFolderStyle.color(color), System.currentTimeMillis())
        database.withTransaction {
            dao.insertFolder(folder)
            dao.move(ConversationFolderMember(chatId, folder.id))
        }
        return folder.id
    }

    suspend fun move(chatId: Int, folderId: String?) {
        if (folderId == null) dao.remove(chatId) else dao.move(ConversationFolderMember(chatId, folderId))
    }

    suspend fun edit(id: String, name: String, color: Long) = dao.edit(id, ConversationFolderStyle.name(name), ConversationFolderStyle.color(color))
    suspend fun delete(id: String) = dao.delete(id)
}
