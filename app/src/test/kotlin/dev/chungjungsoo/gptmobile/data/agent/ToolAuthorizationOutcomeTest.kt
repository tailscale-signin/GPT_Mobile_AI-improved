package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolAuthorizationOutcomeTest {
    @Test fun timedOutReadAllowsFreshInvocationButWriteRequiresReconciliation() = runTest {
        for ((name, expected) in listOf("github_search__github_api" to "READ_DEADLINE", "github_write__github_api" to "TIMEOUT_OUTCOME_UNKNOWN")) {
            var dispatches = 0
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition(name, "", buildJsonObject {})
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    dispatches++
                    kotlinx.coroutines.delay(1000)
                    return AgentToolResult(callId, ToolResultContent.Text("late"), false)
                }
            }
            val wrapped = ToolExecutionBudget(AgentRunLimits(toolTimeoutMillis = 10)).bind(tool)
            assertEquals(expected, wrapped.execute("first", buildJsonObject {}).errorCode)
            val retry = wrapped.execute("retry", buildJsonObject {})
            assertEquals(if (expected == "READ_DEADLINE") "READ_DEADLINE" else "RECONCILIATION_REQUIRED", retry.errorCode)
            assertEquals(if (expected == "READ_DEADLINE") 2 else 1, dispatches)
            assertEquals("DUPLICATE_INVOCATION", wrapped.execute("first", buildJsonObject {}).errorCode)
        }
    }

    @Test fun readTransportExceptionsAllowOnlyBoundedRetries() = runTest {
        var dispatches = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read__github_api", "", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                throw java.io.IOException("connection reset")
            }
        }
        val wrapped = ToolExecutionBudget(AgentRunLimits()).bind(tool)
        assertEquals("READ_FAILED", wrapped.execute("first", buildJsonObject {}).errorCode)
        assertEquals("READ_FAILED", wrapped.execute("retry", buildJsonObject {}).errorCode)
        assertEquals("REPEATED_FAILURE", wrapped.execute("third", buildJsonObject {}).errorCode)
        assertEquals(2, dispatches)
    }

    @Test fun permissionDenialStopsSameToolWithChangedArgumentsForThisTurn() = runTest {
        var authorizations = 0
        var dispatches = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("web_search", "", buildJsonObject { })
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatches++
                return AgentToolResult(callId, ToolResultContent.Text("unexpected"), false)
            }
        }
        val wrapped = ToolExecutionBudget(AgentRunLimits()).bind(tool, authorize = { _, _ ->
            authorizations++
            false
        })
        val first = wrapped.execute("first", buildJsonObject { })
        val second = wrapped.execute("second", buildJsonObject { put("query", kotlinx.serialization.json.JsonPrimitive("different")) })
        assertEquals("AUTH_REQUIRED", first.errorCode)
        assertEquals("AUTH_REQUIRED", second.errorCode)
        assertEquals(1, authorizations)
        assertEquals(0, dispatches)
    }

    @Test fun duplicateActionIsNotReportedAsPermissionDenialAndNeverExecutes() = runTest {
        var dispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "", buildJsonObject { })
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("written"), false)
            }
        }
        val wrapped = ToolExecutionBudget(AgentRunLimits()).bind(tool) { _, _ ->
            throw ToolAuthorizationRejected("DUPLICATE_ACTION", "A matching action already exists")
        }
        val result = wrapped.execute("duplicate", JsonObject(emptyMap()))
        assertEquals("DUPLICATE_ACTION", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, dispatched)
    }
}
