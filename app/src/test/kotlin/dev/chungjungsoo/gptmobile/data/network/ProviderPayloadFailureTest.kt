package dev.chungjungsoo.gptmobile.data.network

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderPayloadFailureTest {
    @Test fun disabledToolsCannotBeReportedAsAConnectionFailure() {
        assertEquals(
            FreeFailureCategory.TOOL_CHOICE_MISMATCH,
            providerPayloadFailure("tool_use_failed", "Attempted tool use with tool_choice none", "none", false)
        )
        assertEquals(
            FreeFailureCategory.PROVIDER_PAYLOAD_ERROR,
            providerPayloadFailure("tool_use_failed", "Invalid function argument", "auto", true)
        )
    }

    @Test fun payloadAuthenticationAndRateLimitsRetainTheirRecoveryCategory() {
        assertEquals(
            FreeFailureCategory.AUTHENTICATION_REJECTED,
            providerPayloadFailure("invalid_api_key", "Rejected", null, false)
        )
        assertEquals(
            FreeFailureCategory.PROVIDER_RATE_LIMITED,
            providerPayloadFailure("429", "Please wait", null, false)
        )
        assertEquals(
            FreeFailureCategory.PROVIDER_PAYLOAD_ERROR,
            providerPayloadFailure("generation_failed", "Upstream failed", null, false)
        )
    }

    @Test fun parserFailuresRemainDistinctFromTransportFailures() {
        assertEquals(
            FreeFailureCategory.OUTPUT_PROTOCOL_INVALID,
            freeFailureCategory(IllegalStateException("OUTPUT_PROTOCOL_INVALID: MALFORMED_JSON_RECORD"))
        )
        assertEquals(
            FreeFailureCategory.DNS_RESOLUTION_FAILED,
            freeFailureCategory(IllegalStateException("DNS unavailable", java.net.UnknownHostException("host")))
        )
        assertEquals(
            FreeFailureCategory.NETWORK_ERROR,
            freeFailureCategory(java.io.IOException("stream ended early"))
        )
    }
}
