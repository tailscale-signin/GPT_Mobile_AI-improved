package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationFailureClassificationTest {
    @Test fun deterministicConfigurationErrorsAreNotTransientConnectivity() {
        listOf("REASONING_OFF_UNSUPPORTED", "REASONING_ON_UNSUPPORTED", "LOCAL_CONTEXT_EXHAUSTED", "NATIVE_SESSION_QUARANTINED", "This artifact/runtime/backend was interrupted inside native code.").forEach {
            assertTrue(isDeterministicDelegateConfigurationFailure(it))
        }
        listOf("HTTP 429", "LOCAL_SERVICE_UNREACHABLE", "connection refused", "HTTP 503").forEach {
            assertFalse(isDeterministicDelegateConfigurationFailure(it))
        }
    }

    @Test
    fun recognizesProviderOutputLimitFailures() {
        assertTrue(isProviderOutputLimitFailure("The response reached its output limit."))
        assertTrue(isProviderOutputLimitFailure("finish_reason=length"))
        assertTrue(isProviderOutputLimitFailure("Maximum completion tokens exceeded"))
    }

    @Test
    fun leavesUnrelatedFailuresRetryable() {
        assertFalse(isProviderOutputLimitFailure("Connection timed out"))
        assertFalse(isProviderOutputLimitFailure("HTTP 503"))
        assertFalse(isProviderOutputLimitFailure("Invalid API key"))
    }
}
