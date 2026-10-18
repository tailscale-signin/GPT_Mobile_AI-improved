package dev.chungjungsoo.gptmobile.data.chat

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class ReplyPublicationAndFolderTest {
    private lateinit var db: ChatDatabaseV2

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
    }

    @After fun close() = db.close()
    private fun draft(uid: String, role: String? = null) = AgentRunDraft(UUID.randomUUID().toString(), uid, "fixture", "fixture", displayRole = role)
    private suspend fun turn(count: Int = 1, mode: String = "STANDARD") = db.agentPersistenceDao().persistAgentTurn(
        PersistAgentTurnRequest(ChatRoomV2(title = "fixture", enabledPlatform = (1..count).map { "p$it" }, conversationMode = mode), MessageV2(content = "question", platformType = null), (1..count).map { draft("p$it") }, emptyMap())
    )
    private suspend fun finish(message: MessageV2, text: String, status: String = "COMPLETED"): Boolean {
        val runId = requireNotNull(message.currentRunId)
        db.agentPersistenceDao().updateRunStatus(runId, "RUNNING", 1, null, null)
        return db.agentPersistenceDao().finishRunningWithMessage(message.copy(content = text), runId, status, 2, null)
    }

    @Test fun errorsAloneDoNotPublishButPartialAnswersRemainAcknowledgableWithStopNotice() = runBlocking {
        val empty = turn()
        assertTrue(finish(empty.assistantMessages.single(), "Error: transport failed", "FAILED"))
        assertTrue(db.replyPublicationDao().all().isEmpty())
        val partial = turn()
        val text = dev.chungjungsoo.gptmobile.util.buildAssistantErrorContent("Useful partial answer", "transport failed")
        assertTrue(finish(partial.assistantMessages.single(), text, "FAILED"))
        val row = db.replyPublicationDao().all().single()
        val store = GenerationCompletionStore(db)
        store.acknowledge(CompletedGeneration(row.logicalReplyKey, row.chatId, row.messageId, row.publishedAt, row.committedRevision, contentDigest = row.contentDigest), text)
        assertTrue(db.replyPublicationDao().observeUnread().first().isEmpty())
    }

    @Test fun partialRetryUsesOneLogicalReplyAndStaleAcknowledgementCannotClearRevision() = runBlocking {
        val turn = turn()
        val first = turn.assistantMessages.single()
        assertTrue(finish(first, "partial answer", "INTERRUPTED"))
        val partial = db.replyPublicationDao().all().single()
        assertEquals("PARTIAL", partial.deliveryKind)
        val retry = db.agentPersistenceDao().persistAgentRetry(PersistAgentRetryRequest(turn.userMessage, first.copy(content = "partial answer"), draft("p1")))
        assertTrue(finish(retry.assistantMessage, "completed answer"))
        val final = db.replyPublicationDao().all().single()
        assertEquals(2L, final.committedRevision)
        assertEquals(0, db.replyPublicationDao().acknowledge(partial.logicalReplyKey, partial.committedRevision, partial.contentDigest))
        assertEquals(1, db.replyPublicationDao().observeUnread().first().size)
        assertFalse(db.agentPersistenceDao().finishRunningWithMessage(first.copy(content = "late stale answer"), requireNotNull(first.currentRunId), "COMPLETED", 3, null))
        assertEquals(final, db.replyPublicationDao().all().single())
        assertEquals(1, db.replyPublicationDao().acknowledge(final.logicalReplyKey, final.committedRevision, final.contentDigest))
        assertTrue(db.replyPublicationDao().observeUnread().first().isEmpty())
    }

    @Test fun combinedContributorsNeverPublishAndFinalWriterPublishesOnce() = runBlocking {
        val turn = turn(3, "COMBINED")
        turn.assistantMessages.forEach { assertTrue(finish(it, "contributor evidence")) }
        assertTrue(db.replyPublicationDao().all().isEmpty())
        val retry = db.agentPersistenceDao().persistAgentRetry(PersistAgentRetryRequest(turn.userMessage, turn.assistantMessages.first().copy(content = "contributor evidence"), draft("p1", ReplyDisplayRole.COMBINED)))
        assertTrue(finish(retry.assistantMessage, "final synthesis"))
        assertEquals(ReplyDisplayRole.COMBINED, db.replyPublicationDao().all().single().displayRole)
    }

    @Test fun unreadIsUncappedAndDeletionPreventsLatePublication() = runBlocking {
        val turn = turn(105)
        turn.assistantMessages.forEach { assertTrue(finish(it, "reply ${it.platformType}")) }
        assertEquals(105, db.replyPublicationDao().observeUnread().first().size)
        val one = db.replyPublicationDao().all().first()
        db.replyPublicationDao().acknowledge(one.logicalReplyKey, one.committedRevision, one.contentDigest)
        assertEquals(104, db.replyPublicationDao().observeUnread().first().size)
        db.openHelper.writableDatabase.execSQL("DELETE FROM chats_v2 WHERE chat_id = ?", arrayOf(turn.chatRoom.id))
        assertTrue(db.replyPublicationDao().all().isEmpty())
        assertFalse(finish(turn.assistantMessages.first(), "deleted chat callback"))
        assertTrue(db.replyPublicationDao().all().isEmpty())
    }

    @Test fun folderUndoCannotOverwriteNewerMoveAndCreateFailureRollsBack() = runBlocking {
        val a = turn().chatRoom.id
        val b = turn().chatRoom.id
        val repository = ConversationFolderRepository(db)
        val first = repository.createAndMove(setOf(a, b), " Work ", ConversationFolderStyle.colors.first())
        repository.moveMany(setOf(b), null)
        assertEquals(1 to 0, repository.undo(first))
        assertTrue(db.conversationFolderDao().members().isEmpty())
        assertTrue(db.conversationFolderDao().folders().isEmpty())
        assertTrue(runCatching { repository.createAndMove(setOf(a, Int.MAX_VALUE), "Atomic", ConversationFolderStyle.colors.first()) }.isFailure)
        assertTrue(db.conversationFolderDao().folders().isEmpty())
        assertTrue(db.conversationFolderDao().members().isEmpty())
    }

    @Test fun legacyInboxImportsOnlyProvenOwnedRepliesAndNeverRevivesAcknowledgedRows() = runBlocking {
        val turn = turn()
        val message = turn.assistantMessages.single()
        assertTrue(finish(message, "owned historical reply"))
        val row = db.replyPublicationDao().all().single()
        db.openHelper.writableDatabase.execSQL("DELETE FROM reply_publications")
        val context = RuntimeEnvironment.getApplication()
        val old = context.getSharedPreferences("generation_completion_inbox_v1", 0)
        old.edit().clear().putStringSet("items", setOf("${message.currentRunId}|${turn.chatRoom.id}|${message.id}|2", "missing|999|999|2")).commit()
        LegacyReplyInboxMigration.migrate(context, db)
        assertEquals(row.contentDigest, db.replyPublicationDao().all().single().contentDigest)
        val imported = db.replyPublicationDao().all().single()
        db.replyPublicationDao().acknowledge(imported.logicalReplyKey, imported.committedRevision, imported.contentDigest)
        LegacyReplyInboxMigration.migrate(context, db)
        assertTrue(db.replyPublicationDao().observeUnread().first().isEmpty())
        assertTrue(old.getStringSet("items", emptySet()).orEmpty().isEmpty())
    }
}
