package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import java.io.IOException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphIndexContinuationTest {
    private val sha = "a".repeat(40)
    private val attempt = "b".repeat(64)
    private val task = "Test all GitHub tools and functions"
    private val context = AgentToolDefinition(
        "github_context__selected",
        "Selected repository reads",
        Json.parseToJsonElement("""{"type":"object","required":["action"],"additionalProperties":false,"properties":{"action":{"enum":["status","repository"]}}}""").jsonObject
    )

    @Test fun unconfirmedIndexDoesNotStrandAuthorizedIndependentReadsOrEraseItsObligation() = runTest {
        var indexWrites = 0
        var statusReads = 0
        val graph = RepositoryGraphTool("repository_graph__selected") { arguments ->
            assertEquals(JsonPrimitive("gateway"), arguments["map_source"])
            if (arguments["action"] == JsonPrimitive("index")) {
                indexWrites++
                throw GatewayGraphOperationException(IOException("Lost index reply"), "selected-host", "Selected host", "CHECK_SAVED_INDEX", true, indexAttemptHash = attempt, currentIndexOutcomeUnknown = true)
            }
            statusReads++
            buildJsonObject {
                put("status", "NOT_INDEXED")
                put("repository", "owner/repo")
                put("commit_sha", sha)
                put(
                    "index_checkpoint",
                    buildJsonObject {
                        put("index_outcome_unknown", true)
                        put("graph_index_attempt_hash", attempt)
                    }
                )
            }
        }
        val tools = listOf(context, graph.definition)
        val book = GitHubEvidenceBook("task", "owner/repo", "main", tools.map { it.name }.toSet(), now = { 1000L })
        observeContext(book)
        val index = ProviderEvent.ToolCall("first-index", graph.definition.name, graphAction("index"))
        book.observe(index, graph.execute(index.callId, index.arguments))
        val gate = session(book, tools, "request-1")
        val recovery = gate.streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(graph.definition.name, recovery.name)
        assertEquals(JsonPrimitive("status"), recovery.arguments["action"])
        assertEquals(JsonPrimitive("gateway"), recovery.arguments["map_source"])
        val observed = graph.execute(recovery.callId, recovery.arguments)
        val continued = gate.streamRound(tools, listOf(AgentToolExchange(listOf(recovery), listOf(observed)))).toList()
        val read = continued.filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(context.name, read.name)
        assertEquals(action("repository"), read.arguments)
        assertTrue(continued.none { it is ProviderEvent.Failed })
        assertNotNull(GitHubExecutionLedger.pendingGraphIndex(book.snapshot().first))
        val ledger = GitHubExecutionLedger.attach(buildJsonObject {}, book.snapshot().first).getValue("execution_ledger").jsonObject
        assertEquals(JsonPrimitive(1), ledger["unresolved_graph_index_attempts"])
        assertEquals(1, indexWrites)
        assertEquals(1, statusReads)
        val fresh = session(book, tools, "request-2").streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(graph.definition.name, fresh.name)
        assertFalse(fresh.callId == recovery.callId)
    }

    @Test fun acceptedJobRefreshesOnceThenLetsNativeAuditProceedWithoutAnotherIndex() = runTest {
        var indexWrites = 0
        var statusReads = 0
        val graph = RepositoryGraphTool("repository_graph__selected") { arguments ->
            assertEquals(JsonPrimitive("gateway"), arguments["map_source"])
            val indexing = arguments["action"] == JsonPrimitive("index")
            if (indexing) indexWrites++ else statusReads++
            buildJsonObject {
                put("repository", "owner/repo")
                put("commit_sha", sha)
                put("has_more", true)
                put("index_cursor", "owned-by-job")
                put(
                    "index_job",
                    buildJsonObject {
                        put("id", "saved-job")
                        put("generation", 1)
                        put("state", if (indexing) "QUEUED" else "RUNNING")
                    }
                )
            }
        }
        val tools = listOf(context, graph.definition)
        val book = GitHubEvidenceBook("task", "owner/repo", "main", tools.map { it.name }.toSet(), now = { 1000L })
        observeContext(book)
        val index = ProviderEvent.ToolCall("accepted-index", graph.definition.name, graphAction("index"))
        book.observe(index, graph.execute(index.callId, index.arguments))
        val gate = session(book, tools, "request-1")
        val refresh = gate.streamRound(tools, emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(graph.definition.name, refresh.name)
        assertEquals(graphAction("status"), refresh.arguments)
        val observed = graph.execute(refresh.callId, refresh.arguments)
        val continued = gate.streamRound(tools, listOf(AgentToolExchange(listOf(refresh), listOf(observed)))).toList()
        val read = continued.filterIsInstance<ProviderEvent.ToolCall>().single()
        assertEquals(context.name, read.name)
        assertEquals(action("repository"), read.arguments)
        assertEquals(1, indexWrites)
        assertEquals(1, statusReads)
        val receipts = book.snapshot().first.values.toList()
        assertTrue(GitHubSelfTestGraph.available(tools, receipts).isEmpty())
        assertFalse(GitHubTestChecklist.capabilitiesComplete(task, book.snapshot().first, tools))
    }

    private fun session(book: GitHubEvidenceBook, tools: List<AgentToolDefinition>, key: String) = GitHubVerifiedSession(
        object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = error("The saved audit has deterministic read recipes")
        },
        book,
        task,
        { 8192 },
        { error("Graph preparation needs no answer verifier") },
        {},
        {},
        now = { 1000L },
        authorizedTools = tools,
        resumeRequestKey = key,
        remainingToolCalls = { 128 }
    )

    private suspend fun observeContext(book: GitHubEvidenceBook) {
        val call = ProviderEvent.ToolCall("selected-repository", context.name, action("status"))
        book.observe(
            call,
            AgentToolResult(
                call.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("branch", "main")
                        put("commit_sha", sha)
                    }
                ),
                false
            )
        )
    }

    private fun graphAction(value: String) = buildJsonObject {
        put("action", value)
        put("map_source", "gateway")
    }

    private fun action(value: String) = buildJsonObject { put("action", value) }

    private fun graphAction(value: String) = buildJsonObject {
        put("action", value)
        put("map_source", "gateway")
    }
}
