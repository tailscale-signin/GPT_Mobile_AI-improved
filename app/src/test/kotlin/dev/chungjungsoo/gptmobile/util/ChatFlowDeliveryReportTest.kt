package dev.chungjungsoo.gptmobile.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidents
import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.dto.ApiState
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayProgress
import dev.chungjungsoo.gptmobile.data.localruntime.LocalInferencePhase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
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
@Config(application = Application::class, sdk = [34])
class ChatFlowDeliveryReportTest {
    @Before
    fun setUp() {
        reset()
        LocalDiagnosticsPolicy.setEnabled(true)
    }

    @After
    fun reset() {
        IssueReportIncidents.initialize(ApplicationProvider.getApplicationContext())
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    @Test fun reasoningOnlyDoneCapturesOnlyObservedEventsWithoutPrivateText() = runBlocking {
        val thinking = "PRIVATE_THOUGHT é🙂 https://private.invalid/token"
        val runId = "PRIVATE_OWNING_RUN"
        val result = flowOf(
            ApiState.Thinking(thinking),
            ApiState.Success(""),
            ApiState.ToolCall(1),
            // Progress means these six collector event types, not tool completion or wire attempts.
            ApiState.ProgressCheckpoint("PRIVATE_CHECKPOINT"),
            ApiState.ActivitySummary("PRIVATE_SUMMARY"),
            ApiState.Notice("PRIVATE_NOTICE"),
            ApiState.PhaseChanged(LocalInferencePhase.GENERATING),
            ApiState.GatewayProgressChanged(GatewayProgress(message = "PRIVATE_GATEWAY", jobId = "PRIVATE_JOB")),
            ApiState.DelegationText("PRIVATE_INVOCATION", "PRIVATE_PROFILE", "PRIVATE_DELEGATION", true),
            ApiState.Done
        ).collectApiStateUpdates(onUpdate = { _, _, _ -> }, diagnosticRunId = runId, diagnosticProvider = "OPENROUTER")

        assertEquals(ApiStateFlowOutcome.Failed("The model finished without a visible answer. Please retry or choose another model."), result)
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("NO_VISIBLE_ANSWER", report.errorCode)
        assertNull(report.errorType)
        assertTrue(report.frames.isEmpty())
        val context = requireNotNull(report.context)
        assertEquals("delivery", context.stage)
        assertEquals("OPENROUTER", context.provider)
        assertEquals(IssueReportContext.identifierHash("run", runId), context.runIdHash)
        for (unknown in listOf(context.invocationIdHash, context.callIdHash, context.jobIdHash, context.exceptionFingerprint, context.dispatched, context.retryable, context.httpStatus, context.remoteAcceptance)) assertNull(unknown)
        val values = requireNotNull(context.diagnostics).values
        assertEquals(JsonPrimitive("CHAT_FLOW_EMPTY_ANSWER"), values["incident_capture_site"])
        assertEquals(JsonPrimitive("CAPTURE_BOUNDARY_ONLY"), values["incident_phase_provenance"])
        assertEquals(JsonPrimitive("DONE"), values["chat_delivery_terminal"])
        assertEquals(JsonPrimitive("EMPTY_CONTENT"), values["chat_delivery_answer_state"])
        assertEquals(JsonPrimitive(0), values["chat_delivery_content_bytes"])
        assertEquals(JsonPrimitive(thinking.toByteArray(Charsets.UTF_8).size), values["chat_delivery_thinking_bytes"])
        assertEquals(JsonPrimitive(1), values["chat_delivery_success_events"])
        assertEquals(JsonPrimitive(1), values["chat_delivery_tool_events"])
        assertEquals(JsonPrimitive(6), values["chat_delivery_progress_events"])
        assertEquals(JsonPrimitive(0), values["chat_delivery_github_rounds"])
        assertEquals(JsonPrimitive(false), values["chat_delivery_counts_capped"])
        val requiredFeatures = requireNotNull(context.diagnostics).requiredServiceFeatures()
        assertTrue("CHAT_DELIVERY_EVIDENCE_V1" in requiredFeatures)
        assertFalse("CAPTURE_BOUNDARY_EVIDENCE_V1" in requiredFeatures)
        val approved = report.approvedPayload()
        assertFalse(approved.contains("PRIVATE_"))
        assertFalse(approved.contains("private.invalid"))
        assertEquals(approved, report.approvedPayload())
    }

    @Test fun nonAnswerContentIsDistinctFromAnEmptyBuffer() = runBlocking {
        val content = "<think>PRIVATE_HIDDEN_ANSWER</think>"
        val thinking = "é🙂\uD800"
        val result = flowOf(ApiState.Success(content), ApiState.Thinking(thinking), ApiState.Done)
            .collectApiStateUpdates(onUpdate = { _, _, _ -> })
        assertTrue(result is ApiStateFlowOutcome.Failed)
        val report = IssueReportIncidents.pending.value.single()
        val values = requireNotNull(report.context?.diagnostics).values
        assertEquals(JsonPrimitive("NO_ANSWER_CONTENT"), values["chat_delivery_answer_state"])
        assertEquals(JsonPrimitive(content.toByteArray(Charsets.UTF_8).size), values["chat_delivery_content_bytes"])
        assertEquals(JsonPrimitive(thinking.toByteArray(Charsets.UTF_8).size), values["chat_delivery_thinking_bytes"])
        assertEquals(JsonPrimitive(1), values["chat_delivery_success_events"])
        assertFalse(report.approvedPayload().contains("PRIVATE_"))
    }

    @Test fun githubPendingAnswerKeepsItsCurrentContentAndReportsTheActualAwaitingState() = runBlocking {
        val content = "PRIVATE earlier answer from this collection"
        var visible = ""
        val result = flowOf(ApiState.Success(content), ApiState.GitHubRoundStarted, ApiState.ProgressCheckpoint("PRIVATE_PROGRESS"), ApiState.Done)
            .collectApiStateUpdates(onUpdate = { text, _, _ -> visible = text })
        assertTrue(result is ApiStateFlowOutcome.Failed)
        assertEquals(content, visible)
        val report = IssueReportIncidents.pending.value.single()
        val values = requireNotNull(report.context?.diagnostics).values
        assertEquals(JsonPrimitive("GITHUB_ANSWER_PENDING"), values["chat_delivery_answer_state"])
        assertEquals(JsonPrimitive(content.toByteArray(Charsets.UTF_8).size), values["chat_delivery_content_bytes"])
        assertEquals(JsonPrimitive(1), values["chat_delivery_github_rounds"])
        assertFalse(report.approvedPayload().contains("PRIVATE"))
    }

    @Test fun eachCollectionStartsFreshAndDoesNotBorrowRestoredTimelineOrProviderUsage() = runBlocking {
        flowOf(ApiState.Thinking("PRIVATE_FIRST"), ApiState.ToolCall(7), ApiState.ProgressCheckpoint("PRIVATE_PROGRESS"), ApiState.Done)
            .collectApiStateUpdates(onUpdate = { _, _, _ -> }, diagnosticRunId = "PRIVATE_FIRST_RUN", diagnosticProvider = "OPENROUTER")
        val restored = listOf(
            AssistantTimelineItem(AssistantTimelineItemType.TEXT, "PRIVATE_RESTORED_ANSWER"),
            AssistantTimelineItem(AssistantTimelineItemType.THINKING, "PRIVATE_RESTORED_THINKING"),
            AssistantTimelineItem(AssistantTimelineItemType.TOOL, toolSequence = 99),
            AssistantTimelineItem(AssistantTimelineItemType.NOTICE, "PRIVATE_RESTORED_PROGRESS", progressCheckpoint = true)
        )
        val result = flowOf(ApiState.TokenUsage(80_000, 40_000, 120_000), ApiState.Done)
            .collectApiStateUpdates(onUpdate = { _, _, _ -> }, restoredTimeline = restored)
        assertTrue(result is ApiStateFlowOutcome.Failed)
        val reports = IssueReportIncidents.pending.value
        assertEquals(2, reports.size)
        val report = reports.single { it.context?.runIdHash == null }
        assertNull(report.context?.provider)
        val values = requireNotNull(report.context?.diagnostics).values
        for (name in listOf("content_bytes", "thinking_bytes", "success_events", "tool_events", "progress_events", "github_rounds")) {
            assertEquals(name, JsonPrimitive(0), values["chat_delivery_$name"])
        }
        assertEquals(JsonPrimitive("EMPTY_CONTENT"), values["chat_delivery_answer_state"])
        assertEquals(JsonPrimitive(false), values["chat_delivery_counts_capped"])
        assertFalse(report.approvedPayload().contains("PRIVATE_"))
    }

    @Test fun largeUtf8CountsAreCappedWithoutPublishingTheText() = runBlocking {
        val thinking = "€".repeat(ChatFlowDeliveryObservation.MAX_COUNT / 3 + 1)
        val result = flowOf(ApiState.Thinking(thinking), ApiState.Done).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        assertTrue(result is ApiStateFlowOutcome.Failed)
        val report = IssueReportIncidents.pending.value.single()
        val values = requireNotNull(report.context?.diagnostics).values
        assertEquals(JsonPrimitive(16_000_000), values["chat_delivery_thinking_bytes"])
        assertEquals(JsonPrimitive(true), values["chat_delivery_counts_capped"])
        val approved = report.approvedPayload()
        assertFalse(approved.contains("€"))
        assertTrue(approved.toByteArray(Charsets.UTF_8).size < 8192)
    }

    @Test fun aTypedErrorRemainsTheSingleCanonicalReport() = runBlocking {
        val originalRun = "PRIVATE_TYPED_RUN"
        val error = ApiState.Error(
            "PRIVATE_TYPED_ERROR",
            errorCode = "NETWORK_TIMEOUT",
            failureDetails = buildJsonObject {
                put("failed_stage", "transport")
                put("provider_kind", "OPENROUTER")
                put("run_id", originalRun)
                put("dispatched", true)
                put("retryable", false)
            }
        )
        val result = flowOf(ApiState.Thinking("PRIVATE_THINKING"), ApiState.ToolCall(1), error, ApiState.Done)
            .collectApiStateUpdates(onUpdate = { _, _, _ -> }, diagnosticRunId = "PRIVATE_OTHER_RUN", diagnosticProvider = "GOOGLE")
        assertEquals(ApiStateFlowOutcome.Failed(error.message, errorCode = "NETWORK_TIMEOUT"), result)
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("NETWORK_TIMEOUT", report.errorCode)
        assertEquals("transport", report.context?.stage)
        assertEquals("OPENROUTER", report.context?.provider)
        assertEquals(IssueReportContext.identifierHash("run", originalRun), report.context?.runIdHash)
        assertFalse(report.context?.diagnostics?.values.orEmpty().keys.any { it.startsWith("chat_delivery_") })
        val approved = report.approvedPayload()
        assertFalse(approved.contains("CHAT_FLOW_EMPTY_ANSWER"))
        assertFalse(approved.contains("PRIVATE_"))
    }

    @Test fun incompleteCancellationAndAnsweredCollectionsDoNotProduceAnEmptyAnswerReport() = runBlocking {
        val incomplete = flowOf(ApiState.Thinking("PRIVATE_INCOMPLETE")).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        assertEquals(ApiStateFlowOutcome.Incomplete, incomplete)
        val cancelled = runCatching {
            flow {
                emit(ApiState.Thinking("PRIVATE_CANCELLED"))
                throw CancellationException("PRIVATE_CANCELLATION")
            }.collectApiStateUpdates(onUpdate = { _, _, _ -> })
        }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
        val answered = flowOf(ApiState.Success("PRIVATE_ANSWER"), ApiState.Done).collectApiStateUpdates(onUpdate = { _, _, _ -> })
        assertEquals(ApiStateFlowOutcome.Completed, answered)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())
    }

    @Test fun privateSessionsStillSuppressDeliveryDiagnostics() = runBlocking {
        IssueReportIncidents.privacyChanged(true)
        val result = flowOf(ApiState.Done).collectApiStateUpdates(onUpdate = { _, _, _ -> }, diagnosticRunId = "PRIVATE_RUN")
        assertTrue(result is ApiStateFlowOutcome.Failed)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())
    }
}
