package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class ToolRepeatAdmissionHistoryTest {
    @Test fun originalAdmissionsKeepObservedOutcomesAndIdenticalModelBytes() {
        val history = ToolRepeatAdmissionHistory(runId)
        val calls = (1..3).map { call("prior-$it") }
        charge(history, calls)
        history.observe(calls[0], result(calls[0], error = "SOURCE_UNAVAILABLE"))
        history.observe(calls[1], result(calls[1]))
        history.observe(calls[2], result(calls[2], error = "INVALID_ARGUMENTS", dispatched = false))
        val refused = call("refused")
        val captured = capture(history, refused, prior = 3)
        val fields = fields(captured, refused)
        assertEquals(hashes(calls), fields["dependency_repeat_prior_call_id_hashes"])
        assertEquals(listOf("DISPATCHED_FAILURE", "DISPATCHED_SUCCESS", "NOT_DISPATCHED"), fields["dependency_repeat_prior_outcomes"])
        assertEquals("COMPLETE", fields["dependency_repeat_prior_coverage"])
        assertEquals(emptyList<String>(), fields["dependency_repeat_batch_call_id_hashes"])
        assertEquals("COMPLETE", fields["dependency_repeat_batch_coverage"])
        assertEquals(3, fields["dependency_repeat_prior_admissions"])
        assertEquals(0, fields["dependency_repeat_batch_admissions"])
        assertEquals(3, fields["dependency_repeat_used"])
        assertEquals(3, fields["dependency_repeat_limit"])
        assertEquals(ToolResultEnvelope.encode(refused.name, direct(refused.callId)), ToolResultEnvelope.encode(refused.name, captured))
        assertFalse(fields.toString().contains("private.invalid"))
        assertFalse(fields.toString().contains("prior-1"))
        assertFalse(fields.toString().contains(runId))
    }

    @Test fun dependencyRefundsKeepTheOriginalProvisionalReferenceWithoutChargingIt() {
        val history = ToolRepeatAdmissionHistory(runId)
        val prior = listOf(call("prior-1"), call("prior-2"))
        charge(history, prior)
        val missing = call("missing", "unavailable")
        val provisional = call("provisional")
        val refused = call("refused")
        val calls = listOf(missing, provisional, refused)
        val original = plan(calls, prior = 2, available = setOf("read_url"))
        val observed = history.capture(original, calls, emptySet(), emptySet())
        val closed = dependencyClosedPlan(
            observed,
            listOf(ToolBatchNode(missing, emptySet(), false), ToolBatchNode(provisional, setOf(missing.callId), true), ToolBatchNode(refused, setOf(missing.callId), true))
        )
        assertEquals(0, closed.acceptedByName.getValue(toolRepeatKey(provisional)))
        history.commit(closed, emptySet(), emptySet())
        val frozen = attach(closed, refused)
        val before = fields(frozen, refused)
        assertEquals(hashes(prior), before["dependency_repeat_prior_call_id_hashes"])
        assertEquals(listOf("UNOBSERVED", "UNOBSERVED"), before["dependency_repeat_prior_outcomes"])
        assertEquals(hashes(listOf(provisional)), before["dependency_repeat_batch_call_id_hashes"])
        assertEquals("COMPLETE", before["dependency_repeat_batch_coverage"])
        prior.forEach { history.observe(it, result(it)) }
        history.observe(provisional, result(provisional))
        val later = call("later")
        charge(history, listOf(later), prior = 2)
        history.observe(later, result(later))
        assertEquals(before, fields(frozen, refused))
        val next = call("next-refusal")
        val nextFields = fields(capture(history, next, 3), next)
        assertEquals(hashes(prior + later), nextFields["dependency_repeat_prior_call_id_hashes"])
        assertEquals(listOf("DISPATCHED_SUCCESS", "DISPATCHED_SUCCESS", "DISPATCHED_SUCCESS"), nextFields["dependency_repeat_prior_outcomes"])
        assertEquals("COMPLETE", nextFields["dependency_repeat_prior_coverage"])
    }

    @Test fun retainedAndSettledCallsStayOutsideChargedHistory() {
        val history = ToolRepeatAdmissionHistory(runId)
        val retained = call("retained", "custom_tool")
        val settled = call("settled", "custom_tool")
        val charged = call("charged", "custom_tool")
        val admitted = charge(history, listOf(retained, settled, charged), limit = 1, retained = setOf(retained.callId), settled = setOf(settled.callId))
        assertEquals(mapOf("custom_tool" to 1), admitted.acceptedByName)
        listOf(retained, settled, charged).forEach { history.observe(it, result(it)) }
        val refused = call("refused", "custom_tool")
        val fields = fields(capture(history, refused, prior = 1, limit = 1), refused)
        assertEquals(hashes(listOf(charged)), fields["dependency_repeat_prior_call_id_hashes"])
        assertEquals(listOf("DISPATCHED_SUCCESS"), fields["dependency_repeat_prior_outcomes"])
        assertEquals("COMPLETE", fields["dependency_repeat_prior_coverage"])
    }

    @Test fun batchReferencesAreFrozenBeforeExecutionAndNeverGainAnOutcomeArray() {
        val history = ToolRepeatAdmissionHistory(runId)
        val calls = (1..4).map { call("batch-$it") }
        val original = plan(calls)
        val observed = history.capture(original, calls, emptySet(), emptySet())
        val frozen = attach(observed, calls.last())
        val before = fields(frozen, calls.last())
        assertEquals(emptyList<String>(), before["dependency_repeat_prior_call_id_hashes"])
        assertEquals(emptyList<String>(), before["dependency_repeat_prior_outcomes"])
        assertEquals("COMPLETE", before["dependency_repeat_prior_coverage"])
        assertEquals(hashes(calls.take(3)), before["dependency_repeat_batch_call_id_hashes"])
        assertEquals("COMPLETE", before["dependency_repeat_batch_coverage"])
        assertFalse(before.containsKey("dependency_repeat_batch_outcomes"))
        history.commit(observed, emptySet(), emptySet())
        history.observe(calls[0], result(calls[0], error = "TOOL_OUTCOME_UNKNOWN"))
        history.observe(calls[1], result(calls[1], shared = true, dispatched = false))
        history.observe(calls[2], result(calls[2]))
        assertEquals(before, fields(frozen, calls.last()))
        val next = call("next-refusal")
        assertEquals(listOf("UNKNOWN_OUTCOME", "SHARED_RESULT", "DISPATCHED_SUCCESS"), fields(capture(history, next, 3), next)["dependency_repeat_prior_outcomes"])
    }

    @Test fun referenceCapsKeyEvictionAndMissingHistoryHaveExplicitCoverage() {
        val history = ToolRepeatAdmissionHistory(runId)
        val many = (1..10).map { call("many-$it", "custom_tool") }
        val admitted = charge(history, many, limit = 10)
        assertEquals(10, admitted.acceptedByName.getValue("custom_tool"))
        val refused = call("many-refused", "custom_tool")
        val fields = fields(capture(history, refused, 10, limit = 10), refused)
        assertEquals(hashes(many.take(8)), fields["dependency_repeat_prior_call_id_hashes"])
        assertEquals("PARTIAL", fields["dependency_repeat_prior_coverage"])
        val original = (1..3).map { call("evicted-$it") }
        val bounded = ToolRepeatAdmissionHistory(runId)
        charge(bounded, original)
        repeat(128) { index ->
            val other = call("other-$index", url = "https://private.invalid/$index")
            charge(bounded, listOf(other))
        }
        val afterEviction = call("after-eviction")
        val evicted = fields(capture(bounded, afterEviction, 3), afterEviction)
        assertEquals(emptyList<String>(), evicted["dependency_repeat_prior_call_id_hashes"])
        assertEquals("UNAVAILABLE", evicted["dependency_repeat_prior_coverage"])
        val latest = call("latest-refusal", url = "https://private.invalid/127")
        val partial = fields(capture(bounded, latest, 3), latest)
        assertEquals(listOf(ToolDependencyDiagnostics.callHash("other-127")), partial["dependency_repeat_prior_call_id_hashes"])
        assertEquals("PARTIAL", partial["dependency_repeat_prior_coverage"])
        val noHistory = fields(capture(ToolRepeatAdmissionHistory(runId), afterEviction, 3), afterEviction)
        assertEquals("UNAVAILABLE", noHistory["dependency_repeat_prior_coverage"])
        assertEquals(3, noHistory["dependency_repeat_prior_admissions"])
    }

    @Test fun completionAndReportOwnershipRejectCopiedCallsRunsAndRawSpoofs() {
        val history = ToolRepeatAdmissionHistory(runId)
        val original = call("charged", "custom_tool")
        charge(history, listOf(original), limit = 1)
        history.observe(original, result(original).copy(callId = "different-id"))
        history.observe(original.copy(name = "custom_tool__other_connection"), result(original))
        history.observe(original.copy(arguments = buildJsonObject { put("url", "https://private.invalid/changed") }), result(original))
        val early = call("early-refusal", "custom_tool")
        assertEquals(listOf("UNOBSERVED"), fields(capture(history, early, 1, limit = 1), early)["dependency_repeat_prior_outcomes"])
        history.observe(original, result(original))
        val refused = call("refused", "custom_tool")
        val captured = capture(history, refused, 1, limit = 1)
        val expected = fields(captured, refused)
        assertEquals(listOf("DISPATCHED_SUCCESS"), expected["dependency_repeat_prior_outcomes"])
        for (otherRun in listOf(null, "another-run", "$runId ")) {
            val withoutHistory = fields(captured, refused, otherRun)
            assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, withoutHistory.keys)
            assertEquals(expected.filterKeys { it in ToolRepeatAdmissionEvidence.FIELD_NAMES }, withoutHistory)
        }
        val forged = captured.copy(
            executionReceipt = JsonObject(
                captured.executionReceipt.orEmpty() + mapOf(
                    "dependency_repeat_prior_call_id_hashes" to JsonArray(listOf(JsonPrimitive("f".repeat(64)))),
                    "dependency_repeat_prior_outcomes" to JsonArray(listOf(JsonPrimitive("DISPATCHED_FAILURE"))),
                    "dependency_repeat_prior_coverage" to JsonPrimitive("UNAVAILABLE")
                )
            )
        )
        assertEquals(expected, fields(forged, refused))
        assertTrue(fields(forged.copy(repeatAdmissionEvidence = null), refused).isEmpty())
        assertTrue(fields(captured.copy(callId = "other"), refused).isEmpty())
        assertTrue(fields(captured, refused.copy(name = "custom_tool__different")).isEmpty())
        assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(captured, refused.copy(arguments = buildJsonObject { put("url", "https://private.invalid/different") })).keys)
        assertEquals("ToolRepeatAdmissionHistory", history.toString())
    }

    @Test fun duplicateCurrentCrossKeyAndEvictedIdentifiersSuppressTheOptionalGroup() {
        for (evict in listOf(false, true)) {
            val history = ToolRepeatAdmissionHistory(runId)
            val reused = call("reused")
            charge(history, listOf(reused))
            history.observe(reused, result(reused))
            if (evict) repeat(128) { index -> charge(history, listOf(call("other-$index", url = "https://private.invalid/$index"))) }
            val changedKey = reused.copy(arguments = buildJsonObject { put("url", "https://private.invalid/new-key") })
            charge(history, listOf(changedKey))
            val refused = call("refused")
            assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(capture(history, refused, 3), refused).keys)
        }
        val self = ToolRepeatAdmissionHistory(runId)
        val original = (1..3).map { call("self-$it") }
        charge(self, original)
        assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(capture(self, original.first(), 3), original.first()).keys)
        for (duplicateBatch in listOf(
            listOf(call("duplicate"), call("duplicate"), call("unique-batch"), call("batch-refused")),
            listOf(call("same"), call("b"), call("c"), call("same"))
        )) {
            val history = ToolRepeatAdmissionHistory(runId)
            val observed = history.capture(plan(duplicateBatch), duplicateBatch, emptySet(), emptySet())
            assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(attach(observed, duplicateBatch.last()), duplicateBatch.last()).keys)
        }
        val crossArrays = ToolRepeatAdmissionHistory(runId)
        val prior = listOf(call("a"), call("b"))
        charge(crossArrays, prior)
        val calls = listOf(prior.first(), call("cross-array-refusal"))
        val observed = crossArrays.capture(plan(calls, prior = 2), calls, emptySet(), emptySet())
        assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(attach(observed, calls.last()), calls.last()).keys)
    }

    @Test fun absentRunOwnershipAndOversizedPrivateInputsDoNotInventHistory() {
        val calls = (1..4).map { call("call-$it") }
        val original = plan(calls)
        for (origin in listOf(null, "", " ")) {
            val unavailable = ToolRepeatAdmissionHistory(origin)
            unavailable.commit(original, emptySet(), emptySet())
            assertSame(original, unavailable.capture(original, calls, emptySet(), emptySet()))
            assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(attach(original, calls.last()), calls.last()).keys)
        }
        var nested: JsonElement = JsonPrimitive("private")
        repeat(13) { nested = JsonObject(mapOf("child" to nested)) }
        for (arguments in listOf(nested as JsonObject, JsonObject((1..257).associate { "key$it" to JsonPrimitive(it) }), buildJsonObject { put("private", "x".repeat(65_537)) })) {
            val history = ToolRepeatAdmissionHistory(runId)
            val large = call("large", "custom_tool").copy(arguments = arguments)
            charge(history, listOf(large), limit = 1)
            history.observe(large, result(large))
            val refused = call("refused", "custom_tool")
            val fields = fields(capture(history, refused, 1, limit = 1), refused)
            assertEquals(1, fields["dependency_repeat_prior_admissions"])
            assertEquals("UNAVAILABLE", fields["dependency_repeat_prior_coverage"])
            assertEquals(emptyList<String>(), fields["dependency_repeat_prior_call_id_hashes"])
        }
        repeat(2000) { nested = JsonObject(mapOf("child" to nested)) }
        val unrelated = call("deep-unrelated", "custom_tool").copy(arguments = nested as JsonObject)
        val refused = call("bounded-refusal")
        val mixed = listOf(unrelated, refused)
        val admission = planToolBatch(mixed, 20, mixed.mapTo(hashSetOf()) { it.name }, mapOf(toolRepeatKey(refused) to 3), emptySet(), 3) { it.name }
        val observed = ToolRepeatAdmissionHistory(runId).capture(admission, mixed, emptySet(), emptySet())
        assertEquals(listOf(unrelated.callId), observed.executable.map { it.callId })
        assertEquals("UNAVAILABLE", fields(attach(observed, refused), refused)["dependency_repeat_prior_coverage"])
        val bounded = ToolRepeatAdmissionHistory(runId)
        repeat(1024) { index -> charge(bounded, listOf(call("seen-$index", url = "https://private.invalid/$index"))) }
        val firstRefusal = call("at-seen-limit")
        assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(capture(bounded, firstRefusal, 3), firstRefusal).keys)
        val laterRefusal = call("after-seen-limit")
        assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, fields(capture(bounded, laterRefusal, 3), laterRefusal).keys)
    }

    @Test fun persistedRunFreshnessRequiresSuccessfulEmptyObservationAndPreservesCancellation() = runBlocking {
        var previousSequence: Int? = null
        suspend fun origin() = ToolRepeatAdmissionHistory.runIdWithoutPriorEvents(runId) { observedRunId ->
            assertEquals(runId, observedRunId)
            previousSequence
        }
        val initialOrigin = origin()
        assertEquals(runId, initialOrigin)
        val empty = ToolBatchCheckpoint(null, "freshness-observation").recoverySnapshot()
        val history = ToolRepeatAdmissionHistory.forLoop(initialOrigin, empty, 0)
        val calls = (1..3).map { call("fresh-$it") }
        charge(history, calls)
        // New events do not retroactively change the ownership frozen for this loop.
        previousSequence = 0
        calls.forEach { history.observe(it, result(it)) }
        val refused = call("fresh-refusal")
        assertEquals(hashes(calls), fields(capture(history, refused, 3), refused)["dependency_repeat_prior_call_id_hashes"])
        // Even the first persisted precheck row excludes a zero-spent recollection.
        assertNull(origin())
        previousSequence = 7
        assertNull(origin())
        val failedLookup = ToolRepeatAdmissionHistory.runIdWithoutPriorEvents(runId) { throw IllegalStateException("Storage unavailable") }
        assertNull(failedLookup)
        assertNull(
            ToolRepeatAdmissionHistory.runIdWithoutPriorEvents(" ") {
                fail("Blank run must not query storage")
                null
            }
        )
        for ((ownership, spent) in listOf(origin() to 0, failedLookup to 0, initialOrigin to null, initialOrigin to -1)) {
            val unavailable = ToolRepeatAdmissionHistory.forLoop(ownership, empty, spent)
            charge(unavailable, calls)
            val unavailableFields = fields(capture(unavailable, refused, 3), refused)
            assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, unavailableFields.keys)
            assertEquals(3, unavailableFields["dependency_repeat_prior_admissions"])
        }
        val cancelled = CancellationException("Cancelled diagnostic lookup")
        try {
            ToolRepeatAdmissionHistory.runIdWithoutPriorEvents(runId) { throw cancelled }
            fail("Cancellation must propagate")
        } catch (failure: CancellationException) {
            assertSame(cancelled, failure)
        }
    }

    private fun charge(history: ToolRepeatAdmissionHistory, calls: List<ProviderEvent.ToolCall>, prior: Int = 0, limit: Int = 3, retained: Set<String> = emptySet(), settled: Set<String> = emptySet()): ToolBatchPlan {
        val observed = history.capture(plan(calls, prior, limit, retained = retained, settled = settled), calls, retained, settled)
        history.commit(observed, retained, settled)
        return observed
    }

    private fun plan(calls: List<ProviderEvent.ToolCall>, prior: Int = 0, limit: Int = 3, available: Set<String> = calls.mapTo(hashSetOf()) { it.name }, retained: Set<String> = emptySet(), settled: Set<String> = emptySet()): ToolBatchPlan =
        planToolBatch(calls, 200, available, calls.associate { toolRepeatKey(it) to prior }, emptySet(), limit, retained, settled) { it.name }

    private fun capture(history: ToolRepeatAdmissionHistory, refused: ProviderEvent.ToolCall, prior: Int, limit: Int = 3): AgentToolResult {
        val calls = listOf(refused)
        return attach(history.capture(plan(calls, prior, limit), calls, emptySet(), emptySet()), refused)
    }

    private fun attach(plan: ToolBatchPlan, refused: ProviderEvent.ToolCall): AgentToolResult = ToolRepeatAdmissionEvidence.attachTo(direct(refused.callId), plan.repeatLimitSnapshots.getValue(refused.callId))

    private fun fields(result: AgentToolResult, call: ProviderEvent.ToolCall, persistedRunId: String? = runId): Map<String, Any> =
        ToolRepeatAdmissionEvidence.reportFields(result, call.callId, call.name, call.arguments.toString(), persistedRunId)

    private fun direct(id: String) = AgentToolResult(
        id,
        ToolResultContent.Text("Repeat refused"),
        true,
        errorCode = "NOT_DISPATCHED",
        dispatched = false,
        executionReceipt = buildJsonObject {
            put("failed_stage", "tool_precheck")
            put("dependency_admission_reasons", JsonArray(listOf(JsonPrimitive("REPEAT_LIMIT"))))
        }
    )

    private fun result(call: ProviderEvent.ToolCall, error: String? = null, dispatched: Boolean = true, shared: Boolean = false) =
        AgentToolResult(call.callId, ToolResultContent.Text("Observed source result"), error != null, errorCode = error, dispatched = dispatched, sharedResult = shared)

    private fun hashes(calls: List<ProviderEvent.ToolCall>) = calls.map { ToolDependencyDiagnostics.callHash(it.callId) }

    private fun call(id: String, name: String = "read_url", url: String = "https://private.invalid/source") = ProviderEvent.ToolCall(id, name, buildJsonObject { put("url", url) })

    private val runId = "originating-report-run"
}
