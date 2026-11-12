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
