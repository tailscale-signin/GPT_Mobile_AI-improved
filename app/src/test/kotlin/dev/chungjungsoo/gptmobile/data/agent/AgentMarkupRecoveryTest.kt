package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentMarkupRecoveryTest {
    @Test fun rawToolMarkupGetsOneNativeProtocolRecoveryWithoutDispatch() = runTest {
        var rounds = 0
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                error("Unparsed markup must never execute")
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (++rounds == 1) {
                    emit(ProviderEvent.TextDelta("<tool_call>{\"name\":\"read\"}</tool_call>"))
                } else {
                    assertEquals(listOf("read"), tools.map { it.name })
                    assertTrue(tools.single().description.contains("Protocol repair"))
                    emit(ProviderEvent.TextDelta("The requested read could not be completed."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, listOf(tool)).toList()
        assertEquals(2, rounds)
        assertEquals(0, executions)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }
}
