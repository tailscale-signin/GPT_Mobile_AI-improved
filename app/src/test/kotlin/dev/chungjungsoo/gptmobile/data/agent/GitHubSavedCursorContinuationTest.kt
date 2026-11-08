package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSavedCursorContinuationTest {
    private fun tool(visible: Boolean = true) = AgentToolDefinition("github_ci", "CI reads", buildJsonObject { put("type", "object") }, modelVisible = visible)
    private fun recipe(name: String = "github_ci", cursor: String = "retained:0:1536") = buildJsonObject {
        put("tool", name)
        put("arguments", buildJsonObject { put("view_cursor", cursor) })
    }
    private fun exchange(
        id: String = "repeat",
        content: JsonObject = buildJsonObject {
            put("schema", "github.repeat.v1")
            put("recovery_call", recipe())
        }
    ): AgentToolExchange {
        val call = ProviderEvent.ToolCall(id, "github_ci", buildJsonObject { put("action", "runs") })
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(id, ToolResultContent.Json(content), false, dispatched = false, sharedResult = true)))
    }

    @Test fun suppressedReadConsumesTheSavedFullResultWithoutRepeatingRemoteArguments() {
        val next = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange()), listOf(tool())))
        assertEquals("github_ci", next.name)
        assertEquals(setOf("view_cursor"), next.arguments.keys)
        assertEquals(recipe().getValue("arguments"), next.arguments)
        val restored = AgentToolExchange(listOf(next), listOf(AgentToolResult(next.callId, ToolResultContent.Json(buildJsonObject { put("schema", "github.result.v1") }), false, dispatched = false)))
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange(), restored), listOf(tool())))
    }

    @Test fun nextLocalPageIsConsumedOnceAndOnlyFromTheIssuingTool() {
        val first = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange()), listOf(tool())))
        val page = buildJsonObject {
            put("schema", "github.result.v1")
            put("recovery_call", recipe())
            put("continuation_call", recipe(cursor = "retained:1536:1536"))
        }
        val completed = AgentToolExchange(listOf(first), listOf(AgentToolResult(first.callId, ToolResultContent.Json(page), false, dispatched = false)))
        val second = requireNotNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange(), completed), listOf(tool())))
        assertEquals(recipe(cursor = "retained:1536:1536").getValue("arguments"), second.arguments)
        val foreign = exchange(
            content = buildJsonObject {
                put("schema", "github.repeat.v1")
                put("recovery_call", recipe("github_write"))
            }
        )
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(foreign), listOf(tool())))
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(exchange()), listOf(tool(visible = false))))
    }

    @Test fun remotePaginationAndMutationRecipesAreNeverAutoDispatched() {
        val remote = exchange(
            content = buildJsonObject {
                put("schema", "github.repeat.v1")
                put(
                    "continuation_call",
                    buildJsonObject {
                        put("tool", "github_ci")
                        put(
                            "arguments",
                            buildJsonObject {
                                put("action", "runs")
                                put("page", 2)
                            }
                        )
                    }
                )
            }
        )
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(remote), listOf(tool())))
        val altered = exchange(
            content = buildJsonObject {
                put("schema", "github.repeat.v1")
                put(
                    "recovery_call",
                    buildJsonObject {
                        put("tool", "github_ci")
                        put(
                            "arguments",
                            buildJsonObject {
                                put("view_cursor", "saved:0:1536")
                                put("action", "rerun")
                            }
                        )
                    }
                )
            }
        )
        assertNull(GitHubReplayRecovery.nextSavedRead(listOf(altered), listOf(tool())))
    }
}
