package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReplayRecoveryTest {
    private val next = buildJsonObject {
        put("tool", "github_read__github_api")
        put("arguments", buildJsonObject { put("view_cursor", "saved-result:42") })
    }
    private val content = ToolResultContent.Json(
        buildJsonObject {
            put("schema", "github.operation.v1")
            put("operation", "github_read")
            put("data", "source code ".repeat(2000))
            put("continuation_call", next)
        }
    )

    @Test fun replayCompactionPreservesTheExactContinuationAsValidJson() {
        val call = ProviderEvent.ToolCall("read", "github_read__github_api", buildJsonObject { put("path", "A.kt") })
        val result = AgentToolResult(call.callId, content, false)
        val replay = ToolExchangeCompactor.compact(listOf(AgentToolExchange(listOf(call), listOf(result))), 1000, 256)
        val compact = (replay.single().results.single().content as ToolResultContent.Json).value as JsonObject
        assertEquals(next, compact["continuation_call"])
        assertTrue(ToolExchangeCompactor.estimateTokens(replay) <= 1000)
    }

    @Test fun suppressionReturnsThePriorContinuationWithoutRepeatingTheRead() {
        val call = ProviderEvent.ToolCall("original", "github_read__github_api", buildJsonObject { put("path", "A.kt") })
        val exchanges = listOf(AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, content, false))))
        val recovered = requireNotNull(GitHubReplayRecovery.repeated(call.copy(callId = "repeat"), exchanges, "Repeat limit"))
        assertFalse(recovered.dispatched)
        assertTrue(recovered.isError)
        assertEquals("REPEAT_SUPPRESSED", recovered.errorCode)
        assertEquals(next, (recovered.content as ToolResultContent.Json).value.let { it as JsonObject }["continuation_call"])
        assertEquals(null, GitHubReplayRecovery.repeated(call.copy(arguments = buildJsonObject { put("path", "B.kt") }), exchanges, "Repeat limit"))
    }

    @Test fun olderStructuredErrorsKeepExecutableCorrectionsIntact() {
        val error = ToolResultContent.Json(
            buildJsonObject {
                put("schema", "github.arguments.v1")
                put("message", "Wrong fields. ".repeat(80))
                put("correction_call", next)
            }
        )
        val call = ProviderEvent.ToolCall("old", "github_read", buildJsonObject {})
        val pair = AgentToolExchange(listOf(call), listOf(AgentToolResult("old", error, true)))
        val later = AgentToolExchange(listOf(call.copy(callId = "new")), listOf(AgentToolResult("new", ToolResultContent.Text("ok"), false)))
        val replay = ToolExchangeCompactor.compact(listOf(pair, later), 4000, 512)
        val retained = (replay.first().results.single().content as ToolResultContent.Json).value as JsonObject
        assertEquals(next, retained["correction_call"])
    }
}
