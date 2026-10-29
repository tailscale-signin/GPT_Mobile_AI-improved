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

    @Test fun disabledToolMarkupGetsOneTextOnlyRecovery() = runTest {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                assertTrue(tools.isEmpty())
                emit(ProviderEvent.TextDelta(if (++rounds == 1) "<tool_call>{}</tool_call>" else "No action was executed."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, emptyList()).toList()
        assertEquals(2, rounds)
        assertFalse(events.any { it is AgentRunEvent.ToolStarted })
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
    }

    @Test fun persistentDisabledToolMarkupStopsAfterOneRecovery() = runTest {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(ProviderEvent.TextDelta("<tool_call>{}</tool_call>"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, emptyList()).toList()
        assertEquals(2, rounds)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertFalse(events.any { it is AgentRunEvent.ToolStarted })
    }

    @Test fun unknownLegacyNameCanBeCorrectedWithoutDisablingAuthorizedTools() = runTest {
        var rounds = 0
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read__github_api", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("source evidence"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (++rounds) {
                    1 -> emit(ProviderEvent.ToolCall("bad", "github_read_file", JsonObject(emptyMap())))
                    2 -> {
                        assertEquals("TOOL_NOT_AVAILABLE", exchanges.last().results.single().errorCode)
                        assertEquals(listOf(tool.definition.name), tools.map { it.name })
                        emit(ProviderEvent.ToolCall("good", "github_read", JsonObject(emptyMap())))
                    }
                    else -> emit(ProviderEvent.TextDelta("Read completed."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, listOf(tool)).toList()
        assertEquals(3, rounds)
        assertEquals(1, executions)
        assertEquals(listOf("github_read__github_api"), events.filterIsInstance<AgentRunEvent.ToolStarted>().map { it.call.name })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun finalizationRepairNeverRepeatsACompletedMutation() = runTest {
        var rounds = 0
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_write__github_api", "Commit", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("commit receipt"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (++rounds) {
                    1 -> emit(ProviderEvent.ToolCall("commit", tool.definition.name, JsonObject(emptyMap())))
                    2 -> {
                        assertTrue(tools.isEmpty())
                        emit(ProviderEvent.TextDelta("<tool_call>{}</tool_call>"))
                    }
                    else -> {
                        assertTrue(tools.isEmpty())
                        assertTrue(exchanges.first().results.single().content.toString().contains("commit receipt"))
                        emit(ProviderEvent.TextDelta("Commit completed."))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(AgentRunLimits(maxToolCalls = 1)).run(session, listOf(tool)).toList()
        assertEquals(3, rounds)
        assertEquals(1, executions)
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun publishedTextPreventsAnAutomaticDuplicateAnswer() = runTest {
        var rounds = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                rounds++
                emit(ProviderEvent.TextDelta("Partial answer. "))
                emit(ProviderEvent.TextDelta("<tool_call>{}</tool_call>"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, emptyList()).toList()
        assertEquals(1, rounds)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }
}
