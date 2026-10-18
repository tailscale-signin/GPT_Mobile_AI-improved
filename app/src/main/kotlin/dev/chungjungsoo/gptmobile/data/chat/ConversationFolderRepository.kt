package dev.chungjungsoo.gptmobile.data.chat

import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import java.text.Normalizer
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@kotlinx.serialization.Serializable
data class FolderMoveReceipt(val version: String, val previous: Map<Int, String?>, val destination: String?, val created: ConversationFolder? = null)

@Singleton
class ConversationFolderRepository @Inject constructor(private val database: ChatDatabaseV2) {
    private val dao get() = database.conversationFolderDao()
    val folders get() = dao.observeFolders()
    val members get() = dao.observeMembers()
    private fun name(value: String) = ConversationFolderStyle.name(Normalizer.normalize(value, Normalizer.Form.NFC).replace(Regex("\\s+"), " "))

    suspend fun create(chatId: Int, name: String, color: Long): String = requireNotNull(createAndMove(setOf(chatId), name, color).destination)
    suspend fun move(chatId: Int, folderId: String?) {
        moveMany(setOf(chatId), folderId)
    }

    suspend fun moveMany(ids: Set<Int>, destination: String?): FolderMoveReceipt = database.withTransaction {
        destination?.let { requireNotNull(dao.folder(it)) { "The destination was deleted. Choose another folder." } }
        applyMove(ids, destination)
    }

    suspend fun createAndMove(ids: Set<Int>, rawName: String, color: Long): FolderMoveReceipt = database.withTransaction {
        val normalized = name(rawName)
        require(dao.folders().none { it.name.equals(normalized, true) }) { "A folder named $normalized already exists. Choose that destination." }
        val created = ConversationFolder(UUID.randomUUID().toString(), normalized, ConversationFolderStyle.color(color), System.currentTimeMillis())
        dao.insertFolder(created)
        applyMove(ids, created.id).copy(created = created)
    }

    private suspend fun applyMove(ids: Set<Int>, destination: String?): FolderMoveReceipt {
        require(ids.isNotEmpty()) { "Select a conversation first." }
        val targets = database.chatRoomDao().getChatRoomsByIds(ids.toList())
        require(targets.size == ids.size && targets.none { it.isArchived }) { "The selected conversations changed. Refresh and try again." }
        val previous = dao.members().filter { it.chatId in ids }.associate { it.chatId to it.folderId }
        val version = UUID.randomUUID().toString()
        ids.forEach { id ->
            if (destination == null) dao.remove(id) else dao.move(ConversationFolderMember(id, destination))
            dao.saveMoveVersion(ConversationFolderMove(id, version))
        }
        return FolderMoveReceipt(version, ids.associateWith { previous[it] }, destination)
    }

    suspend fun undo(receipt: FolderMoveReceipt): Pair<Int, Int> = database.withTransaction {
        var restored = 0
        var missing = 0
        receipt.previous.forEach { (id, previous) ->
            if (dao.moveVersion(id)?.version != receipt.version) return@forEach
            val available = previous?.takeIf { dao.folder(it) != null }
            if (previous != null && available == null) missing++
            if (available == null) dao.remove(id) else dao.move(ConversationFolderMember(id, available))
            dao.saveMoveVersion(ConversationFolderMove(id, UUID.randomUUID().toString()))
            restored++
        }
        receipt.created?.let { created ->
            if (dao.folder(created.id) == created && dao.members().none { it.folderId == created.id }) dao.delete(created.id)
        }
        restored to missing
    }

    suspend fun setPinnedMany(ids: Set<Int>, pinned: Boolean): List<Int> = database.withTransaction {
        val changed = database.chatRoomDao().getChatRoomsByIds(ids.toList()).filter { it.isFavorite != pinned }.map { it.id }
        if (changed.isNotEmpty()) dao.setPinned(changed, pinned)
        changed
    }

    suspend fun edit(id: String, name: String, color: Long) = dao.edit(id, name(name), ConversationFolderStyle.color(color))
    suspend fun delete(id: String) = dao.delete(id)
}
