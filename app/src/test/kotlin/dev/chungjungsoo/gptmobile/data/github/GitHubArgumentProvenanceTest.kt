package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.runBlocking
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

class GitHubArgumentProvenanceTest {
    @Test fun originalAndResolvedArgumentsSurviveTheFacadeAndResultProjection() = runBlocking {
        var sent: JsonObject? = null
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "Test backend", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                sent = arguments
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("workflows", JsonArray(emptyList()))
                            put("has_more", false)
                        }
                    ),
                    false
                )
            }
        }
        val facade = GitHubOperationTool("github_ci", backend, "task-provenance", "github_ci__github_api")
        val args = buildJsonObject {
            put("action", "workflows")
            put("page", 2)
            put("owner", "owner")
            put("repo", "repository")
        }
        val result = facade.execute("observed-call", args)
        assertFalse(result.isError)
        assertEquals(JsonPrimitive("list_workflows"), sent?.get("action"))
        assertEquals(args, result.executionReceipt?.get("original_arguments"))
        assertEquals(sent, result.executionReceipt?.get("resolved_arguments"))
        assertEquals(JsonPrimitive("github_ci__github_api"), result.executionReceipt?.get("model_tool_name"))
        assertEquals(JsonPrimitive("observed-call"), result.executionReceipt?.get("tool_trace")?.jsonObject?.get("call_id"))
    }

    @Test fun actionValidationRejectsMissingIssueAndPermitsOneCorrectedNewCall() = runBlocking {
        val calls = mutableListOf<String>()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "Test backend", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls += callId
                assertEquals(JsonPrimitive(42), arguments["issue_number"])
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("number", 42)
                            put("state", "closed")
                        }
                    ),
                    false
                )
            }
        }
        val facade = GitHubOperationTool("github_issue", backend, "task-issue")
        val missing = buildJsonObject {
            put("action", "update")
            put("state", "closed")
            put("expected_updated_at", "2026-11-07T10:00:00Z")
        }
        val first = facade.execute("bad", missing)
        assertEquals("INVALID_ARGUMENTS", first.errorCode)
        assertFalse(first.dispatched)
        val repeated = facade.execute("repeated", missing)
        assertTrue(repeated.sharedResult)
        assertTrue(calls.isEmpty())
        val corrected = facade.execute("corrected", JsonObject(missing + ("issue_number" to JsonPrimitive(42))))
        assertFalse(corrected.content.toString(), corrected.isError)
        assertEquals(listOf("corrected"), calls)
        assertEquals(JsonPrimitive(42), corrected.executionReceipt?.get("original_arguments")?.jsonObject?.get("issue_number"))
    }

    @Test fun secretContentAndOversizedKeysCannotEscapeTheProjectionBound() {
        val args = buildJsonObject {
            put("action", "commit")
            put("token", "ghp_1234567890secret")
            put("body", "private repository content")
            put(
                "files",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("path", "app.kt")
                            put("content", "secret source")
                        }
                    )
                )
            )
            put("x".repeat(100_000), "large key")
        }
        val projected = GitHubArgumentProvenance.sanitize(args)
        assertEquals(JsonPrimitive("commit"), projected["action"])
        assertEquals(JsonPrimitive("[redacted]"), projected["token"])
        assertEquals(JsonPrimitive(true), projected["_projection_incomplete"])
        assertTrue(projected.toString().encodeToByteArray().size <= 8 * 1024)
        for (hidden in listOf("private repository content", "secret source", "ghp_1234567890secret", "large key")) assertFalse(projected.toString().contains(hidden))
    }
}
