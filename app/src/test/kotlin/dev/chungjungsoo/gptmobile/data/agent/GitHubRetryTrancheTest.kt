package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubRetryTrancheTest {
    @Test fun githubDeadlinePublishesRetainedFindingsWithoutAnotherModelOrToolDispatch() = kotlinx.coroutines.test.runTest {
        var round = 0
        var fallbacks = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    emit(ProviderEvent.ToolCall("read", "github_read", buildJsonObject {}))
                    emit(ProviderEvent.Completed)
                } else {
                    kotlinx.coroutines.awaitCancellation()
                }
            }
            override fun retainedFailureResponse(failure: ProviderEvent.Failed) = flow {
                fallbacks++
                assertEquals("RUN_DEADLINE_REACHED", failure.errorCode)
                emit(ProviderEvent.TextDelta("Retained findings; remaining work is incomplete."))
                emit(failure.copy(taskOutcome = "PARTIAL"))
            }
        }
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("source"), false)
        }
        val events = mutableListOf<AgentRunEvent>()
        AgentRunner(
            AgentRunLimits(githubTask = true, runTimeoutMillis = 1000),
            replayDispatcher = StandardTestDispatcher(testScheduler)
        ).run(session, listOf(tool)).collect { events += it }
        assertEquals(1, fallbacks)
        assertEquals(2, round)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.TextDelta })
    }

    @Test fun ordinaryRetryRetainsExhaustedAllowanceAndHistoricalReceipts() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "same-authorized-task")
        val previous = ProviderEvent.ToolCall("old", "github_read", buildJsonObject { put("path", "Old.kt") })
        checkpoint.enqueue(listOf(previous))
        checkpoint.complete(previous, AgentToolResult("old", ToolResultContent.Text("Historical source"), false))
        var dispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("New source"), false)
            }
        }
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                round++
                assertTrue(tools.isEmpty())
                assertTrue(exchanges.flatMap { it.results }.any { it.callId == "old" })
                emit(ProviderEvent.TextDelta("Historical findings retained; the accepted tool allowance is exhausted."))
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner(AgentRunLimits(githubTask = true, maxToolCalls = 1), checkpoint, retryUndispatchedBudgetFailures = true).run(session, listOf(tool)).collect()
        assertEquals(0, dispatched)
        assertEquals(1, round)
        assertEquals(1, checkpoint.dispatchedCount())
    }

    @Test fun explicitAdditionalAllowancePermitsNewWorkButRetainsHistoricalReceipts() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "same-authorized-task")
        val previous = ProviderEvent.ToolCall("old", "github_read", buildJsonObject { put("path", "Old.kt") })
        checkpoint.enqueue(listOf(previous))
        checkpoint.complete(previous, AgentToolResult("old", ToolResultContent.Text("Historical source"), false))
        var dispatched = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "read", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                dispatched++
                return AgentToolResult(callId, ToolResultContent.Text("New source"), false)
            }
        }
        var round = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    assertTrue(tools.isNotEmpty())
                    emit(ProviderEvent.ToolCall("new", "github_read", buildJsonObject { put("path", "New.kt") }))
                } else {
                    emit(ProviderEvent.TextDelta("Historical and new findings retained."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        AgentRunner(AgentRunLimits(githubTask = true, maxToolCalls = 1), checkpoint, resumeToolBudget = true).run(session, listOf(tool)).collect()
        assertEquals(1, dispatched)
        assertEquals(2, checkpoint.dispatchedCount())
    }
}
