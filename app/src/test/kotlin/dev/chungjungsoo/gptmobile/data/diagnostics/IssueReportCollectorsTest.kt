package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.database.dao.AgentPersistenceDao
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.openrouter.PrivacyAdmissionException
import dev.chungjungsoo.gptmobile.data.repository.ToolEventRecorder
import dev.chungjungsoo.gptmobile.util.collectApiStateUpdates
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportCollectorsTest {
    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(ApplicationProvider.getApplicationContext<Context>())
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    @Test fun reviewComparisonAndApprovalSurviveTheActualCollectorWithoutBorrowingGenerationFacts() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val failures = listOf(
            Triple("REVIEW_INPUT_REQUIRED", "review_comparison", "SELECT_REVIEW_BASELINE"),
            Triple("GITHUB_REVIEW_COMPARISON_REQUIRED", "review_comparison", "RESTORE_REVIEW_COMPARISON_EVIDENCE"),
            Triple("WAITING_FOR_APPROVAL", "approval", null)
        )
        for ((index, failure) in failures.withIndex()) {
            val (code, stage, action) = failure
            val runId = "owned-review-run-$index"
            var retainedStage: String? = null
            flowOf(
                ApiState.Error(
                    "private review details https://private.example",
                    errorCode = code,
                    taskOutcome = "BLOCKED",
                    failureDetails = buildJsonObject {
                        put("run_id", runId)
                        put("provider_kind", "OPENROUTER")
                        put("failed_stage", stage)
                        put("dispatched", false)
                        put("retryable", false)
                        action?.let { put("next_action", it) }
                        put("private_detail", "private comparison paths")
                    }
                )
            ).collectApiStateUpdates(onUpdate = { _, _, timeline -> retainedStage = timeline.lastOrNull { it.profileFailure != null }?.profileFailure?.phase })
            val report = IssueReportIncidents.pending.value.last()
            val context = requireNotNull(report.context)
            assertEquals(2, context.schemaVersion)
            assertEquals(stage, context.stage)
            assertEquals(stage, retainedStage)
            assertEquals(action, context.recoveryAction)
            assertEquals("OPENROUTER", context.provider)
            assertEquals(false, context.dispatched)
            assertEquals(false, context.retryable)
            assertEquals(null, context.remoteAcceptance)
            assertEquals(null, context.invocationIdHash)
            assertEquals(IssueReportContext.identifierHash("run", runId), context.runIdHash)
            assertFalse(report.approvedPayload().contains("private"))
            assertFalse(report.approvedPayload().contains(runId))
        }
    }

    @Test fun typedPrivacyAdmissionSurvivesActualChatCollectorWithoutPublishingPrivateText() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val runId = "exact-admission-run"
        val failure = PrivacyAdmissionException("private model/account and https://private.example").asApiError(runId)
        flowOf(failure).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("PRIVACY_CONFIGURATION", report.errorCode)
        assertEquals("CHAT_FAILURE", report.context?.source)
        assertEquals("OPENROUTER", report.context?.provider)
        assertEquals("provider_admission", report.context?.stage)
        assertEquals(false, report.context?.dispatched)
        assertEquals("NO", report.context?.remoteAcceptance)
        assertEquals(false, report.context?.retryable)
        assertEquals(false, report.context?.resumeAllowed)
        assertEquals(false, report.context?.requiresReconciliation)
        assertEquals("RETRY_AFTER_CONFIGURATION_CHANGE", report.context?.recoveryAction)
        assertEquals(IssueReportContext.identifierHash("run", runId), report.context?.runIdHash)
        assertEquals(null, report.context?.invocationIdHash)
        assertFalse(report.approvedPayload().contains("private"))
        assertFalse(report.approvedPayload().contains(runId))
    }

    @Test fun nativeReaderPaymentReceiptSurvivesActualToolRecorderWithoutPublishingSourceContent() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val event = ToolEvent("reader-event", "reader-run", 1, "reader-call", null, null, "read_url", "read_url", "{}", null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val dao = mockk<AgentPersistenceDao>()
        coEvery { dao.getToolEventById(event.eventId) } returns event
        coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ToolEventRecorder(dao, mockk()).finishTool(
            event.eventId,
            AgentToolResult(
                event.callId,
                ToolResultContent.Text("private upstream response https://private.example"),
                true,
                errorCode = "SOURCE_PAYMENT_REQUIRED",
                dispatched = true,
                executionReceipt = buildJsonObject {
                    put("phase", "RETRIEVAL")
                    put("httpStatus", 402)
                    put("requestSent", true)
                    put("retryable", false)
                    put("requires_reconciliation", false)
                    put("private_url", "https://private.example")
                }
            ),
            2L
        )
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("SOURCE_PAYMENT_REQUIRED", report.errorCode)
        assertEquals("TOOL_RESULT", report.context?.source)
        assertEquals("read_url", report.context?.operation)
        assertEquals("retrieval", report.context?.stage)
        assertEquals(402, report.context?.httpStatus)
        assertEquals(true, report.context?.dispatched)
        assertEquals(false, report.context?.retryable)
        assertEquals(false, report.context?.requiresReconciliation)
        assertEquals(IssueReportContext.identifierHash("run", event.runId), report.context?.runIdHash)
        assertEquals(IssueReportContext.identifierHash("call", event.callId), report.context?.callIdHash)
        assertFalse(report.approvedPayload().contains("private"))
        assertFalse(report.approvedPayload().contains(event.runId))
        assertFalse(report.approvedPayload().contains(event.callId))
    }

    @Test fun chatPublicationUsesTheFailedInvocationAndDoesNotPublishItsMessage() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        flowOf(
            ApiState.Error(
                "private response and URL https://private.example",
                errorCode = "CONVERSATION_CONFLICT",
                failureDetails = buildJsonObject {
                    put("run_id", "exact-local-run")
                    put("invocation_id", "untrusted-invocation")
                    put("active_job_id", "active-other-job")
                    put("failed_stage", "conversation_admission")
                    put("http_status", 409)
                },
                invocationId = "exact-failed-invocation"
            )
        ).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        val report = IssueReportIncidents.pending.value.single()
        assertEquals(IssueReportContext.identifierHash("run", "exact-local-run"), report.context?.runIdHash)
        assertEquals(IssueReportContext.identifierHash("invocation", "exact-failed-invocation"), report.context?.invocationIdHash)
        assertEquals(IssueReportContext.identifierHash("job", "active-other-job"), report.context?.activeJobIdHash)
        assertEquals(null, report.context?.jobIdHash)
        assertEquals(409, report.context?.httpStatus)
        assertFalse(report.approvedPayload().contains("private"))
    }

    @Test fun incompleteTaskPublicationRetainsClosedAcceptanceFactsWithoutPrivateCriteria() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        flowOf(
            ApiState.Error(
                "private task and criterion text",
                errorCode = "GITHUB_TASK_INCOMPLETE",
                taskOutcome = "PARTIAL",
                failureDetails = buildJsonObject {
                    put("run_id", "exact-incomplete-run")
                    put("provider_kind", "OPENROUTER")
                    put("failed_stage", "acceptance_verification")
                    put("stop_reason", "UNVERIFIED_ACCEPTANCE_CRITERIA")
                    put("recovery_action", "RESUME_UNFINISHED_WORK")
                    put("required_criteria", 5)
                    put("verified_criteria", 4)
                    put("remaining_criteria", 1)
                    put("acceptance_stop", buildJsonObject { put("detail", "private source and missing criterion") })
                }
            )
        ).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        val report = IssueReportIncidents.pending.value.single()
        val context = requireNotNull(report.context)
        assertEquals("GITHUB_TASK_INCOMPLETE", report.errorCode)
        assertEquals(2, context.schemaVersion)
        assertEquals("CHAT_FAILURE", context.source)
        assertEquals("OPENROUTER", context.provider)
        assertEquals("acceptance_verification", context.stage)
        assertEquals("UNVERIFIED_ACCEPTANCE_CRITERIA", context.reasonCode)
        assertEquals("RESUME_UNFINISHED_WORK", context.recoveryAction)
        assertEquals(5, context.requiredCriteria)
        assertEquals(4, context.verifiedCriteria)
        assertEquals(1, context.remainingCriteria)
        assertEquals(null, context.dispatched)
        assertEquals(null, context.retryable)
        assertEquals(null, context.invocationIdHash)
        assertEquals(IssueReportContext.identifierHash("run", "exact-incomplete-run"), context.runIdHash)
        assertFalse(report.approvedPayload().contains("private"))
        assertFalse(report.approvedPayload().contains("exact-incomplete-run"))
    }

    @Test fun persistedToolCompletionUsesLocalCallOwnershipAndTypedReceiptFacts() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val event = ToolEvent("event", "actual-run", 1, "actual-call", null, null, "github_read", "github_read__github_api", "{}", null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val dao = mockk<AgentPersistenceDao>()
        coEvery { dao.getToolEventById(event.eventId) } returns event
        coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        val recorder = ToolEventRecorder(dao, mockk())
        recorder.finishTool(
            event.eventId,
            AgentToolResult(
                event.callId,
                ToolResultContent.Text("private repository path /private/project"),
                true,
                errorCode = "GITHUB_CIRCUIT_OPEN",
                dispatched = false,
                executionReceipt = buildJsonObject {
                    put("runId", "unrelated-run-alias")
                    put("callId", "unrelated-call-alias")
                    put("http_status", 429)
                    put("retry_after_ms", 1000L)
                    put("private_url", "https://private.example")
                }
            ),
            2L
        )
        val report = IssueReportIncidents.pending.value.single()
        assertEquals(IssueReportContext.identifierHash("run", event.runId), report.context?.runIdHash)
        assertEquals("TOOL_RESULT", report.context?.source)
        assertEquals(IssueReportContext.identifierHash("call", event.callId), report.context?.callIdHash)
        assertEquals("github_read", report.context?.operation)
        assertEquals("tool_precheck", report.context?.stage)
        assertEquals(false, report.context?.dispatched)
        assertEquals(429, report.context?.httpStatus)
        assertEquals(1000L, report.context?.retryAfterMillis)
        assertEquals(null, report.context?.retryable)
        assertEquals(null, report.context?.requiresReconciliation)
        val payload = report.approvedPayload()
        assertFalse(payload.contains("private"))
        assertFalse(payload.contains("unrelated"))
    }

    @Test fun aGenericToolCodeCannotEraseRetainedUnknownOutcomeOrRetryEvidence() = runBlocking {
        LocalDiagnosticsPolicy.setEnabled(true)
        val event = ToolEvent("event", "actual-run", 1, "uncertain-call", null, null, "github_commit", "github_commit", "{}", null, null, ToolEventStatus.RUNNING, startedAt = 1L)
        val dao = mockk<AgentPersistenceDao>()
        coEvery { dao.getToolEventById(event.eventId) } returns event
        coEvery { dao.finishToolEvent(any(), any(), any(), any(), any(), any(), any(), any()) } returns 1
        ToolEventRecorder(dao, mockk()).finishTool(
            event.eventId,
            AgentToolResult(
                event.callId,
                ToolResultContent.Text("private result"),
                true,
                errorCode = "TOOL_FAILURE",
                dispatched = true,
                executionReceipt = buildJsonObject {
                    put("requires_reconciliation", true)
                    put("retryable", false)
                }
            ),
            2L
        )
        val context = IssueReportIncidents.pending.value.single().context!!
        assertEquals(true, context.requiresReconciliation)
        assertEquals(false, context.retryable)
        assertEquals(true, context.dispatched)
    }
}
