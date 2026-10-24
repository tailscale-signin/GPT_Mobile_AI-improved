package dev.chungjungsoo.gptmobile.data.database

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.withTransaction
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupDatabase
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSelection
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RunPolicySnapshotPersistenceTest {
    private fun memory(): ChatDatabaseV2 = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java)
        .addCallback(RunPolicySnapshotDatabase.callback).build()

    private suspend fun seed(db: ChatDatabaseV2, count: Int = 1, chatId: Int = 1, temporary: Boolean = false): List<AgentRun> {
        db.chatRoomDao().addChatRoom(ChatRoomV2(id = chatId, title = "Saved research", isTemporary = temporary))
        val userId = chatId * 10
        db.messageDao().addMessages(MessageV2(id = userId, chatId = chatId, platformType = null, content = RunPolicyFixture.task))
        return (1..count).map { number ->
            val profile = if (number % 2 == 0) "a" else "b"
            val id = "run-$chatId-$number"
            val assistantId = 100 * chatId + number
            db.messageDao().addMessages(MessageV2(id = assistantId, chatId = chatId, platformType = profile, linkedMessageId = userId, currentRunId = id, content = "Readable saved answer $number"))
            RunPolicyFixture.run(id, profile, chatId, userId, assistantId).also { db.agentRunDao().upsert(it) }
        }
    }

    private suspend fun count(db: ChatDatabaseV2, table: String): Int = withContext(Dispatchers.IO) {
        db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM $table").use {
            it.moveToFirst()
            it.getInt(0)
        }
    }

    @Test fun nineRunsShareTwoFragmentsAndAllPublicReadsReturnOriginalBytes() = runBlocking {
        val db = memory()
        try {
            val originals = seed(db, 9)
            assertEquals(2, count(db, "run_policy_snapshots"))
            assertEquals(18, count(db, "agent_run_policy_refs"))
            assertTrue(db.agentRunDao().rawGetByChatId(1).all { RunPolicySnapshotCodec.isReference(it.synthesisSnapshot) })
            assertEquals(originals.sortedBy { it.runId }, db.agentRunDao().getByChatId(1).sortedBy { it.runId })
            assertEquals(originals.sortedBy { it.runId }, db.agentRunDao().observeByChatId(1).first().sortedBy { it.runId })
            originals.forEach { run -> assertEquals(run, db.agentPersistenceDao().recoveryRun(run.runId)) }
            // Upserting a hydrated record preserves its original seal and the shared immutable set.
            db.agentRunDao().upsert(originals.first().copy(outputTokens = 12))
            assertEquals(2, count(db, "run_policy_snapshots"))
            assertEquals(originals.first().synthesisSnapshot, db.agentRunDao().getById(originals.first().runId)?.synthesisSnapshot)
        } finally {
            db.close()
        }
    }

    @Test fun missingPolicyBlocksLookupButFlowAndAnswerHistoryRemainReadable() = runBlocking {
        val db = memory()
        try {
            val original = seed(db).single()
            // Populate the immutable cache before revoking its database authority.
            assertEquals(original, db.agentRunDao().getById(original.runId))
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM run_policy_snapshots WHERE kind = 'REQUESTED_POLICY'") }
            assertTrue(runCatching { db.agentRunDao().getById(original.runId) }.exceptionOrNull() is RunPolicySnapshotException)
            val projected = db.agentRunDao().observeByChatId(1).first().single()
            assertEquals("FAILED", projected.status)
            assertTrue(projected.terminalError!!.startsWith("POLICY_SNAPSHOT_"))
            assertNull(projected.synthesisSnapshot)
            assertEquals("Readable saved answer 1", db.messageDao().message(original.assistantMessageId)?.content)
            assertTrue(db.agentRunDao().getRecoverableGatewayRuns().isEmpty())
            // Damaged policy data does not prevent the explicit delete action.
            assertEquals(1, db.agentRunDao().getReadableByChatId(1).size)
            db.chatRoomDao().deleteChatRooms(requireNotNull(db.agentPersistenceDao().getChatRoom(1)))
            assertEquals(0, count(db, "run_policy_snapshots"))
        } finally {
            db.close()
        }
    }

    @Test fun policyFailureAfterRunningProjectionTerminalizesOnlyTheOwnedRunAndPreservesSavedAnswer() = runBlocking {
        val db = memory()
        try {
            val original = seed(db).single().copy(inputTokens = 7, outputTokens = 12)
            db.agentRunDao().upsert(original)
            withContext(Dispatchers.IO) { db.openHelper.writableDatabase.execSQL("DELETE FROM run_policy_snapshots WHERE kind = 'REQUESTED_POLICY'") }
            val repository = mockk<dev.chungjungsoo.gptmobile.data.repository.ChatRepository>(relaxed = true)
            coEvery { repository.markAgentRunRunning(any(), any()) } coAnswers {
                db.agentRunDao().markRunning(firstArg(), secondArg())
                // The strict execution read detects missing accepted policy after the raw row
                // became active. Its error handler must not re-enter the broken hydration path.
                db.agentRunDao().getById(firstArg())
                true
            }
            val finished = CompletableDeferred<Boolean>()
            coEvery { repository.finishActiveAgentRun(any(), any(), any(), any()) } coAnswers {
                (db.agentRunDao().finishActive(firstArg(), secondArg(), thirdArg(), arg(3)) == 1).also { finished.complete(it) }
            }
            coEvery { repository.commitAgentRun(any(), any(), any(), any(), any()) } throws RunPolicySnapshotException("POLICY_SNAPSHOT_MISSING")
            val settings = mockk<dev.chungjungsoo.gptmobile.data.repository.SettingRepository>(relaxed = true)
            coEvery { settings.getFeatureSettings() } returns dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings(backgroundGeneration = false)
            val coordinator = dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator(RuntimeEnvironment.getApplication(), repository, mockk(relaxed = true), settings, mockk(relaxed = true))
            val answer = requireNotNull(db.messageDao().message(original.assistantMessageId))
            coordinator.start(
                listOf(
                    dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest(
                        original.runId,
                        1,
                        answer,
                        dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(uid = original.profileUid, name = "Captured profile", model = "model"),
                        listOf(requireNotNull(db.messageDao().message(original.userMessageId))),
                        listOf(listOf(answer))
                    )
                )
            )
            withTimeout(10_000) {
                assertTrue(finished.await())
                coordinator.activeRuns.first { it.isEmpty() }
            }
            val stored = requireNotNull(db.agentRunDao().rawGetById(original.runId))
            assertEquals("FAILED", stored.status)
            assertTrue(stored.terminalError!!.startsWith("POLICY_SNAPSHOT_"))
            assertEquals(7, stored.inputTokens)
            assertEquals(12, stored.outputTokens)
            assertEquals(answer, db.messageDao().message(answer.id))
            coVerify(exactly = 0) { repository.completeChat(any(), any(), any(), any(), any(), any()) }
            coVerify(exactly = 0) { repository.commitAgentRun(any(), any(), any(), any(), any()) }
        } finally {
            db.close()
        }
    }

    @Test fun temporaryCreationConversionAndLastOwnerDeletionDoNotRetainPolicyBodies() = runBlocking {
        val db = memory()
        try {
            val temporary = seed(db, chatId = 2, temporary = true).single()
            assertFalse(RunPolicySnapshotCodec.isReference(db.agentRunDao().rawGetById(temporary.runId)?.synthesisSnapshot))
            assertEquals(0, count(db, "run_policy_snapshots"))
            val originals = seed(db, 2)
            db.messageDao().deleteMessages(requireNotNull(db.messageDao().message(originals.first().assistantMessageId)))
            assertEquals(2, count(db, "run_policy_snapshots"))
            db.messageDao().deleteMessages(requireNotNull(db.messageDao().message(originals.last().assistantMessageId)))
            assertEquals(0, count(db, "run_policy_snapshots"))
            val normal = seed(db, chatId = 3).single()
            db.chatRoomDao().updateTemporary(3, true)
            assertEquals(normal, db.agentRunDao().getById(normal.runId))
            assertFalse(RunPolicySnapshotCodec.isReference(db.agentRunDao().rawGetById(normal.runId)?.synthesisSnapshot))
            assertEquals(0, count(db, "run_policy_snapshots"))
            val snapshot = File(RuntimeEnvironment.getApplication().cacheDir, "policy-temp-${UUID.randomUUID()}.db")
            try {
                db.withTransaction { CompleteBackupDatabase.snapshot(db.openHelper.readableDatabase, snapshot) }
                SQLiteDatabase.openDatabase(snapshot.path, null, SQLiteDatabase.OPEN_READONLY).use { saved ->
                    saved.rawQuery("SELECT COUNT(*) FROM chats_v2 WHERE is_temporary = 1", null).use { rows ->
                        rows.moveToFirst()
                        assertEquals(0, rows.getInt(0))
                    }
                }
            } finally {
                snapshot.delete()
            }
        } finally {
            db.close()
        }
    }

    @Test fun partialBackupKeepsReferencesAndRejectsCorruptImportBeforeReplacingDestination() = runBlocking {
        val source = memory()
        val destination = memory()
        try {
            val originals = seed(source, 2)
            val selection = CompleteBackupSelection(setOf(CompleteBackupSection.AGENT_HISTORY))
            source.withTransaction { CompleteBackupDatabase.retainSections(source.openHelper.writableDatabase, selection) }
            assertEquals(2, count(source, "run_policy_snapshots"))
            destination.withTransaction { CompleteBackupDatabase.restoreSections(source.openHelper.readableDatabase, destination.openHelper.writableDatabase, selection) }
            originals.forEach { original -> assertEquals(original.synthesisSnapshot, destination.agentRunDao().getById(original.runId)?.synthesisSnapshot) }
            val readableBefore = destination.messageDao().getMessageList()
            withContext(Dispatchers.IO) { source.openHelper.writableDatabase.execSQL("UPDATE run_policy_snapshots SET authorityDigest = 'corrupt'") }
            val failure = runCatching {
                destination.withTransaction { CompleteBackupDatabase.restoreSections(source.openHelper.readableDatabase, destination.openHelper.writableDatabase, selection) }
            }.exceptionOrNull()
            assertTrue(failure is RunPolicySnapshotException)
            assertEquals(readableBefore, destination.messageDao().getMessageList())
            assertEquals(originals.first().synthesisSnapshot, destination.agentRunDao().getById(originals.first().runId)?.synthesisSnapshot)
        } finally {
            source.close()
            destination.close()
        }
    }

    @Test fun legacyInlineImportIsNormalizedAndUnknownPoliciesRemainVerbatim() = runBlocking {
        val source = memory()
        val destination = memory()
        try {
            val originals = seed(source)
            source.withTransaction {
                originals.forEach {
                    source.agentRunDao().rawUpsertPolicyRun(it)
                    source.agentRunDao().removePolicyRefs(it.runId)
                }
            }
            assertEquals(0, count(source, "run_policy_snapshots"))
            destination.withTransaction { CompleteBackupDatabase.restore(source.openHelper.readableDatabase, destination.openHelper.writableDatabase) }
            assertEquals(2, count(destination, "run_policy_snapshots"))
            assertEquals(originals.single().synthesisSnapshot, destination.agentRunDao().getById(originals.single().runId)?.synthesisSnapshot)
            val unknown = originals.single().copy(synthesisSnapshot = "{\"futurePolicyVersion\":99}", synthesisKey = null)
            destination.agentRunDao().upsert(unknown)
            assertEquals(unknown, destination.agentRunDao().getById(unknown.runId))
            assertEquals(0, count(destination, "run_policy_snapshots"))
        } finally {
            source.close()
            destination.close()
        }
    }

    @Test fun migrationFrom44NormalizesOnceAndColdReadsPreserveHistoricalSeal() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val name = "policy-migration-${UUID.randomUUID()}.db"
        val file = context.getDatabasePath(name).also { it.parentFile!!.mkdirs() }
        val raw = RunPolicyFixture.raw()
        val original = RunPolicyFixture.run(raw = raw)
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            val schema = File(System.getProperty("room.schemas"), "dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2/44.json").readText()
            val data = Json.parseToJsonElement(schema).jsonObject.getValue("database").jsonObject
            data.getValue("entities").jsonArray.forEach { entry ->
                val entity = entry.jsonObject
                val table = entity.getValue("tableName").jsonPrimitive.content
                old.execSQL(entity.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table))
                entity["indices"]?.jsonArray.orEmpty().forEach { index -> old.execSQL(index.jsonObject.getValue("createSql").jsonPrimitive.content.replace("\${TABLE_NAME}", table)) }
                entity["contentSyncTriggers"]?.jsonArray.orEmpty().forEach { old.execSQL(it.jsonPrimitive.content) }
            }
            old.execSQL("INSERT INTO chats_v2(chat_id,title,enabled_platform,created_at,updated_at) VALUES(1,'preserved','a',1,1)")
            old.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,created_at) VALUES(10,1,'',?,'[]',0,1)", arrayOf(RunPolicyFixture.task))
            old.execSQL("INSERT INTO messages_v2(message_id,chat_id,thoughts,content,attachments,linked_message_id,platform_type,created_at) VALUES(20,1,'','Saved answer','[]',10,'a',1)")
            old.execSQL("INSERT INTO agent_runs(run_id,chat_id,user_message_id,assistant_message_id,profile_uid,provider_snapshot,model_snapshot,status,created_at,gateway_last_sequence,synthesis_key,synthesis_snapshot) VALUES('run-a',1,10,20,'a','CUSTOM','model','QUEUED',1,-1,?,?)", arrayOf(original.synthesisKey, raw))
            old.version = 44
        }
        fun open() = Room.databaseBuilder(context, ChatDatabaseV2::class.java, name).addMigrations(*ChatDatabaseV2Migrations.ALL_MIGRATIONS).addCallback(RunPolicySnapshotDatabase.callback).build()
        var db = open()
        try {
            assertEquals(raw, db.agentRunDao().getById("run-a")?.synthesisSnapshot)
            assertEquals(2, count(db, "run_policy_snapshots"))
            assertTrue(RunPolicySnapshotCodec.isReference(db.agentRunDao().rawGetById("run-a")?.synthesisSnapshot))
            db.close()
            RunPolicySnapshotCodec.forgetChat(1)
            db = open()
            assertEquals(raw, db.agentPersistenceDao().recoveryRun("run-a")?.synthesisSnapshot)
            assertEquals(original.synthesisKey, db.agentRunDao().getById("run-a")?.synthesisKey)
        } finally {
            db.close()
            context.deleteDatabase(name)
        }
    }
}
