package dev.chungjungsoo.gptmobile.data.chat

import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

data class CompletedGeneration(
    val runId: String,
    val chatId: Int,
    val assistantMessageId: Int,
    val completedAt: Long,
    val revision: Long = 0,
    val profileUid: String = "",
    val displayRole: String = ReplyDisplayRole.DIRECT,
    val deliveryKind: String = "FINAL",
    val contentDigest: String = "",
    val visibleChatId: Int = chatId
)

/** Compatibility facade. Room publication commits are the sole writer of unread truth. */
@Singleton
class GenerationCompletionStore @Inject constructor(database: ChatDatabaseV2) {
    private val dao = database.replyPublicationDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val items = kotlinx.coroutines.flow.flow {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        emitAll(dao.observeUnread())
    }.map { rows ->
        rows.map { CompletedGeneration(it.logicalReplyKey, it.chatId, it.messageId, it.publishedAt, it.committedRevision, it.profileUid, it.displayRole, it.deliveryKind, it.contentDigest) }
    }.stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    suspend fun acknowledge(item: CompletedGeneration, displayedText: String) {
        val digest = dev.chungjungsoo.gptmobile.data.research.researchHash(dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.text(dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote(displayedText)))
        if (digest == item.contentDigest) dao.acknowledge(item.runId, item.revision, digest)
    }

    // Legacy callbacks cannot create, acknowledge, or erase durable publications.
    fun record(runId: String, chatId: Int, assistantMessageId: Int, completedAt: Long) = Unit
    fun consume(runId: String) = Unit
    fun clearChat(chatId: Int) = Unit
    fun removeMissingChats(validChatIds: Set<Int>) = Unit
}
