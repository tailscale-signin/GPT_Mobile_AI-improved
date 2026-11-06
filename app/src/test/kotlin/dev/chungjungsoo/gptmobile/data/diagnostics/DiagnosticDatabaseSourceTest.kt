package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import dev.chungjungsoo.gptmobile.presentation.ui.chat.writeStagedChatDiagnostics
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
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
@Config(sdk = [34], application = Application::class)
class DiagnosticDatabaseSourceTest {
    private lateinit var database: ChatDatabaseV2
    private lateinit var research: ResearchDatabase
    private lateinit var workspace: WorkspaceRepository
    private lateinit var source: DiagnosticDatabaseSource
    private val values = mutableMapOf<String, ByteArray>()
    private val vault = object : SecretVault {
        override suspend fun put(secretRef: String, secret: ByteArray) {
            values[secretRef] = secret.copyOf()
        }
        override suspend fun read(secretRef: String) = values[secretRef]?.copyOf()
        override suspend fun storedSize(secretRef: String) = values[secretRef]?.size?.plus(38)?.toLong()
        override suspend fun delete(secretRef: String) {
            values.remove(secretRef)
        }
    }

    @Before fun setUp() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        research = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ResearchDatabase::class.java).build()
        workspace = WorkspaceRepository(database, vault)
        source = DiagnosticDatabaseSource(database, research, workspace)
    }

    @After fun close() {
        database.close()
        research.close()
    }

    @Test fun byteBoundSkipsOversizedUnicodeWhileRetainingOtherRowsAndChatScope() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        val other = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Other")).toInt()
        database.messageDao().addMessages(
            MessageV2(chatId = chat, content = "漢".repeat(600), platformType = null),
            MessageV2(chatId = chat, content = "retained message", platformType = null),
            MessageV2(chatId = other, content = "other chat private message", platformType = null)
        )
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "conversation", 1024, {}, { rows += it }, omissions::add)
        assertTrue(omissions.contains("RECORD_BYTE_LIMIT"))
        assertTrue(rows.toString().contains("retained message"))
        assertFalse(rows.toString().contains("漢"))
        assertFalse(rows.toString().contains("other chat private message"))
    }

    @Test fun newRowsAfterCaptureStartsDoNotMoveTheHighWatermark() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        repeat(20) { database.messageDao().addMessages(MessageV2(chatId = chat, content = "original-$it", platformType = null)) }
        val rows = mutableListOf<JsonObject>()
        source.capture(chat, "conversation", 4096, {}, { row ->
            rows += row
            if (rows.size == 1) database.messageDao().addMessages(MessageV2(chatId = chat, content = "later row", platformType = null))
        }, {})
        assertEquals(20, rows.size)
        assertFalse(rows.toString().contains("later row"))
        assertEquals(1, rows.map { it["tableHighWatermark"] }.distinct().size)
    }

    @Test fun protectedEvidenceIsRestoredWithinBudgetInsteadOfExportingOnlyChunkReferences() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        workspace.saveProtectedSynthesisCheckpoint(
            "github-evidence:one",
            chat,
            "run",
            buildJsonObject {
                put("report", "Retained verified tool receipt")
                put("token", "must-never-export")
            },
            appendOnly = true
        )
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "workspace", 4096, {}, { rows += it }, omissions::add)
        assertTrue(rows.toString().contains("Retained verified tool receipt"))
        assertFalse(rows.toString().contains("must-never-export"))
        assertFalse(rows.toString().contains("workspace-memory-"))
        assertTrue(omissions.isEmpty())
    }

    @Test fun sourceBudgetRetainsOversizedAssistantForRedactedChunking() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        val content = "x".repeat(147_734)
        database.messageDao().addMessages(MessageV2(chatId = chat, content = content, platformType = null))
        val rows = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "conversation", 98_304, {}, { rows += it }, omissions::add, maximumSourceBytes = 256 * 1024)
        assertEquals(1, rows.size)
        assertTrue(rows.single().toString().contains(content))
        assertTrue(omissions.isEmpty())
    }

    @Test fun allReceiptsBeyondOneHundredHaveExplicitCoverageAndProjectedTimeline() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Selected")).toInt()
        val messages = listOf(
            database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "request", platformType = null)),
            database.agentPersistenceDao().insertMessage(MessageV2(chatId = chat, content = "response", platformType = null))
        )
        val run = AgentRun("run", chat, messages[0].toInt(), messages[1].toInt(), "profile", "provider", "model", status = "PARTIAL")
        database.agentPersistenceDao().insertRun(run)
        repeat(153) { index ->
            database.agentPersistenceDao().insertToolEvent(ToolEvent("event-$index", "run", index, "call-$index", null, null, "github_context", "github_context", "{}", "{}", "JSON", "COMPLETED"))
        }
        // A metadata-only run index must remain available even when full run snapshots are large.
        database.openHelper.writableDatabase.execSQL("UPDATE agent_runs SET synthesis_snapshot = ? WHERE run_id = ?", arrayOf("x".repeat(200_000), "run"))
        val rows = mutableListOf<JsonObject>()
        val coverage = mutableListOf<JsonObject>()
        val omissions = mutableListOf<String>()
        source.capture(chat, "tool_receipts", 4096, {}, { rows += it }, omissions::add, metadata = { coverage += it })
        assertEquals(153, rows.size)
        assertEquals(153L, coverage.single()["expectedRows"]!!.jsonPrimitive.long)
        assertEquals(153L, coverage.single()["emittedRows"]!!.jsonPrimitive.long)
        assertEquals("null", coverage.single()["rowLimit"].toString())
        val runs = mutableListOf<JsonObject>()
        source.capture(chat, "run_index", 1024, {}, { runs += it }, omissions::add)
        assertEquals(1, runs.size)
        assertFalse(runs.toString().contains("synthesis_snapshot"))
        val timeline = mutableListOf<JsonObject>()
        source.capture(chat, "timeline", 4096, {}, { timeline += requireNotNull(DiagnosticTimeline.database(it, 123)) }, omissions::add)
        assertEquals(154, timeline.size)
        assertTrue(timeline.any { it["eventId"]?.jsonPrimitive?.content == "tool_events:event-152" })
        assertTrue(omissions.isEmpty())
        val context = RuntimeEnvironment.getApplication()
        val destination = File(context.cacheDir, "all-receipt-export").apply { mkdirs() }
        try {
            val exported = writeStagedChatDiagnostics(context, destination, ChatRoomV2(id = chat, title = "Selected"), emptyList(), ChatMcpToolConfig(), AppFeatureSettings(), "", emptyList(), source, null, false, false) {}
            val exportedRows = ZipFile(exported.file).use { zip ->
                zip.getInputStream(zip.getEntry("tool-receipts.jsonl")).bufferedReader().use { reader ->
                    reader.lineSequence().map { Json.parseToJsonElement(it).jsonObject }.filter { it["fields"] is JsonObject }.toList()
                }
            }
            assertEquals(153, exportedRows.size)
            assertEquals(153, exportedRows.map { it.getValue("fields").jsonObject.getValue("event_id") }.distinct().size)
            assertEquals(exported.manifest, StagedDiagnosticCapture.verify(exported.file))
        } finally {
            DiagnosticCaptureStorage.deleteChat(context, chat)
            destination.deleteRecursively()
        }
    }

    @Test fun roomBackedAdapterPublishesVerifiableOversizedAndEscapedMessageChunks() = runBlocking {
        val chat = database.chatRoomDao().addChatRoom(ChatRoomV2(title = "Chunk verification")).toInt()
        val content = "x".repeat(147_734)
        val escaped = "\"\\\u0001😀\n".repeat(30_000)
        database.messageDao().addMessages(
            MessageV2(chatId = chat, content = content, platformType = null),
            MessageV2(chatId = chat, content = escaped, platformType = null),
            MessageV2(chatId = chat, content = "x".repeat(1_048_577), platformType = null)
        )
        val context = RuntimeEnvironment.getApplication()
        val destination = File(context.cacheDir, "large-message-export").apply { mkdirs() }
        try {
            val result = writeStagedChatDiagnostics(context, destination, ChatRoomV2(id = chat, title = "Chunk verification"), emptyList(), ChatMcpToolConfig(), AppFeatureSettings(), "", emptyList(), source, null, false, false) {}
            val lines = ZipFile(result.file).use { zip ->
                zip.getInputStream(zip.getEntry("conversation.jsonl")).bufferedReader().use { it.readLines() }
            }
            assertTrue(lines.all { it.toByteArray(Charsets.UTF_8).size <= 512 * 1024 })
            val chunks = lines.map { Json.parseToJsonElement(it).jsonObject }.filter { it["kind"]?.jsonPrimitive?.content == "diagnostic_record_chunk" }
            val contents = chunks.groupBy { it.getValue("recordId").jsonPrimitive.content }.values.map { group ->
                val ordered = group.sortedBy { it.getValue("chunkIndex").jsonPrimitive.int }
                assertEquals(ordered.size, ordered.first().getValue("chunkCount").jsonPrimitive.int)
                ordered.forEachIndexed { index, chunk ->
                    assertEquals(index, chunk.getValue("chunkIndex").jsonPrimitive.int)
                    assertEquals(DiagnosticRecordChunks.sha256(chunk.getValue("content").jsonPrimitive.content), chunk.getValue("chunkSha256").jsonPrimitive.content)
                }
                val reassembled = ordered.joinToString("") { it.getValue("content").jsonPrimitive.content }
                assertEquals(DiagnosticRecordChunks.sha256(reassembled), ordered.first().getValue("recordSha256").jsonPrimitive.content)
                Json.parseToJsonElement(reassembled).jsonObject.getValue("fields").jsonObject.getValue("content").jsonPrimitive.content
            }
            assertEquals(setOf(content, escaped), contents.toSet())
            val receipt = (result.manifest.getValue("stages") as JsonArray).map { it.jsonObject }.single { it["id"]?.jsonPrimitive?.content == "conversation" }
            assertEquals("PARTIAL", receipt.getValue("status").jsonPrimitive.content)
            assertEquals(1L, receipt.getValue("omissions").jsonObject.getValue("RECORD_BYTE_LIMIT").jsonPrimitive.long)
            assertFalse(result.complete)
        } finally {
            DiagnosticCaptureStorage.deleteChat(context, chat)
            destination.deleteRecursively()
        }
    }
}
