package dev.chungjungsoo.gptmobile.data.privacy

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrivateDispatchContextTest {
    private fun fixture(): Pair<PrivateDispatchContext, String> {
        val input = "Name: Mira Voss. Please draft a reply."
        val mask = PrivateTextMask(setOf(PrivacyCategory.IDENTITIES), emptyList())
        val protected = mask.protectWithReceipt(input)
        val proof = PrivateDispatchContext.Builder(7, setOf(PrivacyCategory.IDENTITIES)).apply {
            record(PrivateDispatchSurfaceKind.USER_MESSAGE, input, protected)
        }.seal(mask.summary(), mask.issuedMarkers())
        return proof to "{\"messages\":[{\"role\":\"user\",\"content\":${JsonPrimitive(protected.text)}}]}"
    }

    @Test fun finalWireReceiptContainsCountsButNoOriginalTextOrUnkeyedFingerprint() {
        val (proof, wire) = fixture()
        val receipt = proof.validate(wire, "source-12:privacy-7")
        assertEquals("KNOWN_VALUES_ABSENT", (receipt["outcome"] as JsonPrimitive).content)
        assertEquals(PrivateDispatchContext.COVERAGE, (receipt["coverage"] as JsonPrimitive).content)
        assertEquals("NOT_ASSESSED", (receipt["unrecognized_sensitive_values"] as JsonPrimitive).content)
        assertFalse(receipt.toString().contains("Mira"))
        assertFalse(receipt.toString().contains("draft a reply"))
        assertFalse(receipt.toString().contains("PRIVATE_"))
        assertEquals(receipt["payload_hmac"], proof.validate(wire, "source-12:privacy-7")["payload_hmac"])
        val (second, _) = fixture()
        assertNotEquals(receipt["payload_hmac"], second.validate(wire, "source-12:privacy-7")["payload_hmac"])
    }

    @Test fun finalSerializerCannotReinsertKnownOriginalThroughUnicodeEscapesOrNestedToolArguments() {
        val (proof, _) = fixture()
        listOf(
            """{"messages":[{"role":"user","content":"Mira Voss"}]}""",
            """{"messages":[{"role":"user","content":"\u004dira Voss"}]}""",
            """{"tools":[{"arguments":"{\"name\":\"\\u004dira Voss\"}"}]}"""
        ).forEach { wire ->
            val rejected = reject { proof.validate(wire, "source-12:privacy-7") }
            assertEquals("KNOWN_VALUE_REINTRODUCED", (rejected.receipt["outcome"] as JsonPrimitive).content)
            assertFalse(rejected.message.orEmpty().contains("Mira"))
        }
    }

    @Test fun changedPolicyAndMalformedFinalBodyFailBeforeDispatchWithContentFreeEvidence() {
        val (proof, wire) = fixture()
        assertEquals("POLICY_REVISION_MISMATCH", (reject { proof.validate(wire, "source-12:privacy-8") }.receipt["outcome"] as JsonPrimitive).content)
        assertEquals("POLICY_REVISION_MISMATCH", (reject { proof.validate(wire, "source-12:privacy-70") }.receipt["outcome"] as JsonPrimitive).content)
        assertEquals("WIRE_VALIDATION_UNAVAILABLE", (reject { proof.validate("invalid Mira Voss", "privacy:privacy-7") }.receipt["outcome"] as JsonPrimitive).content)
        assertEquals("NO_SUPPORTED_TEXT_SURFACES", (reject { proof.validate("""{"vendor":{"value":"Mira Voss"}}""", "privacy:privacy-7") }.receipt["outcome"] as JsonPrimitive).content)
    }

    @Test fun countsIncludeHistoryAndSystemEvenWhenNoSpanWasDetected() {
        val mask = PrivateTextMask(emptySet(), emptyList())
        val builder = PrivateDispatchContext.Builder(1, emptySet())
        listOf(PrivateDispatchSurfaceKind.ASSISTANT_HISTORY, PrivateDispatchSurfaceKind.SYSTEM_PROMPT, PrivateDispatchSurfaceKind.TOOL_RESULT).forEach { kind ->
            builder.record(kind, "Public text", mask.protectWithReceipt("Public text"))
        }
        val receipt = builder.seal(mask.summary(), mask.issuedMarkers()).validate("""{"system":"Public text"}""", ":privacy-1")
        assertEquals("0", (receipt["known_private_values"] as JsonPrimitive).content)
        assertEquals("0", (receipt["protected_ranges"] as JsonPrimitive).content)
        assertTrue(receipt["surface_counts"].toString().contains("ASSISTANT_HISTORY"))
        assertTrue(receipt.toString().contains("NOT_ASSESSED"))
    }

    @Test fun staleSourceReceiptAndReuseOfSealedBuilderCannotAuthorizeNewText() {
        val builder = PrivateDispatchContext.Builder(1, emptySet())
        try {
            builder.record(PrivateDispatchSurfaceKind.USER_MESSAGE, "Changed original", PrivateProtectedText("safe", PrivacyProtectionReceipt.create("Earlier original", emptyList())))
            fail("Expected a content-bound source receipt")
        } catch (expected: IllegalArgumentException) {
            assertEquals("PRIVATE_SOURCE_RECEIPT_INVALID", expected.message)
        }
        builder.seal()
        try {
            builder.record(PrivateDispatchSurfaceKind.USER_MESSAGE, "new", PrivateProtectedText("new", PrivacyProtectionReceipt.create("new", emptyList())))
            fail("Expected a sealed lifetime")
        } catch (expected: IllegalStateException) {
            assertEquals("PRIVATE_DISPATCH_BUILDER_SEALED", expected.message)
        }
    }

    private fun reject(block: () -> Unit): PrivateDispatchRejected {
        try {
            block()
            fail("Expected final private wire rejection")
        } catch (expected: PrivateDispatchRejected) {
            return expected
        }
        error("unreachable")
    }
}
