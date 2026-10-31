package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentContractValidationTest {
    private val arguments = JsonObject(emptyMap())
    private val call = ProviderEvent.ToolCall("one", "write", arguments)

    @Test fun errorCategoriesPreserveAuthorizationAndUnknownOutcomeWithoutGuessingAvailability() {
        fun error(code: String?) = Json.parseToJsonElement(ToolResultEnvelope.encode("write", AgentToolResult("one", ToolResultContent.Text("failed"), true, errorCode = code))).jsonObject.getValue("error").jsonObject
        for (code in listOf("AUTH_REQUIRED", "AUTH_FAILED", "UNAUTHORIZED", "FORBIDDEN", "PERMISSION_DENIED", "PERMISSION_REVOKED")) {
            assertEquals(JsonPrimitive("AUTHORIZATION"), error(code)["category"])
        }
        assertEquals(JsonPrimitive("TOOL_FAILURE"), error(null)["category"])
        assertEquals(JsonPrimitive(false), error(null)["retryableWithoutChanges"])
        assertEquals(JsonPrimitive(true), error("RECONCILIATION_REQUIRED")["reconciliationRequired"])
    }

    @Test fun exchangeValidationRequiresExactlyOneReceiptPerCallAndAllowsReorderedResults() {
        val second = call.copy(callId = "two")
        val result = AgentToolResult(call.callId, ToolResultContent.Text("receipt"), false)
        assertNull(AgentContractValidation.exchangeError(listOf(AgentToolExchange(listOf(call, second), listOf(result.copy(callId = "two"), result)))))
        for (exchange in listOf(
            AgentToolExchange(listOf(call), emptyList()),
            AgentToolExchange(listOf(call), listOf(result.copy(callId = "other"))),
            AgentToolExchange(listOf(call, call), listOf(result, result)),
            AgentToolExchange(listOf(call), listOf(result, result)),
            AgentToolExchange(listOf(call.copy(callId = "")), listOf(result.copy(callId = "")))
        )) {
            assertNotNull(AgentContractValidation.exchangeError(listOf(exchange)))
        }
    }

    @Test fun incompatibleMajorIsRejectedByDirectAndBudgetedRunnersBeforeDispatch() = runTest {
        val future = object : AgentTool {
            override val definition = AgentToolDefinition("write", "Write", arguments, contractVersion = AgentContractVersion(2, 0))
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("must not dispatch")
        }
        for (tool in listOf(future, ToolExecutionBudget(AgentRunLimits()).bind(future))) {
            val result = runTool(tool)
            assertEquals("UNSUPPORTED_TOOL_CONTRACT", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertTrue(AgentContractVersion(1, 10).supported)
        assertTrue(runCatching { AgentContractVersion(0, 1) }.isFailure)
        assertTrue(runCatching { AgentContractVersion(1, -1) }.isFailure)
    }

    @Test fun directMutationTimeoutAndExceptionPreserveUnknownOutcomeButReadsRemainRetryable() = runTest {
        for (timeout in listOf(false, true)) {
            for (name in listOf("write", "github_read")) {
                val tool = object : AgentTool {
                    override val definition = AgentToolDefinition(name, "Operation", arguments)
                    override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                        if (timeout) delay(1000) else throw IllegalStateException("response lost")
                        return AgentToolResult(callId, ToolResultContent.Text("late"), false)
                    }
                }
                val result = runTool(tool, AgentRunLimits(toolTimeoutMillis = 10))
                val unknown = result.errorCode in ToolResultEnvelope.unknownOutcomeCodes
                assertEquals(name == "write", unknown)
                assertTrue(result.dispatched)
                assertTrue(result.isError)
            }
        }
    }

    @Test fun providerOwnedAndDelegatedReceiptsUseTheSameIdentityAndOutcomeRules() = runTest {
        val unrelated = AgentToolResult("child", ToolResultContent.Text("child success"), false)
        val provider = object : AgentProviderSession {
            override val handlesToolsInternally = true
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                emit(ProviderEvent.ToolCall(call.callId, call.name, call.arguments))
                emit(ProviderEvent.ToolResult(call, unrelated))
                emit(ProviderEvent.TextDelta("Check the external state."))
                emit(ProviderEvent.Completed)
            }
        }
        val result = AgentRunner().run(provider, emptyList()).toList().filterIsInstance<AgentRunEvent.ToolFinished>().single().result
        assertEquals(call.callId, result.callId)
        assertEquals("TOOL_RESULT_ID_MISMATCH", result.errorCode)
        assertTrue(result.isError)
        val contradictory = AgentContractValidation.receipt(call.callId, unrelated.copy(callId = call.callId, errorCode = "RECONCILIATION_REQUIRED"))
        assertTrue(contradictory.isError)
    }

    @Test fun directWritesWithUnknownOutcomesCannotRepeatUnderNewCallIds() = runTest {
        var executions = 0
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition("write", "Write", arguments)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                executions++
                throw IllegalStateException("response lost after write")
            }
        }
        var round = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    emit(call)
                    emit(call.copy(callId = "new-id"))
                } else {
                    emit(ProviderEvent.TextDelta("Requires reconciliation."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        val results = AgentRunner().run(provider, listOf(tool)).toList().filterIsInstance<AgentRunEvent.ToolFinished>().map { it.result }
        assertEquals(1, executions)
        assertEquals(listOf("DISPATCH_OUTCOME_UNKNOWN", "RECONCILIATION_REQUIRED"), results.map { it.errorCode })
        assertFalse(results.last().dispatched)
    }

    private suspend fun runTool(tool: AgentTool, limits: AgentRunLimits = AgentRunLimits()): AgentToolResult {
        var round = 0
        val provider = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (round++ == 0) {
                    emit(call.copy(name = tool.definition.name))
                } else {
                    assertNull(AgentContractValidation.exchangeError(exchanges))
                    emit(ProviderEvent.TextDelta("Receipt inspected."))
                }
                emit(ProviderEvent.Completed)
            }
        }
        return AgentRunner(limits).run(provider, listOf(tool)).toList().filterIsInstance<AgentRunEvent.ToolFinished>().single().result
    }
}
