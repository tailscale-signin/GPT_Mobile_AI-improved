package dev.chungjungsoo.gptmobile.data.network.error

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PreparationErrorClassificationTest {
    @Test fun staleAuthorizationRequiresFreshPreparationAndNeverBlindReplay() {
        val cases = mapOf(
            "MEMORY_STALE" to ErrorCategory.MEMORY_STALE,
            "DISPATCH_POLICY_CHANGED" to ErrorCategory.POLICY_STALE,
            "SOURCE_PERMISSION_REVOKED" to ErrorCategory.PERMISSION_REQUIRED,
            "OUTPUT_CAPACITY_EXCEEDED" to ErrorCategory.INVALID_OUTPUT_CAP
        )
        for ((code, category) in cases) {
            val result = ErrorClassification.classify(IllegalStateException(code))
            assertEquals(category, result.category)
            assertFalse(result.isRetryable)
            assertTrue(result.userMessage.isNotBlank())
        }
    }

    @Test fun wrappedDnsErrorsAreConnectivityFailures() {
        assertEquals(ErrorCategory.NETWORK_DISCONNECTED, ErrorClassification.classify(IllegalStateException("Transport failure", java.net.UnknownHostException("endpoint"))).category)
    }

    @Test fun nativeAndResourceDeadlinesDoNotBecomeNetworkRetries() {
        val cases = mapOf(
            "GGUF initialization timed out: PROBING_DEVICE" to ErrorCategory.LOCAL_INITIALIZATION_FAILED,
            "LOAD_DEADLINE_EXCEEDED" to ErrorCategory.LOCAL_INITIALIZATION_FAILED,
            "ADMISSION_EXPIRED" to ErrorCategory.RESOURCE_DEADLINE,
            "RESEARCH_ATTEMPT_SUPERSEDED" to ErrorCategory.POLICY_STALE,
            "RESEARCH_CONFIGURATION_CHANGED" to ErrorCategory.POLICY_STALE,
            "POLICY_SNAPSHOT_MISSING" to ErrorCategory.SAVED_POLICY_UNAVAILABLE,
            "POLICY_SNAPSHOT_INVALID" to ErrorCategory.SAVED_POLICY_UNAVAILABLE,
            "POLICY_SNAPSHOT_UNRESOLVED_IMPORT" to ErrorCategory.SAVED_POLICY_UNAVAILABLE,
            "POLICY_SNAPSHOT_CHAT_DELETED" to ErrorCategory.SAVED_POLICY_UNAVAILABLE,
            "COMBINED_OUTPUT_ALLOWANCE_CHANGED" to ErrorCategory.INVALID_OUTPUT_CAP,
            "REASONING_BUDGET_EXHAUSTED" to ErrorCategory.REASONING_BUDGET_EXHAUSTED,
            "NO_VISIBLE_ANSWER" to ErrorCategory.NO_VISIBLE_ANSWER,
            "TOOL_CHOICE_MISMATCH" to ErrorCategory.TOOL_CHOICE_MISMATCH,
            "PROVIDER_PAYLOAD_ERROR" to ErrorCategory.PROVIDER_PAYLOAD_ERROR,
            "OUTPUT_PROTOCOL_INVALID" to ErrorCategory.OUTPUT_PROTOCOL_INVALID,
            "MALFORMED_JSON_RECORD" to ErrorCategory.OUTPUT_PROTOCOL_INVALID
        )
        for ((code, category) in cases) {
            val result = ErrorClassification.classify(IllegalStateException("Request failed", IllegalStateException(code)))
            assertEquals(category, result.category)
            assertFalse(result.isRetryable)
            assertFalse(ErrorTemplates.getTemplateFor(result).autoRetry)
        }
    }
}
