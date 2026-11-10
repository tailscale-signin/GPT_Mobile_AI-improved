package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
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
    @Test fun expiredTaskDeliversSavedResultsWithoutRestoringQueuedWritesOrFiveSecondTimeout() = runTest {
        val checkpoint = ToolBatchCheckpoint(null, "expired-task")
        checkpoint.enqueue(listOf(ProviderEvent.ToolCall("queued-write", "github_write", JsonObject(emptyMap()))))
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_write", "Publish the accepted patch", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("An expired task cannot dispatch a queued write")
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("An expired task cannot open another round")
            override fun retainedFailureResponse(failure: ProviderEvent.Failed) = flow {
                assertEquals(JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED"), failure.failureDetails?.get("stop_reason"))
                delay(6_000L)
                emit(ProviderEvent.TextDelta("Saved results are retained."))
                emit(failure.copy(transportComplete = true))
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, runTimeoutMillis = 60_000),
            checkpoint = checkpoint,
            onToolResultsRestored = { error("No checkpoint work should be restored") },
            remainingTaskMillis = { 0L }
        ).run(session, listOf(tool)).toList()
        assertEquals(6_000L, testScheduler.currentTime)
        assertTrue(events.none { it is AgentRunEvent.ToolStarted || it is AgentRunEvent.ToolFinished || it is AgentRunEvent.ToolResultsRestored })
        val providerEvents = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }
        assertEquals(listOf("Saved results are retained."), providerEvents.filterIsInstance<ProviderEvent.TextDelta>().map { it.text })
        assertEquals("GITHUB_TASK_INCOMPLETE", providerEvents.filterIsInstance<ProviderEvent.Failed>().single().errorCode)
        val recovery = checkpoint.recoverySnapshot()
        assertEquals(1, recovery.queuedCount)
        assertEquals(0, recovery.dispatchedCount)
    }

    @Test fun expiredTaskReportDeliveryStillHonorsUserCancellation() = runTest {
        val started = CompletableDeferred<Unit>()
        val events = mutableListOf<AgentRunEvent>()
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = error("No work may start")
            override fun retainedFailureResponse(failure: ProviderEvent.Failed) = flow<ProviderEvent> {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val job = launch {
            AgentRunner(AgentRunLimits(githubTask = true, runTimeoutMillis = 0)).run(session, emptyList()).collect { events += it }
        }
        started.await()
        job.cancelAndJoin()
        assertTrue(events.isEmpty())
    }

    @Test fun bulkRestorationDoesNotEmitFreshResultsOrSpendRemainingBudget() = runTest {
        val checkpoint = ToolBatchCheckpoint(null, "same-task")
        repeat(75) { index ->
            val call = ProviderEvent.ToolCall("saved-$index", "github_read", buildJsonObject { put("path", "file-$index") })
            checkpoint.enqueue(listOf(call))
            checkpoint.complete(call, AgentToolResult(call.callId, ToolResultContent.Text("Saved source"), false))
        }
        var restores = 0
        var fresh = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("github_read", "Read", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Must not replay")
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.TextDelta("Saved evidence restored"))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(
            AgentRunLimits(githubTask = true, maxToolCalls = 256),
            checkpoint = checkpoint,
            initialToolCallCountOverride = 76,
            onToolResult = { _, _ -> fresh++ },
            onToolResultCommitted = { _, _ -> fresh++ },
            onToolResultsRestored = {
                restores++
                assertEquals(75, it.size)
            }
        ).run(session, listOf(tool)).toList()
        assertEquals(1, restores)
        assertEquals(0, fresh)
        assertEquals(listOf(75), events.filterIsInstance<AgentRunEvent.ToolResultsRestored>().map { it.count })
        assertTrue(events.none { it is AgentRunEvent.ToolFinished || it is AgentRunEvent.ToolStarted })
    }

    @Test fun reasoningOnlyPartialFailureContinuesOnceFromSavedReads() = runTest {
        var reads = 0
        var requests = 0
        var waits = 0
        var reopens = 0
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
                if (requests == 1) {
                    assertTrue(exchanges.isEmpty())
                    emit(ProviderEvent.ToolCall("read-once", tool.definition.name, JsonObject(emptyMap())))
                    emit(ProviderEvent.Completed)
                } else {
                    // An ordinary post-tool round still belongs to this provider.
                    // Its reasoning-only interruption creates the recovery boundary.
                    assertEquals(2, requests)
                    assertEquals(1, reads)
                    assertSavedRead(exchanges)
                    emit(ProviderEvent.ThinkingDelta("Unpublished reasoning"))
                    emit(interrupted())
                }
            }
        }
        val session = GitHubOutputContinuationSession(provider, recoveryRevision = "run", readCheckpoint = { checkpoint }, saveCheckpoint = { checkpoint = it }, awaitConnectivity = {
            waits++
            true
        }) { draft, retained, _ ->
            reopens++
            assertEquals("", draft)
            assertEquals(2, requests)
            assertEquals(1, reads)
            assertSavedRead(retained)
            object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                    requests++
                    assertEquals(3, requests)
                    assertTrue(exchanges.isEmpty())
                    assertSavedRead(retained)
                    emit(ProviderEvent.TextDelta("Completed from saved evidence."))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val events = AgentRunner(AgentRunLimits(githubTask = true)).run(session, listOf(tool)).toList()
        assertEquals(1, reads)
        assertEquals(3, requests)
        assertEquals(1, waits)
        assertEquals(1, reopens)
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Completed })
        assertFalse(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
        assertEquals(JsonPrimitive(false), checkpoint?.get("tool_actions_replayed"))
    }

    private fun assertSavedRead(exchanges: List<AgentToolExchange>) {
        val exchange = exchanges.single()
        assertEquals("read-once", exchange.calls.single().callId)
        assertEquals("github_read", exchange.calls.single().name)
        assertEquals("read-once", exchange.results.single().callId)
        assertEquals(ToolResultContent.Text("Saved repository evidence"), exchange.results.single().content)
        assertFalse(exchange.results.single().isError)
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
