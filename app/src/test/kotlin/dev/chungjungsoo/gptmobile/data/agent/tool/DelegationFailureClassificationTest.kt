package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DelegationFailureClassificationTest {
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
