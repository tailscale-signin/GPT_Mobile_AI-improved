package dev.chungjungsoo.gptmobile.data.accounting

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderCallReceiptTest {
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
