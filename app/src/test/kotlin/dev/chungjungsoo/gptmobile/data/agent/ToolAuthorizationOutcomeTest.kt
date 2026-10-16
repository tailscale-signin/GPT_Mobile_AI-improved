package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ToolAuthorizationOutcomeTest {
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
