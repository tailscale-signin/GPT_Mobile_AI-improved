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
}
