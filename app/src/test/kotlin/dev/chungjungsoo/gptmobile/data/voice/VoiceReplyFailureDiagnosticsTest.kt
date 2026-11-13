package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportDiagnostics
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule
import dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceReplyFailureDiagnosticsTest {
    @Test fun absentFailureRecordKeepsThinkingAndTerminalStateWithoutInventingDeliveryOrMicrophoneFault() {
        val fields = VoiceReplyFailureDiagnostics.fields("private-run", VoicePhase.THINKING, true, null, "FAILED")
        val context = context(fields)
        val values = requireNotNull(context.diagnostics).values
        assertEquals("voice_response_wait", context.stage)
        assertEquals(JsonPrimitive("THINKING"), values["voice_phase"])
        assertEquals(JsonPrimitive("FAILED"), values["voice_reply_terminal_status"])
        assertEquals(JsonPrimitive("MISSING_FAILURE_RECORD"), values["voice_reply_failure_evidence"])
        assertNull(context.operation)
        assertNull(context.httpStatus)
        assertNull(context.dispatched)
        assertNull(context.invocationIdHash)
        assertFalse(encoded(context).contains("private"))
    }

    @Test fun typedInvocationLinksTheOwningChatFailureButDoesNotCopyTransportOrRetryClaims() {
        val failure = ProfileFailureRecord("NETWORK_TIMEOUT", retryAllowed = true, dispatched = true, phase = "response_stream", invocationId = "private-invocation", requestReplayAllowed = true)
        val context = context(VoiceReplyFailureDiagnostics.fields("private-run", VoicePhase.THINKING, true, failure, "INTERRUPTED"))
        val values = requireNotNull(context.diagnostics).values
        assertEquals(IssueReportContext.identifierHash("invocation", "private-invocation"), context.invocationIdHash)
        assertEquals(JsonPrimitive("NETWORK_TIMEOUT"), values["voice_chat_failure_code"])
        assertEquals(JsonPrimitive("response_stream"), values["voice_chat_failure_phase"])
        assertEquals(JsonPrimitive("TYPED_INVOCATION_REFERENCE"), values["voice_reply_failure_evidence"])
        assertNull(context.dispatched)
        assertNull(context.retryable)
        assertNull(values["request_replay_allowed"])
        assertFalse(encoded(context).contains("private"))
        val untyped = context(VoiceReplyFailureDiagnostics.fields("private-run", VoicePhase.PAUSED, false, failure.copy(invocationId = null), "COMPLETED"))
        assertEquals(JsonPrimitive("CLASSIFICATION_ONLY"), untyped.diagnostics?.values?.get("voice_reply_failure_evidence"))
        assertEquals(JsonPrimitive(false), untyped.diagnostics?.values?.get("voice_reply_failed"))
    }

    @Test fun terminalCorrelationSurvivesCloseButCannotContaminateANewCapture() {
        var allowed = true
        val snapshots = mutableListOf<IssueReportDiagnostics>()
        val trace = VoiceDiagnosticTrace(now = { 1_000 }, wallClock = { 1_800_000_000_000 }, allowed = { allowed }, retainSnapshot = { snapshots += it })
        val first = trace.advance(1, reset = true)
        first.phase(VoicePhase.THINKING)
        first.replyFailure(VoiceReplyFailureDiagnostics.fields("private-first-run", VoicePhase.THINKING, true, null, "FAILED"))
        trace.advance(2).phase(VoicePhase.ERROR)
        trace.retain()
        assertEquals(JsonPrimitive("voice_response_wait"), snapshots.last().values["incident_phase"])
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("run", "private-first-run")), snapshots.last().values["run_id_hash"])
        val next = trace.advance(3, reset = true)
        next.phase(VoicePhase.LISTENING)
        first.replyFailure(VoiceReplyFailureDiagnostics.fields("private-stale-run", VoicePhase.THINKING, true, null, "FAILED"))
        assertNull(trace.incidentFields()["run_id_hash"])
        next.replyFailure(VoiceReplyFailureDiagnostics.fields("private-next-run", VoicePhase.LISTENING, true, null, "FAILED"))
        trace.close()
        assertEquals(JsonPrimitive(IssueReportContext.identifierHash("run", "private-next-run")), snapshots.last().values["run_id_hash"])
        assertFalse(snapshots.toString().contains("private"))
        allowed = false
        val privateTrace = VoiceDiagnosticTrace(now = { 1_000 }, wallClock = { 1_800_000_000_000 }, allowed = { allowed }, retainSnapshot = { snapshots += it })
        privateTrace.advance(1).replyFailure(VoiceReplyFailureDiagnostics.fields("private-hidden-run", VoicePhase.THINKING, true, null, "FAILED"))
        assertEquals(emptyMap<String, Any?>(), privateTrace.incidentFields())
        privateTrace.close()
    }

    private fun context(fields: Map<String, Any?>) = IssueReportContext.fromFields("VOICE_STATE", IssueReportIncidentCapsule.fields(ReportFeature.VOICE, "VOICE_STATE", "VOICE_SESSION_FAILED", fields), 1_800_000_000_000)
    private fun encoded(context: IssueReportContext) = IssueReport.json.encodeToString(IssueReportContext.serializer(), context)
}
