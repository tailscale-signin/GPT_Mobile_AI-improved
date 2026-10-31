package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CombinedToolAlignmentTest {
    private fun search(name: String, onCall: () -> Unit) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Search", buildJsonObject { put("type", "object") }, capabilities = setOf(AgentToolCapability.WEB_SEARCH))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            onCall()
            return AgentToolResult(callId, ToolResultContent.Text("Evidence"), false)
        }
    }

    @Test fun genericSearchUsesOnlyTheSingleAuthorizedCompatibleTool() = runBlocking {
        var calls = 0
        var round = 0
        val tool = search("mcp__usearch__web_search") { calls++ }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    assertEquals(listOf(tool.definition.name), tools.map { it.name })
                    emit(ProviderEvent.ToolCall("search", "web_search", buildJsonObject { put("query", "food terminology") }))
                } else {
                    emit(ProviderEvent.TextDelta("Finished"))
                }
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner().run(session, listOf(tool)).toList()
        assertEquals(1, calls)
    }

    @Test fun ambiguousSearchAliasNeverSelectsAnArbitraryConnection() = runBlocking {
        var calls = 0
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    emit(ProviderEvent.ToolCall("search", "web_search", buildJsonObject { put("query", "food terminology") }))
                } else {
                    emit(ProviderEvent.TextDelta("Unavailable"))
                }
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner().run(session, listOf(search("mcp__a__web_search") { calls++ }, search("mcp__b__web_search") { calls++ })).toList()
        assertEquals(0, calls)
    }

    @Test fun workAllowanceDenialFinishesWithoutBlamingMaximumCalls() = runBlocking {
        var round = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("inspect", "Inspect", buildJsonObject { put("type", "object") })
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(
                callId,
                ToolResultContent.Text("Optional Combined work allowance reached: remaining=0 tool operations"),
                true,
                errorCode = "BUDGET_EXHAUSTED",
                dispatched = false,
                combinedWorkBudgetExhausted = true
            )
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    emit(ProviderEvent.ToolCall("inspect", "inspect", JsonObject(emptyMap())))
                } else {
                    assertTrue(tools.isEmpty())
                    emit(ProviderEvent.TextDelta("Answer from retained evidence"))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(maxToolCalls = Int.MAX_VALUE)).run(session, listOf(tool)).toList()
        val notices = events.filterIsInstance<AgentRunEvent.Notice>().joinToString { it.message }
        assertTrue(notices.contains("remaining=0"))
        assertFalse(notices.contains("Maximum Tool Calls"))
        assertFalse(notices.contains("2147483647"))
    }
}
