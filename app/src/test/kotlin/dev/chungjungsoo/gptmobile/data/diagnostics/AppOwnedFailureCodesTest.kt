package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.voice.VoiceAdmissionFailureDiagnostics
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AppOwnedFailureCodesTest {
    private val knownCodes = listOf(
        "COMBINED_APPEND_REMOTE_COMPLETION_UNKNOWN",
        "GATEWAY_CONVERSATION_READ_ONLY",
        "TOOL_BATCH_REJECTED",
        "TOOL_BATCH_OVERSIZE"
    )

    @Test fun applicationOwnedRejectionsKeepTheirExactPublicIdentity() {
        for (code in knownCodes) {
            assertEquals(code, IssueReportCodes.known(code))
            assertEquals(code, IssueReportCodes.select(code, "CHAT_FAILED"))
            for (field in listOf("errorCode", "error_code", "failureCode", "failure_code")) {
                assertEquals(code, IssueReportCodes.fromFields(mapOf(field to code)))
            }
        }
    }

    @Test fun arbitraryLookalikesAndProseRemainOutsideTheClosedVocabulary() {
        for (code in knownCodes) {
            for (untrusted in listOf("${code}_PRIVATE_HOST", "private-token:$code", "$code: private prompt", code.lowercase())) {
                assertNull(IssueReportCodes.known(untrusted))
                assertEquals("CHAT_FAILED", IssueReportCodes.select(untrusted, "CHAT_FAILED"))
                assertNull(IssueReportCodes.fromFields(mapOf("error_code" to untrusted)))
            }
            assertNull(IssueReportCodes.fromFields(mapOf("message" to code, "event" to code)))
        }
    }

    @Test fun voiceReadOnlyAdmissionRetainsCauseWithoutInventingDispatchOrRecoveryFacts() {
        val fields = VoiceAdmissionFailureDiagnostics.rejected("GATEWAY_CONVERSATION_READ_ONLY")
        assertEquals("GATEWAY_CONVERSATION_READ_ONLY", fields["voice_chat_failure_code"])
        assertEquals("message_admission", fields["voice_chat_failure_phase"])
        assertEquals("APPLICATION", fields["failure_origin"])
        for (unobserved in listOf("run_id", "invocation_id", "dispatched", "retryable", "remote_acceptance", "request_replay_allowed", "logical_continuation_allowed")) {
            assertFalse(fields.containsKey(unobserved))
        }
    }

    @Test fun privateAdmissionReasonCannotEnterVoiceCodeOrHashFields() {
        val fields = VoiceAdmissionFailureDiagnostics.rejected("GATEWAY_CONVERSATION_READ_ONLY_PRIVATE_TOKEN")
        assertFalse(fields.containsKey("voice_chat_failure_code"))
        assertFalse(fields.containsKey("voice_chat_failure_code_hash"))
        assertFalse(fields.toString().contains("PRIVATE_TOKEN"))
    }
}
