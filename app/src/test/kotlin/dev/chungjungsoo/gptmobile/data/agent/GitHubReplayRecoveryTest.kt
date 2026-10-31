package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubResultViews
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReplayRecoveryTest {
    @Test fun recoveryCursorsKeepTheirScopeOperationAndByteCeiling() = runTest {
        val views = GitHubResultViews()
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) =
                AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("body", "retained ".repeat(400)) }), false)
        }
        val owner = GitHubOperationTool("github_release", backend, "owner", views = views)
        val first = owner.execute("first", buildJsonObject { put("action", "list") })
        val recovery = (first.content as ToolResultContent.Json).value.jsonObject["recovery_call"]!!.jsonObject["arguments"]!!.jsonObject
        val foreign = GitHubOperationTool("github_release", backend, "other-account", views = views).execute("foreign", recovery)
        assertEquals("SAVED_VIEW_UNAVAILABLE", foreign.errorCode)
        assertFalse(foreign.dispatched)
        val wrongOperation = GitHubOperationTool("github_ci", backend, "owner", views = views).execute("wrong-operation", recovery)
        assertEquals("SAVED_VIEW_UNAVAILABLE", wrongOperation.errorCode)
        val cursor = recovery["view_cursor"]!!.jsonPrimitive.content.substringBeforeLast(':')
        for (suffix in listOf(":0", ":999999", ":1536:extra")) {
            val result = owner.execute("invalid-$suffix", buildJsonObject { put("view_cursor", cursor + suffix) })
            assertEquals("SAVED_VIEW_UNAVAILABLE", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertFalse(owner.execute("authorized", recovery).isError)
    }

    @Test fun aCompactedMutationReceiptIsReadWithoutRepeatingTheMutation() = runTest {
        var mutations = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                mutations++
                return AgentToolResult(
                    callId,
                    ToolResultContent.Json(
                        buildJsonObject {
                            put("sha", "b".repeat(40))
                            put("details", "Accepted commit details ".repeat(600))
                        }
                    ),
                    false
                )
            }
        }
        val tool = GitHubOperationTool("github_commit", backend, "receipt-recovery", views = GitHubResultViews())
        val input = buildJsonObject {
            put("action", "commit")
            put("stage_id", "stage")
            put("branch", "work")
            put("expected_head_sha", "a".repeat(40))
            put("message", "Apply staged fix")
        }
        val first = tool.execute("commit", input)
        val compact = (GitHubReplayRecovery.compact(first.content, 1536) as ToolResultContent.Json).value.jsonObject
        val saved = compact["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
        assertTrue(GitHubAuthorizationBoundary.isReplayableRead("github_commit", saved))
        val recovered = tool.execute("inspect-receipt", saved)
        assertFalse(recovered.isError)
        assertFalse(recovered.dispatched)
        assertEquals(1, mutations)
    }

    @Test fun aCompactedFreshPageIsRecoveredFromItsStartWithoutAnotherBackendCall() = runTest {
        val original = (0..199).joinToString("\n") { "$it:hé🙂" }
        var backendCalls = 0
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                backendCalls++
                return AgentToolResult(callId, ToolResultContent.Json(buildJsonObject { put("body", original) }), false)
            }
        }
        val tool = GitHubOperationTool("github_release", backend, "recover-current-page", views = GitHubResultViews())
        val input = buildJsonObject { put("action", "list") }
        val first = tool.execute("original", input)
        assertTrue((first.content as ToolResultContent.Json).value.jsonObject.containsKey("data"))
        val initialCall = ProviderEvent.ToolCall("original", "github_release", input)
        val firstReplay = ToolExchangeCompactor.compact(listOf(AgentToolExchange(listOf(initialCall), listOf(first))), 1000, 512)
        val compact = (firstReplay.single().results.single().content as ToolResultContent.Json).value.jsonObject
        assertEquals(JsonPrimitive(true), compact["recovery_required"])
        assertEquals(JsonPrimitive(false), compact["local_page_complete"])
        assertFalse(compact.containsKey("remote_continuations"))
        var next = compact["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
        assertTrue(next["view_cursor"]!!.jsonPrimitive.content.contains(":0:"))
        val pieces = sortedMapOf<Int, String>()
        val history = (0..79).map { index ->
            val call = ProviderEvent.ToolCall("old-$index", "github_read", buildJsonObject { put("evidence_id", "old-evidence-$index") })
            AgentToolExchange(listOf(call), listOf(AgentToolResult(call.callId, ToolResultContent.Text("Earlier result"), false)))
        }.toMutableList()
        var pages = 0
        while (true) {
            assertTrue(GitHubAuthorizationBoundary.isReplayableRead("github_release", next))
            val call = ProviderEvent.ToolCall("recover-${pages++}", "github_release", next)
            val recovered = tool.execute(call.callId, call.arguments)
            assertFalse(recovered.isError)
            assertFalse(recovered.dispatched)
            history += AgentToolExchange(listOf(call), listOf(recovered))
            val replay = ToolExchangeCompactor.compact(history, 4000, 512)
            assertTrue(ToolExchangeCompactor.estimateTokens(replay) <= 4000)
            val delivered = (replay.last().results.last().content as ToolResultContent.Json).value.jsonObject
            assertFalse(delivered.containsKey("replay_compacted"))
            for (row in delivered["fragments"] as JsonArray) {
                val fragment = row.jsonObject
                if (fragment["pointer"]?.jsonPrimitive?.content == "/body") {
                    val offset = fragment["offset"]?.jsonPrimitive?.intOrNull ?: 0
                    assertEquals(null, pieces.put(offset, fragment["value"]!!.jsonPrimitive.content))
                }
            }
            val continuation = delivered["continuation_call"] as? JsonObject ?: break
            next = continuation["arguments"]!!.jsonObject
            assertTrue(pages < 80)
        }
        assertEquals(original, pieces.values.joinToString(""))
        assertEquals(1, backendCalls)
    }

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
        assertFalse(recovered.isError)
        assertTrue(recovered.sharedResult)
        assertEquals(null, recovered.errorCode)
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
