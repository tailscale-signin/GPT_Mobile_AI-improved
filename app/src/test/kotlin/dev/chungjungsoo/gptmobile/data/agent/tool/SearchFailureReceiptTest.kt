package dev.chungjungsoo.gptmobile.data.agent.tool

import java.io.IOException
import java.net.SocketException
import java.net.UnknownHostException
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFailureReceiptTest {
    @Test fun nestedDnsFailureIsNotProviderRejectionOrDispatchedWork() {
        val failure = SearchTransportFailure.from(IOException("wrapped", UnknownHostException("private-host")))
        assertEquals("DNS_FAILED", failure.code)
        assertEquals("DNS", failure.phase)
        assertEquals(false, failure.requestSent)
        assertFalse(failure.message.contains("private-host"))
        val receipt = searchOperationReceipt("search-1", "provider", failure.phase, failure.requestSent, false, failure.code)
        assertEquals(JsonPrimitive("PRECHECK_REJECTED"), receipt["state"])
        assertEquals(JsonPrimitive(true), receipt["retryable"])
    }

    @Test fun abortedSearchPreservesUncertainTransmission() {
        val failure = SearchTransportFailure.from(SocketException("Software caused connection abort"))
        assertEquals("STREAM_INTERRUPTED", failure.code)
        val receipt = searchOperationReceipt("search-2", "provider", failure.phase, failure.requestSent, false, failure.code)
        assertEquals(JsonPrimitive("UNKNOWN_OUTCOME"), receipt["state"])
        assertEquals(JsonNull, receipt["requestSent"])
    }

    @Test fun providerRejectionsAndMalformedResponseRetainKnownResponseReceipt() {
        for ((code, status) in listOf("AUTH_REQUIRED" to 403, "PARSE_FAILED" to 200)) {
            val receipt = searchOperationReceipt("search-3", "provider", "PARSING", true, true, code, status, "req-123:abc")
            assertEquals(JsonPrimitive("FAILED"), receipt["state"])
            assertEquals(JsonPrimitive(status), receipt["httpStatus"])
            assertEquals(JsonPrimitive("req-123:abc"), receipt["requestId"])
            assertEquals(JsonPrimitive(false), receipt["retryable"])
        }
    }

    @Test fun requestIdentifiersCannotExposeHeadersOrArbitraryProviderText() {
        val receipt = searchOperationReceipt("search-4", "provider", "RESPONSE", true, false, "UNAVAILABLE", 503, "Bearer secret\nprivate query")
        assertFalse(receipt.containsKey("requestId"))
    }

    @Test fun healthRegistryStripsEndpointCredentialsQueriesAndFragments() {
        SearchServiceHealth.record("privacy-fixture", SearchServiceObservation("provider", "https://user:secret@example.org/search?api_key=private#private", "DNS_FAILED", requestSent = false, bodyReceived = false, observedAt = 1, message = "DNS failed"))
        val observation = requireNotNull(SearchServiceHealth.observations.value["privacy-fixture"])
        assertEquals("https://example.org", observation.route)
        assertTrue(searchFailureRetryable(observation.outcome))
    }
}
