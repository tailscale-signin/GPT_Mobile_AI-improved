package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
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
}
