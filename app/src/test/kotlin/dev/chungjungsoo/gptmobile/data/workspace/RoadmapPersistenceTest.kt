package dev.chungjungsoo.gptmobile.data.workspace

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation
import dev.chungjungsoo.gptmobile.data.accounting.ModelPrice
import dev.chungjungsoo.gptmobile.data.accounting.SpendBudgetSettings
import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupDatabase
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import dev.chungjungsoo.gptmobile.data.privacy.ConversationDeletion
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class RoadmapPersistenceTest {
    private lateinit var db: ChatDatabaseV2
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(context, ChatDatabaseV2::class.java).build()
    }

    @After fun close() {
        db.close()
    }

    @Test fun parallelCostReservationsCannotOverspendOneTurn() = runBlocking {
        val budget = SpendBudgetSettings(perTurnMicros = 100)
        val outcomes = (0..7).map { index ->
            async {
                runCatching { db.invocationDao().reserve(ModelInvocation("$index", "run", "turn", "provider", "model", "primary", 10, 10, costMicros = 40, currency = "USD"), Int.MAX_VALUE, budget) }.isSuccess
            }
        }.awaitAll()
        assertEquals(2, outcomes.count { it })
        assertEquals(80L, db.invocationDao().turnCost("turn", "USD"))
        assertTrue(runCatching { db.invocationDao().reserve(ModelInvocation("unknown", "run", "turn2", "provider", "model", "primary", 10, 10), Int.MAX_VALUE, budget) }.isFailure)
        assertEquals(1L, ModelPrice("model", 1, 1, "fixture", 1).cost(1, 1))
    }

    @Test fun snapshotsPreserveAComposerChangedWhileAResponseWasSaving() = runBlocking {
        val original = ChatRoomV2(title = "Chat", draftText = "older")
        val id = db.chatRoomDao().addChatRoom(original).toInt()
        db.chatRoomDao().saveComposerDraft(id, "newer unsent prompt", "[]", 200)
        db.agentPersistenceDao().saveChatSnapshot(original.copy(id = id), emptyList(), emptyMap())
        assertEquals("newer unsent prompt", db.chatRoomDao().getChatRoomsByIds(listOf(id)).single().draftText)
    }

    @Test fun temporaryDeletionPurgesApprovalsContextAndUnsharedFilesButPreservesBranchFiles() = runBlocking {
        val shared = File(context.filesDir, "shared-privacy-fixture.txt").apply { writeText("shared") }
        val privateFile = File(context.filesDir, "private-privacy-fixture.txt").apply { writeText("private") }
        fun attachment(file: File) = ChatAttachment(file.path, file.path, file.name, "text/plain", file.length())
        val room = ChatRoomV2(title = "private", isTemporary = true, draftAttachments = Json.encodeToString(listOf(attachment(privateFile))))
        val id = db.chatRoomDao().addChatRoom(room).toInt()
        val other = db.chatRoomDao().addChatRoom(ChatRoomV2(title = "branch")).toInt()
        val user = db.agentPersistenceDao().insertMessage(MessageV2(chatId = id, content = "private", platformType = null, attachments = listOf(attachment(shared)))).toInt()
        val assistant = db.agentPersistenceDao().insertMessage(MessageV2(chatId = id, content = "", platformType = "p", linkedMessageId = user)).toInt()
        db.agentPersistenceDao().insertMessage(MessageV2(chatId = other, content = "branch", platformType = null, attachments = listOf(attachment(shared))))
        db.agentRunDao().upsert(AgentRun("run", id, user, assistant, "p", "test", "test"))
        db.invocationDao().save(ModelInvocation("spend", "run", "sensitive-turn", "provider", "private-model", "primary", 100, 100, costMicros = 40, currency = "USD"))
        db.toolApprovalDao().insert(ToolApproval("approval", "run", "c", "write", "hash", "private argument"))
        db.workspaceDao().save(WorkspaceRecord("context", "context", "request", "private context", id, "run"))
        ConversationDeletion(db, context).delete(listOf(room.copy(id = id)))
        assertTrue(db.toolApprovalDao().pending().first().isEmpty())
        assertNull(db.workspaceDao().get("context"))
        val cost = db.invocationDao().recent().first().single()
        assertEquals("anonymous_cost", cost.kind)
        assertEquals("", cost.parentRunId)
        assertEquals("", cost.model)
        assertEquals(40L, db.invocationDao().dailyCost(0, "USD"))
        assertFalse(privateFile.exists())
        assertTrue(shared.exists())
        shared.delete()
        Unit
    }

    @Test fun portableDatabaseNeverContainsTemporaryConversationsOrTheirDerivedRecords() = runBlocking {
        val id = db.chatRoomDao().addChatRoom(ChatRoomV2(title = "private", isTemporary = true)).toInt()
        db.chatRoomDao().addChatRoom(ChatRoomV2(title = "keep", draftText = "draft"))
        db.agentPersistenceDao().insertMessage(MessageV2(chatId = id, content = "private message", platformType = null))
        db.workspaceDao().save(WorkspaceRecord("evidence", "evidence", "private", "private source", id))
        val file = File(context.cacheDir, "private-backup-test.sqlite").also { it.delete() }
        CompleteBackupDatabase.snapshot(db.openHelper.writableDatabase, file)
        SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { copy ->
            copy.rawQuery("SELECT title, draft_text FROM chats_v2", null).use {
                assertTrue(it.moveToFirst())
                assertEquals("keep", it.getString(0))
                assertEquals("draft", it.getString(1))
                assertFalse(it.moveToNext())
            }
            copy.rawQuery("SELECT COUNT(*) FROM workspace_records", null).use {
                it.moveToFirst()
                assertEquals(0, it.getInt(0))
            }
            copy.rawQuery("PRAGMA foreign_key_check", null).use { assertFalse(it.moveToFirst()) }
        }
        assertEquals(2, db.chatRoomDao().getChatRooms().size)
        file.delete()
        Unit
    }

    @Test fun pluginExportExcludesCredentialsQueriesAndPermissionsAndRejectsInvalidImports() {
        val connection = ToolConnection("id", "Example", "example", "MCP", "https://example.com/token-in-path?api_key=secret", "BEARER", "vault-secret-ref", "client", toolPolicy = "TRUSTED", approvedReadTools = "write")
        val encoded = PluginConfiguration.export(mapOf("clock" to PluginExecutionSettings()), listOf(connection))
        listOf("token-in-path", "api_key", "vault-secret-ref", "TRUSTED", "approvedReadTools").forEach { assertFalse(encoded.contains(it)) }
        val decoded = PluginConfiguration.decode(encoded)
        assertTrue(decoded.connections.single().requiresCredential)
        assertEquals("https://example.com", decoded.connections.single().origin)
        assertTrue(runCatching { PluginConfiguration.validate(PluginExecutionSettings(timeoutSeconds = -1)) }.isFailure)
        assertTrue(runCatching { PluginConfiguration.decode(encoded.replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")) }.isFailure)
        assertTrue(ReviewDiff.render("a.txt", "same\nold\nend", "same\nnew\nend").contains("-old\n+new\n"))
    }

    @Test fun requestReceiptsKeepMemoryContentOnlyInTheProtectedVault() = runBlocking {
        val secrets = mutableMapOf<String, ByteArray>()
        val vault = object : dev.chungjungsoo.gptmobile.data.security.SecretVault {
            override suspend fun put(secretRef: String, secret: ByteArray) {
                secrets[secretRef] = secret.copyOf()
            }
            override suspend fun read(secretRef: String) = secrets[secretRef]?.copyOf()
            override suspend fun delete(secretRef: String) {
                secrets.remove(secretRef)
            }
            override suspend fun references() = secrets.keys.toSet()
        }
        val room = ChatRoomV2(title = "Protected")
        val id = db.chatRoomDao().addChatRoom(room).toInt()
        val repository = WorkspaceRepository(db, vault)
        val fact = dev.chungjungsoo.gptmobile.data.rag.VaultFact("private-fact", dev.chungjungsoo.gptmobile.data.rag.MemoryLearning.observation("Private phrase BLUEBIRD"))
        val plan = dev.chungjungsoo.gptmobile.data.context.ContextPlan(emptyList(), "", emptyList(), 0, 256, "fixture")
        repository.recordContext(id, "run", dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2(name = "Test", uid = "p"), plan, emptyList(), dev.chungjungsoo.gptmobile.data.rag.FactRecall(listOf(fact)), "Document phrase REDBIRD", false, false)
        val entry = repository.dao.list("context").single()
        assertFalse(entry.payload.contains("BLUEBIRD"))
        assertFalse(entry.payload.contains("REDBIRD"))
        assertTrue(repository.readReceipt(entry).facts.values.single().contains("BLUEBIRD"))
        assertTrue(repository.readReceipt(entry).documents.contains("REDBIRD"))
        ConversationDeletion(db, context, vault).delete(listOf(room.copy(id = id)))
        assertTrue(secrets.isEmpty())
    }
}
