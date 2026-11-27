package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.GitHubProviderCheckpoint
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubProviderCooldownContinuationTest {
    private val tool = AgentToolDefinition("github_read", "Read pinned evidence", JsonObject(emptyMap()))
    private val context = buildJsonObject { put("task_binding", "a".repeat(64)) }

    private fun session(vararg events: ProviderEvent) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flowOf(*events)
    }

    private fun rejection(invocation: String, retryAt: Long) = ProviderEvent.Failed(
        "The upstream provider rejected this request.",
        errorCode = "429",
        invocationId = invocation,
        providerLimit = ProviderLimitDecision("TEST", "profile", "model", retryAt, "shared-scope", "PROVIDER_RATE_LIMITED", true, httpStatus = 429, retrySource = "APP_BACKOFF"),
        failureDetails = buildJsonObject {
            put("failed_stage", "model_invocation")
            put("http_status", 429)
            put("dispatch_state", "FAILED")
            put("dispatched", true)
            put("remote_acceptance", "NO")
            put("request_replay_allowed", true)
        }
    )

    @Test fun repeatedCooldownsAndSuccessfulToolHandoffsSurviveRestartWithoutSpendingConnectionRetries() = runTest {
        var checkpoint: JsonObject? = null
        var requests = 0
        var waits = 0
        var activeDeadline = 0L
        val executedCalls = mutableListOf<String>()
        val exchanges = mutableListOf<AgentToolExchange>()
        val journal = mutableListOf<ToolHandoffReceipt>()
        val dispatchTimes = mutableListOf<Long>()
        val savedWaits = mutableListOf<Pair<JsonObject, ProviderLimitDecision>>()
        fun physicalSession() = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                assertTrue(testScheduler.currentTime >= activeDeadline)
                dispatchTimes += testScheduler.currentTime
                requests++
                when (requests) {
                    1, 3, 5 -> {
                        emit(ProviderEvent.ToolCall("read-$requests", tool.name, buildJsonObject { put("path", "source-$requests.kt") }))
                        emit(ProviderEvent.Completed)
                    }
                    2, 4, 6 -> emit(rejection("rejection-$requests", testScheduler.currentTime + 5_000))
                    7 -> {
                        emit(ProviderEvent.TextDelta("The retained evidence completes the requested investigation."))
                        emit(ProviderEvent.Completed)
                    }
                    else -> error("No extra provider request is needed")
                }
            }
        }
        fun restored(expected: GitHubProviderCheckpoint? = null) = GitHubOutputContinuationSession(
            physicalSession(),
            recoveryRevision = "same-run-after-process-restart",
            expectedProviderCheckpoint = expected,
            checkpointContext = context,
            durableProviderJournal = { journal.toList() },
            durableToolReceipts = { fingerprints -> journal.filter { it.invocationFingerprint in fingerprints } },
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { _, retained, _ ->
            assertEquals(exchanges, retained)
            physicalSession()
        }
        var output = restored()
        while (requests < 7) {
            val events = output.streamRound(listOf(tool), exchanges.toList()).toList()
            events.filterIsInstance<ProviderEvent.ToolCall>().forEach { call ->
                assertFalse(call.callId in executedCalls)
                executedCalls += call.callId
                val result = AgentToolResult(call.callId, ToolResultContent.Text("Confirmed ${call.callId}"), false)
                exchanges += AgentToolExchange(listOf(call), listOf(result))
                journal += ToolHandoffReceipt.from(call, result).copy(receiptSource = "TOOL_BATCH_CHECKPOINT")
                output.observeToolResult(call, result)
            }
            val failure = events.filterIsInstance<ProviderEvent.Failed>().singleOrNull()
            if (failure != null) {
                waits++
                val limit = requireNotNull(failure.providerLimit) { "Expected a provider wait after $requests requests, got ${failure.errorCode}" }
                val saved = requireNotNull(checkpoint)
                assertEquals(JsonPrimitive("WAITING_PROVIDER_LIMIT"), saved["state"])
                assertTrue(requireNotNull(limit.gitHubCheckpoint).matches(saved))
                savedWaits += saved to limit
                // The existing durable wait owner uses this same maximum of the
                // rejection and shared-pool deadlines. Restart retains both.
                val schedule = ProviderWaitDeadline("same-run-after-process-restart", 1, limit.retryAt).withSchedule(limit.retryAt + 2_000)
                activeDeadline = schedule.effectiveRetryAt
                output = restored(limit.gitHubCheckpoint)
                val before = requests
                delay(activeDeadline - testScheduler.currentTime - 1)
                assertEquals(before, requests)
                assertEquals(saved, checkpoint)
                delay(1)
            } else if (requests < 7) {
                // A fresh wrapper must recover completed receipts without running
                // a previously completed tool or reusing a transport retry claim.
                output = restored()
            } else {
                assertEquals(ProviderEvent.Completed, events.last())
                assertTrue(events.filterIsInstance<ProviderEvent.TextDelta>().single().text.contains("completes"))
            }
        }
        assertEquals(listOf("read-1", "read-3", "read-5"), executedCalls)
        assertEquals(listOf(0L, 0L, 7_000L, 7_000L, 14_000L, 14_000L, 21_000L), dispatchTimes)
        assertEquals(3, waits)
        savedWaits.forEachIndexed { index, (saved, limit) ->
            assertEquals(JsonPrimitive("PROVIDER_COOLDOWN"), saved["recovery_cause"])
            assertEquals(JsonPrimitive(0), saved["interruption_attempts"])
            assertEquals(JsonPrimitive(index + 1), saved["provider_waits"])
            assertEquals(JsonPrimitive(limit.retryAt), saved["provider_retry_at_ms"])
        }
        assertEquals(JsonPrimitive("COMPLETED"), checkpoint?.get("state"))
        assertEquals(JsonPrimitive(3), checkpoint?.get("provider_waits"))
        assertEquals(JsonPrimitive(3), checkpoint?.get("tool_handoffs"))
        assertEquals(JsonPrimitive(0), checkpoint?.get("transport_recoveries"))
        assertEquals(JsonPrimitive(0), checkpoint?.get("continuations"))
        assertEquals(JsonPrimitive(0), checkpoint?.get("no_output_repairs"))
        assertEquals(JsonPrimitive(false), checkpoint?.get("tool_actions_replayed"))
        assertEquals(JsonPrimitive("rejection-2"), ((checkpoint?.get("original_interruption") as JsonObject)["details"] as JsonObject)["invocation_id"])
        assertEquals(JsonPrimitive("rejection-6"), ((checkpoint.get("latest_interruption") as JsonObject)["details"] as JsonObject)["invocation_id"])
    }

    @Test fun transportLimitAfterAProviderWaitRetainsBothTheOriginalRejectionAndLatestInterruption() = runBlocking {
        var checkpoint: JsonObject? = null
        val first = GitHubOutputContinuationSession(
            session(rejection("original-429", 30_000)),
            checkpointContext = context,
            recoveryRevision = "same-run",
            saveCheckpoint = { checkpoint = it }
        ) { _, _, _ -> error("The wait owner resumes this request") }.streamRound(emptyList(), emptyList()).toList().last() as ProviderEvent.Failed
        fun interrupted(invocation: String) = ProviderEvent.Failed(
            "A transport interruption stopped this response.",
            errorCode = "CONNECTION_ABORTED",
            invocationId = invocation,
            failureDetails = buildJsonObject {
                put("failed_stage", "response_stream")
                put("dispatched", true)
                put("remote_acceptance", "YES")
                put("request_replay_allowed", false)
                put("logical_continuation_allowed", true)
            }
        )
        var reopens = 0
        fun restored(expected: GitHubProviderCheckpoint? = null) = GitHubOutputContinuationSession(
            session(),
            checkpointContext = context,
            recoveryRevision = "same-run",
            expectedProviderCheckpoint = expected,
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { _, _, _ -> session(interrupted("transport-${++reopens}")) }
        restored(first.providerLimit?.gitHubCheckpoint).streamRound(emptyList(), emptyList()).toList()
        assertEquals(JsonPrimitive(0), checkpoint?.get("interruption_attempts"))
        restored().streamRound(emptyList(), emptyList()).toList()
        assertEquals(JsonPrimitive(1), checkpoint?.get("interruption_attempts"))
        val stopped = restored().streamRound(emptyList(), emptyList()).toList().single() as ProviderEvent.Failed
        assertEquals(2, reopens)
        assertEquals("OUTPUT_CONTINUATION_UNAVAILABLE", stopped.errorCode)
        assertEquals(JsonPrimitive("TRANSPORT_RECOVERY_LIMIT"), stopped.failureDetails?.get("stop_reason"))
        val original = stopped.failureDetails?.get("original_interruption") as JsonObject
        assertEquals(JsonPrimitive("429"), original["code"])
        val originalDetails = original["details"] as JsonObject
        assertEquals(JsonPrimitive(429), originalDetails["http_status"])
        assertEquals(JsonPrimitive("model_invocation"), originalDetails["failed_stage"])
        assertEquals(JsonPrimitive("original-429"), originalDetails["invocation_id"])
        assertEquals(JsonPrimitive(30_000L), originalDetails["retry_at_ms"])
        val latest = stopped.failureDetails.get("latest_interruption") as JsonObject
        assertEquals(JsonPrimitive("CONNECTION_ABORTED"), latest["code"])
        assertEquals(JsonPrimitive("transport-2"), (latest["details"] as JsonObject)["invocation_id"])
        assertEquals(JsonPrimitive("WAITING_FOR_CONNECTION"), checkpoint?.get("state"))
        assertEquals(original, checkpoint?.get("original_interruption"))
        assertEquals(latest, checkpoint?.get("latest_interruption"))
    }

    @Test fun aRestoredCooldownCannotRepeatAnUncertainWrite() = runBlocking {
        val call = ProviderEvent.ToolCall("write-once", "github_commit", JsonObject(emptyMap()))
        var checkpoint: JsonObject? = null
        val first = GitHubOutputContinuationSession(
            session(rejection("before-write", 30_000)),
            checkpointContext = context,
            saveCheckpoint = { checkpoint = it }
        ) { _, _, _ -> error("Wait first") }.streamRound(listOf(tool), emptyList()).toList().last() as ProviderEvent.Failed
        val resumed = GitHubOutputContinuationSession(
            session(),
            checkpointContext = context,
            expectedProviderCheckpoint = first.providerLimit?.gitHubCheckpoint,
            readCheckpoint = { checkpoint },
            saveCheckpoint = { checkpoint = it }
        ) { _, _, _ -> session(call, ProviderEvent.Completed) }
        assertEquals(listOf(call), resumed.streamRound(listOf(tool), emptyList()).toList().filterIsInstance<ProviderEvent.ToolCall>())
        val uncertain = AgentToolResult(call.callId, ToolResultContent.Text("Outcome unknown"), true, errorCode = "TOOL_OUTCOME_UNKNOWN", dispatched = true)
        resumed.observeToolResult(call, uncertain)
        val restarted = GitHubOutputContinuationSession(
            session(),
            checkpointContext = context,
            readCheckpoint = { checkpoint }
        ) { _, _, _ -> error("The write must be reconciled before any provider continuation") }
        val stopped = restarted.streamRound(listOf(tool), listOf(AgentToolExchange(listOf(call), listOf(uncertain)))).toList().single() as ProviderEvent.Failed
        assertEquals("OUTPUT_CONTINUATION_PENDING_TOOLS", stopped.errorCode)
        assertEquals("BLOCKED", stopped.taskOutcome)
    }
}
