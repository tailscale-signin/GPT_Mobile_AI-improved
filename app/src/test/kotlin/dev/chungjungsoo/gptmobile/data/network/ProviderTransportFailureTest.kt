package dev.chungjungsoo.gptmobile.data.network

import java.io.EOFException
import java.io.IOException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTransportFailureTest {
    @Test fun reasoningOnlyAcceptedStreamKeepsItsIdentityAndCannotReplayTheRequest() {
        val observer = ProviderResponseObserver { 100L }
        observer.headersReceived(200) { name -> mapOf("X-Request-ID" to "req-17", "X-Generation-ID" to "gen-29")[name] }
        val guard = ProviderDispatchRetryGuard(ProviderRequestConfig("https://provider.example/v1", null))
        guard.dispatched()
        guard.accepted()
        assertFalse(guard.mayRetry(false))
        assertFalse(guard.mayRetry(true))
        val details = providerTransportFailureDetails(
            buildJsonObject { put("failed_stage", "response_stream") },
            IOException("private endpoint https://private.example?token=secret", EOFException("private response")),
            observer.parsed(true),
            dispatched = true,
            receivedPayload = true,
            receivedVisibleText = false,
            generationCompleted = false,
            replayAllowed = guard.mayRetry(true),
            requiresReconciliation = false
        )
        assertEquals(JsonPrimitive("CONNECTION_ABORTED"), details["failure_kind"])
        assertEquals(JsonPrimitive("YES"), details["remote_acceptance"])
        assertEquals(JsonPrimitive(200), details["http_status"])
        assertEquals(JsonPrimitive("req-17"), details["server_request_id"])
        assertEquals(JsonPrimitive("gen-29"), details["generation_id"])
        assertEquals(JsonPrimitive(false), details["generation_completed"])
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
        assertEquals(JsonPrimitive(true), details["logical_continuation_allowed"])
        assertEquals(JsonPrimitive(false), details["visible_output_preserved"])
        assertEquals(JsonArray(listOf(JsonPrimitive("java.io.IOException"), JsonPrimitive("java.io.EOFException"))), details["cause_types"])
        assertFalse(details.toString().contains("secret"))
        assertFalse(details.toString().contains("private.example"))
    }

    @Test fun unknownRemoteToolsRequireReconciliationEvenAfterAnAcceptedResponse() {
        val details = providerTransportFailureDetails(null, EOFException(), ProviderResponseTiming(httpStatus = 200), true, true, true, false, false, true)
        assertEquals(JsonPrimitive("YES"), details["remote_acceptance"])
        assertEquals(JsonPrimitive(true), details["requires_reconciliation"])
        assertEquals(JsonPrimitive(false), details["logical_continuation_allowed"])
    }

    @Test fun precheckIsUnsentWithoutClaimingACompletedTransport() {
        val details = providerTransportFailureDetails(buildJsonObject { put("failed_stage", "provider_admission") }, IllegalStateException("Model not ready"), null, false, false, false, false, false, false)
        assertEquals(JsonPrimitive(false), details["dispatched"])
        assertEquals(JsonPrimitive("NO"), details["remote_acceptance"])
        assertEquals(JsonPrimitive(false), details["generation_completed"])
        assertEquals(JsonPrimitive("provider_admission"), details["failed_stage"])
        assertFalse(details.containsKey("http_status"))
        assertFalse(details.containsKey("failure_kind"))
    }

    @Test fun arbitraryProviderFailureKeepsObservedDispatchWithoutInventingANetworkDiagnosis() {
        val details = providerTransportFailureDetails(
            buildJsonObject { put("failed_stage", "stage_not_recorded") },
            IllegalStateException("Provider failed without a recorded transport cause"),
            null, true, false, false, false, false, false
        )
        assertEquals(JsonPrimitive("stage_not_recorded"), details["failed_stage"])
        assertEquals(JsonPrimitive(true), details["dispatched"])
        assertEquals(JsonPrimitive("UNKNOWN"), details["remote_acceptance"])
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
        assertEquals(JsonPrimitive(false), details["logical_continuation_allowed"])
        assertFalse(details.containsKey("failure_kind"))
        assertFalse(details.containsKey("http_status"))
    }

    @Test fun unsafeResponseIdentifiersAreExcludedAndCausesAreBounded() {
        val observer = ProviderResponseObserver { 1L }
        observer.headersReceived(200) { "secret=token\nCookie: private" }
        assertEquals(null, observer.snapshot().serverRequestId)
        assertEquals(null, observer.snapshot().generationId)
        var chain: Throwable = EOFException("private")
        repeat(40) { chain = IOException("secret-$it", chain) }
        assertEquals(16, sanitizedCauseTypes(chain).size)
        assertTrue(sanitizedCauseTypes(chain).all { it == "java.io.IOException" })
    }

    @Test fun laterUnsentPrecheckDoesNotBorrowAnEarlierAttemptsAcceptedIdentity() {
        val details = providerTransportFailureDetails(
            buildJsonObject {
                put("failed_stage", "provider_admission")
                put("dispatched", false)
            },
            IOException("NETWORK_DISCONNECTED"),
            ProviderResponseTiming(httpStatus = 200, serverRequestId = "earlier-request"),
            true, false, false, false, false, false
        )
        assertEquals(JsonPrimitive(false), details["dispatched"])
        assertEquals(JsonPrimitive("NO"), details["remote_acceptance"])
        assertFalse(details.containsKey("http_status"))
        assertFalse(details.containsKey("server_request_id"))
    }
}
