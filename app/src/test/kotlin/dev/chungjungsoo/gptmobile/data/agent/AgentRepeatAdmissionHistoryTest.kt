package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRepeatAdmissionHistoryTest {
    @Test fun freshCheckpointLinksChargedOutcomesWithoutChangingDispatchOrProviderBytes() = runBlocking {
        val providerBytes = mutableListOf<List<String>>()
        for (origin in listOf(null, runId)) {
            var rounds = 0
            var writes = 0
            val invoked = mutableListOf<String>()
            val requests = mutableListOf<String>()
            val read = tool("read_url") { id, _ ->
                invoked += id
                when (id) {
                    "source-404" -> AgentToolResult(id, ToolResultContent.Text("Source unavailable"), true, errorCode = "SOURCE_UNAVAILABLE", executionReceipt = buildJsonObject { put("http_status", 404) })
                    "unsent" -> AgentToolResult(id, ToolResultContent.Text("Local precheck declined"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
                    else -> AgentToolResult(id, ToolResultContent.Text("Observed source"), false)
                }
            }
            val write = tool("write_record") { id, _ ->
                writes++
                AgentToolResult(id, ToolResultContent.Text("Unexpected mutation"), false)
            }
            val provider = session { _, exchanges ->
                exchanges.forEach { exchange ->
                    val names = exchange.calls.associate { it.callId to it.name }
                    exchange.results.forEach { requests += ToolResultEnvelope.encode(names.getValue(it.callId), it) }
                }
                when (++rounds) {
                    1 -> listOf("source-404", "success", "unsent").forEach { emit(readCall(it)) }
                    2 -> {
                        emit(readCall("refused"))
                        emit(ProviderEvent.ToolCall("dependent", "write_record", JsonObject(emptyMap())))
                    }
                    else -> emit(ProviderEvent.TextDelta("Available receipts reviewed."))
                }
                emit(ProviderEvent.Completed)
            }
            val checkpoint = ToolBatchCheckpoint(null, "fresh-$origin")
            assertTrue(checkpoint.recoverySnapshot().completedExchanges.isEmpty())
            val events = AgentRunner(limits, checkpoint = checkpoint, diagnosticRunId = origin).run(provider, listOf(read, write)).toList()
            assertCompleted(events)
            assertEquals(3, rounds)
            assertEquals(listOf("source-404", "success", "unsent"), invoked)
            assertEquals(0, writes)
            val results = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }.associateBy { it.callId }
            val refusal = results.getValue("refused")
            assertEquals("NOT_DISPATCHED", refusal.errorCode)
            assertEquals(JsonPrimitive(3), refusal.executionReceipt?.get("dependency_repeat_prior_admissions"))
            assertEquals(JsonPrimitive(0), refusal.executionReceipt?.get("dependency_repeat_batch_admissions"))
            assertEquals("DEPENDENCY_DEFERRED", results.getValue("dependent").errorCode)
            assertNoHistory(results.getValue("dependent"))
            invoked.forEach { assertNoHistory(results.getValue(it)) }
            if (origin == null) {
                assertNoHistory(refusal)
            } else {
                assertEquals(hashes("source-404", "success", "unsent"), strings(refusal, "dependency_repeat_prior_call_id_hashes"))
                assertEquals(listOf("DISPATCHED_FAILURE", "DISPATCHED_SUCCESS", "NOT_DISPATCHED"), strings(refusal, "dependency_repeat_prior_outcomes"))
                assertEquals(JsonPrimitive("COMPLETE"), refusal.executionReceipt?.get("dependency_repeat_prior_coverage"))
                assertEquals(emptyList<String>(), strings(refusal, "dependency_repeat_batch_call_id_hashes"))
                val call = readCall("refused")
                val correctRun = ToolRepeatAdmissionEvidence.reportFields(refusal, call.callId, call.name, call.arguments.toString(), runId)
                assertTrue(correctRun.keys.containsAll(ToolRepeatAdmissionHistory.FIELD_NAMES))
                val wrongRun = ToolRepeatAdmissionEvidence.reportFields(refusal, call.callId, call.name, call.arguments.toString(), "different-run")
                assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, wrongRun.keys)
            }
            providerBytes += requests
        }
        assertEquals(providerBytes.first(), providerBytes.last())
    }

    @Test fun dependencyRefundKeepsTheProvisionalGuardViewAndLaterAdmissionHistory() = runBlocking {
        var rounds = 0
        val invoked = mutableListOf<String>()
        val read = tool("read_url") { id, _ ->
            invoked += id
            AgentToolResult(id, ToolResultContent.Text("Observed source"), false)
        }
        val provider = session { _, _ ->
            when (++rounds) {
                1 -> listOf("prior-1", "prior-2").forEach { emit(readCall(it)) }
                2 -> {
                    emit(ProviderEvent.ToolCall("missing", "unavailable_barrier", JsonObject(emptyMap())))
                    emit(readCall("removed-provisional"))
                    emit(readCall("first-refusal"))
                }
                3 -> emit(readCall("after-closure"))
                4 -> emit(readCall("later-refusal"))
                else -> emit(ProviderEvent.TextDelta("Retained receipts reviewed."))
            }
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner(limits, diagnosticRunId = runId).run(provider, listOf(read)).toList()
        assertCompleted(events)
        assertEquals(5, rounds)
        assertEquals(listOf("prior-1", "prior-2", "after-closure"), invoked)
        val results = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }.associateBy { it.callId }
        val first = results.getValue("first-refusal")
        assertEquals(hashes("prior-1", "prior-2"), strings(first, "dependency_repeat_prior_call_id_hashes"))
        assertEquals(hashes("removed-provisional"), strings(first, "dependency_repeat_batch_call_id_hashes"))
        assertEquals(JsonPrimitive(2), first.executionReceipt?.get("dependency_repeat_prior_admissions"))
        assertEquals(JsonPrimitive(1), first.executionReceipt?.get("dependency_repeat_batch_admissions"))
        assertFalse(first.executionReceipt.orEmpty().containsKey("dependency_repeat_batch_outcomes"))
        assertEquals("DEPENDENCY_DEFERRED", results.getValue("removed-provisional").errorCode)
        assertNoHistory(results.getValue("removed-provisional"))
        val later = results.getValue("later-refusal")
        assertEquals(hashes("prior-1", "prior-2", "after-closure"), strings(later, "dependency_repeat_prior_call_id_hashes"))
        assertEquals(listOf("DISPATCHED_SUCCESS", "DISPATCHED_SUCCESS", "DISPATCHED_SUCCESS"), strings(later, "dependency_repeat_prior_outcomes"))
        assertEquals(emptyList<String>(), strings(later, "dependency_repeat_batch_call_id_hashes"))
        assertEquals(JsonPrimitive(3), later.executionReceipt?.get("dependency_repeat_prior_admissions"))
    }

    @Test fun actualReplayCompactionDoesNotEraseChargedAdmissionReferences() = runBlocking {
        var rounds = 0
        val requests = mutableListOf<List<AgentToolExchange>>()
        val invoked = mutableListOf<String>()
        val arguments = buildJsonObject {
            put("url", "https://private.invalid/source")
            put("mode", "private-context-" + "x".repeat(1100))
        }
        val read = tool("read_url") { id, _ ->
            invoked += id
            AgentToolResult(id, ToolResultContent.Text("Observed source " + "x".repeat(6000)), false)
        }
        val provider = session { _, exchanges ->
            requests += exchanges.toList()
            when (++rounds) {
                1, 2, 3 -> emit(ProviderEvent.ToolCall("charged-$rounds", "read_url", arguments))
                4 -> emit(ProviderEvent.ToolCall("refused", "read_url", arguments))
                else -> emit(ProviderEvent.TextDelta("Retained observations reviewed."))
            }
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner(limits.copy(maxReplayTokens = 650, maxReplayResultTokens = 64), diagnosticRunId = runId).run(provider, listOf(read)).toList()
        assertCompleted(events)
        assertEquals(listOf("charged-1", "charged-2", "charged-3"), invoked)
        assertEquals(5, rounds)
        assertFalse(requests[3].flatMap { it.calls }.any { it.callId == "charged-1" })
        val refusal = events.filterIsInstance<AgentRunEvent.ToolFinished>().single { it.result.callId == "refused" }.result
        assertEquals(hashes("charged-1", "charged-2", "charged-3"), strings(refusal, "dependency_repeat_prior_call_id_hashes"))
        assertEquals(JsonPrimitive("COMPLETE"), refusal.executionReceipt?.get("dependency_repeat_prior_coverage"))
        assertEquals(listOf("DISPATCHED_SUCCESS", "DISPATCHED_SUCCESS", "DISPATCHED_SUCCESS"), strings(refusal, "dependency_repeat_prior_outcomes"))
    }

    @Test fun restoredCheckpointNeverSuppliesRunOwnershipForNewHistory() = runBlocking {
        val checkpoint = ToolBatchCheckpoint(null, "prior-run-checkpoint")
        val old = readCall("old-run-call")
        checkpoint.enqueue(listOf(old))
        checkpoint.execute(old) { AgentToolResult(old.callId, ToolResultContent.Text("Old run observation"), false) }
        assertEquals(1, checkpoint.recoverySnapshot().completedCount)
        var rounds = 0
        val invoked = mutableListOf<String>()
        val read = tool("read_url") { id, _ ->
            invoked += id
            AgentToolResult(id, ToolResultContent.Text("New run observation"), false)
        }
        val provider = session { _, _ ->
            when (++rounds) {
                1 -> (1..3).forEach { emit(readCall("new-$it")) }
                2 -> emit(readCall("new-refusal"))
                else -> emit(ProviderEvent.TextDelta("Available receipts reviewed."))
            }
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner(limits, checkpoint = checkpoint, diagnosticRunId = runId).run(provider, listOf(read)).toList()
        assertCompleted(events)
        assertEquals(listOf("new-1", "new-2", "new-3"), invoked)
        val refusal = events.filterIsInstance<AgentRunEvent.ToolFinished>().single { it.result.callId == "new-refusal" }.result
        assertEquals(JsonPrimitive(3), refusal.executionReceipt?.get("dependency_repeat_prior_admissions"))
        assertEquals(JsonPrimitive(3), refusal.executionReceipt?.get("dependency_repeat_limit"))
        assertNoHistory(refusal)
    }

    @Test fun spentCallsWithEmptyCheckpointSuppressHistoryWhileFreshControlRetainsIt() = runBlocking {
        for (spentCalls in listOf(0, 1)) {
            val checkpoint = ToolBatchCheckpoint(null, "new-authority-journal-$spentCalls")
            val empty = checkpoint.recoverySnapshot()
            assertEquals(0, empty.dispatchedCount)
            assertTrue(empty.completedExchanges.isEmpty() && empty.pending.isEmpty())
            var rounds = 0
            val invoked = mutableListOf<String>()
            val read = tool("read_url") { id, _ ->
                invoked += id
                AgentToolResult(id, ToolResultContent.Text("Current loop observation"), false)
            }
            val provider = session { _, _ ->
                when (++rounds) {
                    1 -> listOf("possibly-reused-call", "current-2", "current-3").forEach { emit(readCall(it)) }
                    2 -> emit(readCall("current-refusal"))
                    else -> emit(ProviderEvent.TextDelta("Current observations reviewed."))
                }
                emit(ProviderEvent.Completed)
            }
            val events = AgentRunner(limits, checkpoint = checkpoint, initialToolCallCountOverride = spentCalls, diagnosticRunId = runId).run(provider, listOf(read)).toList()
            assertCompleted(events)
            assertEquals(3, rounds)
            assertEquals(listOf("possibly-reused-call", "current-2", "current-3"), invoked)
            val refusal = events.filterIsInstance<AgentRunEvent.ToolFinished>().single { it.result.callId == "current-refusal" }.result
            assertEquals("NOT_DISPATCHED", refusal.errorCode)
            assertEquals(JsonPrimitive(3), refusal.executionReceipt?.get("dependency_repeat_prior_admissions"))
            assertEquals(JsonPrimitive(0), refusal.executionReceipt?.get("dependency_repeat_batch_admissions"))
            assertEquals(JsonPrimitive(3), refusal.executionReceipt?.get("dependency_repeat_used"))
            assertEquals(JsonPrimitive(3), refusal.executionReceipt?.get("dependency_repeat_limit"))
            if (spentCalls == 0) {
                assertEquals(hashes("possibly-reused-call", "current-2", "current-3"), strings(refusal, "dependency_repeat_prior_call_id_hashes"))
                assertEquals(JsonPrimitive("COMPLETE"), refusal.executionReceipt?.get("dependency_repeat_prior_coverage"))
            } else {
                assertNoHistory(refusal)
                val call = readCall("current-refusal")
                assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, ToolRepeatAdmissionEvidence.reportFields(refusal, call.callId, call.name, call.arguments.toString(), runId).keys)
            }
        }
    }

    private fun assertNoHistory(result: AgentToolResult) = assertTrue(result.executionReceipt.orEmpty().keys.intersect(ToolRepeatAdmissionHistory.FIELD_NAMES).isEmpty())

    private fun strings(result: AgentToolResult, key: String): List<String>? = (result.executionReceipt?.get(key) as? JsonArray)?.map { (it as JsonPrimitive).content }

    private fun hashes(vararg ids: String): List<String> = ids.map(ToolDependencyDiagnostics::callHash)

    private fun assertCompleted(events: List<AgentRunEvent>) {
        assertTrue(events.toString(), events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        assertFalse(events.toString(), events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    private fun readCall(id: String) = ProviderEvent.ToolCall(id, "read_url", buildJsonObject { put("url", "https://private.invalid/source") })

    private fun tool(name: String, action: suspend (String, JsonObject) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Bounded source fixture", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = action(callId, arguments)
    }

    private fun session(block: suspend FlowCollector<ProviderEvent>.(List<AgentToolDefinition>, List<AgentToolExchange>) -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow { block(tools, exchanges) }
    }

    private val limits = AgentRunLimits(maxRounds = 8, maxToolCalls = 12, maxConcurrentTools = 1)
    private val runId = "current-report-run"
}
