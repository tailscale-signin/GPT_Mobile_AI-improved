package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.agent.tool.ResolvedAgentTool
import dev.chungjungsoo.gptmobile.data.agent.tool.aggregateWebSearch
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.repository.ToolEventRecorder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ToolPrecheckIssueReportTest {
    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(ApplicationProvider.getApplicationContext<Context>())
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    @Test fun budgetDuplicateAndCheckpointRejectionsRetainTheirExactLocalReason() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        for ((index, code) in listOf("BUDGET_EXHAUSTED", "DUPLICATE_INVOCATION", "BUDGET_CHECKPOINT_UNAVAILABLE").withIndex()) {
            val callId = "private-call-$index"
            val operation = listOf("github_read", "github_ci", "github_issue")[index]
            val result = ToolResultEnvelope.error(callId, code, "private repository /private/repo and private arguments")
            val report = record(operation, result)
            val context = requireNotNull(report.context)
            val diagnostics = requireNotNull(context.diagnostics)
            assertEquals(if (code == "BUDGET_EXHAUSTED") "TOOL_BUDGET_EXHAUSTED" else "TOOL_FAILURE", report.errorCode)
            assertEquals(code, context.reasonCode)
            assertEquals(7, context.schemaVersion)
            assertEquals("TOOL", diagnostics.area)
            assertEquals("INCIDENT", diagnostics.captureMode)
            assertEquals(JsonPrimitive(IssueReportContext.identifierHash("call", callId)), diagnostics.values["call_id_hash"])
            assertEquals(JsonPrimitive(IssueReportContext.identifierHash("tool-error-code", code)), diagnostics.values["original_error_code_hash"])
            assertEquals(JsonPrimitive(IssueReportContext.identifierHash("tool-reason-code", code)), diagnostics.values["original_reason_code_hash"])
            assertEquals(JsonPrimitive(code), diagnostics.values["tool_reason_code"])
            assertEquals("TOOL_RESULT", context.source)
            assertEquals("tool_precheck", context.stage)
            assertEquals(operation, context.operation)
            assertEquals(false, context.dispatched)
            assertNull(context.retryable)
            assertNull(context.requiresReconciliation)
            assertNull(context.httpStatus)
            assertEquals(IssueReportContext.identifierHash("call", callId), context.callIdHash)
            assertEquals(code, result.errorCode)
            assertFalse(report.approvedPayload().contains("private"))
            assertTrue(report.approvedPayload().encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
        }
    }

    @Test fun searchValidationStaysRejectedWithoutGuessingTheMissingArgument() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val definition = GitHubOperationTool.definition("github_search")
        val arguments = buildJsonObject { put("private_unrecognized_field", "private query") }
        val errors = ToolArgumentValidator.errors(arguments, definition.inputSchema)
        assertTrue(errors.isNotEmpty())
        val rejected = ToolArgumentValidator.rejection("private-search-call", definition.name, arguments, definition.inputSchema, errors)
        val report = record("github_search", rejected)
        assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        assertFalse(rejected.dispatched)
        assertEquals("INVALID_ARGUMENTS", report.errorCode)
        assertEquals(JsonPrimitive(1), report.context?.diagnostics?.values?.get("unknown_field_count"))
        assertEquals("tool_precheck", report.context?.stage)
        assertEquals(false, report.context?.dispatched)
        assertNull(report.context?.reasonCode)
        assertNull(report.context?.missingFields)
        assertNull(report.context?.invalidFields)
        assertFalse(report.approvedPayload().contains("private"))
    }

    @Test fun completedReadWithheldOnlyAtDeliveryUsesThePublicBudgetCategory() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val report = record(
            "github_read",
            AgentToolResult(
                callId = "withheld-read",
                content = ToolResultContent.Json(
                    buildJsonObject { put("result_delivery", "WITHHELD_BY_RESULT_BUDGET") }
                ),
                isError = false,
                outputBudgetExhausted = true,
                retainedContent = ToolResultContent.Text("private completed read"),
                dispatched = true
            )
        )
        assertEquals("BUDGET_RESTRICTED", report.errorCode)
        assertEquals("delivery", report.context?.stage)
        assertEquals(true, report.context?.dispatched)
        assertFalse(report.approvedPayload().contains("private"))
    }

    @Test fun unrecognizedCodeAndErrorProseCannotInventALocalPrecheckReason() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val report = record(
            "github_read",
            ToolResultEnvelope.error("private-unknown-call", "PRIVATE_ACCOUNT_ERROR", "BUDGET_EXHAUSTED; DUPLICATE_INVOCATION; private raw arguments")
        )
        assertEquals("TOOL_FAILURE", report.errorCode)
        assertNull(report.context?.reasonCode)
        assertEquals(false, report.context?.dispatched)
        assertFalse(report.approvedPayload().contains("PRIVATE_ACCOUNT_ERROR"))
        assertFalse(report.approvedPayload().contains("BUDGET_EXHAUSTED"))
        assertFalse(report.approvedPayload().contains("private"))
    }

    @Test fun dispatchAndUnknownOutcomeEvidenceCannotBecomeALocalPrecheck() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val dispatched = record("github_read", ToolResultEnvelope.error("dispatched-call", "DUPLICATE_INVOCATION", "private", dispatched = true))
        assertEquals("tool_execution", dispatched.context?.stage)
        assertEquals(true, dispatched.context?.dispatched)
        assertNull(dispatched.context?.reasonCode)

        val unknown = record("github_issue", ToolResultEnvelope.error("uncertain-call", "DISPATCH_OUTCOME_UNKNOWN", "private", dispatched = true))
        assertEquals("DISPATCH_OUTCOME_UNKNOWN", unknown.errorCode)
        assertEquals(true, unknown.context?.requiresReconciliation)
        assertNull(unknown.context?.reasonCode)

        val retained = record(
            "github_ci",
            AgentToolResult(
                "retained-unknown-call",
                ToolResultContent.Text("private"),
                true,
                errorCode = "BUDGET_EXHAUSTED",
                dispatched = false,
                executionReceipt = buildJsonObject { put("requires_reconciliation", true) }
            )
        )
        assertEquals(true, retained.context?.requiresReconciliation)
        assertNull(retained.context?.reasonCode)
        assertNull(retained.context?.retryable)
    }

    @Test fun searchGuardBudgetSnapshotReachesApprovedPayloadWithoutQueryData() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 4, maxToolOutputBytes = 2048))
        val arguments = buildJsonObject { put("query", "private search terms and account") }
        val prior = object : AgentTool {
            override val definition = AgentToolDefinition("prior", "", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("x".repeat(1536)), false)
        }
        budget.bind(prior).execute("private-prior-call", buildJsonObject {})
        val engine = object : AgentTool {
            override val definition = AgentToolDefinition(
                "selected",
                "Web search",
                buildJsonObject { put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) }) }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("No engine may run")
        }
        val selected = ResolvedAgentTool(budget.bind(engine, authorize = { _, _ -> error("No approval may be requested") }), "selected", "Selected search", "web_search", "selected")
        val search = aggregateWebSearch(
            listOf(selected),
            canExecute = budget::canExecute,
            remainingBytes = budget::remainingOutputBytes,
            budgetSnapshot = budget::snapshot
        ).single().tool
        val result = search.execute("private-search-call", arguments)
        assertEquals(1536, result.toolResultBudgetUsedBytes)
        assertEquals(2048, result.toolResultBudgetLimitBytes)
        val report = record("web_search", result, arguments)
        val context = requireNotNull(report.context)
        val values = requireNotNull(context.diagnostics).values
        assertEquals("BUDGET_EXHAUSTED", result.errorCode)
        assertEquals("TOOL_BUDGET_EXHAUSTED", report.errorCode)
        assertEquals(false, context.dispatched)
        assertEquals(false, context.retryable)
        assertEquals(false, context.requiresReconciliation)
        assertEquals(JsonPrimitive("RESULT_BYTES"), values["tool_budget_kind"])
        assertEquals(JsonPrimitive("BEFORE_DISPATCH"), values["tool_budget_phase"])
        assertEquals(JsonPrimitive(512), values["tool_budget_bytes_available"])
        assertEquals(JsonPrimitive(1536), values["tool_result_bytes_used"])
        assertEquals(JsonPrimitive(2048), values["tool_result_bytes_limit"])
        assertEquals(JsonPrimitive(1), values["tool_budget_calls_used"])
        assertEquals(JsonPrimitive(4), values["tool_budget_calls_limit"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("tool-error-code", "BUDGET_EXHAUSTED")), values["original_error_code_hash"])
        assertEquals(1, budget.usedCalls())
        assertEquals(512, budget.remainingOutputBytes())
        val payload = report.approvedPayload()
        assertTrue(payload.contains("\"tool_result_bytes_used\":1536"))
        assertTrue(payload.contains("\"tool_result_bytes_limit\":2048"))
        assertTrue(payload.contains("\"tool_budget_bytes_available\":512"))
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("search terms"))
        assertTrue(payload.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)

        for (available in listOf(0, 1023)) {
            val separateBudget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 4, maxToolOutputBytes = available, researchAcquisition = true))
            val separateSelected = selected.copy(tool = separateBudget.bind(engine, authorize = { _, _ -> error("No approval may be requested") }))
            val separateSearch = aggregateWebSearch(
                listOf(separateSelected),
                canExecute = separateBudget::canExecute,
                remainingBytes = separateBudget::remainingOutputBytes,
                budgetSnapshot = separateBudget::snapshot
            ).single().tool
            val separateResult = separateSearch.execute("private-separate-$available", arguments)
            val separateReport = record("web_search", separateResult, arguments)
            val separateValues = requireNotNull(separateReport.context?.diagnostics).values
            assertEquals("TOOL_BUDGET_EXHAUSTED", separateReport.errorCode)
            assertEquals(JsonPrimitive(available), separateValues["tool_budget_bytes_available"])
            assertNull(separateValues["tool_result_bytes_used"])
            assertNull(separateValues["tool_result_bytes_limit"])
            assertEquals(false, separateReport.context?.dispatched)
            assertFalse(separateReport.approvedPayload().contains("private"))
        }

        val event = ToolEvent("event", "private-run", 1, result.callId, null, null, "web_search", "web_search", arguments.toString(), null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val receipt = requireNotNull(result.executionReceipt)
        fun receiptWith(key: String, value: kotlinx.serialization.json.JsonElement) = result.copy(executionReceipt = JsonObject(receipt + (key to value)))
        val unqualified = listOf(
            result.copy(isError = false),
            result.copy(dispatched = true),
            result.copy(errorCode = "UNAVAILABLE"),
            result.copy(outputBudgetExhausted = false),
            result.copy(executionReceipt = null),
            receiptWith("schema", JsonPrimitive("foreign")),
            receiptWith("callId", JsonPrimitive("another-call")),
            receiptWith("phase", JsonPrimitive("SEARCH_DISPATCH")),
            receiptWith("state", JsonPrimitive("FAILED")),
            receiptWith("retryable", JsonPrimitive(true)),
            receiptWith("tool_budget_bytes_available", JsonPrimitive("512")),
            receiptWith("tool_budget_bytes_available", JsonPrimitive(-1)),
            receiptWith("tool_budget_bytes_available", JsonPrimitive(1024))
        )
        for (candidate in unqualified) assertNull(ToolReportDiagnostics.fields(event, candidate)["tool_budget_bytes_available"])
        for (candidate in listOf(receiptWith("schema", JsonPrimitive("foreign")), receiptWith("callId", JsonPrimitive("another-call")), result.copy(outputBudgetExhausted = false))) {
            val unqualifiedReport = record("web_search", candidate, arguments)
            assertNull(unqualifiedReport.context?.diagnostics?.values?.get("tool_budget_bytes_available"))
            assertFalse(unqualifiedReport.approvedPayload().contains("tool_budget_bytes_available"))
        }
        assertNull(ToolReportDiagnostics.fields(null, result)["tool_budget_bytes_available"])
        assertNull(ToolReportDiagnostics.fields(event.copy(callId = "foreign-call"), result)["tool_budget_bytes_available"])
        assertNull(ToolReportDiagnostics.fields(event.copy(modelToolName = "read_url"), result)["tool_budget_bytes_available"])
    }

    @Test fun graphAttemptHashRequiresTheCurrentLocallyBoundGraphReceipt() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val hash = "a".repeat(64)
        val callId = "private-graph-call"
        val arguments = buildJsonObject { put("action", "status") }
        val receipt = buildJsonObject {
            put("schema", RepositoryGraphTool.RECEIPT_SCHEMA)
            put("call_id", callId)
            put("action", "status")
            put("graph_index_attempt_hash", hash)
        }
        val result = AgentToolResult(callId, ToolResultContent.Text("private graph response"), true, errorCode = "REPOSITORY_GRAPH_UNAVAILABLE", dispatched = false, executionReceipt = receipt)
        val event = ToolEvent("event", "private-run", 1, callId, null, null, "repository_graph", "repository_graph", arguments.toString(), null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        assertEquals(hash, ToolReportDiagnostics.fields(event, result)["graph_index_attempt_hash"])
        val report = record("repository_graph", result, arguments)
        assertEquals(JsonPrimitive(hash), report.context?.diagnostics?.values?.get("graph_index_attempt_hash"))
        assertFalse(report.approvedPayload().contains("private"))

        fun receiptWith(key: String, value: kotlinx.serialization.json.JsonElement) = result.copy(executionReceipt = JsonObject(receipt + (key to value)))
        val unboundActions = listOf(
            result.copy(executionReceipt = JsonObject(receipt - "action")),
            receiptWith("action", JsonPrimitive("index")),
            receiptWith("action", JsonPrimitive(1)),
            receiptWith("action", JsonNull)
        )
        val unqualified = listOf(
            result.copy(executionReceipt = null),
            receiptWith("schema", JsonPrimitive("remote.provider.v1")),
            receiptWith("call_id", JsonPrimitive("another-call")),
            receiptWith("graph_index_attempt_hash", JsonPrimitive("A".repeat(64))),
            receiptWith("graph_index_attempt_hash", JsonPrimitive("a".repeat(63))),
            receiptWith("graph_index_attempt_hash", JsonPrimitive(123)),
            result.copy(executionReceipt = JsonObject(receipt - "graph_index_attempt_hash" + ("child" to buildJsonObject { put("graph_index_attempt_hash", hash) }))),
            result.copy(content = ToolResultContent.Json(buildJsonObject { put("graph_index_attempt_hash", hash) }), executionReceipt = JsonObject(receipt - "graph_index_attempt_hash"))
        ) + unboundActions
        for (candidate in unqualified) assertNull(ToolReportDiagnostics.fields(event, candidate)["graph_index_attempt_hash"])
        for (candidate in unboundActions.take(2) + listOf(receiptWith("schema", JsonPrimitive("remote.provider.v1")), receiptWith("call_id", JsonPrimitive("another-call")))) {
            val unboundReport = record("repository_graph", candidate, arguments)
            assertNull(unboundReport.context?.diagnostics?.values?.get("graph_index_attempt_hash"))
            assertFalse(unboundReport.approvedPayload().contains(hash))
        }
        for (unboundArguments in listOf(buildJsonObject {}, buildJsonObject { put("action", 1) }, buildJsonObject { put("action", "index") })) {
            assertNull(ToolReportDiagnostics.fields(event.copy(arguments = unboundArguments.toString()), result)["graph_index_attempt_hash"])
        }
        assertNull(ToolReportDiagnostics.fields(null, result)["graph_index_attempt_hash"])
        assertNull(ToolReportDiagnostics.fields(event.copy(callId = "another-call"), result)["graph_index_attempt_hash"])
        assertNull(ToolReportDiagnostics.fields(event.copy(modelToolName = "web_search"), result)["graph_index_attempt_hash"])
    }

    private suspend fun record(operation: String, result: AgentToolResult, arguments: JsonObject = buildJsonObject {}): IssueReport {
        val event = ToolEvent("event-${result.callId}", "private-run", 1, result.callId, null, null, operation, operation, arguments.toString(), null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val dao = mockk<AgentPersistenceDao>()
        coEvery { dao.getToolEventById(event.eventId) } returns event
        coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ToolEventRecorder(dao, mockk()).finishTool(event.eventId, result, 2L)
        return IssueReportIncidents.pending.value.last()
    }
}
