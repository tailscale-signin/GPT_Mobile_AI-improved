package dev.chungjungsoo.gptmobile.data.backup

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.GatewayWorkDatabaseSchema
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.github.GatewayCommandRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringUpdateRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayMessageRecord
import dev.chungjungsoo.gptmobile.data.github.GatewayReplayCursor
import dev.chungjungsoo.gptmobile.data.github.GatewayScopeRecord
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
        open().use { database ->
            seed(database)
            CompleteBackupDatabase.retainSections(database.openHelper.writableDatabase, conversations)
            assertSavedIdentity(database)
            assertEquals("original-run", database.agentRunDao().getById("original-run")?.runId)
            assertNull(database.agentRunDao().getById("unrelated-run"))
        }
    }

    @Test fun restoringConversationsKeepsTheOriginalCommandAndPausesLocalExecution() = runBlocking {
        open().use { source ->
            open().use { target ->
                seed(source)
                CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, conversations)
                CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, conversations)
                assertSavedIdentity(target)
                val restored = requireNotNull(target.agentRunDao().getById("original-run"))
                assertEquals(AgentRunStatus.INTERRUPTED, restored.status)
                assertEquals("BACKUP_RESTORED", restored.terminalError)
                assertEquals("remote-job", restored.gatewayJobId)
                assertEquals(7L, restored.ownershipFence)
                assertEquals("Original request", target.gatewayWorkDao().chatMessage(2)?.content)
            }
        }
    }

    @Test fun settingsOnlyBackupDoesNotRetainConversationCommandsOrTranscript() = runBlocking {
        open().use { database ->
            seed(database)
            CompleteBackupDatabase.retainSections(database.openHelper.writableDatabase, CompleteBackupSelection(setOf(CompleteBackupSection.SETTINGS)))
            for (table in GatewayWorkDatabaseSchema.tables) {
                database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use { rows ->
                    assertTrue(rows.moveToFirst())
                    assertEquals(table, 0, rows.getInt(0))
                }
            }
        }
    }

    @Test fun ordinaryJobWithoutCommandKeepsItsExactReplyOwnerAcrossConversationOnlyRestore() = runBlocking {
        open().use { source ->
            open().use { target ->
                seedOrdinary(source)
                assertTrue(source.gatewayWorkDao().bindings().isEmpty())
                assertNull(source.gatewayWorkDao().command("ordinary-run"))
                CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, conversations)
                CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, conversations)
                val run = requireNotNull(target.agentRunDao().getById("ordinary-run"))
                val message = requireNotNull(target.gatewayWorkDao().chatMessage(3))
                assertEquals(AgentRunStatus.INTERRUPTED, run.status)
                assertEquals("BACKUP_RESTORED", run.terminalError)
                assertEquals("ordinary-job", run.gatewayJobId)
                assertEquals(3, run.assistantMessageId)
                assertEquals(run.runId, message.currentRunId)
                assertEquals(7L, run.ownershipFence)
                assertEquals(run.ownershipFence, message.ownershipFence)
                assertEquals("Saved partial", message.content)
                assertEquals("ANDROID_RUN", target.gatewayWorkDao().conversation("ordinary-conversation")?.projectionOwner)
            }
        }
    }

    @Test fun ordinaryMirrorDoesNotRetainMismatchedJobsScopesProfilesOrSupersededOwners() = runBlocking {
        val changes = listOf<suspend (ChatDatabaseV2) -> Unit>(
            { db -> db.agentRunDao().upsert(requireNotNull(db.agentRunDao().getById("ordinary-run")).copy(gatewayJobId = " ")) },
            { db -> db.agentRunDao().upsert(requireNotNull(db.agentRunDao().getById("ordinary-run")).copy(gatewayBaseUrl = null)) },
            { db -> db.agentRunDao().upsert(requireNotNull(db.agentRunDao().getById("ordinary-run")).copy(gatewayBaseUrl = "https://other.invalid/v1")) },
            { db -> db.gatewayWorkDao().putJob(requireNotNull(db.gatewayWorkDao().job("scope", "chat", "ordinary-job")).copy(kind = "engineering")) },
            { db ->
                db.gatewayWorkDao().putScope(requireNotNull(db.gatewayWorkDao().scope("scope")).copy(id = "another-scope"))
                db.gatewayWorkDao().updateConversation(requireNotNull(db.gatewayWorkDao().conversation("ordinary-conversation")).copy(scopeId = "another-scope"))
            },
            { db -> db.gatewayWorkDao().updateConversation(requireNotNull(db.gatewayWorkDao().conversation("ordinary-conversation")).copy(profileUid = "another-profile")) },
            { db -> db.gatewayWorkDao().updateConversation(requireNotNull(db.gatewayWorkDao().conversation("ordinary-conversation")).copy(model = "another-model")) },
            { db -> db.gatewayWorkDao().updateConversation(requireNotNull(db.gatewayWorkDao().conversation("ordinary-conversation")).copy(projectionOwner = "CONTINUITY")) },
            { db -> db.gatewayWorkDao().updateChatMessage(requireNotNull(db.gatewayWorkDao().chatMessage(3)).copy(currentRunId = "newer-run")) },
            { db -> db.gatewayWorkDao().updateChatMessage(requireNotNull(db.gatewayWorkDao().chatMessage(3)).copy(ownershipFence = 8)) }
        )
        for (change in changes) {
            open().use { database ->
                seedOrdinary(database)
                change(database)
                val message = database.gatewayWorkDao().chatMessage(3)
                CompleteBackupDatabase.retainSections(database.openHelper.writableDatabase, conversations)
                assertNull(database.agentRunDao().getById("ordinary-run"))
                assertEquals(message, database.gatewayWorkDao().chatMessage(3))
            }
        }
    }

    @Test fun ordinaryAdmissionBeforeCatalogSyncRetainsOnlyItsCurrentLocalOwner() = runBlocking {
        open().use { source ->
            open().use { target ->
                seedOrdinary(source, includeMirror = false)
                val original = requireNotNull(source.agentRunDao().getById("ordinary-run"))
                source.agentPersistenceDao().insertRun(original.copy(runId = "older-owner", ownershipFence = 6))
                CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, conversations)
                CompleteBackupDatabase.restoreSections(source.openHelper.writableDatabase, target.openHelper.writableDatabase, conversations)
                val restored = requireNotNull(target.agentRunDao().getById("ordinary-run"))
                assertEquals(AgentRunStatus.INTERRUPTED, restored.status)
                assertEquals("ordinary-job", restored.gatewayJobId)
                assertEquals(original.gatewayBaseUrl, restored.gatewayBaseUrl)
                assertEquals(restored.runId, target.gatewayWorkDao().chatMessage(3)?.currentRunId)
                assertEquals(restored.ownershipFence, target.gatewayWorkDao().chatMessage(3)?.ownershipFence)
                assertNull(target.agentRunDao().getById("older-owner"))
                assertTrue(target.gatewayWorkDao().conversations(1).isEmpty())
            }
        }
    }

    private suspend fun seedOrdinary(database: ChatDatabaseV2, includeMirror: Boolean = true) {
        val dao = database.gatewayWorkDao()
        dao.insertChat(ChatRoomV2(id = 1, title = "Original ordinary chat", enabledPlatform = listOf("profile")))
        dao.insertChatMessage(MessageV2(id = 2, chatId = 1, content = "Original request", platformType = null))
        dao.insertChatMessage(MessageV2(id = 3, chatId = 1, content = "Saved partial", platformType = "profile", linkedMessageId = 2, currentRunId = "ordinary-run", ownershipFence = 7))
        database.agentPersistenceDao().insertRun(AgentRun("ordinary-run", 1, 2, 3, "profile", "LLAMA", "original-model", status = AgentRunStatus.WAITING_REMOTE_JOB, gatewayJobId = "ordinary-job", gatewayBaseUrl = "https://gateway.invalid/v1", ownershipFence = 7))
        if (!includeMirror) return
        dao.putScope(GatewayScopeRecord("scope", "gateway-profile", "https://gateway.invalid/v1", "gateway", "principal", "account", "ONLINE", 3000))
        dao.insertConversation(GatewayConversationRecord("ordinary-conversation", "scope", "conversation", 1, "profile", "Original profile", "original-model", null, null, "Original request", 2, null, null, true, null, 3000, projectionOwner = "ANDROID_RUN"))
        dao.putJob(GatewayJobRecord("ordinary-job-key", "scope", "ordinary-job", "ordinary-conversation", "chat", 2, "running", "running", 1000, null, 3000, 100, "process", 3000, "{}"))
    }

    private suspend fun assertSavedIdentity(database: ChatDatabaseV2) {
        val dao = database.gatewayWorkDao()
        val command = requireNotNull(dao.command("original-command"))
        assertEquals("{\"prompt\":\"Original request\"}", command.payload)
        assertEquals("original-run", command.localRunId)
        assertEquals("UNKNOWN", command.state)
        assertEquals("remote-job", dao.job("scope", "engineering", "remote-job")?.jobId)
        assertEquals(3L, dao.cursor("conversation-key")?.sequence)
        assertEquals("Saved partial", dao.message("conversation-key", 3)?.content)
        assertEquals("original-command", dao.bindings().single().requestId)
        assertEquals(2, dao.updates("original-command").single().revision)
        assertEquals("gateway", dao.scope("scope")?.gatewayId)
    }

    private suspend fun seed(database: ChatDatabaseV2) {
        val dao = database.gatewayWorkDao()
        dao.insertChat(ChatRoomV2(id = 1, title = "Original", enabledPlatform = listOf("profile")))
        dao.insertChatMessage(MessageV2(id = 2, chatId = 1, content = "Original request", platformType = null))
        dao.insertChatMessage(MessageV2(id = 3, chatId = 1, content = "Saved partial", platformType = "profile", currentRunId = "original-run", ownershipFence = 7))
        val run = AgentRun("original-run", 1, 2, 3, "profile", "LLAMA", "original-model", status = AgentRunStatus.WAITING_REMOTE_JOB, gatewayJobId = "remote-job", gatewayBaseUrl = "https://gateway.invalid/v1", ownershipFence = 7)
        database.agentPersistenceDao().insertRun(run)
        database.agentPersistenceDao().insertRun(run.copy(runId = "unrelated-run", status = AgentRunStatus.COMPLETED, gatewayJobId = null))
        dao.putScope(GatewayScopeRecord("scope", "gateway-profile", "https://gateway.invalid/v1", "gateway", "principal", "account", "ONLINE", 3000))
        dao.insertConversation(GatewayConversationRecord("conversation-key", "scope", "conversation", 1, "profile", "Original profile", "original-model", "owner/repo", "feature", "Original request", 2, null, null, true, null, 3000))
        dao.insertCommand(GatewayCommandRecord("original-command", "scope", "conversation-key", 1, 2, 3, "original-run", "create", "original-digest", "{\"prompt\":\"Original request\"}", "UNKNOWN", "remote-job", 1000, 3000))
        dao.putJob(GatewayJobRecord("job-key", "scope", "remote-job", "conversation-key", "engineering", 2, "running", "IMPLEMENT", 1000, null, 3000, 100, "process", 3000, "{}"))
        dao.insertMessage(GatewayMessageRecord("conversation-key", 3, "remote-message", "remote-job", "assistant", "partial", "Saved partial", 3000, "fingerprint", 3))
        dao.putCursor(GatewayReplayCursor("conversation-key", sequence = 3, hasMore = false))
        dao.putBinding(GatewayEngineeringRecord("original-command", "scope", 1, "remote-job", "{}", 2, 1, 1))
        dao.putUpdates(listOf(GatewayEngineeringUpdateRecord("original-command", 2, "IMPLEMENT", "Saved progress", 3.0)))
    }

    private fun open() = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).allowMainThreadQueries().build()
    private val conversations = CompleteBackupSelection(setOf(CompleteBackupSection.CONVERSATIONS))
}
