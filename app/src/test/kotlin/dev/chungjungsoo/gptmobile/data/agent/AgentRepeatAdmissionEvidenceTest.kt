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

/** Uses only pre-existing Runner/result APIs so the identical class can run against the parent. */
class AgentRepeatAdmissionEvidenceTest {
    @Test fun directRepeatReportsAdmissionsWhileItsDependentKeepsOnlyTheCausalReason() = runBlocking {
        for (firstUnsent in listOf(false, true)) {
            var rounds = 0
            val invoked = mutableListOf<String>()
            var writes = 0
            val requests = mutableListOf<List<AgentToolExchange>>()
            val read = tool("read_url") { id, _ ->
                invoked += id
                if (firstUnsent && id == "prior-1") {
                    AgentToolResult(id, ToolResultContent.Text("Local precheck declined"), true, errorCode = "INVALID_ARGUMENTS", dispatched = false)
                } else {
                    AgentToolResult(id, ToolResultContent.Text("Observed source"), false)
                }
            }
            val write = tool("write_record") { id, _ ->
                writes++
                AgentToolResult(id, ToolResultContent.Text("Unexpected write"), false)
            }
            val provider = session { _, exchanges ->
                requests += exchanges.toList()
                when (++rounds) {
                    1 -> {
                        emit(readCall("prior-1"))
                        emit(readCall("prior-2"))
                    }
                    2 -> {
                        emit(readCall("provisional"))
                        emit(readCall("repeat"))
                        emit(ProviderEvent.ToolCall("dependent", "write_record", JsonObject(emptyMap())))
                    }
                    else -> emit(ProviderEvent.TextDelta("Available source receipts reviewed."))
                }
                emit(ProviderEvent.Completed)
            }
            val events = AgentRunner(limits).run(provider, listOf(read, write)).toList()
            assertCompleted(events)
            assertEquals(3, rounds)
            assertEquals(listOf("prior-1", "prior-2", "provisional"), invoked)
            assertEquals(0, writes)
            val results = requests.last().flatMap { it.results }.associateBy { it.callId }
            assertEquals(if (firstUnsent) 2 else 3, results.values.count { it.dispatched })
            assertRepeat(results.getValue("repeat"), prior = 2, batch = 1)
            val dependent = results.getValue("dependent")
            assertEquals("DEPENDENCY_DEFERRED", dependent.errorCode)
            assertTrue(dependent.isError && !dependent.dispatched)
            assertEquals(JsonArray(listOf(JsonPrimitive("REPEAT_LIMIT"))), dependent.executionReceipt?.get("dependency_admission_reasons"))
            assertFalse(dependent.executionReceipt.orEmpty().containsKey("dependency_failed_count"))
            assertNoRepeatFields(dependent)
            results.filterKeys { it in invoked }.values.forEach(::assertNoRepeatFields)
        }
    }

