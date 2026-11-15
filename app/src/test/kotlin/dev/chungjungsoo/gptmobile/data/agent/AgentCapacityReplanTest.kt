package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.RequestAdmission
import dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
import dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind
import dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCapacityReplanTest {
    @Test
    fun unsentCapacityCanReplanSettledEvidenceWithoutChangingItsCallOrRepeatingWork() = runBlocking {
        for (code in listOf("CONTEXT_CAPACITY_EXCEEDED", "INPUT_CAPACITY_EXCEEDED")) {
            val checkpoint = checkpoint(unknown = false)
            val requests = mutableListOf<List<AgentToolExchange>>()
            val provider = session { exchanges ->
                requests += exchanges
                if (requests.size == 1) listOf(capacityFailure(code)) else listOf(ProviderEvent.TextDelta("Retained evidence reviewed."), ProviderEvent.Completed)
            }
            val events = AgentRunner(limits, checkpoint).run(provider, listOf(originalTool())).toList()
            assertEquals(events.toString(), 2, requests.size)
            assertTrue(ToolExchangeCompactor.estimateTokens(requests.last()) < ToolExchangeCompactor.estimateTokens(requests.first()))
            requests.forEach { assertEquals(listOf(original), it.flatMap { exchange -> exchange.calls }) }
            assertFalse(events.any { it is AgentRunEvent.ToolStarted })
            assertTrue(events.providerEvents().any { it is ProviderEvent.Completed })
            assertFalse(events.providerEvents().any { it is ProviderEvent.Failed })
            assertEquals(body, (checkpoint.recoverySnapshot().completedExchanges.single().results.single().content as ToolResultContent.Text).text)
        }
    }

    @Test
    fun restoredUncertainWriteStopsAtReconciliationBeforeCapacityReplanning() = runBlocking {
        val checkpoint = checkpoint(unknown = true)
        var requests = 0
        val provider = session {
            requests++
            if (requests == 1) listOf(capacityFailure()) else listOf(ProviderEvent.TextDelta("Unexpected continuation"), ProviderEvent.Completed)
        }
        val events = AgentRunner(limits, checkpoint).run(provider, listOf(originalTool())).toList()
        assertEquals(1, requests)
        assertReconciliation(events)
        assertEquals(1, checkpoint.recoverySnapshot().unknownCount)
        assertFalse(events.any { it is AgentRunEvent.ToolStarted })
    }

    @Test
    fun currentUncertainWriteCannotBeClearedByTheNextUnsentCapacityAttempt() = runBlocking {
        var executions = 0
        var requests = 0
        val writer = object : AgentTool {
            override val definition = AgentToolDefinition(original.name, "Owned write", original.arguments)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                return AgentToolResult(callId, ToolResultContent.Text(body), true, errorCode = "TOOL_OUTCOME_UNKNOWN")
            }
        }
        val provider = session {
            when (++requests) {
                1 -> listOf(original, ProviderEvent.Completed)
                2 -> listOf(capacityFailure())
                else -> listOf(ProviderEvent.TextDelta("Unexpected continuation"), ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(limits).run(provider, listOf(writer)).toList()
        assertEquals(2, requests)
        assertEquals(1, executions)
        assertReconciliation(events)
        assertEquals(1, events.filterIsInstance<AgentRunEvent.ToolStarted>().size)
    }

    @Test
    fun explicitContinuationProhibitionOutranksAnOtherwiseReplannableCapacityReceipt() = runBlocking {
        var requests = 0
        val provider = session {
            requests++
            if (requests == 1) listOf(capacityFailure(continuationAllowed = false)) else listOf(ProviderEvent.TextDelta("Unexpected continuation"), ProviderEvent.Completed)
        }
        val events = AgentRunner(limits, checkpoint(unknown = false)).run(provider, listOf(originalTool())).toList()
        assertEquals(1, requests)
        val terminal = events.providerEvents().filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals(terminal.toString(), "CONTEXT_CAPACITY_EXCEEDED", terminal.errorCode)
        assertEquals(JsonPrimitive(false), terminal.failureDetails?.get("logical_continuation_allowed"))
        assertFalse(events.providerEvents().any { it is ProviderEvent.Completed })
        assertFalse(events.any { it is AgentRunEvent.ToolStarted })
    }

    @Test
    fun replannedReplayCeilingSurvivesFollowingToolBatchesAndSchemaPlanning() = runBlocking {
        val requests = mutableListOf<List<AgentToolExchange>>()
        val schemas = mutableListOf<List<AgentToolDefinition>>()
        val next = ProviderEvent.ToolCall("after-replan", original.name, buildJsonObject { put("operation_id", "next") })
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(original.name, "Owned write", original.arguments)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                assertEquals(next.arguments, arguments)
                return AgentToolResult(callId, ToolResultContent.Text("New evidence after replanning. ".repeat(500)), false)
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                schemas += tools.toList()
                requests += exchanges.toList()
                when (requests.size) {
                    1 -> emit(capacityFailure())
                    2 -> {
                        emit(next)
                        emit(ProviderEvent.Completed)
                    }
                    else -> {
                        emit(ProviderEvent.TextDelta("Both retained operations reviewed."))
                        emit(ProviderEvent.Completed)
                    }
                }
            }
        }
        val events = AgentRunner(limits.copy(contextTokens = 8000, initialContextTokens = 1000, finalResponseReserveTokens = 1000), checkpoint(unknown = false)).run(provider, listOf(tool)).toList()
        assertEquals(events.toString(), 3, requests.size)
        assertEquals(1, executions)
        assertEquals(listOf(next.callId), events.filterIsInstance<AgentRunEvent.ToolStarted>().map { it.call.callId })
        assertTrue("The later batch must plan schemas against the admitted smaller replay projection", schemas.last().any { it.name == original.name })
        assertFalse(events.filterIsInstance<AgentRunEvent.Notice>().any { it.message.contains("Context limit approaching") })
        val admitted = ToolExchangeCompactor.estimateTokens(requests.first()) - 98
        assertTrue(ToolExchangeCompactor.estimateTokens(requests.last()) <= admitted)
        assertTrue(events.providerEvents().any { it is ProviderEvent.Completed })
        assertFalse(events.providerEvents().any { it is ProviderEvent.Failed })
    }

    @Test
    fun smallerReplayCeilingStillRejectsIrreduciblePendingArgumentsWithoutChangingThem() = runBlocking {
        val next = ProviderEvent.ToolCall(
            "exact-large-call",
            original.name,
            buildJsonObject {
                put("operation_id", "next")
                put("exact_anchor", "x".repeat(9000))
            }
        )
        var requests = 0
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(original.name, "Owned write", original.arguments)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                assertEquals(next.arguments, arguments)
                return AgentToolResult(callId, ToolResultContent.Text("Original argument receipt retained."), false)
            }
        }
        val provider = session {
            when (++requests) {
                1 -> listOf(capacityFailure())
                2 -> listOf(next, ProviderEvent.Completed)
                else -> error("An irreducible pending call must not reach another provider request")
            }
        }
        val events = AgentRunner(limits, checkpoint(unknown = false)).run(provider, listOf(tool)).toList()
        assertEquals(2, requests)
        assertEquals(1, executions)
        assertEquals(next.arguments, events.filterIsInstance<AgentRunEvent.ToolStarted>().single().call.arguments)
        val failure = events.providerEvents().filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("REPLAY_EVIDENCE_REQUIRED", failure.errorCode)
        assertEquals("BLOCKED", failure.taskOutcome)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertFalse(events.providerEvents().any { it is ProviderEvent.Completed })
    }

    @Test
    fun inclusiveExactContextFitKeepsToolSchemaForTheNextRound() = runBlocking {
        val result = AgentToolResult(original.callId, ToolResultContent.Text("Exact evidence ".repeat(40)), false)
        val replay = ToolExchangeCompactor.estimateTokens(listOf(AgentToolExchange(listOf(original), listOf(result))))
        val initial = 200
        val reserve = 128
        val prompt = initial.toLong() + replay
        val context = (prompt + RequestAdmission.estimateSafety(prompt) + reserve).toInt()
        val schemas = mutableListOf<List<AgentToolDefinition>>()
        val requests = mutableListOf<List<AgentToolExchange>>()
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(original.name, "Owned write", original.arguments)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                assertEquals(original.arguments, arguments)
                return result
            }
        }
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
                schemas += tools.toList()
                requests += exchanges.toList()
                if (requests.size == 1) emit(original) else emit(ProviderEvent.TextDelta("Exact receipt reviewed."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(limits.copy(contextTokens = context, initialContextTokens = initial, finalResponseReserveTokens = reserve)).run(provider, listOf(tool)).toList()
        assertEquals(events.toString(), 2, requests.size)
        assertEquals(1, executions)
        assertEquals(result.content, requests.last().single().results.single().content)
        assertTrue("An inclusive exact fit must retain its authorized schema", schemas.last().any { it.name == original.name })
        assertFalse(events.filterIsInstance<AgentRunEvent.Notice>().any { it.message.contains("Context limit approaching") })
        assertTrue(events.providerEvents().any { it is ProviderEvent.Completed })
        assertFalse(events.providerEvents().any { it is ProviderEvent.Failed })
    }

    private suspend fun checkpoint(unknown: Boolean): ToolBatchCheckpoint {
        val checkpoint = ToolBatchCheckpoint(null, "capacity-$unknown")
        checkpoint.enqueue(listOf(original))
        checkpoint.execute(original) {
            AgentToolResult(original.callId, ToolResultContent.Text(body), unknown, errorCode = "TOOL_OUTCOME_UNKNOWN".takeIf { unknown })
        }
        return checkpoint
    }

    private fun originalTool() = object : AgentTool {
        override val definition = AgentToolDefinition(original.name, "Owned write", original.arguments)
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Retained work must never be repeated")
    }

    private fun capacityFailure(code: String = "CONTEXT_CAPACITY_EXCEEDED", continuationAllowed: Boolean? = null): ProviderEvent.Failed {
        val ceiling = if (code == "INPUT_CAPACITY_EXCEEDED") 1222L else 1350L
        val detail = requireNotNull(providerExceptionDetails(RequestCapacityException(code, "Local serialized admission", 1000, 256, 128, ceiling, TokenMeasurementKind.UTF8_ESTIMATE), false, nowMillis = 1000, monotonicMillis = 1000))
        val details = requireNotNull(detail.failureDetails)
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), details["attempt_dispatch_state"])
        return ProviderEvent.Failed(
            requireNotNull(detail.message),
            errorCode = detail.code,
            failureDetails = if (continuationAllowed == null) details else JsonObject(details + ("logical_continuation_allowed" to JsonPrimitive(continuationAllowed)))
        )
    }

    private fun session(events: (List<AgentToolExchange>) -> List<ProviderEvent>) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>): Flow<ProviderEvent> = flow {
            events(exchanges).forEach { emit(it) }
        }
    }

    private fun assertReconciliation(events: List<AgentRunEvent>) {
        val failure = events.providerEvents().filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("RECONCILIATION_REQUIRED", failure.errorCode)
        assertEquals("PARTIAL", failure.taskOutcome)
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("requires_reconciliation"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("logical_continuation_allowed"))
        assertFalse(events.providerEvents().any { it is ProviderEvent.Completed })
    }

    private fun List<AgentRunEvent>.providerEvents() = filterIsInstance<AgentRunEvent.Provider>().map { it.event }

    private val original = ProviderEvent.ToolCall("owned-write", "write_fixture", buildJsonObject { put("operation_id", "original") })
    private val body = "Retained exact operation evidence. ".repeat(200)
    private val limits = AgentRunLimits(maxRounds = 4, maxReplayTokens = 8000, maxReplayResultTokens = 8000)
}
