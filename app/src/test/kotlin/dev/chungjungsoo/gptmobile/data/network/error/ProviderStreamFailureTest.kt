package dev.chungjungsoo.gptmobile.data.network.error

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.RemoteRequestAcceptance
import dev.chungjungsoo.gptmobile.data.agent.provider.OpenAIResponsesEventAssembler
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseError
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseFailedEvent
import dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderStreamFailureTest {
    @Test fun gatewayAdmissionRejectionDoesNotBecomeAnInferenceDispatchAfterHttpNormalization() {
        val details = dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure(
            """{"error":{"code":"CONVERSATION_CONFLICT","failed_stage":"conversation_admission","dispatch_state":"not_dispatched","retryable":false,"request_replay_allowed":false}}""",
            409
        )
        val event = ProviderStreamFailure.event("CONVERSATION_CONFLICT", "Review the saved conversation", details = details, httpStatus = 409)
        val failure = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertEquals(false, failure.dispatched)
        assertEquals(true, failure.httpRequestDispatched)
        assertEquals(false, failure.upstreamInferenceDispatched)
        assertEquals("UPSTREAM_INFERENCE", failure.dispatchScope)
        assertEquals("NOT_DISPATCHED", failure.dispatchState)
        assertEquals(RemoteRequestAcceptance.NO, failure.remoteAcceptance)
        assertEquals(false, failure.requestReplayAllowed)
        assertFalse(failure.retryAllowed)
    }

    @Test fun anHttpErrorWithoutInferenceEvidenceKeepsThatStageUnknown() {
        val event = ProviderStreamFailure.event("server_error", "Failure", httpStatus = 502)
        val failure = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertEquals(true, failure.httpRequestDispatched)
        assertEquals(null, failure.upstreamInferenceDispatched)
        assertEquals("HTTP_REQUEST", failure.dispatchScope)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, failure.remoteAcceptance)
    }

    @Test fun malformedBudgetReasonsAndContradictoryAcceptanceNeverAcquireManualRetryTiming() {
        for (source in ProviderBudgetEvidence.LIMIT_SOURCES) {
            for (reason in listOf(kotlinx.serialization.json.JsonNull, JsonPrimitive(true), JsonPrimitive(7), JsonObject(emptyMap()), kotlinx.serialization.json.JsonArray(emptyList()))) {
                val metadata = JsonObject(mapOf("limit_source" to JsonPrimitive(source), "reason" to reason))
                assertEquals(ProviderBudgetKind.UNKNOWN, ProviderBudgetEvidence.from(metadata).kind)
            }
        }
        val metadata = JsonObject(mapOf("limit_source" to JsonPrimitive("openrouter_in_flight_budget")))
        for (details in listOf(
            JsonObject(mapOf("remote_acceptance" to JsonPrimitive("YES"))),
            JsonObject(mapOf("remote_acceptance" to JsonPrimitive("ACCEPTED"))),
            JsonObject(mapOf("requires_reconciliation" to JsonPrimitive(true)))
        )) {
            val event = ProviderStreamFailure.event("402", "private", details = details, httpStatus = 402, metadata = metadata, knownNotAccepted = true, retryAfterMillis = 12_000)
            val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
            assertEquals(JsonPrimitive("REVIEW_SAVED_WORK"), record.providerFailure?.get("provider_budget_retry"))
            assertEquals(null, record.retryAfterMillis)
            assertFalse(record.retryAllowed)
            assertEquals(false, record.requestReplayAllowed)
        }
    }

    @Test fun documentedBudgetMetadataKeepsSpecificGuidanceWithoutRetainingProviderText() {
        val cases = listOf(
            Triple("openrouter_credits", null, ProviderBudgetKind.ACCOUNT_CREDITS),
            Triple("openrouter_key_limit", null, ProviderBudgetKind.KEY_LIMIT),
            Triple("openrouter_credits", "weight_exceeds_budget", ProviderBudgetKind.REQUEST_AFFORDABILITY),
            Triple("openrouter_in_flight_budget", "in_flight_budget_exhausted", ProviderBudgetKind.IN_FLIGHT_BUDGET),
            Triple("openrouter_in_flight_budget", "weight_exceeds_budget", ProviderBudgetKind.UNKNOWN),
            Triple("private-source", "private-reason", ProviderBudgetKind.UNKNOWN)
        )
        for ((source, reason, expected) in cases) {
            val metadata = JsonObject(mapOf("limit_source" to JsonPrimitive(source), "remedy_hint" to JsonPrimitive("private-token-and-prompt")) + reason?.let { mapOf("reason" to JsonPrimitive(it)) }.orEmpty())
            val body = JsonObject(mapOf("error" to JsonObject(mapOf("code" to JsonPrimitive(402), "metadata" to metadata)))).toString()
            val parsed = ProviderFailure.parse(402, body)
            assertEquals(expected, parsed.budget?.kind)
            assertFalse(parsed.retryable)
            val event = ProviderStreamFailure.event("402", "private-message", httpStatus = 402, metadata = metadata, knownNotAccepted = true, retryAfterMillis = 5_000)
            val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
            assertEquals("BILLING_UNAVAILABLE", record.category)
            assertEquals(JsonPrimitive(expected.name), record.providerFailure?.get("provider_budget_kind"))
            assertFalse(record.retryAllowed)
            assertEquals(false, record.requestReplayAllowed)
            assertFalse(event.message.contains("private"))
            assertFalse(record.providerFailure.toString().contains("private"))
        }
    }

    @Test fun inFlightBudgetTimingRequiresAValidHeaderAndAnUnacceptedRequest() {
        val metadata = JsonObject(mapOf("limit_source" to JsonPrimitive("openrouter_in_flight_budget"), "reason" to JsonPrimitive("in_flight_budget_exhausted")))
        for (knownNotAccepted in listOf(false, true)) {
            for (payload in listOf(false, true)) {
                for (delay in listOf(null, 0L, -1L, 86_400_001L, 12_000L)) {
                    val event = ProviderStreamFailure.event("402", "private", httpStatus = 402, metadata = metadata, retryAfterMillis = delay, knownNotAccepted = knownNotAccepted, responsePayloadObserved = payload)
                    val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
                    val mode = when {
                        !knownNotAccepted || payload -> "REVIEW_SAVED_WORK"
                        delay != 12_000L -> "PROVIDER_TIMING_UNAVAILABLE"
                        else -> "MANUAL_AFTER_PROVIDER_DELAY"
                    }
                    assertEquals(JsonPrimitive(mode), record.providerFailure?.get("provider_budget_retry"))
                    assertEquals(if (mode == "MANUAL_AFTER_PROVIDER_DELAY") JsonPrimitive(12_000) else null, record.providerFailure?.get("retry_after_ms"))
                    assertEquals(mode == "MANUAL_AFTER_PROVIDER_DELAY", record.retryAfterMillis != null)
                    assertFalse(record.retryAllowed)
                    assertEquals(false, record.requestReplayAllowed)
                }
            }
        }
    }

    @Test fun assemblerKeepsPartialReasoningAcceptanceAndFailedResponseUsage() {
        val assembler = OpenAIResponsesEventAssembler()
        assembler.accept(dev.chungjungsoo.gptmobile.data.dto.openai.response.ReasoningSummaryTextDeltaEvent("reasoning", 0, 0, "Retained reasoning"))
        val usage = dev.chungjungsoo.gptmobile.data.dto.openai.response.ResponseUsage(11, 7, 18, outputTokensDetails = JsonObject(mapOf("reasoning_tokens" to JsonPrimitive(7))))
        val events = assembler.accept(ResponseFailedEvent(ResponseObject("resp_retained", "failed", ResponseError("server_error", "private prompt"), usage)))
        assertEquals(7, events.filterIsInstance<ProviderEvent.Usage>().single().reasoningTokens)
        val failure = events.filterIsInstance<ProviderEvent.Failed>().single()
        val record = ProfileFailureRecord.fromProvider(failure.message, failure.errorCode, failure.failureDetails)
        assertEquals(RemoteRequestAcceptance.YES, record.remoteAcceptance)
        assertEquals(false, record.requestReplayAllowed)
        assertEquals(JsonPrimitive("response.failed"), record.providerFailure?.get("stream_event_type"))
        assertTrue(record.providerFailure?.get("provider_response_id_hash") != null)
        assertFalse(record.providerFailure.toString().contains("resp_retained"))
    }

    @Test fun acceptedStreamWithExhaustedCreditsRequiresConfigurationInsteadOfRetry() {
        val event = ProviderStreamFailure.event(null, "You have no credits remaining", httpStatus = 200, serverRequestId = "req_billing_123")
        val emitted = OpenAIResponsesEventAssembler().accept(event).single() as ProviderEvent.Failed
        val record = ProfileFailureRecord.fromProvider(emitted.message, emitted.errorCode, emitted.failureDetails)
        assertEquals("BILLING_UNAVAILABLE", record.category)
        assertFalse(record.retryAllowed)
        assertEquals(false, record.requestReplayAllowed)
        assertEquals(false, record.generationCompleted)
        assertEquals("response_stream", record.phase)
        assertEquals("req_billing_123", record.serverRequestId)
        assertEquals(JsonPrimitive(200), record.providerFailure?.get("http_status"))
        assertTrue("RETRY_AFTER_CONFIGURATION_CHANGE" in record.recoveryActions)
        assertFalse("RESUME_UNFINISHED_WORK" in record.recoveryActions)
    }

    @Test fun failedResponseAndHttpRejectionUseTheSameBudgetCategory() {
        val event = ResponseFailedEvent(ResponseObject("response_1", "failed", ResponseError("insufficient_quota", "private prompt canary")))
        val emitted = OpenAIResponsesEventAssembler().accept(event).single() as ProviderEvent.Failed
        assertEquals("BILLING_UNAVAILABLE", emitted.errorCode)
        assertFalse(emitted.message.contains("private"))
        assertTrue(emitted.message.contains("insufficient API quota"))
        assertEquals(JsonPrimitive("PROVIDER_QUOTA"), emitted.failureDetails?.get("provider_budget_kind"))
        assertEquals(ProviderFailureKind.BUDGET, ProviderFailure.parse(429, """{"error":{"code":"insufficient_quota"}}""").kind)
        assertFalse(ProfileFailureRecord.classify("You have no credits remaining").retryAllowed)
    }

    @Test fun transientStreamFailureRetainsPriorPayloadAndForbidsWholeRequestReplay() {
        val event = ProviderStreamFailure.event("server_error", "private upstream prompt", httpStatus = 200, responsePayloadObserved = true)
        val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertEquals("UPSTREAM_UNAVAILABLE", record.category)
        assertTrue(record.retryAllowed)
        assertEquals(RemoteRequestAcceptance.YES, record.remoteAcceptance)
        assertEquals(false, record.requestReplayAllowed)
        assertEquals(JsonPrimitive("server_error"), record.providerFailure?.get("upstream_error_code"))
        assertFalse(event.message.contains("private"))
    }

    @Test fun unknownRemoteOutcomeKeepsReconciliationEvenWhenTheCurrentErrorIsBilling() {
        val details = JsonObject(mapOf("requires_reconciliation" to JsonPrimitive(true), "request_replay_allowed" to JsonPrimitive(false), "retryable" to JsonPrimitive(false)))
        val event = ProviderStreamFailure.event("insufficient_credits", "private", details = details, httpStatus = 200)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", event.code)
        val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", record.category)
        assertFalse(record.retryAllowed)
        assertEquals(false, record.requestReplayAllowed)
        assertTrue("RECONCILE_REMOTE_OUTCOME" in record.recoveryActions)
        assertEquals(JsonPrimitive("BUDGET"), record.providerFailure?.get("provider_failure_kind"))
    }

    @Test fun structuredTemplateContractOverridesAGenericTransientBackendCode() {
        val details = dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure("""{"error":{"code":"server_error","message":"Jinja exception: system message must be at the beginning"}}""", 200)
        val event = ProviderStreamFailure.event("server_error", "An error occurred while processing your request", details = details, httpStatus = 200)
        val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertEquals("BACKEND_TEMPLATE_INVALID", event.code)
        assertEquals("BACKEND_TEMPLATE_INVALID", record.category)
        assertEquals("backend_template", record.phase)
        assertFalse(record.retryAllowed)
        assertEquals(false, record.requestReplayAllowed)
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("retryable"))
    }

    @Test fun structuredUnknownCommandOutcomeOutranksTheLatestRequestRejection() {
        val details = dev.chungjungsoo.gptmobile.data.network.structuredProviderFailure("""{"error":{"code":"REQUEST_REJECTED","command_outcome_unknown":true,"dispatched":false,"retryable":true}}""", 200)
        val event = ProviderStreamFailure.event("REQUEST_REJECTED", "The provider is temporarily unavailable", details = details, httpStatus = 200)
        val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", event.code)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", record.category)
        assertEquals(true, record.dispatched)
        assertEquals(RemoteRequestAcceptance.UNKNOWN, record.remoteAcceptance)
        assertFalse(record.retryAllowed)
        assertEquals(false, record.requestReplayAllowed)
        assertEquals(JsonPrimitive(true), event.failureDetails?.get("requires_reconciliation"))

        val laterRejection = JsonObject(mapOf("code" to JsonPrimitive("REQUEST_REJECTED"), "dispatched" to JsonPrimitive(false), "remote_acceptance" to JsonPrimitive("NO")))
        val alreadyUnknown = ProviderStreamFailure.event("GATEWAY_OUTCOME_UNKNOWN", "A later rejection", details = laterRejection, httpStatus = 200)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", alreadyUnknown.code)
        assertEquals(JsonPrimitive("UNKNOWN"), alreadyUnknown.failureDetails?.get("remote_acceptance"))
    }

    @Test fun explicitRetryRestrictionAlsoForbidsReplayAndAutomaticContinuationGuidance() {
        val details = JsonObject(mapOf("retryable" to JsonPrimitive(false)))
        val event = ProviderStreamFailure.event("server_error", "Temporary processing failure", details = details, httpStatus = 200)
        val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertFalse(record.retryAllowed)
        assertEquals(false, record.requestReplayAllowed)
        assertFalse("RESUME_UNFINISHED_WORK" in record.recoveryActions)
        val execution = ProviderStreamFailure.event("EXECUTION_POLICY_CHANGED", "Temporary processing failure", httpStatus = 200)
        assertEquals(JsonPrimitive(false), execution.failureDetails?.get("retryable"))
        assertEquals(JsonPrimitive(false), execution.failureDetails?.get("request_replay_allowed"))
    }

    @Test fun arbitraryUpstreamCodesAndTextDoNotEnterRetainedClassification() {
        val event = ProviderStreamFailure.event("private-secret-code", "private message", type = "private-type", httpStatus = 200)
        assertEquals("PROVIDER_GENERATION_FAILED", event.code)
        val retained = requireNotNull(ProviderStreamFailure.retained(event.failureDetails))
        assertFalse(retained.toString().contains("private"))
        assertEquals(JsonPrimitive("UNKNOWN"), retained["provider_failure_kind"])
    }

    @Test fun documentedContractFailureRetainsItsCorrectionAndCannotBecomeARetry() {
        val event = ProviderStreamFailure.event("CLIENT_TOOL_RESULTS_REQUIRED", "The saved job requires its exact client results", httpStatus = 200)
        val record = ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails)
        assertEquals("CLIENT_TOOL_RESULTS_REQUIRED", record.category)
        assertFalse(record.retryAllowed)
        assertEquals(false, record.requestReplayAllowed)
        for (code in listOf("REQUEST_REJECTED", "GATEWAY_OUTCOME_UNKNOWN", "CLIENT_TOOL_RESULTS_REQUIRED")) {
            val mixed = ProviderStreamFailure.event(code, "An error occurred while processing your request; provider is temporarily unavailable", type = "rate_limit_error", httpStatus = 200)
            val saved = ProfileFailureRecord.fromProvider(mixed.message, mixed.code, mixed.failureDetails)
            assertEquals(code, saved.category)
            assertFalse(saved.retryAllowed)
            assertEquals(false, saved.requestReplayAllowed)
        }
        assertEquals(ProviderFailureKind.BUDGET, ProviderFailure.parse(429, """{"code":"insufficient_quota","error":{"message":"Unavailable"}}""").kind)
    }

    @Test fun semanticRateLimitRetainsActualHttpSuccessAndTheDurableWaitDeadline() {
        val observed = System.currentTimeMillis()
        val failure = dev.chungjungsoo.gptmobile.data.network.ProviderHttpFailure(
            429,
            3_019,
            knownNotAccepted = true,
            observedAtMillis = observed,
            retrySource = "RETRY_AFTER",
            consecutiveRejections = 2,
            observedHttpStatus = 200,
            cooldownScope = "provider:" + "a".repeat(64)
        )
        val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(failure, false))
        val record = ProfileFailureRecord.fromProvider(detail.message, detail.code, detail.failureDetails)
        assertEquals("RATE_LIMIT", record.category)
        assertEquals(observed + 3_019, record.retryAfterMillis)
        assertEquals(JsonPrimitive(200), record.providerFailure?.get("http_status"))
        assertEquals(JsonPrimitive(429), record.providerFailure?.get("provider_semantic_status"))
        assertEquals(ProviderFailureKind.RATE_LIMIT, ProviderFailure.fromValues(200, null, "Rate limited", "rate_limit_error").kind)
        assertEquals(ProviderFailureKind.RATE_LIMIT, ProviderFailure.fromValues(200, "tokens_limit_exceeded", "Rate limited").kind)
    }
}
