package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReadRoundProgressTest {
    @Test fun repeatedBranchPageDoesNotResetTheUnproductiveSourceReadCounter() {
        fun branches(id: Int, name: String = "main"): AgentToolExchange {
            val call = ProviderEvent.ToolCall("branch-$id", "github_context", Json.parseToJsonElement("""{"action":"branches","page":1}""") as JsonObject)
            val result = Json.parseToJsonElement("""{"branches":[{"name":"$name","commit":{"sha":"head"}}],"has_more":true}""")
            return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(result), false)))
        }
        val guard = GitHubReadRoundProgress()
        assertFalse(guard.replan(branches(1), 1))
        assertFalse(guard.replan(range(1, 1, 20), 1))
        assertFalse(guard.replan(branches(2), 1))
        assertFalse(guard.replan(range(2, 1, 20), 1))
        assertTrue(guard.replan(branches(3), 1))
        assertFalse(guard.replan(branches(4, "feature/new"), 1))
    }

    private fun exchange(id: Int, fields: String, tool: String = "github_read"): AgentToolExchange {
        val call = ProviderEvent.ToolCall("call-$id", tool, JsonObject(emptyMap()))
        val result = Json.parseToJsonElement("""{"commit_sha":"revision",$fields}""")
        return AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Json(result), false)))
    }

    private fun range(id: Int, start: Int, end: Int) =
        exchange(id, """"items":[{"path":"src/A.kt","sha":"blob","start_line":$start,"end_line":$end,"range_complete":true}]""")

    @Test fun overlappingLineRequestsCannotDisguiseThreeUnproductiveRounds() {
        val guard = GitHubReadRoundProgress()
        assertFalse(guard.replan(range(1, 1, 20), 1))
        assertFalse(guard.replan(range(2, 20, 40), 1))
        assertFalse(guard.replan(range(3, 10, 35), 1))
        assertFalse(guard.replan(range(3, 10, 35), 1))
        assertFalse(guard.replan(range(4, 2, 8), 1))
        assertTrue(guard.replan(range(5, 5, 25), 1))
        assertFalse(guard.replan(range(6, 41, 60), 1))
    }

    @Test fun scanningNewPagesAndWaitingForCiDoNotTriggerAReplan() {
        val guard = GitHubReadRoundProgress()
        repeat(7) { i ->
            val page = """"search_id":"fixed","interval_start":${i * 10},"interval_end":${(i + 1) * 10},"items":[]"""
            assertFalse(guard.replan(exchange(i, page, "github_search"), 100))
        }
        assertFalse(guard.replan(exchange(8, """"status":"waiting"""", "github_ci"), 120_000))
    }
}
