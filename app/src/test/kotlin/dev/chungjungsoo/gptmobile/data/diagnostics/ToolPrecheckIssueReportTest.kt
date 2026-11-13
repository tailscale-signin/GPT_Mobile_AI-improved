package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.repository.ToolEventRecorder
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
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
            assertEquals("TOOL_FAILURE", report.errorCode)
            assertEquals(code, context.reasonCode)
            assertEquals(5, context.schemaVersion)
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

    private suspend fun record(operation: String, result: AgentToolResult): IssueReport {
        val event = ToolEvent("event-${result.callId}", "private-run", 1, result.callId, null, null, operation, operation, "{}", null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val dao = mockk<AgentPersistenceDao>()
        coEvery { dao.getToolEventById(event.eventId) } returns event
        coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ToolEventRecorder(dao, mockk()).finishTool(event.eventId, result, 2L)
        return IssueReportIncidents.pending.value.last()
    }
}
