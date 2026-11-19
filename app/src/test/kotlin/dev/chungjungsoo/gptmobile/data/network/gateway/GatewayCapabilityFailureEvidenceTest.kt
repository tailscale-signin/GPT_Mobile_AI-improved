package dev.chungjungsoo.gptmobile.data.network.gateway

import io.ktor.client.network.sockets.ConnectTimeoutException
import java.io.EOFException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCapabilityFailureEvidenceTest {
    @Test fun nestedConnectTimeoutRetainsItsTypedCauseWithoutConnectionDetails() {
        val error = GatewayCapabilityDiscoveryException("PRIVATE_ENDPOINT", ConnectTimeoutException("PRIVATE_HOST", SocketTimeoutException("PRIVATE_SECRET")))
        val details = GatewayCapabilityFailureEvidence.failureDetails(error, partialOutput = false)
        assertEquals(JsonPrimitive("CONNECT_TIMEOUT"), details[GatewayCapabilityFailureEvidence.KIND])
        assertEquals(JsonPrimitive("connection_preflight"), details["failed_stage"])
        assertFalse("attempt_dispatch_state" in details)
        assertEquals(JsonPrimitive(false), details["request_replay_allowed"])
        assertEquals(JsonPrimitive(false), details["logical_continuation_allowed"])
        assertFalse("dispatched" in details)
        assertFalse("remote_acceptance" in details)
        assertFalse(details.toString().contains("PRIVATE_"))
    }

    @Test fun knownTransportTypesRemainDistinctAndProseDoesNotBecomeEvidence() {
        val cases = listOf(
            SocketTimeoutException("PRIVATE") to "TIMEOUT",
            UnknownHostException("PRIVATE") to "DNS",
            SSLHandshakeException("PRIVATE") to "TLS",
            ConnectException("PRIVATE") to "CONNECTION",
            EOFException("PRIVATE") to "CONNECTION_CLOSED",
            IllegalStateException("timeout DNS PRIVATE_URL") to "UNKNOWN"
        )
        for ((cause, kind) in cases) {
            val details = GatewayCapabilityFailureEvidence.failureDetails(GatewayCapabilityDiscoveryException("PRIVATE", cause), partialOutput = true)
            assertEquals(JsonPrimitive(kind), details[GatewayCapabilityFailureEvidence.KIND])
            assertEquals(JsonPrimitive(true), details["partial_output_preserved"])
            assertFalse(details.toString().contains("PRIVATE"))
        }
    }

    @Test fun decodeEvidenceKeepsOnlyClosedReasonsAndNeverFieldPaths() {
        for (reason in GatewayCapabilityFailureEvidence.decodeReasons) {
            val error = GatewayCapabilityDiscoveryException("PRIVATE_BODY", decodeReason = reason, fieldPath = "PRIVATE_PATH")
            val details = GatewayCapabilityFailureEvidence.failureDetails(error, partialOutput = false)
            assertEquals(JsonPrimitive("DECODE_REJECTED"), details[GatewayCapabilityFailureEvidence.KIND])
            assertEquals(JsonPrimitive(reason), details[GatewayCapabilityFailureEvidence.DECODE_REASON])
            assertFalse(details.toString().contains("PRIVATE"))
        }
        val unknown = GatewayCapabilityFailureEvidence.failureDetails(GatewayCapabilityDiscoveryException("PRIVATE", decodeReason = "PRIVATE_REASON", fieldPath = "PRIVATE_PATH"), false)
        assertEquals(JsonPrimitive("UNKNOWN"), unknown[GatewayCapabilityFailureEvidence.KIND])
        assertFalse(GatewayCapabilityFailureEvidence.DECODE_REASON in unknown)
    }

    @Test fun observedHttpRejectionAndIsolationFailureUseTheirOwnCodes() {
        val denied = GatewayCapabilityFailureEvidence.failureDetails(GatewayCapabilityDiscoveryException("PRIVATE", httpStatus = 403), false)
        assertEquals(JsonPrimitive("HTTP_REJECTED"), denied[GatewayCapabilityFailureEvidence.KIND])
        assertEquals(JsonPrimitive(403), denied["http_status"])
        val isolation = GatewayCapabilityFailureEvidence.failureDetails(GatewayCapabilityDiscoveryException("PRIVATE", code = "GATEWAY_ISOLATION_UNAVAILABLE"), false)
        assertEquals(JsonPrimitive("ISOLATION_UNVERIFIED"), isolation[GatewayCapabilityFailureEvidence.KIND])
        assertTrue(GatewayCapabilityFailureEvidence.retained("GATEWAY_ISOLATION_UNAVAILABLE", isolation).isNotEmpty())
        assertTrue(GatewayCapabilityFailureEvidence.retained("GATEWAY_CAPABILITY_UNAVAILABLE", isolation).isEmpty())
        assertTrue(GatewayCapabilityFailureEvidence.retained("GATEWAY_OUTCOME_UNKNOWN", denied).isEmpty())
    }

    @Test fun malformedOrUnboundDiagnosticFieldsAreNotRetained() {
        val code = "GATEWAY_CAPABILITY_UNAVAILABLE"
        val valid = mapOf(GatewayCapabilityFailureEvidence.KIND to JsonPrimitive("DECODE_REJECTED"), GatewayCapabilityFailureEvidence.DECODE_REASON to JsonPrimitive("EXPECTED_BOOLEAN"))
        assertEquals(valid, GatewayCapabilityFailureEvidence.retained(code, JsonObject(valid)))
        for (invalid in listOf(JsonNull, JsonPrimitive(1), JsonPrimitive(false), JsonPrimitive("PRIVATE"))) {
            assertTrue(GatewayCapabilityFailureEvidence.retained(code, JsonObject(valid + (GatewayCapabilityFailureEvidence.KIND to invalid))).isEmpty())
            assertTrue(GatewayCapabilityFailureEvidence.retained(code, JsonObject(valid + (GatewayCapabilityFailureEvidence.DECODE_REASON to invalid))).isEmpty())
        }
        assertTrue(GatewayCapabilityFailureEvidence.retained(code, JsonObject(valid - GatewayCapabilityFailureEvidence.KIND)).isEmpty())
        assertTrue(GatewayCapabilityFailureEvidence.retained(code, JsonObject(valid + (GatewayCapabilityFailureEvidence.KIND to JsonPrimitive("TIMEOUT")))).isEmpty())
    }
}
