package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunnerGracefulFinalizationTest {
    @Test
    fun `batched calls stop before limit and final round has no tools`() = runBlocking {
        val calls = listOf(
            ProviderEvent.ToolCall("call-1", "test", buildJsonObject {}),
            ProviderEvent.ToolCall("call-2", "test", buildJsonObject {})
        )
        val exposedToolCounts = mutableListOf<Int>()
        val observedExchanges = mutableListOf<List<AgentToolExchange>>()
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ) = flow {
                exposedToolCounts += tools.size
                observedExchanges += exchanges.toList()
                if (round++ == 0) {
                    calls.forEach { emit(it) }
                    emit(ProviderEvent.Completed)
                } else {
                    emit(ProviderEvent.TextDelta("Completed available work; reply \"continue\" for the rest."))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("test", "test", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("ok"), false)
            }
        }

        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(AgentRunLimits(maxToolCalls = 2, finalResponseToolCallReserve = 1))
            .run(session, listOf(tool))
            .collect { events += it }

        assertEquals(1, executions)
        assertEquals(listOf(1, 0), exposedToolCounts)
        assertEquals(1, observedExchanges.last().size)
        val results = observedExchanges.last().single().results
        assertEquals(2, results.size)
        assertFalse(results.first().isError)
        assertTrue(results.last().isError)
        assertTrue((results.last().content as ToolResultContent.Text).text.contains("continue"))
        assertTrue(events.any { it is AgentRunEvent.Notice })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test
    fun `zero executable allowance requests final response without running tools`() = runBlocking {
        var executions = 0
        var exposedTools = -1
        val session = object : AgentProviderSession {
            override fun streamRound(
                tools: List<AgentToolDefinition>,
                exchanges: List<AgentToolExchange>
            ) = flow {
                exposedTools = tools.size
                emit(ProviderEvent.TextDelta("No tool work was performed."))
                emit(ProviderEvent.Completed)
            }
        }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("test", "test", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("unexpected"), false)
            }
        }
        val events = mutableListOf<AgentRunEvent>()

        AgentRunner(AgentRunLimits(maxToolCalls = 1, finalResponseToolCallReserve = 1))
            .run(session, listOf(tool))
            .collect { events += it }

        assertEquals(0, executions)
        assertEquals(0, exposedTools)
        assertTrue(events.any { it is AgentRunEvent.Notice })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }
}
