package dev.chungjungsoo.gptmobile.data.network

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TlsTransportFailureTest {
    @Test fun unsentTlsPrecheckCannotBorrowEarlierTransportPhaseOrPeerLabels() {
        val details = providerTransportFailureDetails(
            buildJsonObject { put("dispatched", false) }, javax.net.ssl.SSLHandshakeException("precheck"), null,
            true, false, false, false, false, false,
            HttpAttemptEvidence(HttpAttemptPhase.RESPONSE_HEADERS, true, true, "private version", "private protocol")
        )
        assertEquals(JsonPrimitive("NO"), details["remote_acceptance"])
        assertFalse(details.containsKey("tls_last_observed_phase"))
        assertFalse(details.containsKey("tls_request_body_completed"))
        assertFalse(details.toString().contains("private"))
    }

    @Test fun postBodyProtocolFailureKeepsUnknownAcceptanceAndCannotAuthorizeReplay() {
        val details = providerTransportFailureDetails(
            null, javax.net.ssl.SSLProtocolException("private endpoint"), null,
            true, false, false, false, false, false,
            HttpAttemptEvidence(HttpAttemptPhase.REQUEST_BODY_COMPLETE, true, true, "TLSv1.3", "h2")
        )
        assertEquals(JsonPrimitive("PROTOCOL"), details["tls_failure_kind"])
        assertEquals(JsonPrimitive("REQUEST_BODY_COMPLETE"), details["tls_last_observed_phase"])
        assertEquals(JsonPrimitive(true), details["tls_request_body_completed"])
        assertEquals(JsonPrimitive("UNKNOWN"), details["remote_acceptance"])
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
        assertEquals(JsonPrimitive(false), details["logical_continuation_allowed"])
        assertFalse(details.toString().contains("private"))
    }
}