    @Test fun closureKeepsTheOriginalGuardCountsWithoutChargingItsRemovedAdmission() = runBlocking {
        var rounds = 0
        val invoked = mutableListOf<String>()
        val requests = mutableListOf<List<AgentToolExchange>>()
        val read = tool("read_url") { id, _ ->
            invoked += id
            AgentToolResult(id, ToolResultContent.Text("Observed source"), false)
        }
        val provider = session { _, exchanges ->
            requests += exchanges.toList()
            when (++rounds) {
                1 -> {
                    emit(readCall("prior-1"))
                    emit(readCall("prior-2"))
                }
                2 -> {
                    emit(ProviderEvent.ToolCall("missing", "unavailable_barrier", JsonObject(emptyMap())))
                    emit(readCall("removed-provisional"))
                    emit(readCall("repeat"))
                }
                3 -> emit(readCall("after-closure"))
                else -> emit(ProviderEvent.TextDelta("Retained source receipts reviewed."))
            }
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner(limits).run(provider, listOf(read)).toList()
        assertCompleted(events)
        assertEquals(4, rounds)
        assertEquals(listOf("prior-1", "prior-2", "after-closure"), invoked)
        val results = requests.last().flatMap { it.results }.associateBy { it.callId }
        assertRepeat(results.getValue("repeat"), prior = 2, batch = 1)
        assertEquals("TOOL_NOT_AVAILABLE", results.getValue("missing").errorCode)
        assertNoRepeatFields(results.getValue("missing"))
        val removed = results.getValue("removed-provisional")
        assertEquals("DEPENDENCY_DEFERRED", removed.errorCode)
        assertNoRepeatFields(removed)
        val later = results.getValue("after-closure")
        assertTrue(later.dispatched && !later.isError)
        assertNoRepeatFields(later)
    }

    @Test fun sharedGithubObservationDoesNotBecomeADirectRepeatRefusal() = runBlocking {
        var rounds = 0
        var executions = 0
        val requests = mutableListOf<List<AgentToolExchange>>()
        val read = tool("github_ci") { id, _ ->
            executions++
            AgentToolResult(id, ToolResultContent.Text("Saved workflow runs"), false)
        }
        val arguments = buildJsonObject {
            put("action", "runs")
            put("commit_sha", "a".repeat(40))
        }
        val provider = session { _, exchanges ->
            requests += exchanges.toList()
            when (++rounds) {
                1 -> (1..3).forEach { emit(ProviderEvent.ToolCall("ci-$it", "github_ci", arguments)) }
                2 -> emit(ProviderEvent.ToolCall("ci-repeat", "github_ci", arguments))
                else -> emit(ProviderEvent.TextDelta("The saved workflow observations are available."))
            }
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner(limits).run(provider, listOf(read)).toList()
        assertCompleted(events)
        assertEquals(3, rounds)
        assertEquals(3, executions)
        val reused = requests.last().flatMap { it.results }.single { it.callId == "ci-repeat" }
        assertTrue(reused.sharedResult)
        assertFalse(reused.dispatched)
        assertFalse(reused.isError)
        assertNoRepeatFields(reused)
    }

    @Test fun authorityInvalidationOutranksAlreadyPlannedRepeatDiagnostics() = runBlocking {
        var rounds = 0
        var executions = 0
        val read = tool("read_url") { id, _ ->
            executions++
            AgentToolResult(id, ToolResultContent.Text("Authority changed"), true, errorCode = "DISPATCH_POLICY_CHANGED", dispatched = false)
        }
        val provider = session { _, _ ->
            rounds++
            (1..4).forEach { emit(readCall("authority-$it")) }
            emit(ProviderEvent.Completed)
        }
        val events = AgentRunner(limits).run(provider, listOf(read)).toList()
        assertEquals(1, rounds)
        assertEquals(1, executions)
        val failure = events.filterIsInstance<AgentRunEvent.Provider>().map { it.event }.filterIsInstance<ProviderEvent.Failed>().single()
        assertEquals("DISPATCH_POLICY_CHANGED", failure.errorCode)
        val results = events.filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
        assertEquals(4, results.size)
        val originallySuppressed = results.single { it.callId == "authority-4" }
        assertEquals("DISPATCH_POLICY_CHANGED", originallySuppressed.errorCode)
        assertFalse(originallySuppressed.dispatched)
        results.forEach(::assertNoRepeatFields)
    }

    private fun assertRepeat(result: AgentToolResult, prior: Int, batch: Int) {
        assertEquals("NOT_DISPATCHED", result.errorCode)
        assertTrue(result.isError && !result.dispatched && !result.sharedResult)
        val receipt = result.executionReceipt.orEmpty()
        assertEquals(JsonPrimitive("TOOL_NAME_AND_ARGUMENTS"), receipt["dependency_repeat_scope"])
        assertEquals(JsonPrimitive(prior), receipt["dependency_repeat_prior_admissions"])
        assertEquals(JsonPrimitive(batch), receipt["dependency_repeat_batch_admissions"])
        assertEquals(JsonPrimitive(3), receipt["dependency_repeat_used"])
        assertEquals(JsonPrimitive(3), receipt["dependency_repeat_limit"])
        assertEquals(repeatFields, receipt.keys.intersect(repeatFields))
        assertFalse(receipt.toString().contains("private.invalid"))
        assertFalse(receipt.toString().contains("private-mode"))
    }

    private fun assertNoRepeatFields(result: AgentToolResult) = assertTrue(result.executionReceipt.orEmpty().keys.intersect(repeatFields).isEmpty())

    private fun assertCompleted(events: List<AgentRunEvent>) {
        assertTrue(events.toString(), events.any { it is AgentRunEvent.Provider && it.event == ProviderEvent.Completed })
        assertFalse(events.toString(), events.any { it is AgentRunEvent.Provider && it.event is ProviderEvent.Failed })
    }

    private fun readCall(id: String) = ProviderEvent.ToolCall(
        id,
        "read_url",
        buildJsonObject {
            put("url", "https://private.invalid/source")
            put("mode", "private-mode")
        }
    )

    private fun tool(name: String, action: suspend (String, JsonObject) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Bounded synthetic source", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = action(callId, arguments)
    }

    private fun session(block: suspend FlowCollector<ProviderEvent>.(List<AgentToolDefinition>, List<AgentToolExchange>) -> Unit) = object : AgentProviderSession {
        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow { block(tools, exchanges) }
    }

    private val limits = AgentRunLimits(maxRounds = 8, maxToolCalls = 12, maxConcurrentTools = 1)
    private val repeatFields = setOf("dependency_repeat_scope", "dependency_repeat_prior_admissions", "dependency_repeat_batch_admissions", "dependency_repeat_used", "dependency_repeat_limit")
}
