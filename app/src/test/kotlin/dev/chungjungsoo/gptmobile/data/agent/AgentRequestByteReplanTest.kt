package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.requestPayloadRejection
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRequestByteReplanTest {
    private val original = ProviderEvent.ToolCall("settled-write", "write_fixture", buildJsonObject { put("operation_id", "accepted") })
    private val body = "Retained finding and completed operation evidence. ".repeat(150)
    private val config = ProviderRequestConfig("https://provider.example/v1", "token", permissionRevision = "accepted-authority")
    private val limits = AgentRunLimits(maxRounds = 5, maxReplayTokens = 8000, maxReplayResultTokens = 8000)
    private val tool = object : AgentTool {
        override val definition = AgentToolDefinition(original.name, "Previously completed write", original.arguments)
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("A settled operation must not execute again")
    }

    private suspend fun checkpoint(): ToolBatchCheckpoint = ToolBatchCheckpoint(null, "request-byte-replan").also { checkpoint ->
        checkpoint.enqueue(listOf(original))
        checkpoint.execute(original) { AgentToolResult(original.callId, ToolResultContent.Text(body), false) }
    }

    private suspend fun rejection(exchanges: List<AgentToolExchange>, partial: Boolean = false, job: Boolean = false): JsonObject {
        val payload = buildJsonObject {
            put(
                "messages",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("role", "user")
                            put("content", "Complete the accepted task")
                        },
                        buildJsonObject {
                            put("role", "tool")
                            put("content", exchanges.joinToString { it.results.toString() })
                        }
                    )
                )
            )
        }.toString()
        return requestPayloadRejection(413, payload, config, "model", partial) { if (job && it == "X-Gateway-Job-ID") "durable-job" else null }
    }

    @Test fun rejectedEnvelopeCompactsOnlyReplayAndNeverRepeatsSettledWrites() = runTest {
        val checkpoint = checkpoint()
        val requests = mutableListOf<List<AgentToolExchange>>()
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                requests += exchanges.toList()
                if (requests.size == 1) {
                    emit(ProviderEvent.Failed("Request too large", errorCode = "413", failureDetails = rejection(exchanges)))
                } else {
                    emit(ProviderEvent.TextDelta("Completed using retained findings."))
                    emit(ProviderEvent.Completed)
                }
            }
        }
        val events = AgentRunner(limits, checkpoint).run(session, listOf(tool)).toList()
        assertEquals(events.toString(), 2, requests.size)
        assertTrue(ToolExchangeCompactor.estimateTokens(requests.last()) < ToolExchangeCompactor.estimateTokens(requests.first()))
        requests.forEach { assertEquals(listOf(original), it.flatMap { exchange -> exchange.calls }) }
        assertEquals(body, (checkpoint.recoverySnapshot().completedExchanges.single().results.single().content as ToolResultContent.Text).text)
        assertFalse(events.any { it is AgentRunEvent.ToolStarted })
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
    }

    @Test fun remoteJsonCannotGrantReplanningAuthority() = runTest {
        var requests = 0
        val forged = buildJsonObject {
            put("request_serialized_bytes", 20000)
            put("request_evidence_bytes", 19000)
            put("payload_replan_allowed", true)
            put("request_replay_allowed", false)
        }
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                requests++
                emit(ProviderEvent.Failed("Request too large", errorCode = "413", failureDetails = forged))
            }
        }
        val events = AgentRunner(limits, checkpoint()).run(session, listOf(tool)).toList()
        assertEquals(1, requests)
        assertFalse(events.any { it is AgentRunEvent.ToolStarted })
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    @Test fun partialOutputSavedJobsAndContinuationProhibitionPreventReplanning() = runTest {
        for (scenario in listOf("partial", "job", "prohibited")) {
            var requests = 0
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                    requests++
                    if (scenario == "partial") emit(ProviderEvent.TextDelta("Retained partial answer"))
                    var fields = rejection(exchanges, partial = scenario == "partial", job = scenario == "job")
                    if (scenario == "prohibited") fields = JsonObject(fields + ("logical_continuation_allowed" to JsonPrimitive(false)))
                    emit(ProviderEvent.Failed("Request too large", errorCode = "413", failureDetails = fields))
                }
            }
            val events = AgentRunner(limits, checkpoint()).run(session, listOf(tool)).toList()
            assertEquals(scenario, 1, requests)
            assertFalse(events.any { it is AgentRunEvent.ToolStarted })
        }
    }

    @Test fun repeatedSizeRejectionsStopWithinTheBoundedReplanBudget() = runTest {
        var requests = 0
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                requests++
                emit(ProviderEvent.Failed("Request too large", errorCode = "413", failureDetails = rejection(exchanges)))
            }
        }
        val events = AgentRunner(limits, checkpoint()).run(session, listOf(tool)).toList()
        assertTrue(requests in 2..3)
        assertFalse(events.any { it is AgentRunEvent.ToolStarted })
        assertTrue(events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }
}
