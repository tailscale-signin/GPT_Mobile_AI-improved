package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Exercises the runner's retry decision and the durable generation boundary together. */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class GitHubLogicalContinuationRunnerTest {
    @Test fun reasoningOnlyPartialFailureContinuesOnceFromSavedReads() = runTest {
        var reads = 0
        var requests = 0
        var waits = 0
        var checkpoint: JsonObject? = null
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                reads++
                return AgentToolResult(callId, ToolResultContent.Text("Saved repository evidence"), false)
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                requests++
                emit(ProviderEvent.ToolCall("read-once", tool.definition.name, JsonObject(emptyMap())))
                emit(ProviderEvent.Completed)
            }
        }
        val session = GitHubOutputContinuationSession(provider, recoveryRevision = "run", readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }, awaitConnectivity = {
            waits++
            true
        }) { _, retained, _ ->
            requests++
            assertEquals("read-once", retained.single().results.single().callId)
            if (requests == 2) {
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(
                        ProviderEvent.ThinkingDelta("Unpublished reasoning"),
                        interrupted()
                    )
                }
            } else {
                object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(ProviderEvent.TextDelta("Completed from saved evidence."), ProviderEvent.Completed)
                }
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()
        assertEquals(1, reads)
        assertEquals(3, requests)
        assertEquals(1, waits)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertEquals(JsonPrimitive(false), checkpoint?.get("tool_actions_replayed"))
    }

    @Test fun boundedConnectivityWaitKeepsTypedFailureAndDoesNotDispatchAnotherRequest() = runTest {
        var requests = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                requests++
                emit(interrupted())
            }
            override suspend fun awaitRecoveryReady(maxWaitMillis: Long): Boolean {
                assertEquals(30_000L, maxWaitMillis)
                delay(maxWaitMillis)
                return false
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, emptyList()).toList()
        val failure = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>().last()
        assertEquals(1, requests)
        assertEquals(31_000L, testScheduler.currentTime)
        assertEquals("CONNECTION_ABORTED", failure.errorCode)
        assertEquals("PARTIAL", failure.taskOutcome)
        assertEquals(JsonPrimitive("WAITING_FOR_CONNECTION"), failure.failureDetails?.get("recovery_state"))
        assertEquals(JsonPrimitive("req-1"), failure.failureDetails?.get("server_request_id"))
    }

    private fun interrupted() = ProviderEvent.Failed(
        "Localized provider message",
        errorCode = "CONNECTION_ABORTED",
        taskOutcome = "PARTIAL",
        failureDetails = buildJsonObject {
            put("failed_stage", "response_stream")
            put("remote_acceptance", "YES")
            put("server_request_id", "req-1")
            put("request_replay_allowed", false)
            put("logical_continuation_allowed", true)
        }
    )
}
