package dev.chungjungsoo.gptmobile.data.accounting

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCallReceiptTest {
    @Test fun toolOnlyRoundRetainsFinishReasonCountsAndUnavailableBreakdown() {
        val receipt = ProviderCallReceipt("round", finishReason = "tool_calls", roundOutcome = "TOOL_CALLS_PRODUCED", toolCallCount = 2, reasoningBytes = 128).json()
        assertEquals(JsonPrimitive("tool_calls"), receipt["finishReason"])
        assertEquals(JsonPrimitive("TOOL_CALLS_PRODUCED"), receipt["roundOutcome"])
        assertEquals(JsonPrimitive(2), receipt["toolCallCount"])
        assertEquals(JsonPrimitive(0), receipt["visibleBytes"])
        assertTrue(receipt.getValue("usageBreakdownUnavailableFields").toString().contains("toolCallTokens"))
    }

    @Test fun missingServerEvidenceProducesExplicitPartialReceiptWithoutInventingUsageOrSettings() {
        val receipt = ProviderCallReceipt("local-invocation", outputCap = 512, outputCapSource = "CONFIGURED").json()
        assertEquals(JsonPrimitive("local-invocation"), receipt["requestId"])
        assertEquals(JsonPrimitive("CLIENT"), receipt["requestIdSource"])
        assertEquals(JsonPrimitive("PARTIAL"), receipt["receiptStatus"])
        assertEquals(JsonNull, receipt["finishReason"])
        assertEquals(JsonNull, receipt["inputTokens"])
        assertEquals(JsonPrimitive("unreported"), receipt["inputSource"])
        assertEquals(JsonPrimitive(false), receipt["settingsConfirmed"])
        assertTrue(receipt.keys.containsAll(setOf("contextTokens", "outputCap", "reasoningState", "runtime", "accelerator", "unverifiedFields")))
    }

    @Test fun confirmedValuesRetainTheirProvenanceAndServerIdentity() {
        val receipt = ProviderCallReceipt("client", "server-id", "stop", 20, 10, "provider_reported", "provider_reported", true, 8192, "VERIFIED_ROUTE", 512, "SERIALIZED_REQUEST", "disabled", true, "llama.cpp", "cpu").json()
        assertEquals(JsonPrimitive("server-id"), receipt["requestId"])
        assertEquals(JsonPrimitive("SERVER"), receipt["requestIdSource"])
        assertEquals(JsonPrimitive("COMPLETE"), receipt["receiptStatus"])
        assertEquals(JsonPrimitive(8192), receipt["contextTokens"])
    }
}
