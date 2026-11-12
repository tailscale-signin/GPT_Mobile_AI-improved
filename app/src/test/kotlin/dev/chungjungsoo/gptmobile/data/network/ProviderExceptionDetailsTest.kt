package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderExceptionDetailsTest {
    @Test fun finalSerializedCapacityFailureKeepsTypedAdmissionAndDispatchEvidence() {
        val runtime = dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint("selected", "route", "credential", "model", "model", "runtime", "template", "allocation")
        val error = runCatching {
            dev.chungjungsoo.gptmobile.data.context.RequestAdmission.prepare(
                "complete unchanged input",
                "github-review",
                dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot(runtime, 32768),
                dev.chungjungsoo.gptmobile.data.context.RequestMeasurement(27553, 6888, dev.chungjungsoo.gptmobile.data.context.TokenMeasurementKind.UTF8_ESTIMATE),
                1536,
                "policy"
            )
        }.exceptionOrNull() as dev.chungjungsoo.gptmobile.data.context.RequestCapacityException
        val detail = requireNotNull(providerExceptionDetails(error, false))
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", detail.code)
        assertEquals(JsonPrimitive(27553), detail.failureDetails?.get("prompt_tokens"))
        val durable = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(detail.message, detail.code, detail.failureDetails)
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", durable.category)
        assertEquals("provider_admission", durable.phase)
        assertEquals(false, durable.dispatched)
        assertEquals(false, durable.retryAllowed)
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.NO, durable.remoteAcceptance)
        val continuation = requireNotNull(providerExceptionDetails(error, true))
        assertNull(continuation.failureDetails?.get("dispatched"))
        assertNull(continuation.failureDetails?.get("remote_acceptance"))
    }

    @Test fun outputAllowanceRejectionDoesNotEraseTheCapacityExceptionInAStringWrapper() {
        val runtime = dev.chungjungsoo.gptmobile.data.context.RuntimeFingerprint("selected", "route", "credential", "model", "model", "runtime", "template", "allocation")
        val error = runCatching { resolveOutputAllowance("x".repeat(90000), 1536, dev.chungjungsoo.gptmobile.data.context.CapacitySnapshot(runtime, 32768)) }.exceptionOrNull() as Exception
        val detail = requireNotNull(providerExceptionDetails(error, false))
        assertEquals("CONTEXT_CAPACITY_EXCEEDED", detail.code)
        assertEquals(JsonPrimitive("provider_admission"), detail.failureDetails?.get("failed_stage"))
    }

    @Test fun fullGithubCatalogBudgetFailureRemainsAnUnsentAdmissionFailure() {
        val error = dev.chungjungsoo.gptmobile.data.agent.GitHubToolContextBudgetException(8000, 4000)
        val detail = requireNotNull(providerExceptionDetails(error, partialOutput = false))
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", detail.code)
        assertEquals(JsonPrimitive(false), detail.failureDetails?.get("dispatched"))
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), detail.failureDetails?.get("dispatch_state"))
        val record = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(detail.message, detail.code, detail.failureDetails)
        assertEquals("GITHUB_TOOL_CONTEXT_BUDGET", record.category)
        assertEquals("provider_admission", record.phase)
        assertEquals(false, record.retryAllowed)
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.NO, record.remoteAcceptance)
    }

    @Test fun cachedRepositoryAdmissionSurvivesProviderAndDurableFailureBoundaries() {
        val gate = dev.chungjungsoo.gptmobile.data.github.GitHubRefAvailability(now = { 1000 }, cooldownMillis = 5000)
        gate.failed("/repos/owner/repo", 404, "Not found")
        val rejection = runCatching { gate.requireAvailable("/repos/owner/repo/commits/main") }.exceptionOrNull() as dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation
        val detail = requireNotNull(providerExceptionDetails(rejection, partialOutput = false))
        assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", detail.code)
        val record = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider(detail.message, detail.code, detail.failureDetails)
        val restored = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord>(kotlinx.serialization.json.Json.encodeToString(dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.serializer(), record))
        assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", restored.category)
        assertEquals("repository_admission", restored.phase)
        assertEquals("PRECHECK_REJECTED", restored.dispatchState)
        assertEquals(false, restored.retryAllowed)
        assertEquals(false, restored.dispatched)
        assertEquals(dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance.NO, restored.remoteAcceptance)
        assertEquals(listOf("REFRESH_REPOSITORY_ACCESS", "CHANGE_REPOSITORY"), restored.recoveryActions)
        org.junit.Assert.assertTrue(requireNotNull(restored.retryAfterMillis) > System.currentTimeMillis())
        val partial = requireNotNull(providerExceptionDetails(rejection, partialOutput = true))
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), partial.failureDetails?.get("attempt_dispatch_state"))
        assertNull(partial.failureDetails?.get("dispatched"))
        assertNull(partial.failureDetails?.get("dispatch_state"))
        assertNull(partial.failureDetails?.get("remote_acceptance"))
    }

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
