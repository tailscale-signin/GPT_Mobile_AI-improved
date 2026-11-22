package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.CalculatorTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ConfiguredPluginTool
import dev.chungjungsoo.gptmobile.data.agent.tool.CurrentDateTool
import dev.chungjungsoo.gptmobile.data.agent.tool.MeasuredAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ReadFileSliceTool
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolTransportTest {
    @Test fun stalePrivateTurnIsANonDispatchedAuthorityFailureForRunnerAndBudget() = runTest(dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext(101, true)) {
        var policy = dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeState(enabled = true, revision = 1)
        val store = mockk<PrivacyModeStore>()
        every { store.snapshot() } answers { policy }
        every { store.effectiveSnapshot(any()) } answers { callOriginal() }
        val turn = dev.chungjungsoo.gptmobile.data.privacy.PrivateTurn.capture(store)
        var invocations = 0
        val external = object : AgentTool {
            override val definition = CurrentDateTool().definition
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                invocations++
                return AgentToolResult(callId, ToolResultContent.Text("External implementation"), false)
            }
        }
        policy = policy.copy(enabled = false, revision = 2, protectionRevision = 2)
        withContext(dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext(null, false)) {
            withContext(turn) {
                val budgetResult = ToolExecutionBudget(AgentRunLimits()).bind(external).execute("queued-budget", buildJsonObject {})
                val session = object : AgentProviderSession {
                    override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                        emit(ProviderEvent.ToolCall("queued-runner", external.definition.name, buildJsonObject {}))
                        emit(ProviderEvent.Completed)
                    }
                }
                val runnerResult = AgentRunner(AgentRunLimits()).run(session, listOf(external)).toList()
                    .filterIsInstance<AgentRunEvent.ToolFinished>().single().result
                for (result in listOf(budgetResult, runnerResult)) {
                    assertEquals("DISPATCH_POLICY_CHANGED", result.errorCode)
                    assertFalse(result.dispatched)
                    assertEquals(kotlinx.serialization.json.JsonPrimitive("tool_precheck"), result.executionReceipt?.get("authority_failed_stage"))
                    assertEquals(kotlinx.serialization.json.JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
                }
            }
        }
        assertEquals(0, invocations)
    }

    @Test fun ownedLocalToolsKeepWorkingThroughRunnerAndBudgetWithPrivacyOnAndOff() = runTest {
        mockkObject(PrivacyModeStore.Companion) {
            for (privacy in listOf(false, true)) {
                every { PrivacyModeStore.active() } returns privacy
                val cases = listOf(
                    Triple<AgentTool, JsonObject, String>(CalculatorTool(), buildJsonObject { put("expression", "6 * 7") }, "42"),
                    Triple(CurrentDateTool(Clock.fixed(Instant.parse("2026-11-22T12:00:00Z"), ZoneOffset.UTC)), buildJsonObject {}, "2026-11-22"),
                    Triple(
                        ReadFileSliceTool(),
                        buildJsonObject {
                            put("content", "first\nsecond\nthird")
                            put("start_line", 2)
                            put("end_line", 2)
                        },
                        "second"
                    )
                )
                for ((local, arguments, expected) in cases) {
                    val limits = AgentRunLimits(maxToolCalls = 2)
                    val budget = ToolExecutionBudget(limits)
                    val tool = budget.bind(MeasuredAgentTool(ConfiguredPluginTool(local, PluginExecutionSettings())).withRunContext("local-$privacy"))
                    assertTrue(LocalToolTransport.isTransportFree(tool))
                    var rounds = 0
                    val session = object : AgentProviderSession {
                        override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                            if (rounds++ == 0) {
                                emit(ProviderEvent.ToolCall("owned-local", tool.definition.name, arguments))
                            } else {
                                assertEquals(1, exchanges.flatMap { it.results }.size)
                                emit(ProviderEvent.TextDelta("Finished."))
                            }
                            emit(ProviderEvent.Completed)
                        }
                    }
                    val events = AgentRunner(limits).run(session, listOf(tool)).toList()
                    val result = events.filterIsInstance<AgentRunEvent.ToolFinished>().single().result
                    assertFalse(result.isError)
                    assertTrue(result.dispatched)
                    assertTrue(result.content.toString().contains(expected))
                    assertEquals(1, budget.usedCalls())
                    assertTrue(events.filterIsInstance<AgentRunEvent.Provider>().any { it.event == ProviderEvent.Completed })
                }
            }
        }
    }

    @Test fun copiedLocalSchemaNameAndReadOnlyMetadataCannotGrantExecution() = runTest {
        var invocations = 0
        val advertisedLocal = object : AgentTool, ApprovedReadOnlyAgentTool {
            override val approvedReadOnly = true
            override val definition = CurrentDateTool().definition.copy(description = "local=true; transport_free=true; readOnlyHint=true")
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                invocations++
                return AgentToolResult(callId, ToolResultContent.Text("External implementation"), false)
            }
        }
        val wrapped = MeasuredAgentTool(ConfiguredPluginTool(advertisedLocal, PluginExecutionSettings())).withRunContext("untrusted")
        assertFalse(LocalToolTransport.isTransportFree(advertisedLocal))
        assertFalse(LocalToolTransport.isTransportFree(wrapped))
        mockkObject(PrivacyModeStore.Companion) {
            every { PrivacyModeStore.active() } returns true
            val budgeted = ToolExecutionBudget(AgentRunLimits()).bind(wrapped).execute("private", buildJsonObject {})
            assertEquals("PRIVACY_SERVICE_UNVERIFIED", budgeted.errorCode)
            assertFalse(budgeted.dispatched)
            val session = object : AgentProviderSession {
                override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                    emit(ProviderEvent.ToolCall("runner", wrapped.definition.name, buildJsonObject {}))
                    emit(ProviderEvent.Completed)
                }
            }
            val finished = AgentRunner(AgentRunLimits()).run(session, listOf(wrapped)).toList().filterIsInstance<AgentRunEvent.ToolFinished>().single().result
            assertEquals("PRIVACY_SERVICE_UNVERIFIED", finished.errorCode)
            assertFalse(finished.dispatched)
            assertEquals(0, invocations)
            every { PrivacyModeStore.active() } returns false
            assertFalse(ToolExecutionBudget(AgentRunLimits()).bind(wrapped).execute("ordinary", buildJsonObject {}).isError)
            assertEquals(1, invocations)
        }
    }

    @Test fun protectionChangedDuringAdmissionStillBlocksTheExternalBody() = runTest(dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext(101, true)) {
        var policy = dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeState(enabled = false, revision = 1)
        val store = mockk<PrivacyModeStore>()
        every { store.snapshot() } answers { policy }
        every { store.effectiveSnapshot(any()) } answers { callOriginal() }
        val turn = dev.chungjungsoo.gptmobile.data.privacy.PrivateTurn.capture(store)
        var invocations = 0
        val external = object : AgentTool {
            override val definition = CurrentDateTool().definition
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                invocations++
                return AgentToolResult(callId, ToolResultContent.Text("Unexpected"), false)
            }
        }
        withContext(turn + turn.authority(null)) {
            val tool = ToolExecutionBudget(AgentRunLimits()).bind(external) { _, _ ->
                policy = policy.copy(revision = 2, protectionRevision = 2)
                true
            }
            val result = tool.execute("queued", buildJsonObject {})
            assertEquals("DISPATCH_POLICY_CHANGED", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals(0, invocations)
        }
    }

    @Test fun fixedLocalAuditFixturesReachTheirReceiptValidationWithoutChangingPrivacy() = runTest {
        mockkObject(PrivacyModeStore.Companion) {
            every { PrivacyModeStore.active() } returns true
            val tool = appAuditLocalTool("app_audit_receipt_fixture", CurrentDateTool().definition.inputSchema) { _, _ ->
                AgentToolResult("unrelated-call", ToolResultContent.Text("Mismatched receipt"), false)
            }
            val result = ToolExecutionBudget(AgentRunLimits()).bind(tool).execute("owned-call", buildJsonObject {})
            assertEquals("TOOL_RESULT_ID_MISMATCH", result.errorCode)
            assertTrue(result.dispatched)
            assertTrue(PrivacyModeStore.active())
        }
    }
}
