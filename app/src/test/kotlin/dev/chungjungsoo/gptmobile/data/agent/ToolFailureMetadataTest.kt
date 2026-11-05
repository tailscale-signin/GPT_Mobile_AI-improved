package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolFailureMetadataTest {
    @Test fun searchTransportUncertaintyRemainsUnknownWhileReadRetryIsSafe() {
        val receipt = buildJsonObject {
            put("schema", "gptmobile.search-operation.v1")
            put("callId", "search-1")
            put("state", "UNKNOWN_OUTCOME")
            put("requestSent", JsonNull)
            put("bodyReceived", false)
        }
        val result = ToolFailureMetadata.receipt(AgentToolResult("search-1", ToolResultContent.Text("Response timed out"), true, errorCode = "TIMEOUT", executionReceipt = receipt), "web_search", replaySafe = true)
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(result))
        assertTrue(ToolFailureMetadata.retryable(result))
        val envelope = kotlinx.serialization.json.Json.parseToJsonElement(ToolResultEnvelope.encode("web_search", result)).jsonObject
        assertEquals(JsonPrimitive("unknown"), envelope.getValue("execution").jsonObject["outcome"])
        assertEquals(JsonPrimitive(false), envelope.getValue("error").jsonObject["reconciliationRequired"])
    }

    @Test fun aDifferentCallOrArbitraryReceiptCannotChangeOutcomeCertainty() {
        val receipt = buildJsonObject {
            put("schema", "gptmobile.search-operation.v1")
            put("callId", "another-call")
            put("state", "PRECHECK_REJECTED")
            put("requestSent", false)
        }
        val result = AgentToolResult("write", ToolResultContent.Text("Failed"), true, errorCode = "DISPATCH_OUTCOME_UNKNOWN", executionReceipt = receipt)
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(result))
        assertFalse(ToolFailureMetadata.retryable(result))
    }

    @Test fun failedResultsAlwaysHaveANormalizedCodeIncludingLegacyNone() {
        for (code in listOf(null, "", "none", " NONE ", "unknown")) {
            val result = ToolFailureMetadata.normalize(AgentToolResult("call", ToolResultContent.Text("UnknownHostException: source.example"), true, errorCode = code))
            assertEquals("DNS_RESOLUTION_FAILED", result.errorCode)
        }
    }

    @Test fun gatewayAmbiguityIsNeverReclassifiedAsASafeTransportRetry() {
        val code = ToolFailureMetadata.classify(java.io.IOException("GATEWAY_OUTCOME_UNKNOWN: response timed out"))
        val result = ToolFailureMetadata.receipt(AgentToolResult("call", ToolResultContent.Text("Gateway outcome unknown"), true, errorCode = code), "gateway", replaySafe = true)
        assertEquals("GATEWAY_OUTCOME_UNKNOWN", code)
        assertEquals("UNKNOWN_OUTCOME", ToolResultEnvelope.executionState(result))
        assertFalse(ToolFailureMetadata.retryable(result))
    }

    @Test fun dnsPreflightRemainsEligibleForAControlledReadRetryAfterConnectivityReturns() {
        val result = ToolFailureMetadata.receipt(AgentToolResult("search", ToolResultContent.Text("DNS preflight rejected"), true, errorCode = "DNS_FAILED", dispatched = false), "web_search", replaySafe = true)
        assertEquals("PRECHECK_REJECTED", ToolResultEnvelope.executionState(result))
        assertTrue(ToolFailureMetadata.retryable(result))
    }
}
