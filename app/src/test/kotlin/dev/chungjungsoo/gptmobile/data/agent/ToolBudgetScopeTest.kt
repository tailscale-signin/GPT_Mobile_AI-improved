package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolBudgetScopeTest {
    private val arguments = buildJsonObject { put("query", "source") }
    private fun source() = object : AgentTool {
        override val definition = AgentToolDefinition("read_source", "Read an authorized source", buildJsonObject {})
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("source"), false)
    }
    private fun catalog() = ProgressiveToolCatalog(listOf(source().definition), "Find source")

    @Test fun mainAndNestedNativeCataloguesUseTheSameAcceptedAllowance() = runTest {
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 3, maxConcurrentTools = 1))
        val mainCatalog = withContext(ToolBudgetScope(budget)) { appendBudgetedCatalog(emptyList(), catalog()).single() }
        assertFalse(mainCatalog.execute("main-discovery", arguments).isError)
        var inheritedName: String? = null
        var childResult: AgentToolResult? = null
        val delegate = object : AgentTool {
            override val definition = AgentToolDefinition("delegate_to_model", "Run a selected helper", buildJsonObject {})
            override val managesExecutionBudget = true
            override val coordinatesChildWork = true
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                inheritedName = currentCoroutineContext()[CoroutineName]?.name
                val nested = appendBudgetedCatalog(emptyList(), catalog()).single()
                childResult = nested.execute("nested-discovery", this@ToolBudgetScopeTest.arguments)
                return AgentToolResult(callId, ToolResultContent.Text("helper finished"), false)
            }
        }
        withContext(CoroutineName("accepted-authority-context")) {
            assertFalse(budget.bind(delegate).execute("helper", buildJsonObject {}).isError)
        }
        assertEquals("accepted-authority-context", inheritedName)
        assertFalse(requireNotNull(childResult).isError)
        assertEquals(3, budget.usedCalls())
        val afterNested = mainCatalog.execute("another-discovery", arguments)
        assertEquals("BUDGET_EXHAUSTED", afterNested.errorCode)
        assertFalse(afterNested.dispatched)
    }

    @Test fun resolverOwnedCatalogKeepsItsOriginalAuthorizationAndIsChargedOnce() = runTest {
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 1))
        val directory = catalog()
        var authorizationChecks = 0
        val existing = budget.bind(directory, authorize = { _, _ ->
            authorizationChecks++
            true
        })
        val tools = listOf(existing)
        val admitted = withContext(ToolBudgetScope(budget)) { appendBudgetedCatalog(tools, directory) }
        assertSame(tools, admitted)
        assertSame(existing, admitted.single())
        assertFalse(admitted.single().execute("catalog", arguments).isError)
        assertEquals(1, authorizationChecks)
        assertEquals(1, budget.usedCalls())
    }

    @Test fun authorizationDenialNeverConsumesTheParentAllowanceOrQueriesColdTools() = runTest {
        var coldAcquisitions = 0
        val directory = ProgressiveToolCatalog(listOf(source().definition), "source", acquireCold = { _, _ ->
            coldAcquisitions++
            emptyList()
        })
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 1))
        val authorizedCatalog = budget.bind(directory, authorize = { _, _ -> false })
        val admitted = withContext(ToolBudgetScope(budget)) { appendBudgetedCatalog(listOf(authorizedCatalog), directory).single() }
        val result = admitted.execute("denied-discovery", arguments)
        assertEquals("AUTH_REQUIRED", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, budget.usedCalls())
        assertEquals(0, coldAcquisitions)
    }

    @Test fun catalogueReservationSurvivesBudgetRecreationAndBlocksExternalWork() = runTest {
        var stored: JsonObject? = null
        val checkpoint = ToolCallBudgetCheckpoint("catalogue-restart", { stored }, { stored = it })
        val limits = AgentRunLimits(githubTask = true, maxToolCalls = 1)
        val initial = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(1), durableCalls = checkpoint)
        val directory = withContext(ToolBudgetScope(initial)) { appendBudgetedCatalog(emptyList(), catalog()).single() }
        val discovered = directory.execute("discovery", arguments)
        assertFalse(discovered.isError)
        assertFalse(discovered.dispatched)
        val restored = ToolExecutionBudget(limits, initialCalls = checkpoint.initialize(1), durableCalls = checkpoint)
        val denied = restored.bind(source()).execute("read", buildJsonObject {})
        assertEquals("BUDGET_EXHAUSTED", denied.errorCode)
        assertFalse(denied.dispatched)
        assertEquals(1, restored.usedCalls())
    }

    @Test fun completedGitHubReadSurvivesConcurrentResultBudgetExhaustion() = runTest {
        val bothStarted = CompletableDeferred<Unit>()
        val finishSecond = CompletableDeferred<Unit>()
        var started = 0
        val read = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read an authorized source", buildJsonObject {})

            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                if (++started == 2) bothStarted.complete(Unit)
                bothStarted.await()
                if (callId == "second") finishSecond.await()
                return AgentToolResult(
                    callId,
                    ToolResultContent.Text("x".repeat(128)),
                    false,
                    executionReceipt = buildJsonObject {
                        put("schema", "github.dispatch.v1")
                        put("phase", "OBSERVED")
                        put("parent_result_successful", true)
                        put("mutation_requests", 0)
                    }
                )
            }
        }
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolOutputBytes = 64, maxConcurrentTools = 2))
        val tool = budget.bind(read)
        val first = async { tool.execute("first", buildJsonObject {}) }
        val second = async { tool.execute("second", buildJsonObject {}) }
        assertFalse(first.await().isError)
        finishSecond.complete(Unit)
        val result = second.await()
        assertTrue(result.dispatched)
        assertTrue(result.outputBudgetExhausted)
        assertFalse(result.isError)
        assertEquals(ToolResultContent.Text("x".repeat(128)), result.retainedContent)
        assertEquals(JsonPrimitive("WITHHELD_BY_RESULT_BUDGET"), (result.content as ToolResultContent.Json).value.jsonObject["result_delivery"])
    }

    @Test fun zeroAllowanceBlocksASynthesizedDirectoryBeforeAnyDiscovery() = runTest {
        var acquired = false
        val directory = ProgressiveToolCatalog(listOf(source().definition), "source", acquireCold = { _, _ ->
            acquired = true
            emptyList()
        })
        val budget = ToolExecutionBudget(AgentRunLimits(githubTask = true, maxToolCalls = 0))
        val admitted = withContext(ToolBudgetScope(budget)) { appendBudgetedCatalog(emptyList(), directory).single() }
        val result = admitted.execute("zero", arguments)
        assertTrue(result.isError)
        assertEquals("BUDGET_EXHAUSTED", result.errorCode)
        assertFalse(acquired)
        assertEquals(0, budget.usedCalls())
    }
}
