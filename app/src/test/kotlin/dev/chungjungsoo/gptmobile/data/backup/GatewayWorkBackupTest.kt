package dev.chungjungsoo.gptmobile.data.backup

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringBindingRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringEventRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessagePartRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessageRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMigrationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.gatewayIdentityKey
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** Real Room/SQLite backup selection must keep the original remote reply owner. */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class GatewayWorkBackupTest {
    @Test fun conversationsWithoutExecutionHistoryRetainRemoteIdentityAndReplyOwnership() = runBlocking {
        withDatabase { database ->
            seed(database)
            CompleteBackupDatabase.retainSections(database.openHelper.writableDatabase, conversations)
            assertSavedIdentity(database)
            assertEquals("original-run", database.agentRunDao().getById("original-run")?.runId)
            assertNull(database.agentRunDao().getById("unrelated-run"))
        }
    }

    @Test fun restoringConversationsKeepsTheOriginalCommandAndPausesLocalExecution() = runBlocking {
        withDatabase { source ->
            withDatabase { target ->
                seed(source)
                CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, conversations)
                CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, conversations)
                assertSavedIdentity(target)
                val restored = requireNotNull(target.agentRunDao().getById("original-run"))
                assertEquals(AgentRunStatus.INTERRUPTED, restored.status)
                assertEquals("BACKUP_RESTORED", restored.terminalError)
                assertEquals("remote-job", restored.gatewayJobId)
                assertEquals(7L, restored.ownershipFence)
                assertEquals("Original request", target.gatewayJobDao().localMessage(2)?.content)
            }
        }
    }

    @Test fun settingsOnlyBackupDoesNotRetainConversationCommandsOrTranscript() = runBlocking {
        withDatabase { database ->
            seed(database)
            CompleteBackupDatabase.retainSections(database.openHelper.writableDatabase, CompleteBackupSelection(setOf(CompleteBackupSection.SETTINGS)))
            for (table in gatewayTables) {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(table, 0, rows.getInt(0))
                }
            }
        }
    }

    @Test fun ordinaryJobWithoutCommandKeepsItsExactReplyOwnerAcrossConversationOnlyRestore() = runBlocking {
        withDatabase { source ->
            withDatabase { target ->
                seedOrdinary(source)
                assertTrue(source.gatewayJobDao().bindings(null).isEmpty())
                assertTrue(source.gatewayJobDao().submissionsForChat(1).isEmpty())
                CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, conversations)
                CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, conversations)
                val run = requireNotNull(target.agentRunDao().getById("ordinary-run"))
                val message = requireNotNull(target.gatewayJobDao().localMessage(3))
                assertEquals(AgentRunStatus.INTERRUPTED, run.status)
                assertEquals("BACKUP_RESTORED", run.terminalError)
                assertEquals("ordinary-job", run.gatewayJobId)
                assertEquals(3, run.assistantMessageId)
                assertEquals(run.runId, message.currentRunId)
                assertEquals(7L, run.ownershipFence)
                assertEquals(run.ownershipFence, message.ownershipFence)
                assertEquals("Saved partial", message.content)
                val saved = requireNotNull(target.gatewayJobDao().conversation(conversationKey))
                assertEquals(1, saved.localChatId)
                assertEquals("profile", saved.executionProfileUid)
                assertEquals("gateway", saved.gatewayId)
                assertEquals("principal", saved.principalId)
            }
        }
    }

    @Test fun ordinaryMirrorDoesNotRetainMismatchedJobsScopesProfilesOrSupersededOwners() = runBlocking {
        val changes = listOf<suspend (ChatDatabaseV2) -> Unit>(
            { db -> db.agentRunDao().upsert(requireNotNull(db.agentRunDao().getById("ordinary-run")).copy(gatewayJobId = " ")) },
            { db -> db.agentRunDao().upsert(requireNotNull(db.agentRunDao().getById("ordinary-run")).copy(gatewayBaseUrl = null)) },
            { db -> db.agentRunDao().upsert(requireNotNull(db.agentRunDao().getById("ordinary-run")).copy(gatewayBaseUrl = "https://other.invalid/v1")) },
            { db -> db.gatewayJobDao().saveJobs(listOf(requireNotNull(db.gatewayJobDao().job(ordinaryJobKey)).copy(kind = "engineering"))) },
            { db -> db.gatewayJobDao().saveConversation(requireNotNull(db.gatewayJobDao().conversation(conversationKey)).copy(gatewayId = "another-gateway")) },
            { db -> db.gatewayJobDao().saveConversation(requireNotNull(db.gatewayJobDao().conversation(conversationKey)).copy(principalId = "another-principal")) },
            { db -> db.gatewayJobDao().saveConversation(requireNotNull(db.gatewayJobDao().conversation(conversationKey)).copy(connectionProfileUid = "another-connection")) },
            { db -> db.gatewayJobDao().saveConversation(requireNotNull(db.gatewayJobDao().conversation(conversationKey)).copy(executionProfileUid = "another-profile")) },
            { db -> db.gatewayJobDao().saveConversation(requireNotNull(db.gatewayJobDao().conversation(conversationKey)).copy(snapshot = """{"model":"another-model"}""")) },
            { db -> db.gatewayJobDao().saveConversation(requireNotNull(db.gatewayJobDao().conversation(conversationKey)).copy(localChatId = null)) },
            { db -> db.messageDao().editMessages(requireNotNull(db.gatewayJobDao().localMessage(3)).copy(currentRunId = "newer-run")) },
            { db -> db.messageDao().editMessages(requireNotNull(db.gatewayJobDao().localMessage(3)).copy(ownershipFence = 8)) }
        )
        for (change in changes) {
            withDatabase { database ->
                seedOrdinary(database)
                change(database)
                val message = database.gatewayJobDao().localMessage(3)
                CompleteBackupDatabase.retainSections(database.openHelper.writableDatabase, conversations)
                assertNull(database.agentRunDao().getById("ordinary-run"))
                assertEquals(message, database.gatewayJobDao().localMessage(3))
            }
        }
    }

    @Test fun ordinaryAdmissionBeforeCatalogSyncRetainsOnlyItsCurrentLocalOwner() = runBlocking {
        for (beforeReceipt in listOf(false, true)) {
            withDatabase { source ->
                withDatabase { target ->
                    seedOrdinary(source, includeMirror = false)
                    var original = requireNotNull(source.agentRunDao().getById("ordinary-run"))
                    if (beforeReceipt) {
                        original = original.copy(gatewayJobId = null, gatewayBaseUrl = null)
                        source.agentRunDao().upsert(original)
                        source.gatewayJobDao().insertSubmission(
                            GatewaySubmissionRecord("ordinary-command", 1, 2, 3, original.runId, "profile", endpoint, "profile", "frozen-request", """{"reservationKind":"ordinary","model":"original-model","gatewayId":"gateway","principalId":"principal"}""", "PENDING_ORDINARY", createdAtMillis = 1000)
                        )
                    }
                    source.agentPersistenceDao().insertRun(original.copy(runId = "older-owner", ownershipFence = 6))
                    CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, conversations)
                    CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, conversations)
                    val restored = requireNotNull(target.agentRunDao().getById("ordinary-run"))
                    assertEquals(AgentRunStatus.INTERRUPTED, restored.status)
                    assertEquals("BACKUP_RESTORED", restored.terminalError)
                    assertEquals(original.gatewayJobId, restored.gatewayJobId)
                    assertEquals(original.gatewayBaseUrl, restored.gatewayBaseUrl)
                    assertEquals(restored.runId, target.gatewayJobDao().localMessage(3)?.currentRunId)
                    assertEquals(restored.ownershipFence, target.gatewayJobDao().localMessage(3)?.ownershipFence)
                    assertNull(target.agentRunDao().getById("older-owner"))
                    assertTrue(target.gatewayJobDao().conversationsForChat(1).isEmpty())
                    if (beforeReceipt) assertEquals(source.gatewayJobDao().submission("ordinary-command"), target.gatewayJobDao().submission("ordinary-command"))
                }
            }
        }
    }

    private suspend fun seedOrdinary(database: ChatDatabaseV2, includeMirror: Boolean = true) {
        val dao = database.gatewayJobDao()
        seedLocalOwner(database, "ordinary-run", "ordinary-job")
        if (!includeMirror) return
        dao.saveConversation(GatewayConversationRecord(conversationKey, "gateway", "principal", "conversation", "profile", endpoint, "profile", 1, ordinarySnapshot, revision = 2, syncedAtMillis = 3000))
        dao.saveJobs(listOf(GatewayJobRecord(ordinaryJobKey, "gateway", "principal", "generation", "ordinary-job", conversationKey, "profile", endpoint, "profile", """{"model":"original-model"}""", 3000)))
    }

    private suspend fun assertSavedIdentity(database: ChatDatabaseV2) {
        val dao = database.gatewayJobDao()
        val command = requireNotNull(dao.submission("original-command"))
        assertEquals(engineeringPayload, command.payload)
        assertEquals("original-run", command.runId)
        assertEquals("OUTCOME_UNKNOWN", command.state)
        assertEquals(2, command.userMessageId)
        assertEquals(3, command.assistantMessageId)
        assertEquals("profile", command.executionProfileUid)
        assertEquals(conversationKey, command.conversationKey)
        assertEquals("remote-job", dao.job(engineeringJobKey)?.remoteJobId)
        assertEquals("opaque-cursor", dao.conversation(conversationKey)?.messageCursor)
        assertEquals(3L, dao.conversation(conversationKey)?.revision)
        assertEquals("gateway", dao.conversation(conversationKey)?.gatewayId)
        assertEquals("principal", dao.conversation(conversationKey)?.principalId)
        assertEquals("Saved partial", dao.message(conversationKey, "remote-message")?.content)
        assertEquals("Saved partial", dao.messagePart(conversationKey, "remote-message", 3, 0)?.content)
        assertEquals("original-command", dao.bindings(null).single().requestId)
        assertEquals(2, dao.events("original-command").single().revision)
        assertEquals("retained-checksum", dao.migration("fixture-migration")?.checksum)
        val message = requireNotNull(dao.localMessage(3))
        val run = requireNotNull(database.agentRunDao().getById("original-run"))
        assertEquals(run.runId, message.currentRunId)
        assertEquals(7L, message.ownershipFence)
        assertEquals(run.ownershipFence, message.ownershipFence)
    }

    private suspend fun seed(database: ChatDatabaseV2) {
        val dao = database.gatewayJobDao()
        seedLocalOwner(database, "original-run", "remote-job")
        val run = requireNotNull(database.agentRunDao().getById("original-run"))
        database.agentPersistenceDao().insertRun(run.copy(runId = "unrelated-run", status = AgentRunStatus.COMPLETED, gatewayJobId = null))
        dao.saveConversation(GatewayConversationRecord(conversationKey, "gateway", "principal", "conversation", "gateway-profile", endpoint, "profile", 1, engineeringSnapshot, revision = 3, messageCursor = "opaque-cursor", syncedAtMillis = 3000))
        dao.insertSubmission(GatewaySubmissionRecord("original-command", 1, 2, 3, "original-run", "gateway-profile", endpoint, "profile", "original-digest", engineeringPayload, "OUTCOME_UNKNOWN", conversationKey = conversationKey, createdAtMillis = 1000))
        dao.saveJobs(listOf(GatewayJobRecord(engineeringJobKey, "gateway", "principal", "engineering", "remote-job", conversationKey, "gateway-profile", endpoint, "profile", """{"model":"original-model"}""", 3000)))
        dao.saveMessage(GatewayMessageRecord(conversationKey, "remote-message", "original-command", "assistant", "Saved partial", "generating", 3, null, 3.0, 3))
        dao.saveMessagePart(GatewayMessagePartRecord(conversationKey, "remote-message", 3, 0, 1, "remote-message:0", "original-command", "assistant", "message", "Saved partial", "generating", null, "original-command", null, 3.0, contentVerified = true))
        dao.saveBinding(GatewayEngineeringBindingRecord("original-command", 1, "remote-job", "{}", 3000))
        dao.saveEvents(listOf(GatewayEngineeringEventRecord("original-command", 2, "IMPLEMENT", "Saved progress", 3.0)))
        dao.saveMigration(GatewayMigrationRecord("fixture-migration", "retained-checksum", 3000))
    }

    private suspend fun seedLocalOwner(database: ChatDatabaseV2, runId: String, jobId: String) {
        database.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Original", enabledPlatform = listOf("profile")))
        val dao = database.gatewayJobDao()
        dao.insertLocalMessage(MessageV2(id = 2, chatId = 1, content = "Original request", platformType = null))
        dao.insertLocalMessage(MessageV2(id = 3, chatId = 1, content = "Saved partial", platformType = "profile", linkedMessageId = 2, currentRunId = runId, ownershipFence = 7))
        database.agentPersistenceDao().insertRun(AgentRun(runId, 1, 2, 3, "profile", "LLAMA", "original-model", status = AgentRunStatus.WAITING_REMOTE_JOB, gatewayJobId = jobId, gatewayBaseUrl = endpoint, ownershipFence = 7))
    }

    private inline fun <T> withDatabase(block: (ChatDatabaseV2) -> T): T {
        val database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).allowMainThreadQueries().build()
        return try {
            block(database)
        } finally {
            database.close()
        }
    }

    private val conversations = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
    private val endpoint = "https://gateway.invalid/v1"
    private val conversationKey = gatewayIdentityKey("gateway", "principal", "conversation")
    private val ordinaryJobKey = gatewayIdentityKey("gateway", "principal", "generation", "ordinary-job")
    private val engineeringJobKey = gatewayIdentityKey("gateway", "principal", "engineering", "remote-job")
    private val ordinarySnapshot = """{"gateway_id":"gateway","principal_id":"principal","conversation_id":"conversation","profile_id":"profile","model":"original-model"}"""
    private val engineeringSnapshot = """{"gateway_id":"gateway","principal_id":"principal","conversation_id":"conversation","profile_id":"profile","model":"original-model","repository":"owner/repo"}"""
    private val engineeringPayload = """{"prompt":"Original request","model":"original-model","repository":"owner/repo"}"""
    private val gatewayTables = listOf("gateway_jobs", "gateway_conversations", "gateway_messages", "gateway_message_parts", "gateway_engineering_bindings", "gateway_engineering_events", "gateway_submissions", "gateway_migrations")
}
