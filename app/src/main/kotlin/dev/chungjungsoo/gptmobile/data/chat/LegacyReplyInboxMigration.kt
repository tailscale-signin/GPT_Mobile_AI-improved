package dev.chungjungsoo.gptmobile.data.chat

import android.content.Context
import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2

/** Old Boolean unread flags cannot prove a reply. Import only surviving owned terminal receipts. */
internal object LegacyReplyInboxMigration {
    suspend fun migrate(context: Context, database: ChatDatabaseV2) {
        val inbox = context.getSharedPreferences("generation_completion_inbox_v1", Context.MODE_PRIVATE)
        if (inbox.getBoolean("room_publications_migrated", false)) return
        val receipts = inbox.getStringSet("items", emptySet()).orEmpty().toList()
        database.withTransaction {
            val dao = database.agentPersistenceDao()
            receipts.forEach { encoded ->
                val fields = encoded.split('|')
                if (fields.size != 4) return@forEach
                val run = dao.recoveryRun(fields[0]) ?: return@forEach
                val message = dao.recoveryMessage(fields[2].toIntOrNull() ?: return@forEach) ?: return@forEach
                val room = dao.getChatRoom(fields[1].toIntOrNull() ?: return@forEach) ?: return@forEach
                if (run.status != "COMPLETED" || run.chatId != room.id || message.chatId != room.id || run.assistantMessageId != message.id || message.currentRunId != run.runId || message.ownershipFence != run.ownershipFence) return@forEach
                val role = when {
                    ReplyDisplayRole.visible(run.displayRole) -> run.displayRole
                    room.conversationMode == "COMBINED" && run.synthesisKey != null -> ReplyDisplayRole.COMBINED
                    room.conversationMode == "COMBINED" -> return@forEach // Cannot prove an old contributor was the delivered writer.
                    room.enabledPlatform.size > 1 -> ReplyDisplayRole.MULTI
                    else -> ReplyDisplayRole.DIRECT
                }
                val channel = if (role == ReplyDisplayRole.COMBINED) "combined" else run.profileUid
                if (dao.publication("${room.id}:${run.userMessageId}:$channel") == null) {
                    dao.publishReply(run.copy(displayRole = role), message, "COMPLETED", fields[3].toLongOrNull() ?: return@forEach)
                }
            }
        }
        // Crash before this commit safely reruns the DB import without revising or reviving rows.
        check(inbox.edit().clear().putBoolean("room_publications_migrated", true).commit()) { "Could not retire the legacy reply inbox" }
        check(context.getSharedPreferences("conversation_read_state_v1", Context.MODE_PRIVATE).edit().clear().commit()) { "Could not retire legacy unread flags" }
    }
}
