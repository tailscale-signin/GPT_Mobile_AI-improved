package dev.chungjungsoo.gptmobile.data.network.error

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderStreamClassificationTest {
    @Test fun numericAuthenticationRetainsTheSuccessfulHandshakeAndOriginalDenial() {
        val event = ProviderStreamFailure.event("401", "User not found.", httpStatus = 200)
        val retained = requireNotNull(ProviderStreamFailure.retained(event.failureDetails))
        assertEquals("AUTHENTICATION_FAILED", event.code)
        assertEquals(JsonPrimitive(200), retained["http_status"])
        assertEquals(JsonPrimitive("AUTHENTICATION"), retained["provider_failure_kind"])
        assertEquals(JsonPrimitive("401"), retained["upstream_error_code"])
        assertEquals(IssueReportContext.identifierHash("provider-error-code", "401"), ProviderStreamFailure.upstreamCodeHash(event.failureDetails))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("retryable"))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("request_replay_allowed"))
        assertTrue(event.message.contains("Settings → Platforms"))
    }

    @Test fun unknownUpstreamCodeKeepsBoundedCorrelationWithoutPublishingItsText() {
        val code = "private-upstream-code"
        val event = ProviderStreamFailure.event(code, "Private provider message", httpStatus = 200)
        val retained = requireNotNull(ProviderStreamFailure.retained(event.failureDetails))
        assertEquals("PROVIDER_GENERATION_FAILED", event.code)
        assertEquals(JsonPrimitive("UNKNOWN"), retained["provider_failure_kind"])
        assertEquals(JsonPrimitive(requireNotNull(IssueReportContext.identifierHash("provider-error-code", code))), retained["upstream_error_code_hash"])
        assertFalse(retained.containsKey("upstream_error_code"))
        assertFalse(retained.toString().contains("private", ignoreCase = true))
        assertFalse(retained.toString().contains("message", ignoreCase = true))
        assertEquals(IssueReportContext.identifierHash("provider-error-code", code), ProviderStreamFailure.upstreamCodeHash(event.failureDetails))
    }

    @Test fun unavailableStreamPreservesTheServerCodeAndForbidsAcceptedRequestReplay() {
        val event = ProviderStreamFailure.event("503", "Backend unavailable", httpStatus = 200, responsePayloadObserved = true)
        val retained = requireNotNull(ProviderStreamFailure.retained(event.failureDetails))
        assertEquals("PROVIDER_UNAVAILABLE", event.code)
        assertEquals(JsonPrimitive(200), retained["http_status"])
        assertEquals(JsonPrimitive("503"), retained["upstream_error_code"])
        assertEquals(JsonPrimitive("YES"), event.failureDetails?.get("remote_acceptance"))
        assertEquals(JsonPrimitive(false), event.failureDetails?.get("request_replay_allowed"))
    }

    @Test fun oversizedCodesAndMalformedHashFieldsAreExcludedFromRetainedEvidence() {
        val oversized = ProviderStreamFailure.event("x".repeat(257), "Unknown code", httpStatus = 200)
        assertFalse(requireNotNull(oversized.failureDetails).containsKey("upstream_error_code_hash"))
        for (invalid in listOf(JsonPrimitive("private-text"), kotlinx.serialization.json.Json.parseToJsonElement("1".repeat(64)))) {
            val malformed = kotlinx.serialization.json.JsonObject(
                requireNotNull(oversized.failureDetails) + ("upstream_error_code_hash" to invalid)
            )
            assertFalse(requireNotNull(ProviderStreamFailure.retained(malformed)).containsKey("upstream_error_code_hash"))
            assertEquals(null, ProviderStreamFailure.upstreamCodeHash(malformed))
        }
    }

    @Test fun knownUpstreamCodeOutranksAnInheritedHashFromAnotherFailure() {
        val inherited = kotlinx.serialization.json.JsonObject(mapOf("upstream_error_code_hash" to JsonPrimitive("a".repeat(64))))
        for (code in listOf("401", "503")) {
            val event = ProviderStreamFailure.event(code, "Provider rejected the request", details = inherited, httpStatus = 200)
            assertEquals(IssueReportContext.identifierHash("provider-error-code", code), ProviderStreamFailure.upstreamCodeHash(event.failureDetails))
            assertEquals(JsonPrimitive(code), event.failureDetails?.get("upstream_error_code"))
            assertEquals(JsonPrimitive(200), event.failureDetails?.get("http_status"))
        }
    }

    @Test fun safeErrorTypeClassifiesWithoutReplacingTheActualRawCodeIdentity() {
        val upstream = "private-upstream-a"
        val inherited = kotlinx.serialization.json.JsonObject(mapOf("upstream_error_code" to JsonPrimitive("503")))
        val event = ProviderStreamFailure.event(upstream, "Private provider message", type = "server_error", details = inherited, httpStatus = 200)
        assertEquals("PROVIDER_UNAVAILABLE", event.code)
        assertEquals(JsonPrimitive("UNAVAILABLE"), event.failureDetails?.get("provider_failure_kind"))
        assertFalse(requireNotNull(event.failureDetails).containsKey("upstream_error_code"))
        assertEquals(JsonPrimitive("server_error"), event.failureDetails?.get("upstream_error_type"))
        assertEquals(IssueReportContext.identifierHash("provider-error-code", upstream), ProviderStreamFailure.upstreamCodeHash(event.failureDetails))

        val known = ProviderStreamFailure.event("401", "Provider rejected the request", type = "server_error", httpStatus = 200)
        assertEquals("AUTHENTICATION_FAILED", known.code)
        assertEquals(JsonPrimitive("401"), known.failureDetails?.get("upstream_error_code"))
        assertEquals(JsonPrimitive("server_error"), known.failureDetails?.get("upstream_error_type"))
        assertEquals(IssueReportContext.identifierHash("provider-error-code", "401"), ProviderStreamFailure.upstreamCodeHash(known.failureDetails))
    }
}
