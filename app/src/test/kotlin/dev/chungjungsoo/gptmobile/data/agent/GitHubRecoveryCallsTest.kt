package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubRecoveryCallsTest {
    private fun tool(name: String) = object : AgentTool {
        override val definition = GitHubOperationTool.definition(name, name)
        override suspend fun execute(callId: String, arguments: JsonObject) = error("Routing must not execute tools")
    }

    @Test fun exactRetainedReadEnvelopeIsUnwrappedAndRoutedThroughAuthorizedTool() {
        val recipe = buildJsonObject {
            put("tool", "github_search")
            put("arguments", buildJsonObject { put("view_cursor", "issued:4") })
        }
        val result = AgentToolResult(
            "prior",
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.arguments.v1")
                    put("correction_call", recipe)
                }
            ),
            true,
            dispatched = false
        )
        val exchanges = listOf(AgentToolExchange(listOf(ProviderEvent.ToolCall("prior", "github_read", buildJsonObject {})), listOf(result)))
        val call = ProviderEvent.ToolCall("next", "github_read", buildJsonObject { put("correction_call", recipe) })
        val routed = GitHubRecoveryCalls.resolve(call, exchanges, mapOf("github_search" to tool("github_search")))
        assertEquals("github_search", routed.name)
        assertEquals(recipe["arguments"], routed.arguments)
        assertEquals("next", routed.callId)
        assertEquals(call, GitHubRecoveryCalls.resolve(call, emptyList(), mapOf("github_search" to tool("github_search"))))
        assertEquals(call, GitHubRecoveryCalls.resolve(call, exchanges, emptyMap()))
    }

    @Test fun retainedMutationRecipeIsNeverAutomaticallyExecutedOrUnwrapped() {
        val recipe = buildJsonObject {
            put("tool", "github_commit")
            put("arguments", buildJsonObject { put("message", "change") })
        }
        val result = AgentToolResult(
            "prior",
            ToolResultContent.Json(
                buildJsonObject {
                    put("schema", "github.operation.v1")
                    put("recovery_call", recipe)
                }
            ),
            false
        )
        val call = ProviderEvent.ToolCall("next", "github_read", buildJsonObject { put("recovery_call", recipe) })
        val exchanges = listOf(AgentToolExchange(listOf(call.copy(callId = "prior")), listOf(result)))
        assertEquals(call, GitHubRecoveryCalls.resolve(call, exchanges, mapOf("github_commit" to tool("github_commit"))))
    }
}
