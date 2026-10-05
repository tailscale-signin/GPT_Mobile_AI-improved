package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerEmptyAnswerTest {
    @Test
    fun `empty completion finalizes once without repeating executed actions`() = runBlocking {
        var round = 0
        var executions = 0
        val sizes = mutableListOf<Int>()
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                sizes += tools.size
                when (round++) {
                    0 -> emit(ProviderEvent.ToolCall("action", "test", buildJsonObject {}))
                    1 -> emit(ProviderEvent.TextDelta("   "))
                    else -> {
                        assertEquals("action", exchanges.single().results.single().callId)
                        emit(ProviderEvent.TextDelta("Done."))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("test", "test", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("done"), false)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(AgentRunLimits()).run(session, listOf(tool)).collect { events += it }
        assertEquals(1, executions)
        assertEquals(listOf(1, 1, 0), sizes)
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test
    fun `repeated empty completion fails terminally instead of looping or reporting success`() = runBlocking {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(ProviderEvent.Completed)
            }
        }
        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(AgentRunLimits()).run(session, emptyList()).collect { events += it }
        assertEquals(2, rounds)
        assertEquals(1, events.count { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertTrue(events.none { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
    }
}
