package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.provider.toChatMessages
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderToolReplayRunnerTest {
    @Test fun runnerExecutesCanonicalActionOnceAndReturnsTheOriginalInvocation() = runBlocking {
        for ((arguments, expected) in listOf(
            JsonObject(emptyMap()) to "workflows",
            buildJsonObject { put("action", "") } to "workflows",
            buildJsonObject { put("action", "rerun") } to "rerun_failed_jobs"
        )) {
            val original = ProviderEvent.ToolCall(
                "ci-call",
                "github_ci__fixture",
                arguments,
                originalInvocationFingerprint = "untrusted-provider-value",
                originalInvocation = ProviderToolInvocation("github_write", buildJsonObject { put("action", "write") })
            )
            var executions = 0
            val tool = object : AgentTool {
                override val definition = AgentToolDefinition("github_ci__fixture", "CI fixture", buildJsonObject { put("type", "object") })
                override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                    executions++
                    assertEquals(buildJsonObject { put("action", expected) }, arguments)
                    return AgentToolResult(callId, ToolResultContent.Text("Confirmed receipt"), false)
                }
            }
            var rounds = 0
            val provider = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    if (rounds++ == 0) {
                        emit(original)
                    } else {
                        val exchange = exchanges.single()
                        assertEquals(tool.definition.name, exchange.calls.single().name)
                        assertEquals(buildJsonObject { put("action", expected) }, exchange.calls.single().arguments)
                        val wire = exchange.toChatMessages().first().toolCalls!!.single()
                        assertEquals(original.callId, wire.id)
                        assertEquals(original.name, wire.function.name)
                        assertEquals(arguments.toString(), wire.function.arguments)
                        emit(ProviderEvent.TextDelta("Completed from the confirmed receipt."))
                    }
                    emit(ProviderEvent.Completed)
                }
            }
            val events = AgentRunner(AgentRunLimits(githubTask = true)).run(provider, listOf(tool)).toList()
            assertEquals(1, executions)
            assertEquals(2, rounds)
            assertTrue(events.none { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
            assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
        }
    }
}
