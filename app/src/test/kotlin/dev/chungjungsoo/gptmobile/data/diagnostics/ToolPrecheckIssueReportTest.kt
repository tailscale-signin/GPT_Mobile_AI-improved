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
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphOperationException
import dev.chungjungsoo.gptmobile.data.github.GatewayGraphResultException
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.repository.ToolEventRecorder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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
    @Test fun durableDecisionWaitDoesNotQueueAutomaticFailureButActualDecisionFailureStillDoes() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val event = ToolEvent("choice-event", "choice-run", 0, "choice-call", null, null, "github_decide", "github_decide", "{}", null, null, ToolEventStatus.RUNNING, startedAt = 1)
        val dao = mockk<AgentPersistenceDao>()
        coEvery { dao.getToolEventById(event.eventId) } returns event
        coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val recorder = ToolEventRecorder(dao, mockk())
        recorder.finishTool(
            event.eventId,
            AgentToolResult(
                event.callId,
                ToolResultContent.Json(
                    buildJsonObject {
                        put("status", "GITHUB_CHOICE_PENDING")
                        put("choice_prompt_id", "a".repeat(64))
                    }
                ),
                isError = true,
                dispatched = false,
                sharedResult = true,
                errorCode = "GITHUB_CHOICE_PENDING"
            ),
            2
        )
        assertTrue(IssueReportIncidents.pending.value.isEmpty())

        recorder.finishTool(event.eventId, ToolResultEnvelope.error(event.callId, "TOOL_FAILURE", "The decision could not be saved", dispatched = false), 3)
        val incident = IssueReportIncidents.pending.value.single()
        assertEquals("TOOL_FAILURE", incident.errorCode)
        assertEquals("TOOL_RESULT", incident.context?.source)
        assertEquals(false, incident.context?.dispatched)
    }

    @Test fun aLocallyRejectedGitHubRequestKeepsItsCanonicalPolicyCodeThroughRecording() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val accounting = dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting(record = {})
        withContext(accounting) {
            val request = dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting.started(false)
            dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting.locallyRejected(request)
        }
        val result = accounting.finish(ToolResultEnvelope.error("privacy-call", "PRIVACY_SERVICE_UNVERIFIED", "private rejected request"), false)
        val normalized = dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.receipt(result, "github_read")
        assertEquals(JsonPrimitive(1), normalized.executionReceipt?.get("local_request_rejections"))
        val report = record("github_read", normalized)
        assertEquals("PRIVACY_SERVICE_UNVERIFIED", report.errorCode)
        assertEquals(false, report.context?.dispatched)
        assertEquals(false, report.context?.requiresReconciliation)
        assertEquals("tool_precheck", report.context?.stage)
        assertEquals(JsonPrimitive("PRIVACY_SERVICE_UNVERIFIED"), report.context?.diagnostics?.values?.get("tool_error_code"))
        assertNull(report.context?.httpStatus)
        assertFalse(report.toString().contains("private rejected request"))
    }

    @Test fun repeatedArgumentPrechecksKeepFirstAndLastCallWithoutCombiningTargetsOrGrants() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        suspend fun reject(id: String, path: String = "private-a", grant: String = "grant-a", attempt: Int = 1) {
            val event = ToolEvent("event-$id", "same-run", 1, id, grant, null, "github_read", "github_read", buildJsonObject { put("path", path) }.toString(), null, null, ToolEventStatus.RUNNING)
            val dao = mockk<AgentPersistenceDao>()
            coEvery { dao.getToolEventById(event.eventId) } returns event
            coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
            val receipt = buildJsonObject {
                put("failed_stage", "tool_precheck")
                put("argument_validation_call_id", id)
                put("reason_code", "TOOL_ARGUMENT_SCHEMA_REJECTED")
                put("argument_repair_attempt", attempt)
                put("argument_repair_exhausted", attempt >= 2)
            }
            val result = ToolResultEnvelope.error(id, "INVALID_ARGUMENTS", "Invalid arguments").copy(dispatched = false, executionReceipt = receipt)
            ToolEventRecorder(dao, mockk()).finishTool(event.eventId, result, 2L)
        }
        reject("one")
        val original = IssueReportIncidents.pending.value.single()
        reject("two", attempt = 2)
        reject("two", attempt = 2)
        val grouped = IssueReportIncidents.pending.value.single()
        assertEquals(original.reportId, grouped.reportId)
        val fields = checkNotNull(grouped.context?.diagnostics).values
        assertEquals(JsonPrimitive(2), fields["precheck_rejection_count"])
        assertEquals(JsonPrimitive(2), fields["argument_repair_attempt"])
        val calls = kotlinx.serialization.json.JsonArray(listOf("one", "two").map { JsonPrimitive(IssueReportContext.identifierHash("call", it)) })
        assertEquals(calls, fields["precheck_call_hashes"])
        assertFalse("precheck_rejection_count" in checkNotNull(original.context?.diagnostics).values)
        assertFalse(grouped.toString().contains("private-a"))
        reject("other-target", path = "private-b")
        reject("other-grant", grant = "grant-b")
        assertEquals(3, IssueReportIncidents.pending.value.size)
    }

    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(ApplicationProvider.getApplicationContext<Context>())
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    @Test fun failedCompareReceiptKeepsItsRemoteReadStage() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val receipt = buildJsonObject {
            put("schema", "github.dispatch.v1")
            put("phase", "READ_FAILED")
            put("http_status", 422)
            put("mutation_requests", 0)
            put("requires_reconciliation", false)
        }
        val result = ToolResultEnvelope.error("compare-call", "GITHUB_VALIDATION_FAILED", "private detail", dispatched = true).copy(executionReceipt = receipt)
        val report = record("github_context", result, buildJsonObject { put("action", "changes") })
        assertEquals("tool_execution", report.context?.stage)
        assertEquals(422, report.context?.httpStatus)
        assertEquals(true, report.context?.dispatched)
        assertEquals(JsonPrimitive("READ_FAILED"), report.context?.diagnostics?.values?.get("tool_dispatch_phase"))
    }

    @Test fun combinedWorkPrecheckKeepsItsOwnBudgetDimension() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val result = ToolResultEnvelope.error("budget-check", "BUDGET_EXHAUSTED", "budget reached").copy(combinedWorkBudgetExhausted = true)
        val report = record("github_ci", result)
        assertEquals("TOOL_BUDGET_EXHAUSTED", report.errorCode)
        assertEquals(JsonPrimitive("COMBINED_WORK"), report.context?.diagnostics?.values?.get("tool_budget_kind"))
        assertEquals(JsonPrimitive("BEFORE_DISPATCH"), report.context?.diagnostics?.values?.get("tool_budget_phase"))
    }

    @Test fun unavailableToolAdmissionKeepsItsPublicCategory() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val result = AgentToolResult("absent-tool", ToolResultContent.Text("Private tool is unavailable"), isError = true, dispatched = false, errorCode = "TOOL_NOT_AVAILABLE")
        val report = record("web_search", result)
        assertEquals("TOOL_UNAVAILABLE", report.errorCode)
        assertEquals(false, report.context?.dispatched)
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
        assertEquals("TOOL_ARGUMENT_SCHEMA_REJECTED", report.context?.reasonCode)
        assertNull(report.context?.missingFields)
        assertNull(report.context?.invalidFields)
        assertFalse(report.approvedPayload().contains("private"))
    }

    @Test fun lastAdmittedCiConstraintStaysPrimaryBesideTheExhaustedCallSnapshot() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val backend = object : AgentTool {
            override val definition = AgentToolDefinition("github", "Fixture backend", buildJsonObject {})
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = error("Neither rejected call may reach the backend")
        }
        val native = GitHubOperationTool("github_ci", backend, "private-budget-report", "github_ci__selected")
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 32), initialCalls = 31)
        val tool = budget.bind(native)
        val arguments = buildJsonObject {
            put("action", "logs")
            put("job_id", 7)
            put("start_line", 20)
            put("end_line", 10)
        }
        val rejected = tool.execute("private-last-admitted", arguments)
        assertEquals("INVALID_ARGUMENTS", rejected.errorCode)
        assertTrue(rejected.toolCallBudgetExhausted)
        assertFalse(rejected.dispatched)
        val report = record(native.definition.name, rejected, arguments)
        val values = requireNotNull(report.context?.diagnostics).values
        assertEquals("INVALID_ARGUMENTS", report.errorCode)
        assertEquals(false, report.context?.dispatched)
        assertEquals(false, report.context?.requiresReconciliation)
        assertEquals(JsonPrimitive("TOOL_ARGUMENT_CONSTRAINT_REJECTED"), values["tool_reason_code"])
        assertEquals(JsonPrimitive("INVALID_ARGUMENTS"), values["tool_error_code"])
        assertEquals(JsonPrimitive("CALLS"), values["tool_budget_kind"])
        assertEquals(JsonPrimitive("BEFORE_DISPATCH"), values["tool_budget_phase"])
        assertEquals(JsonPrimitive(32), values["tool_budget_calls_used"])
        assertEquals(JsonPrimitive(32), values["tool_budget_calls_limit"])
        assertFalse(report.approvedPayload().contains("private"))

        val corrected = JsonObject(arguments + ("end_line" to JsonPrimitive(30)))
        val denied = tool.execute("private-denied", corrected)
        val deniedReport = record(native.definition.name, denied, corrected)
        assertEquals("BUDGET_EXHAUSTED", denied.errorCode)
        assertFalse(denied.dispatched)
        assertEquals("TOOL_BUDGET_EXHAUSTED", deniedReport.errorCode)
        assertEquals(JsonPrimitive("BUDGET_EXHAUSTED"), deniedReport.context?.diagnostics?.values?.get("tool_reason_code"))
        assertEquals(32, budget.usedCalls())
        assertFalse(deniedReport.approvedPayload().contains("private"))
    }

    @Test fun recognizedAuthorityAndUnknownOutcomeCausesAlsoPrecedeAnExhaustedBudgetSnapshot() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        for ((code, expected) in listOf("SAVED_VIEW_AUTHORIZATION_CHANGED" to "GITHUB_AUTHORIZATION_UNVERIFIED", "DISPATCH_OUTCOME_UNKNOWN" to "DISPATCH_OUTCOME_UNKNOWN")) {
            val result = ToolResultEnvelope.error("private-$code", code, "private reason").copy(
                toolCallBudgetExhausted = true,
                toolCallBudgetUsed = 32,
                toolCallBudgetLimit = 32
            )
            val report = record("github_ci", result)
            assertEquals(expected, report.errorCode)
            assertEquals(JsonPrimitive("CALLS"), report.context?.diagnostics?.values?.get("tool_budget_kind"))
            if (code == "DISPATCH_OUTCOME_UNKNOWN") assertEquals(true, report.context?.requiresReconciliation)
            assertFalse(report.approvedPayload().contains("private"))
        }
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

    @Test fun phoneReadDeadlineKeepsItsExecutionStageThroughRecordingAndPayloadRestore() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (action in listOf("index", "query")) {
            for (observedRead in listOf(false, true)) {
                val arguments = buildJsonObject {
                    put("action", action)
                    if (action == "index") put("map_source", "phone")
                    put("query", "private source query")
                }
                val result = phoneDeadline("private-$action-$observedRead", arguments, observedRead)
                val receipt = requireNotNull(result.executionReceipt)
                val originalReceipt = receipt.toString()
                assertEquals("READ_DEADLINE", result.errorCode)
                assertEquals(observedRead, result.dispatched)
                assertEquals(JsonPrimitive("phone_read_deadline"), receipt["failed_stage"])
                assertEquals(JsonPrimitive("CANCELLED_LOCALLY"), receipt["phase"])
                for (key in listOf("requires_reconciliation", "index_outcome_unknown", "current_index_outcome_unknown", "replay_safe", "request_replay_allowed")) {
                    assertEquals(key, JsonPrimitive(false), receipt[key])
                }
                val report = record("repository_graph", result, arguments)
                val context = requireNotNull(report.context)
                val values = requireNotNull(context.diagnostics).values
                assertEquals("READ_DEADLINE", report.errorCode)
                assertEquals("tool_execution", context.stage)
                assertEquals(observedRead, context.dispatched)
                assertEquals(if (observedRead) 200 else null, context.httpStatus)
                assertEquals(false, context.retryable)
                assertEquals(false, context.requiresReconciliation)
                assertEquals(JsonPrimitive("tool_execution"), values["incident_phase"])
                assertEquals(JsonPrimitive("READ_DEADLINE"), values["causal_failure_code"])
                assertEquals(JsonPrimitive("READ_DEADLINE"), values["tool_error_code"])
                assertEquals(JsonPrimitive("CANCELLED_LOCALLY"), values["tool_dispatch_phase"])
                assertEquals(if (action == "index") JsonPrimitive(action) else null, values["tool_action"])
                val payload = report.approvedPayload()
                val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), payload)
                assertEquals(context.stage, restored.context?.stage)
                assertEquals(context.diagnostics, restored.context?.diagnostics)
                assertFalse(payload.contains("private"))
                assertFalse(payload.contains("phone_read_deadline"))
                assertTrue(payload.encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
                assertEquals(originalReceipt, requireNotNull(result.executionReceipt).toString())
            }
        }
    }

    @Test fun unboundPhoneReadDeadlineReceiptsKeepThePublicStageUnavailable() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val arguments = buildJsonObject {
            put("action", "index")
            put("map_source", "phone")
        }
        val result = phoneDeadline("private-bound-deadline", arguments)
        val receipt = requireNotNull(result.executionReceipt)
        val required = listOf("schema", "call_id", "error_code", "action", "map_source", "failed_stage", "phase", "dispatched", "retryable", "local_work_stopped", "requires_reconciliation", "index_outcome_unknown", "current_index_outcome_unknown", "replay_safe", "request_replay_allowed", "authorization_recheck_required", "mutation_requests", "accepted_mutations", "rejected_mutations", "unknown_mutations")
        val missing = required.map { key -> key to result.copy(executionReceipt = JsonObject(receipt - key)) }
        val replaced = listOf(
            "schema" to JsonPrimitive("foreign.receipt.v1"),
            "call_id" to JsonPrimitive("another-call"),
            "error_code" to JsonPrimitive("APP_ERROR"),
            "action" to JsonPrimitive("query"),
            "map_source" to JsonPrimitive("gateway"),
            "failed_stage" to JsonPrimitive("private-stage"),
            "phase" to JsonPrimitive("FAILED"),
            "phase" to JsonPrimitive("CANCELLED_LOCALLY private"),
            "dispatched" to JsonPrimitive(true),
            "retryable" to JsonPrimitive(true),
            "local_work_stopped" to JsonPrimitive(false),
            "requires_reconciliation" to JsonPrimitive(true),
            "request_replay_allowed" to JsonPrimitive(true),
            "unknown_mutations" to JsonPrimitive(1)
        ).map { (key, value) -> "$key=$value" to result.copy(executionReceipt = JsonObject(receipt + (key to value))) }
        val event = ToolEvent("event", "private-run", 1, result.callId, null, null, "repository_graph", "repository_graph", arguments.toString(), null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        for ((label, unbound) in missing + replaced) {
            assertFalse(label, "failed_stage" in ToolReportDiagnostics.fields(event, unbound))
        }
        val recordedRejections = missing.filter { it.first in setOf("phase", "local_work_stopped") } +
            replaced.filter { it.first.startsWith("call_id=") || it.first.startsWith("action=") || it.first.startsWith("phase=") || it.first.startsWith("schema=") }
        for ((label, unbound) in recordedRejections) {
            val report = record("repository_graph", unbound, arguments)
            assertNull(label, report.context?.stage)
            assertEquals(label, JsonPrimitive("unknown"), report.context?.diagnostics?.values?.get("incident_phase"))
            assertFalse(label, report.approvedPayload().contains("private"))
        }
        for ((operation, unboundArguments) in listOf(
            "web_search" to arguments,
            "repository_graph" to buildJsonObject {
                put("action", "query")
                put("map_source", "phone")
            },
            "repository_graph" to buildJsonObject {
                put("action", "index")
                put("map_source", "gateway")
            }
        )) {
            val report = record(operation, result, unboundArguments)
            assertNull(report.context?.stage)
            assertEquals(JsonPrimitive("unknown"), report.context?.diagnostics?.values?.get("incident_phase"))
        }
        for (unbound in listOf(event.copy(callId = "another-call"), event.copy(arguments = "private malformed arguments"), null)) {
            assertFalse("failed_stage" in ToolReportDiagnostics.fields(unbound, result))
        }
        for (unbound in listOf(result.copy(isError = false), result.copy(dispatched = true), result.copy(errorCode = "APP_ERROR"), result.copy(executionReceipt = null))) {
            assertFalse("failed_stage" in ToolReportDiagnostics.fields(event, unbound))
        }
    }

    @Test fun phoneReadDeadlineRecordingStillHonorsDisabledDiagnosticsAndPrivacy() = runBlocking {
        val arguments = buildJsonObject {
            put("action", "index")
            put("map_source", "phone")
        }
        try {
            for ((enabled, privateMode) in listOf(false to false, true to true)) {
                LocalDiagnosticsPolicy.setEnabled(enabled)
                IssueReportIncidents.privacyChanged(privateMode)
                val before = IssueReportIncidents.pending.value
                val result = phoneDeadline("private-deadline-$enabled-$privateMode", arguments)
                val event = ToolEvent("event-${result.callId}", "private-run", 1, result.callId, null, null, "repository_graph", "repository_graph", arguments.toString(), null, null, ToolEventStatus.RUNNING, startedAt = 1L)
                val dao = mockk<AgentPersistenceDao>()
                coEvery { dao.getToolEventById(event.eventId) } returns event
                coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
                ToolEventRecorder(dao, mockk()).finishTool(event.eventId, result, 2L)
                assertEquals(before, IssueReportIncidents.pending.value)
            }
        } finally {
            IssueReportIncidents.privacyChanged(false)
            LocalDiagnosticsPolicy.setEnabled(false)
        }
    }

    private suspend fun phoneDeadline(callId: String, arguments: JsonObject, observedRead: Boolean = false): AgentToolResult {
        val tool = RepositoryGraphTool("repository_graph") {
            if (observedRead) {
                val requestId = GitHubDispatchAccounting.started(false, "/repos/private-owner/private-repo/contents/private-path")
                GitHubDispatchAccounting.responded(false, 200, requestId)
            }
            throw GatewayGraphOperationException(
                GatewayGraphResultException("READ_DEADLINE", "private deadline detail", "READ_PHONE_INDEX_STATUS"),
                "private-revision",
                "private repository",
                "READ_PHONE_INDEX_STATUS",
                false
            )
        }
        return tool.execute(callId, arguments)
    }

    private suspend fun record(operation: String, result: AgentToolResult, arguments: JsonObject = buildJsonObject {}): IssueReport {
        IssueReportIncidents.clearRecent()
        val previousReportIds = IssueReportIncidents.pending.value.map { it.reportId }.toSet()
        val event = ToolEvent("event-${result.callId}", "private-run", 1, result.callId, null, null, operation, operation, arguments.toString(), null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val dao = mockk<AgentPersistenceDao>()
        coEvery { dao.getToolEventById(event.eventId) } returns event
        coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ToolEventRecorder(dao, mockk()).finishTool(event.eventId, result, 2L)
        return IssueReportIncidents.pending.value.single { it.reportId !in previousReportIds }
    }
}
