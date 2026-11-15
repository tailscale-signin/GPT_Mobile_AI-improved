package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportCodes
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext

/** Admission observations do not establish a run, provider dispatch, or a terminal reply. */
internal object VoiceAdmissionFailureDiagnostics {
    // Explicit rejection outcomes emitted by SendAttemptTrace or voice admission restoration/queue observation.
    // Never fingerprint arbitrary notice text or a reason supplied by another request.
    private val rejectionReasons = setOf(
        "VALIDATION_REJECTED", "GATEWAY_QUEUE_SELECTION_BLOCKED", "PROFILE_UNAVAILABLE",
        "REASONING_CAPABILITY_CHECK_PENDING", "COMPOSER_CHANGED_DURING_REASONING_CHECK",
        "REASONING_CAPABILITY_REJECTED", "REASONING_CAPABILITY_CHECK_CANCELLED", "REASONING_CAPABILITY_CHECK_UNAVAILABLE",
        "GATEWAY_ADMISSION_CHECK_PENDING", "GATEWAY_CONVERSATION_READ_ONLY", "GATEWAY_ADMISSION_UNCONFIRMED",
        "COMPOSER_CHANGED_DURING_GATEWAY_CHECK", "GATEWAY_ADMISSION_CHECK_CANCELLED", "GATEWAY_ADMISSION_CHECK_UNAVAILABLE",
        "QUEUE_SAVE_IN_PROGRESS", "CONVERSATION_STARTING", "QUEUE_SAVE_CANCELLED", "QUEUE_SAVE_FAILED",
        "PREPARATION_REJECTED", "PERSISTENCE_FAILED", "PREPARATION_CANCELLED", "PREPARATION_FAILED",
        "ADMISSION_INTERRUPTED", "QUEUED_TURN_REMOVED"
    )

    fun submission(): Map<String, Any?> = mapOf("failed_stage" to "message_admission", "failure_origin" to "APPLICATION")

    fun rejected(reason: String?): Map<String, Any?> = submission() + buildMap {
        put("voice_chat_failure_phase", "message_admission")
        val code = IssueReportCodes.known(reason)
        if (code != null) put("voice_chat_failure_code", code)
        if (code != null || reason in rejectionReasons) {
            put("voice_chat_failure_code_hash", IssueReportContext.identifierHash("voice-chat-failure", reason))
        }
    }
}
