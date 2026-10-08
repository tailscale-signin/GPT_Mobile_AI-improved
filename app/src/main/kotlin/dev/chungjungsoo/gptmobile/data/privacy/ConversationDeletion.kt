package dev.chungjungsoo.gptmobile.data.privacy

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

/** Deletes dependent records before their run IDs disappear; only removes unreferenced app-owned files. */
@Singleton
class ConversationDeletion @Inject constructor(private val database: ChatDatabaseV2, @ApplicationContext private val context: Context, private val vault: dev.chungjungsoo.gptmobile.data.security.SecretVault? = null, private val semantic: dev.chungjungsoo.gptmobile.data.memory.LocalSemanticMemory? = null, private val facts: dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository? = null, private val amazonMedia: dev.chungjungsoo.gptmobile.data.amazon.AmazonProductMediaCache? = null) {
    suspend fun delete(rooms: List<ChatRoomV2>) = withContext(Dispatchers.IO) {
        val files = mutableSetOf<String>()
        rooms.filter { it.isTemporary }.forEach { facts?.forgetChat(it.id, preventFutureCapture = true) }
        // Remove derived document vectors before Room cascades erase their identities.
        rooms.forEach { room -> database.knowledgeDao().chatDocuments(room.id).forEach { document -> semantic?.removeDocument(document.id) } }
        database.withTransaction {
            val db = database.openHelper.writableDatabase
            rooms.forEach { room ->
                val current = database.chatRoomDao().getChatRoomsByIds(listOf(room.id)).firstOrNull() ?: return@forEach
                val attachments = database.messageDao().loadMessages(room.id).flatMap { it.attachments } +
                    runCatching { Json.decodeFromString<List<ChatAttachment>>(current.draftAttachments) }.getOrDefault(emptyList())
                attachments.forEach {
                    files += it.localFilePath
                    files += it.preparedFilePath
                }
                db.execSQL("DELETE FROM tool_approvals WHERE runId IN (SELECT run_id FROM agent_runs WHERE chat_id = ?)", arrayOf(room.id))
                // Preserve a daily cost total without conversation, profile, timing or model attribution.
                db.execSQL("UPDATE model_invocations SET parentRunId = '', turnKey = 'deleted:' || id, provider = 'Deleted conversation', model = '', kind = 'anonymous_cost', inputTokens = 0, outputTokens = 0, durationMs = 0, firstTokenMs = NULL, profileUid = NULL, priceSource = NULL, startedAt = (startedAt / 86400000) * 86400000, status = 'COMPLETED' WHERE parentRunId IN (SELECT run_id FROM agent_runs WHERE chat_id = ?) AND costMicros > 0", arrayOf(room.id))
                db.execSQL("DELETE FROM model_invocations WHERE parentRunId IN (SELECT run_id FROM agent_runs WHERE chat_id = ?)", arrayOf(room.id))
                database.chatRoomDao().deleteChatRooms(current)
            }
        }
        rooms.forEach { amazonMedia?.clearConversation(it.id) }
        val references = vault?.references().orEmpty()
        rooms.forEach { room -> references.filter { it.startsWith("workspace-memory-${room.id}-") }.forEach { vault?.delete(it) } }
        val db = database.openHelper.readableDatabase
        files.filter(String::isNotBlank).forEach { path ->
            val file = File(path).canonicalFile
            val owned = listOfNotNull(context.filesDir, context.cacheDir, context.getExternalFilesDir(null)).any { file.toPath().startsWith(it.canonicalFile.toPath()) }
            if (owned) {
                // JSON string matching also covers a file reused by a branch or an unsent prompt.
                val escaped = Json.encodeToString(kotlinx.serialization.serializer<String>(), path)
                val referenced = listOf("messages_v2" to "attachments", "chats_v2" to "draft_attachments", "pending_prompts" to "payload").any { (table, column) ->
                    db.query("SELECT 1 FROM $table WHERE instr($column, ?) > 0 LIMIT 1", arrayOf(escaped)).use { it.moveToFirst() }
                }
                if (!referenced) file.delete()
            }
        }
    }
}
