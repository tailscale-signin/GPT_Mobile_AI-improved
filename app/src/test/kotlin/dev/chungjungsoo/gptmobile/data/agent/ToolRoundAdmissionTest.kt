package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRoundAdmissionTest {
    @Test fun parserSlotOverflowRetriesOneSmallBatchWithoutExecutingRejectedCalls() = runBlocking {
        var rounds = 0
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("lookup", "Lookup", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text("Retained source"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (rounds++) {
                    0 -> throw ToolBatchOversizeException("STREAM_TOOL_SLOTS", 33, 32)
                    1 -> {
                        assertTrue(tools.single().description.contains("at most 4 total calls"))
                        assertTrue(exchanges.isEmpty())
                        emit(ProviderEvent.ToolCall("retry", "lookup", buildJsonObject {}))
                    }
                    else -> {
                        assertEquals("retry", exchanges.single().calls.single().callId)
                        emit(ProviderEvent.TextDelta("Evidence answer"))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, listOf(tool)).toList()
        assertEquals(3, rounds)
        assertEquals(1, executions)
        assertTrue(events.any { it == AgentRunEvent.Provider(ProviderEvent.Completed) })
    }

    @Test fun repeatedParserOverflowFallsBackOnceAndNeverDispatchesPartialBatches() = runBlocking {
        var rounds = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("lookup", "Lookup", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult = error("Rejected calls must not execute")
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (rounds++ < 2) throw ToolBatchOversizeException("STREAM_TOOL_SLOTS", 33, 32)
                assertTrue(tools.isEmpty())
                emit(ProviderEvent.TextDelta("Unable to obtain new evidence"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, listOf(tool)).toList()
        assertEquals(3, rounds)
        assertTrue(events.none { it is AgentRunEvent.ToolFinished })
        assertEquals(1, events.filterIsInstance<AgentRunEvent.Notice>().count { "smaller batch" in it.message })
    }

    @org.junit.Test fun excerptFooterKeepsOnlyCompleteSourceReferences() {
        val url = "https://example.com/" + "a".repeat(500)
        val input = "x".repeat(1000) + "\n$url\nhttps://example.com/complete"
        val result = ToolObservationRetention.content(ToolResultContent.Text(input), 256) as ToolResultContent.Text
        assertTrue(result.text.toByteArray().size <= 256)
        assertTrue(result.text.contains("https://example.com/complete"))
        assertTrue(!result.text.contains("https://example.com/aaa"))
    }

    @Test fun stormCancelsStreamBeforeMaterializationAndPreservesSuccessfulEvidence() = runBlocking {
        var emitted = 0
        var executed = 0
        var round = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("web_search", "Search", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: kotlinx.serialization.json.JsonObject): AgentToolResult {
                executed++
                return AgentToolResult(callId, ToolResultContent.Text("Useful source https://example.org/evidence"), false)
            }
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                when (round++) {
                    0 -> emit(ProviderEvent.ToolCall("good", "web_search", buildJsonObject {}))
                    1 -> repeat(3000) {
                        emitted++
                        emit(ProviderEvent.ToolCall("storm-$it", "web_search", buildJsonObject {}))
                    }
                    else -> {
                        assertTrue(tools.isEmpty())
                        assertEquals(listOf("good"), exchanges.flatMap { it.calls }.map { it.callId })
                        assertTrue(exchanges.single().results.single().content.toString().contains("https://example.org/evidence"))
                        emit(ProviderEvent.TextDelta("Saved evidence answer"))
                    }
                }
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner().run(session, listOf(tool)).toList()
        assertEquals(1, executed)
        assertTrue(emitted <= 33)
        assertEquals(1, events.filterIsInstance<AgentRunEvent.ToolFinished>().size)
        assertEquals(1, events.filterIsInstance<AgentRunEvent.Notice>().count { "oversized" in it.message })
        assertTrue(events.any { it == AgentRunEvent.Provider(ProviderEvent.Completed) })
    }

    @Test fun eightValidCallsFitAndDuplicatesAndHugeArgumentsDoNot() {
        val admission = ToolRoundAdmission()
        repeat(8) { assertTrue(admission.admit(ProviderEvent.ToolCall("c$it", "search", buildJsonObject {}))) }
        assertFalse(admission.admit(ProviderEvent.ToolCall("c0", "read", buildJsonObject {})))
        assertFalse(ToolRoundAdmission().admit(ProviderEvent.ToolCall("large", "search", buildJsonObject { put("query", "界".repeat(20_000)) })))
        assertFalse(ToolRoundAdmission().admit(ProviderEvent.ToolCall("x".repeat(257), "search", buildJsonObject {})))
    }

    @Test fun infiniteConfiguredExecutionStillHasFiniteObservedLimit() {
        val admission = ToolRoundAdmission(Int.MAX_VALUE, Int.MAX_VALUE, Int.MAX_VALUE)
        var admitted = 0
        repeat(3000) { if (admission.admit(ProviderEvent.ToolCall("c$it", "tool$it", buildJsonObject {}))) admitted++ }
        assertEquals(64, admitted)
    }

    @Test fun recoveryWrapperCannotBufferTheStormBeforeTheRunner() = runBlocking {
        var emitted = 0
        val source = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                repeat(3000) {
                    emitted++
                    emit(ProviderEvent.ToolCall("c$it", "web_search", buildJsonObject {}))
                }
            }
        }
        val wrapper = OutputLimitRecoverySession(source) { _, _ -> error("No continuation") }
        assertTrue(runCatching { wrapper.streamRound(emptyList(), emptyList()).toList() }.exceptionOrNull() is ToolBatchOversizeException)
        assertTrue(emitted <= 33)
    }
}
