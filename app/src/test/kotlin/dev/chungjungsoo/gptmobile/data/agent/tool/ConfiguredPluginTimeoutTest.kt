package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ApprovedReadOnlyAgentTool
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolReplaySafety
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredPluginTimeoutTest {
    private val settings = PluginExecutionSettings(timeoutSeconds = 5)
    private fun arguments(action: String) = JsonObject(mapOf("action" to JsonPrimitive(action)))

    @Test fun nativeReadDeadlineAllowsOneFreshReadWithoutReconciliation() = runTest {
        for ((name, action) in listOf("github_context" to "status", "repository_graph" to "status")) {
            var executions = 0
            val delegate = object : AgentTool {
                override val definition = AgentToolDefinition(name, "Read selected repository", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    if (++executions == 1) delay(10_000)
                    return AgentToolResult(callId, ToolResultContent.Text("observed"), false)
                }
            }
            val bounded = ToolExecutionBudget(AgentRunLimits()).bind(ConfiguredPluginTool(delegate, settings))
            val first = bounded.execute("first", arguments(action))
            assertEquals("READ_DEADLINE", first.errorCode)
            assertEquals("FAILED", ToolResultEnvelope.executionState(first))
            assertTrue(first.dispatched)
            val retried = bounded.execute("fresh", arguments(action))
            assertFalse(retried.isError)
            assertEquals(2, executions)
        }
    }

    @Test fun configuredWrapperRetainsOnlyLocallyApprovedRemoteReadClassification() = runTest {
        var executions = 0
        val delegate = object : AgentTool, ApprovedReadOnlyAgentTool {
            override val approvedReadOnly = true
            override val definition = AgentToolDefinition("connected_query", "Approved remote query", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                if (++executions == 1) delay(10_000)
                return AgentToolResult(callId, ToolResultContent.Text("observed"), false)
            }
        }
        val configured = ConfiguredPluginTool(delegate, settings)
        assertTrue(ToolReplaySafety.isReplayableRead(configured.definition.name, JsonObject(emptyMap()), configured))
        val bounded = ToolExecutionBudget(AgentRunLimits()).bind(configured)
        assertEquals("READ_DEADLINE", bounded.execute("first", JsonObject(emptyMap())).errorCode)
        assertFalse(bounded.execute("fresh", JsonObject(emptyMap())).isError)
        assertEquals(2, executions)
    }

    @Test fun graphMutationsAndUnapprovedRemoteToolsKeepUnknownOutcomeFence() = runTest {
        for ((name, action) in listOf("repository_graph" to "index", "repository_graph" to "note", "connected_query" to "read")) {
            var executions = 0
            val delegate = object : AgentTool {
                override val definition = AgentToolDefinition(name, "Advertised as a safe read", JsonObject(emptyMap()))
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    executions++
                    delay(10_000)
                    return AgentToolResult(callId, ToolResultContent.Text("unobserved"), false)
                }
            }
            val bounded = ToolExecutionBudget(AgentRunLimits()).bind(ConfiguredPluginTool(delegate, settings))
            val first = bounded.execute("first", arguments(action))
            assertEquals("TIMEOUT_OUTCOME_UNKNOWN", first.errorCode)
            assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(first))
            assertEquals("RECONCILIATION_REQUIRED", bounded.execute("repeat", arguments(action)).errorCode)
            assertEquals(1, executions)
        }
    }

    @Test fun readApprovalCannotTurnIndexPreparationIntoAReplayableRead() = runTest {
        val delegate = object : AgentTool, ApprovedReadOnlyAgentTool {
            override val approvedReadOnly = true
            override val definition = AgentToolDefinition("repository_graph", "Graph", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                delay(10_000)
                return AgentToolResult(callId, ToolResultContent.Text("unobserved"), false)
            }
        }
        val configured = ConfiguredPluginTool(delegate, settings)
        assertFalse(ToolReplaySafety.isReplayableRead(configured.definition.name, arguments("index"), configured))
        assertEquals("TIMEOUT_OUTCOME_UNKNOWN", configured.execute("index", arguments("index")).errorCode)
    }
}
