package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLiveVerificationTest {
    @Test fun freshReadRetainsBackendAuthorizationAndBudgetRejections() = runTest {
        val received = mutableListOf<JsonObject>()
        var rejection = "PERSONAL_DATA_LOCAL_ONLY"
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "GitHub", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                received += arguments
                return AgentToolResult(callId, ToolResultContent.Text(rejection), true, errorCode = rejection, dispatched = false)
            }
        }
        val tool = GitHubOperationTool("github_context", backend, "scope")
        val request = buildJsonObject {
            put("action", "branches")
            put("verify_live", true)
        }
        val privacy = tool.execute("privacy", request)
        assertEquals("PERSONAL_DATA_LOCAL_ONLY", privacy.errorCode)
        assertFalse(privacy.dispatched)
        assertEquals(JsonPrimitive("list_branches"), received.single()["action"])
        assertEquals(JsonPrimitive(true), received.single()["verify_live"])
        rejection = "BUDGET_EXHAUSTED"
        val budget = tool.execute("budget", request)
        assertEquals("BUDGET_EXHAUSTED", budget.errorCode)
        assertFalse(budget.dispatched)
        assertEquals(2, received.size)
    }

    @Test fun liveVerificationNeverAuthorizesMutation() = runTest {
        var dispatched = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "GitHub", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("accepted"), false)
            }
        }
        val tool = GitHubOperationTool("github_pr", backend, "scope")
        val request = buildJsonObject {
            put("action", "comment")
            put("pull_number", 7)
            put("body", "fixture")
            put("verify_live", true)
        }
        val result = tool.execute("write", request)
        assertEquals("INVALID_ARGUMENTS", result.errorCode)
        assertFalse(result.dispatched)
        assertEquals(0, dispatched)
    }

    @Test fun durableContinuationRecipesDoNotExpandModelEnvelope() {
        val recipes = JsonArray(List(100) { index -> buildJsonObject { put("arguments", "exact-private-recipe-$index".repeat(20)) } })
        val result = AgentToolResult(
            "read",
            ToolResultContent.Json(buildJsonObject { put("summary", "bounded display") }),
            false,
            retainedContent = ToolResultContent.Text("full source"),
            executionReceipt = buildJsonObject {
                put("read_requests", 1)
                put("retained_original_complete", true)
                put("retained_remote_continuations", recipes)
            }
        )
        val envelope = Json.parseToJsonElement(ToolResultEnvelope.encode("github_context", result)).jsonObject
        val receipt = envelope.getValue("execution").jsonObject.getValue("receipt").jsonObject
        assertFalse(receipt.containsKey("retained_remote_continuations"))
        assertEquals(JsonPrimitive(1), receipt["read_requests"])
        assertEquals(JsonPrimitive(true), receipt["retained_original_complete"])
        assertEquals(recipes, result.executionReceipt?.get("retained_remote_continuations"))
        assertTrue(envelope.toString().length < 2048)
    }
}
