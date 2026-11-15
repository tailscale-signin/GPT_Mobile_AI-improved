package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolRepeatAdmissionDiagnosticsTest {
    @Test fun originalGuardOperandsIncludePriorAndProvisionalAdmissions() {
        for ((name, limit, scope) in listOf(
            Triple("read_url", 3, ToolDependencyDiagnostics.RepeatScope.TOOL_NAME_AND_ARGUMENTS),
            Triple("custom_tool", 5, ToolDependencyDiagnostics.RepeatScope.TOOL_NAME),
            Triple("search_nodes", 3, ToolDependencyDiagnostics.RepeatScope.TOOL_NAME)
        )) {
            val calls = (1..3).map { call("call-$it", name) }
            val key = toolRepeatKey(calls.first())
            val prior = mutableMapOf(key to limit - 2)
            val plan = planToolBatch(calls, 20, setOf(name), prior, emptySet(), 5) { it.name }
            val evidence = plan.repeatLimitSnapshots.getValue("call-3")
            assertEquals(ToolDependencyDiagnostics.RepeatLimitSnapshot(scope, limit - 2, 2, limit, limit), evidence.snapshot)
            assertEquals(listOf("call-1", "call-2"), plan.executable.map { it.callId })
            assertEquals(listOf("call-3"), plan.deferred.map { it.callId })
            assertEquals(setOf("call-3"), plan.perToolSuppressedIds)
            assertEquals(mapOf(key to 2), plan.acceptedByName)
            assertEquals(if (name == "read_url") emptySet() else setOf(name), plan.disabledNames)
            assertEquals(setOf("call-3"), plan.repeatLimitSnapshots.keys)
            prior[key] = 999
            assertEquals(limit - 2, evidence.snapshot.priorAdmissions)
            assertFalse(evidence.toString().contains(key))
            assertFalse(ToolDependencyDiagnostics.repeatLimitDetails(evidence.snapshot).toString().contains("private.invalid"))
        }
    }

    @Test fun argumentAndConnectionIdentityKeepUnrelatedReadsIndependent() {
        val original = call("original", "mcp__reader_one__read_url")
        val reordered = original.copy(
            callId = "reordered",
            arguments = buildJsonObject {
                put("mode", "summary")
                put("url", "https://private.invalid/source")
            }
        )
        val changedOption = original.copy(
            callId = "changed-option",
            arguments = buildJsonObject {
                put("url", "https://private.invalid/source")
                put("mode", "full")
            }
        )
        val changedSource = original.copy(
            callId = "changed-source",
            arguments = buildJsonObject {
                put("url", "https://private.invalid/other")
                put("mode", "summary")
            }
        )
        val otherConnection = original.copy(callId = "other-connection", name = "mcp__reader_two__read_url")
        val calls = listOf(reordered, changedOption, changedSource, otherConnection)
        val plan = planToolBatch(calls, 20, calls.mapTo(hashSetOf()) { it.name }, mapOf(toolRepeatKey(original) to 3), emptySet(), 16) { it.name }
        assertEquals(toolRepeatKey(original), toolRepeatKey(reordered))
        assertEquals(listOf(changedOption, changedSource, otherConnection), plan.executable)
        assertEquals(setOf(reordered.callId), plan.perToolSuppressedIds)
        assertEquals(setOf(reordered.callId), plan.repeatLimitSnapshots.keys)
        assertEquals(3, plan.acceptedByName.size)
        assertTrue(plan.acceptedByName.values.all { it == 1 })
        assertTrue(plan.disabledNames.isEmpty())
    }

    @Test fun earlierAdmissionGuardsAndRetainedCallsDoNotCreateRepeatSnapshots() {
        val call = call("candidate")
        fun plan(available: Boolean = true, blocked: Boolean = false, remaining: Int = 10, prior: Int = 3, retained: Boolean = false, settled: Boolean = false): ToolBatchPlan =
            planToolBatch(listOf(call), remaining, if (available) setOf(call.name) else emptySet(), mapOf(toolRepeatKey(call) to prior), if (blocked) setOf("host") else emptySet(), 16, if (retained) setOf(call.callId) else emptySet(), if (settled) setOf(call.callId) else emptySet()) { "host" }
        val settled = plan(available = false, blocked = true, remaining = 0, settled = true)
        assertEquals(listOf(call), settled.executable)
        assertTrue(settled.acceptedByName.isEmpty() && settled.repeatLimitSnapshots.isEmpty())
        for (retained in listOf(false, true)) {
            val unavailable = plan(available = false, blocked = true, remaining = 0, retained = retained)
            assertEquals(setOf(call.callId), unavailable.unavailableIds)
            assertTrue(unavailable.perToolSuppressedIds.isEmpty() && unavailable.repeatLimitSnapshots.isEmpty())
        }
        val host = plan(blocked = true, remaining = 0)
        assertEquals(setOf(call.callId), host.hostSuppressedIds)
        assertTrue(host.perToolSuppressedIds.isEmpty() && host.repeatLimitSnapshots.isEmpty())
        val retained = plan(blocked = true, remaining = 0, retained = true)
        assertEquals(listOf(call), retained.executable)
        assertTrue(retained.acceptedByName.isEmpty() && retained.repeatLimitSnapshots.isEmpty())
        val allowance = plan(remaining = 0, prior = 2)
        assertEquals(listOf(call), allowance.deferred)
        assertTrue(allowance.perToolSuppressedIds.isEmpty() && allowance.repeatLimitSnapshots.isEmpty())
        val repeatBeforeAllowance = plan(remaining = 0)
        assertEquals(setOf(call.callId), repeatBeforeAllowance.perToolSuppressedIds)
        assertEquals(3, repeatBeforeAllowance.repeatLimitSnapshots.getValue(call.callId).snapshot.used)
        val admitted = plan(remaining = 1, prior = 2)
        assertEquals(listOf(call), admitted.executable)
        assertEquals(mapOf(toolRepeatKey(call) to 1), admitted.acceptedByName)
        assertTrue(admitted.repeatLimitSnapshots.isEmpty())
    }

    @Test fun dependencyClosurePreservesGuardObservationWhenCommittedAdmissionsShrink() {
        val missing = call("missing", "unavailable_barrier")
        val accepted = call("accepted")
        val refused = call("refused")
        val plan = planToolBatch(listOf(missing, accepted, refused), 20, setOf(accepted.name), mapOf(toolRepeatKey(accepted) to 2), emptySet(), 16) { it.name }
        val nodes = listOf(
            ToolBatchNode(missing, emptySet(), false),
            ToolBatchNode(accepted, setOf(missing.callId), true),
            ToolBatchNode(refused, setOf(missing.callId), true)
        )
        val closed = dependencyClosedPlan(plan, nodes)
        assertEquals(1, plan.acceptedByName.getValue(toolRepeatKey(accepted)))
        assertEquals(0, closed.acceptedByName.getValue(toolRepeatKey(accepted)))
        assertTrue(closed.executable.isEmpty())
        assertEquals(setOf(accepted.callId), closed.dependencyDeferredIds)
        assertEquals(setOf(refused.callId), closed.perToolSuppressedIds)
        assertSame(plan.repeatLimitSnapshots, closed.repeatLimitSnapshots)
        assertSame(plan.repeatLimitSnapshots.getValue(refused.callId), closed.repeatLimitSnapshots.getValue(refused.callId))
        assertEquals(ToolDependencyDiagnostics.RepeatLimitSnapshot(ToolDependencyDiagnostics.RepeatScope.TOOL_NAME_AND_ARGUMENTS, 2, 1, 3, 3), closed.repeatLimitSnapshots.getValue(refused.callId).snapshot)
    }

    @Test fun repeatCountsAreAllOrNoneWithinThePublicDomain() {
        val valid = ToolDependencyDiagnostics.RepeatLimitSnapshot(ToolDependencyDiagnostics.RepeatScope.TOOL_NAME_AND_ARGUMENTS, 2, 1, 3, 3)
        assertEquals(expectedFields, ToolDependencyDiagnostics.repeatLimitDetails(valid))
        assertEquals(ToolRepeatAdmissionEvidence.FIELD_NAMES, expectedFields.keys)
        for (invalid in listOf(
            null,
            valid.copy(priorAdmissions = -1),
            valid.copy(batchAdmissions = -1),
            valid.copy(used = -1),
            valid.copy(limit = -1),
            valid.copy(priorAdmissions = 16_000_001),
            valid.copy(batchAdmissions = 16_000_001),
            valid.copy(used = 16_000_001),
            valid.copy(limit = 16_000_001),
            valid.copy(used = 4),
            valid.copy(limit = 4),
            valid.copy(priorAdmissions = Int.MAX_VALUE, batchAdmissions = 1, used = Int.MIN_VALUE)
        )) {
            assertTrue(ToolDependencyDiagnostics.repeatLimitDetails(invalid).isEmpty())
        }
        val boundary = valid.copy(priorAdmissions = 16_000_000, batchAdmissions = 0, used = 16_000_000, limit = 16_000_000)
        assertEquals(16_000_000, ToolDependencyDiagnostics.repeatLimitDetails(boundary)["dependency_repeat_used"])
        assertEquals(0, ToolDependencyDiagnostics.repeatLimitDetails(valid.copy(priorAdmissions = 0, batchAdmissions = 0, used = 0, limit = 0))["dependency_repeat_limit"])
        assertEquals(3, ToolDependencyDiagnostics.repeatLimitDetails(valid.copy(limit = 2))["dependency_repeat_used"])
    }

    @Test fun reportProjectionRequiresTypedCurrentCallOwnership() {
        val call = call("private-call", "mcp__private_connection__read_url")
        val result = capturedResult(call, 2, 1)
        fun projected(candidate: AgentToolResult = result, id: String? = call.callId, name: String? = call.name, arguments: String? = call.arguments.toString()) =
            ToolRepeatAdmissionEvidence.reportFields(candidate, id, name, arguments)
        assertEquals(expectedFields, projected())
        assertTrue(projected(result.copy(repeatAdmissionEvidence = null)).isEmpty())
        assertTrue(projected(id = "other-call").isEmpty())
        assertTrue(projected(name = "mcp__other_connection__read_url").isEmpty())
        assertTrue(projected(result.copy(callId = "other-call")).isEmpty())
        assertTrue(
            projected(
                arguments = buildJsonObject {
                    put("url", "https://private.invalid/changed")
                    put("mode", "summary")
                }.toString()
            ).isEmpty()
        )
        assertTrue(projected(id = null).isEmpty())
        assertTrue(projected(name = null).isEmpty())
        assertTrue(projected(arguments = null).isEmpty())
        for (candidate in listOf(
            result.copy(isError = false),
            result.copy(dispatched = true),
            result.copy(sharedResult = true),
            result.copy(errorCode = "DEPENDENCY_DEFERRED"),
            result.copy(errorCode = "DEPENDENCY_FAILED"),
            result.copy(errorCode = "TOOL_OUTCOME_UNKNOWN"),
            result.copy(executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("authority_invalidation_code" to JsonPrimitive("DISPATCH_POLICY_CHANGED")))),
            result.copy(executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("native_dependency_state" to JsonPrimitive("DEFERRED")))),
            result.copy(executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("requires_reconciliation" to JsonPrimitive(true))))
        )) {
            assertTrue(projected(candidate).isEmpty())
        }
        val forgedCounts = result.copy(executionReceipt = JsonObject(result.executionReceipt.orEmpty() + ("dependency_repeat_used" to JsonPrimitive(999))))
        assertEquals(expectedFields, projected(forgedCounts))
        val reconstructed = AgentToolResult(call.callId, result.content, true, errorCode = "NOT_DISPATCHED", dispatched = false, executionReceipt = result.executionReceipt)
        assertTrue(projected(reconstructed).isEmpty())
        assertFalse(projected().toString().contains("private"))
        assertEquals("ToolRepeatAdmissionEvidence", result.repeatAdmissionEvidence.toString())
    }

    @Test fun persistedArgumentProjectionHasFiniteParseAndCanonicalizationBounds() {
        fun fields(arguments: JsonObject): Map<String, Any> {
            val call = call("bounded").copy(arguments = arguments)
            return ToolRepeatAdmissionEvidence.reportFields(capturedResult(call), call.callId, call.name, arguments.toString())
        }
        fun nested(depth: Int): JsonObject {
            var value: JsonElement = JsonPrimitive("private")
            repeat(depth) { value = JsonObject(mapOf("child" to value)) }
            return value as JsonObject
        }
        assertFalse(fields(nested(12)).isEmpty())
        assertTrue(fields(nested(13)).isEmpty())
        assertFalse(fields(JsonObject((1..255).associate { "key$it" to JsonPrimitive(it) })).isEmpty())
        assertTrue(fields(JsonObject((1..256).associate { "key$it" to JsonPrimitive(it) })).isEmpty())
        val overhead = JsonObject(mapOf("value" to JsonPrimitive(""))).toString().length
        val exact = JsonObject(mapOf("value" to JsonPrimitive("x".repeat(65_536 - overhead))))
        assertEquals(65_536, exact.toString().length)
        assertFalse(fields(exact).isEmpty())
        assertTrue(fields(JsonObject(mapOf("value" to JsonPrimitive("x".repeat(65_537 - overhead))))).isEmpty())
        val call = call("malformed")
        val result = capturedResult(call)
        for (arguments in listOf("{", "[]", "null", "[".repeat(2000) + "0" + "]".repeat(2000))) {
            assertTrue(ToolRepeatAdmissionEvidence.reportFields(result, call.callId, call.name, arguments).isEmpty())
        }
    }

    @Test fun reportMetadataDoesNotChangeTheModelEnvelope() {
        val call = call("same-model-bytes")
        val original = directResult(call.callId)
        val qualified = capturedResult(call)
        assertTrue(qualified.executionReceipt.orEmpty().keys.containsAll(ToolRepeatAdmissionEvidence.FIELD_NAMES))
        assertFalse(original.executionReceipt.orEmpty().keys.any { it in ToolRepeatAdmissionEvidence.FIELD_NAMES })
        assertEquals(ToolResultEnvelope.encode(call.name, original), ToolResultEnvelope.encode(call.name, qualified))
        assertEquals(original.executionReceipt, qualified.executionReceipt?.let { JsonObject(it - ToolRepeatAdmissionEvidence.FIELD_NAMES) })
    }

    private fun call(id: String, name: String = "read_url") = ProviderEvent.ToolCall(
        id,
        name,
        buildJsonObject {
            put("url", "https://private.invalid/source")
            put("mode", "summary")
        }
    )

    private fun capturedResult(call: ProviderEvent.ToolCall, prior: Int = 3, batch: Int = 0): AgentToolResult {
        val calls = (0 until batch).map { call.copy(callId = "provisional-$it") } + call
        val plan = planToolBatch(calls, 20, setOf(call.name), mapOf(toolRepeatKey(call) to prior), emptySet(), 16) { it.name }
        return ToolRepeatAdmissionEvidence.attachTo(directResult(call.callId), plan.repeatLimitSnapshots.getValue(call.callId))
    }

    private fun directResult(id: String) = AgentToolResult(
        id,
        ToolResultContent.Text("Not dispatched"),
        true,
        errorCode = "NOT_DISPATCHED",
        dispatched = false,
        executionReceipt = buildJsonObject {
            put("failed_stage", "tool_precheck")
            put("dependency_admission_reasons", JsonArray(listOf(JsonPrimitive("REPEAT_LIMIT"))))
        }
    )

    private val expectedFields = mapOf(
        "dependency_repeat_scope" to "TOOL_NAME_AND_ARGUMENTS",
        "dependency_repeat_prior_admissions" to 2,
        "dependency_repeat_batch_admissions" to 1,
        "dependency_repeat_used" to 3,
        "dependency_repeat_limit" to 3
    )
}
