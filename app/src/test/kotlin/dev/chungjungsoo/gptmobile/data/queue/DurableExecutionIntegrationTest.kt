package dev.chungjungsoo.gptmobile.data.queue

import android.app.Application
import androidx.room.Room
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.knowledge.KnowledgeWorkspaceRepository
import dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.security.SecretVault
import io.mockk.mockk
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [34])
class DurableExecutionIntegrationTest {
    private lateinit var database: ChatDatabaseV2

    @Before fun setup() {
        database = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ChatDatabaseV2::class.java).build()
        runBlocking { database.chatRoomDao().addChatRoom(ChatRoomV2(id = 1, title = "Queue", enabledPlatform = listOf("p"))) }
    }

    @After fun close() {
        database.close()
    }
    private fun prompt(text: String = "queued") = PendingPrompt("q", 1, text, Json.encodeToString(PendingPromptPayload(profileUids = listOf("p"))), 1)
    private fun request(text: String = "queued") = PersistAgentTurnRequest(
        chatRoom = ChatRoomV2(id = 1, title = "Queue", enabledPlatform = listOf("p")),
        userMessage = MessageV2(content = text, platformType = null),
        runs = listOf(AgentRunDraft("r", "p", "CUSTOM", "model")),
        chatPlatformModels = mapOf("p" to "model"),
        queuedPromptId = "q"
    )

    @Test fun `accepted queue input survives DAO reload and dispatches once atomically`() = runBlocking {
        database.pendingPromptDao().enqueue(prompt())
        assertEquals("queued", database.pendingPromptDao().observePending().first().single().text)
        val result = database.agentPersistenceDao().persistAgentTurn(request())
        assertNotNull(database.pendingPromptDao().get("q")!!.userMessageId)
        assertTrue(database.pendingPromptDao().observePending().first().isEmpty())
        assertTrue(runCatching { database.agentPersistenceDao().persistAgentTurn(request()) }.isFailure)
        assertEquals(2, database.messageDao().loadMessages(1).size)
        assertEquals(result.userMessage.id, database.agentRunDao().getById("r")!!.userMessageId)
    }

    @Test fun `editing or pausing a queued prompt prevents stale dispatch`() = runBlocking {
        database.pendingPromptDao().enqueue(prompt())
        database.pendingPromptDao().edit("q", "changed")
        assertTrue(runCatching { database.agentPersistenceDao().persistAgentTurn(request()) }.isFailure)
        database.pendingPromptDao().pause("q", true)
        assertTrue(runCatching { database.agentPersistenceDao().persistAgentTurn(request("changed")) }.isFailure)
        assertTrue(database.messageDao().loadMessages(1).isEmpty())
    }

    @Test fun `interrupted gateway answer restores only its current revision`() = runBlocking {
        database.pendingPromptDao().enqueue(prompt())
        val result = database.agentPersistenceDao().persistAgentTurn(request())
        database.agentRunDao().bindGatewayJob("r", "job-1", "http://192.168.1.2")
        database.agentRunDao().updateStatus("r", "INTERRUPTED", 1, 2, null)
        assertFalse(database.agentPersistenceDao().restoreGatewayAnswer("r", "stale-job", "stale", 3))
        assertTrue(database.agentPersistenceDao().restoreGatewayAnswer("r", "job-1", "recovered", 3))
        assertEquals("recovered", database.messageDao().loadMessages(1).first { it.id == result.assistantMessages.single().id }.content)
        assertEquals("COMPLETED", database.agentRunDao().getById("r")!!.status)
        assertFalse(database.agentPersistenceDao().restoreGatewayAnswer("r", "job-1", "duplicate", 4))
    }

    @Test fun `deleted knowledge does not reappear when original attachment is seen again`() = runBlocking {
        val repository = KnowledgeWorkspaceRepository(database)
        val id = repository.index("Manual", "The camera uses a blue lens.", chatId = 1)
        assertTrue(repository.context(1, "camera lens").contains("blue lens"))
        repository.dao.deleteDocument(id)
        repository.index("Manual", "The camera uses a blue lens.", chatId = 1)
        assertTrue(repository.dao.chunks(id).isEmpty())
        assertFalse(repository.context(1, "camera").contains("blue lens"))
    }

    @Test fun `approved mutation is claimed once and read only policy blocks unknown tools`() = runBlocking {
        val connection = ToolConnection("c", "Server", "server", "MCP", "https://example.com/mcp", "NONE", null, null)
        database.toolConnectionDao().upsertConnection(connection)
        val repository = ToolConnectionRepository(database.toolConnectionDao(), mockk<SecretVault>(relaxed = true))
        val manager = ToolApprovalManager(database, repository)
        val request = async { manager.authorize("c", "run", "call", "write_file", buildJsonObject { put("path", "notes.txt") }) }
        val pending = kotlinx.coroutines.withTimeout(10000) { manager.pending.first { it.isNotEmpty() }.single() }
        manager.decide(pending.id, true)
        assertTrue(request.await())
        assertFalse(manager.authorize("c", "run", "call", "write_file", buildJsonObject {}))
        database.toolConnectionDao().upsertConnection(connection.copy(toolPolicy = "READ_ONLY", approvedReadTools = "read_file"))
        assertFalse(manager.authorize("c", "run", "write2", "write_file", buildJsonObject {}))
        assertTrue(manager.authorize("c", "run", "read", "read_file", buildJsonObject {}))
    }

    @Test
    fun `full text search follows edits and deletion while window keeps complete turns`() = runBlocking {
        val dao = database.messageDao()
        (1..60).forEach { turn ->
            dao.addMessages(
                MessageV2(id = turn * 2, chatId = 1, content = "Question $turn café", platformType = null, createdAt = turn.toLong()),
                MessageV2(id = turn * 2 + 1, chatId = 1, content = "Answer $turn", platformType = "p", linkedMessageId = turn * 2, createdAt = turn.toLong())
            )
        }
        assertEquals(80, dao.observeWindow(1, 39).first().size)
        assertEquals(60, dao.observeTurnCount(1).first())
        assertEquals(listOf(1), dao.searchMessagesByContent("café"))
        val original = dao.loadMessages(1).first()
        dao.editMessages(original.copy(content = "unique replacement"))
        assertEquals(listOf(1), dao.searchMessagesByContent("unique"))
        dao.deleteMessages(original)
        assertTrue(dao.searchMessagesByContent("unique").isEmpty())
        assertTrue(dao.searchMessagesByContent("\" OR *").isEmpty())
    }

    @Test
    fun `delegates and primary models reserve the same turn budget atomically`() = runBlocking {
        val ledger = database.invocationDao()
        fun record(id: String) = dev.chungjungsoo.gptmobile.data.accounting.ModelInvocation(id, "run", "turn", "CUSTOM", "model", "delegate", 50, 50)
        val results = (1..3).map { id -> async { runCatching { ledger.reserve(record("$id"), 200) }.isSuccess } }.map { it.await() }
        assertEquals(2, results.count { it })
        assertEquals(200L, ledger.committedTokens("turn"))
        ledger.recover()
        assertTrue(ledger.recent().first().all { it.status == "INTERRUPTED" })
    }
    @Test
    fun `trusted retry of a matching write still asks before repeating its side effect`() = runBlocking {
        database.pendingPromptDao().enqueue(prompt())
        val turn = database.agentPersistenceDao().persistAgentTurn(request())
        val run = turn.runs.single()
        database.agentRunDao().upsert(run.copy(runId = "retry"))
        val connection = ToolConnection("c", "Server", "server", "MCP", "https://example.com/mcp", "NONE", null, null, toolPolicy = "TRUSTED")
        database.toolConnectionDao().upsertConnection(connection)
        val manager = ToolApprovalManager(database, ToolConnectionRepository(database.toolConnectionDao(), mockk<SecretVault>(relaxed = true)))
        val args = buildJsonObject { put("path", "notes.txt") }
        assertTrue(manager.authorize("c", run.runId, "first", "write_file", args))
        manager.finish(run.runId, "first", false)
        val repeated = async { manager.authorize("c", "retry", "second", "write_file", args) }
        val pending = kotlinx.coroutines.withTimeout(10000) { manager.pending.first { it.isNotEmpty() }.single() }
        assertTrue(pending.argumentPreview.contains("OUTCOME_UNKNOWN"))
        manager.decide(pending.id, false)
        assertFalse(repeated.await())
    }

}
