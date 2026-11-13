package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportCodes
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext

/** Voice observes its own phase and an explicit owning run, never infers a provider error from reply prose. */
internal object VoiceReplyFailureDiagnostics {
    fun fields(runId: String, phase: VoicePhase, failed: Boolean, failure: ProfileFailureRecord?, terminalStatus: String?): Map<String, Any?> = buildMap {
        put("diagnostic_area", "VOICE")
        put("run_id", runId)
        put("voice_phase", phase.name)
        put("operation", null)
        put("failed_stage", if (phase == VoicePhase.THINKING) "voice_response_wait" else "voice_reply")
        put("voice_reply_failed", failed)
        put("voice_reply_terminal_status", terminalStatus)
        put(
            "voice_reply_failure_evidence",
            when {
                failure?.invocationId != null -> "TYPED_INVOCATION_REFERENCE"
                failure != null -> "CLASSIFICATION_ONLY"
                else -> "MISSING_FAILURE_RECORD"
            }
        )
        if (failure != null) {
            put("voice_chat_failure_code", IssueReportCodes.known(failure.category))
            put("voice_chat_failure_code_hash", IssueReportContext.identifierHash("voice-chat-failure", failure.category))
            put("voice_chat_failure_phase", failure.phase)
            // Invocation identity comes from typed failure metadata. Classification
            // alone grants no dispatch, HTTP status, remote-acceptance or retry fact.
            failure.invocationId?.let { put("invocation_id", it) }
        }
    }
}
