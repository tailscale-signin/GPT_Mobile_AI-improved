package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderExceptionDetailsTest {
    @Test fun gatewayModelQualificationPreservesOriginalPropertiesFailureThroughProviderAdapter() {
        val observation = dev.chungjungsoo.gptmobile.data.context.GatewayModelCapabilityObservation(
            failureCode = "EXECUTION_MODEL_NOT_LOADED",
            reasonCode = "SELECTED_MODEL_NOT_LOADED",
            dependency = "backend_properties",
            upstreamStatus = 400,
            modelState = "UNLOADED",
            modelAdvertised = true,
            modelLoaded = false,
            upstreamMessageCode = "MODEL_NOT_LOADED"
        )
        val exception = dev.chungjungsoo.gptmobile.data.context.ReasoningCapabilityUnavailableException("REASONING_CAPABILITY_UNAVAILABLE", observation)
        val failure = requireNotNull(providerExceptionDetails(exception, partialOutput = false))
        assertEquals("REASONING_CAPABILITY_UNAVAILABLE", failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(JsonPrimitive("provider_admission"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), failure.failureDetails?.get("attempt_dispatch_state"))
        assertEquals(JsonPrimitive("SELECTED_MODEL_NOT_LOADED"), failure.failureDetails?.get("gateway_reason_code"))
        assertEquals(JsonPrimitive("backend_properties"), failure.failureDetails?.get("gateway_dependency"))
        assertEquals(JsonPrimitive(400), failure.failureDetails?.get("gateway_upstream_status"))
        org.junit.Assert.assertTrue(failure.message.contains("prepare-model --model"))
    }

    @Test fun gatewayPrerequisitesPreserveTypedCauseWithoutClaimingEarlierAttemptsWereUnsent() {
        val error = dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionException(dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionReason.ENGINEERING_GITHUB_REQUIRED, 200, null, dev.chungjungsoo.gptmobile.data.github.GatewayAdmissionSurface.CAPABILITIES)
        val failure = requireNotNull(providerExceptionDetails(error, partialOutput = true))
        assertEquals("GATEWAY_ENGINEERING_GITHUB_REQUIRED", failure.code)
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), failure.failureDetails?.get("attempt_dispatch_state"))
        assertEquals(JsonPrimitive("CONFIGURE_HOST_GITHUB"), failure.failureDetails?.get("repair"))
        assertNull(failure.failureDetails?.get("dispatched"))
        assertNull(failure.failureDetails?.get("dispatch_state"))
        assertEquals(false, failure.retryable)
    }

    @Test fun failedCapabilityDiscoveryRetainsItsPreflightStageAndHttpEvidence() {
        val error = dev.chungjungsoo.gptmobile.data.network.gateway.GatewayCapabilityDiscoveryException("Capability check denied", httpStatus = 403)
        val failure = requireNotNull(providerExceptionDetails(error, partialOutput = false))
        assertEquals("GATEWAY_CAPABILITY_UNAVAILABLE", failure.code)
        assertEquals(JsonPrimitive("connection_preflight"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(403), failure.failureDetails?.get("http_status"))
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), failure.failureDetails?.get("attempt_dispatch_state"))
        assertNull(failure.failureDetails?.get("dispatched"))
    }

    @Test fun typedReasoningAdmissionRetainsItsPhaseWithoutErasingEarlierUncertainty() {
        val failure = requireNotNull(providerExceptionDetails(dev.chungjungsoo.gptmobile.data.context.ReasoningAdmissionException(dev.chungjungsoo.gptmobile.data.context.ReasoningMode.OFF), partialOutput = false))
        assertEquals("REASONING_OFF_UNSUPPORTED", failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(JsonPrimitive("provider_admission"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), failure.failureDetails?.get("attempt_dispatch_state"))
        assertNull(failure.failureDetails?.get("dispatched"))
    }

    @Test fun actualCircuitRejectionRemainsTypedBeforeTransportFallback() {
        val failure = requireNotNull(providerExceptionDetails(dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException(5000L), partialOutput = false))
        assertEquals("CIRCUIT_BREAKER_OPEN", failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(JsonPrimitive("provider_admission"), failure.failureDetails?.get("failed_stage"))
        assertEquals(JsonPrimitive(false), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(5000L), failure.failureDetails?.get("retry_after_ms"))
    }

    @Test fun circuitRejectionCannotEraseAlreadyObservedPartialOutput() {
        val failure = requireNotNull(providerExceptionDetails(dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException(5000L), partialOutput = true))
        assertEquals("CIRCUIT_BREAKER_OPEN", failure.code)
        assertEquals(false, failure.retryable)
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive(true), failure.failureDetails?.get("partial_output_preserved"))
    }

    @Test fun gatewayUncertaintyHasPriorityOverCircuitAdmissionDetails() {
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "REMOTE_OUTCOME_UNKNOWN")) {
            val error = dev.chungjungsoo.gptmobile.data.network.error.CircuitBreakerOpenException(5000L, "provider", "$code: reconcile the prior request")
            val failure = requireNotNull(providerExceptionDetails(error, partialOutput = false))
            assertEquals("GATEWAY_OUTCOME_UNKNOWN", failure.code)
            assertEquals(false, failure.retryable)
            assertEquals(JsonPrimitive("gateway_dispatch"), failure.failureDetails?.get("failed_stage"))
            assertNull(failure.failureDetails?.get("dispatched"))
        }
    }

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
