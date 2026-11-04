package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderDispatchRetryGuardTest {
    @Test fun gatewayMutationMayHaveFinishedBeforeItsFirstEventSoDispatchIsNotReplayed() {
        val guard = ProviderDispatchRetryGuard(ProviderRequestConfig("http://100.64.0.2:8080/v1", null, extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "true")))
        assertTrue(guard.mayRetry(hasPayload = false))
        guard.dispatched()
        assertFalse(guard.mayRetry(hasPayload = false))
    }

    @Test fun anExplicitPreExecutionRejectionCanBeRetriedWithoutReplayingAJob() {
        val guard = ProviderDispatchRetryGuard(ProviderRequestConfig("https://gateway.example/v1", null, extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "true")))
        guard.dispatched()
        guard.rejectedBeforeExecution()
        assertTrue(guard.mayRetry(hasPayload = false))
    }

    @Test fun ordinaryInferenceCanRetryBeforePayloadButNeverAfterPayload() {
        val guard = ProviderDispatchRetryGuard(ProviderRequestConfig("http://100.64.0.2:8080/v1", null, extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "false")))
        guard.dispatched()
        assertTrue(guard.mayRetry(hasPayload = false))
        assertFalse(guard.mayRetry(hasPayload = true))
    }
}
