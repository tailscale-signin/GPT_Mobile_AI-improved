package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderExceptionDetailsTest {
    @Test fun transientHttpResponsesRetainStatusCooldownAndDispatchEvidence() {
        listOf(429, 502, 503, 504).forEach { status ->
            val failure = requireNotNull(providerExceptionDetails(ProviderHttpFailure(status, 1_500), partialOutput = false))
            assertEquals(status.toString(), failure.code)
            assertEquals("http_error", failure.type)
            assertEquals(true, failure.retryable)
            assertEquals(JsonPrimitive(status), failure.failureDetails?.get("http_status"))
            assertEquals(JsonPrimitive(1_500), failure.failureDetails?.get("retry_after_ms"))
            assertEquals(JsonPrimitive("model_invocation"), failure.failureDetails?.get("failed_stage"))
            assertEquals(JsonPrimitive(true), failure.failureDetails?.get("dispatched"))
        }
    }

    @Test fun knownProviderFailuresNeverAuthorizeReplayOfPartialOutput() {
        val failure = requireNotNull(providerExceptionDetails(ProviderHttpFailure(503, 0), partialOutput = true))
        assertEquals(false, failure.retryable)
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("retryable"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("partial_output_preserved"))
    }

    @Test fun remoteRateLimitAndLocalCooldownHaveDifferentDispatchEvidence() {
        listOf(false, true).forEach { localBlock ->
            val cooldown = FreeCooldown(retryAt = 9_000, status = 429, providerCode = "rate_limit_exceeded")
            val error = FreeAiRateLimitException(FreeAiProvider.POLLINATIONS, 8, cooldown = cooldown, localBlock = localBlock)
            val failure = requireNotNull(providerExceptionDetails(error, partialOutput = false, nowMillis = 1_000))
            assertEquals(if (localBlock) "LOCAL_COOLDOWN_ACTIVE" else "PROVIDER_RATE_LIMITED", failure.code)
            assertEquals(true, failure.retryable)
            assertEquals(JsonPrimitive(8_000), failure.failureDetails?.get("retry_after_ms"))
            assertEquals(JsonPrimitive(!localBlock), failure.failureDetails?.get("dispatched"))
            assertEquals(JsonPrimitive(if (localBlock) "provider_admission" else "model_invocation"), failure.failureDetails?.get("failed_stage"))
            assertEquals(JsonPrimitive(429), failure.failureDetails?.get(if (localBlock) "originating_http_status" else "http_status"))
            if (localBlock) assertNull(failure.failureDetails?.get("http_status"))
        }
    }

    @Test fun typedProviderRejectionRetainsCategoryWithoutInventingDispatchOrTransportPhase() {
        val failure = requireNotNull(providerExceptionDetails(FreeProviderException(FreeFailureCategory.AUTHENTICATION_REQUIRED, "Add API token"), partialOutput = false))
        assertEquals("AUTHENTICATION_REQUIRED", failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(JsonPrimitive("stage_not_recorded"), failure.failureDetails?.get("failed_stage"))
        assertNull(failure.failureDetails?.get("dispatched"))
        assertNull(providerExceptionDetails(IllegalStateException("Preparation failed"), partialOutput = false))
    }
}
