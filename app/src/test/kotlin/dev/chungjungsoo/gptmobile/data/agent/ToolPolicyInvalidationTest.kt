package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.context.DispatchPolicyChangedException
import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolPolicyInvalidationTest {
    private val args = JsonObject(emptyMap())

    @Test fun durableAllowancePolicyRejectionIsUnsentAndNeverRenewsSpentCalls() = runTest {
        for (rejectRead in listOf(false, true)) {
            var stale = false
            var stored: JsonObject? = null
            var invocations = 0
            val policy = DispatchPolicyChangedException(listOf("profile.disableAllTools"))
            val checkpoint = ToolCallBudgetCheckpoint(
                "policy-$rejectRead",
                read = { if (stale && rejectRead) throw policy else stored },
                write = { if (stale) throw policy else stored = it }
            )
            checkpoint.initialize(4, previouslyDispatched = 1)
            val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 4), initialCalls = 1, durableCalls = checkpoint)
            val write = budget.bind(
                tool("write_fixture") { id ->
                    invocations++
                    AgentToolResult(id, ToolResultContent.Text("committed"), false)
                }
            )
            stale = true
            val rejected = write.execute("rejected", args)
            assertPrecheck(rejected)
            assertEquals(JsonPrimitive("tool_precheck"), rejected.executionReceipt?.get("phase"))
            assertEquals(JsonPrimitive(false), rejected.executionReceipt?.get("requires_reconciliation"))
            assertEquals(JsonPrimitive(1), stored?.get("used"))
            stale = false
            val other = budget.bind(
                tool("another_selected_tool") { id ->
                    invocations++
                    AgentToolResult(id, ToolResultContent.Text("unexpected"), false)
                }
            )
            assertPrecheck(other.execute("later", args))
            assertEquals(0, invocations)
            assertEquals(1, budget.usedCalls())
            assertEquals(3, budget.remainingCalls())
            assertEquals(JsonPrimitive(1), stored?.get("used"))
        }
    }

    @Test fun preparedCallRevalidatesTheSelectedAuthorityBeforePhysicalInvocation() = runTest {
        var changed = false
        var invocations = 0
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2))
        val tool = budget.bind(
            tool("write_fixture") { id ->
                invocations++
                AgentToolResult(id, ToolResultContent.Text("unexpected"), false)
            }
        ) as PreparableAgentTool
        withContext(RequestAuthorizationGuard("selected-profile-model") { if (changed) throw DispatchPolicyChangedException(listOf("profile.model")) }) {
            val prepared = tool.prepareExecution("prepared", args)
            changed = true
            assertPrecheck(prepared())
        }
        assertPrecheck(tool.execute("new-call", args))
        assertEquals(0, invocations)
        // Preparing the first call reserved its original slot. Invalidation cannot reset it.
        assertEquals(1, budget.usedCalls())
        assertEquals(1, budget.remainingCalls())
    }

    @Test fun policyThrownAfterInvocationKeepsUnknownMutationAndBlocksPreparedSibling() = runTest {
        var invocations = 0
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 3))
        val tool = budget.bind(
            tool("write_fixture") {
                invocations++
                throw DispatchPolicyChangedException(listOf("engineering"))
            }
        ) as PreparableAgentTool
        val first = tool.prepareExecution("first", args)
        val sibling = tool.prepareExecution("sibling", args)
        val unknown = first()
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", unknown.errorCode)
        assertTrue(unknown.dispatched)
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(unknown))
        assertEquals(JsonPrimitive(true), unknown.executionReceipt?.get("requires_reconciliation"))
        assertEquals("DISPATCH_POLICY_CHANGED", ToolFailureMetadata.authorityCode(unknown))
        assertPrecheck(sibling())
        assertEquals("RECONCILIATION_REQUIRED", tool.execute("repeat", args).errorCode)
        assertEquals(1, invocations)
        assertEquals(2, budget.usedCalls())
        assertEquals(1, budget.remainingCalls())
    }

    @Test fun secondaryObservationPolicyFailurePreservesTheActualDomainReceipt() = runTest {
        for ((code, policy) in listOf(
            "DISPATCH_POLICY_CHANGED" to DispatchPolicyChangedException(listOf("engineering")),
            "POLICY_STALE" to dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("POLICY_STALE", "Selected authority changed")
        )) {
            for (unknown in listOf(false, true)) {
                var invocations = 0
                val original = AgentToolResult(
                    "observed",
                    ToolResultContent.Text("retained operation receipt"),
                    unknown,
                    errorCode = if (unknown) "TOOL_OUTCOME_UNKNOWN" else null,
                    executionReceipt = buildJsonObject {
                        put("schema", "fixture.operation.v1")
                        put("remote_receipt", "retained-identity")
                    }
                )
                val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 3))
                val tool = budget.bind(
                    tool("write_fixture") {
                        invocations++
                        original
                    }
                )
                val observed = withContext(ToolReceiptObserver { _, _ -> throw policy }) {
                    tool.execute(original.callId, args)
                }
                assertEquals(original.content, observed.content)
                assertEquals(original.errorCode, observed.errorCode)
                assertEquals(original.isError, observed.isError)
                assertTrue(observed.dispatched)
                original.executionReceipt!!.forEach { (key, value) -> assertEquals(value, observed.executionReceipt?.get(key)) }
                assertEquals(code, ToolFailureMetadata.authorityCode(observed))
                assertEquals(if (unknown) "UNKNOWN_OUTCOME" else "COMPLETED", ToolResultEnvelope.executionState(observed))
                val later = tool.execute("later", args)
                assertEquals(if (unknown) "RECONCILIATION_REQUIRED" else code, later.errorCode)
                assertFalse(later.dispatched)
                assertEquals(1, invocations)
                assertEquals(1, budget.usedCalls())
            }
        }
    }

    @Test fun measuredMutationAuthorityFailureCannotBecomeAnUnsentRefusal() = runTest {
        for ((code, policy) in listOf(
            "DISPATCH_POLICY_CHANGED" to DispatchPolicyChangedException(listOf("engineering")),
            "POLICY_STALE" to dev.chungjungsoo.gptmobile.data.permissions.ToolAuthorizationRejected("POLICY_STALE", "Selected authority changed")
        )) {
            var effects = 0
            val measured = dev.chungjungsoo.gptmobile.data.agent.tool.MeasuredAgentTool(
                tool("write_fixture") {
                    effects++
                    throw policy
                }
            )
            val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 3))
            val bound = budget.bind(measured)
            val firstArgs = buildJsonObject { put("operation_id", 1) }
            val first = bound.execute("first", firstArgs)
            assertEquals("DISPATCH_OUTCOME_UNKNOWN", first.errorCode)
            assertTrue(first.dispatched)
            assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(first))
            assertEquals(code, ToolFailureMetadata.authorityCode(first))
            assertEquals(JsonPrimitive(true), first.executionReceipt?.get("requires_reconciliation"))
            val queued = bound.execute("queued", buildJsonObject { put("operation_id", 2) })
            assertEquals(code, queued.errorCode)
            assertFalse(queued.dispatched)
            assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(queued))
            assertEquals("RECONCILIATION_REQUIRED", bound.execute("repeat", firstArgs).errorCode)
            assertEquals(1, effects)
            assertEquals(1, budget.usedCalls())
        }
    }

    private fun assertPrecheck(result: AgentToolResult) {
        assertTrue(result.isError)
        assertFalse(result.dispatched)
        assertEquals("DISPATCH_POLICY_CHANGED", result.errorCode)
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(result))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("resume_required"))
    }

    private fun tool(name: String, execute: suspend (String) -> AgentToolResult) = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Selected fixture operation", args)
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = execute(callId)
    }
}
