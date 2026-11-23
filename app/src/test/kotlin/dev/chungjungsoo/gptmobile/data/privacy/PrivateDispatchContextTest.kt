package dev.chungjungsoo.gptmobile.data.privacy

import dev.chungjungsoo.gptmobile.data.agent.provider.toolRoundSystemPrompt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
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

    @Test fun shortNameCollisionsAreAllowedOnlyInExactAppOwnedSystemInstructions() {
        val (proof, system, user) = shortNameFixture()
        val transformed = requireNotNull(toolRoundSystemPrompt(system, false))
        assertTrue(system.contains("an amount"))
        assertFalse(user.contains("An"))
        val wires = listOf(
            chatWire(system, user),
            chatWire(transformed, user),
            chatWire(system, user, "developer"),
            JsonObject(mapOf("messages" to JsonArray(listOf(message("system", JsonArray(listOf(textPart(system)))), message("user", JsonPrimitive(user)))))),
            JsonObject(mapOf("system" to JsonPrimitive(system), "messages" to JsonArray(listOf(message("user", JsonPrimitive(user)))))),
            JsonObject(mapOf("system" to JsonArray(listOf(textPart(transformed))), "messages" to JsonArray(listOf(message("user", JsonPrimitive(user)))))),
            JsonObject(mapOf("instructions" to JsonPrimitive(transformed), "input" to JsonPrimitive(user))),
            JsonObject(mapOf("systemInstruction" to JsonObject(mapOf("parts" to JsonArray(listOf(textPart(transformed))))), "contents" to JsonArray(listOf(textPart(user)))))
        )
        wires.forEach { wire ->
            val receipt = proof.validate(wire.toString(), ":privacy-7")
            assertEquals(JsonPrimitive("KNOWN_VALUES_ABSENT"), receipt["outcome"])
            assertEquals(JsonPrimitive(1), receipt["known_private_values"])
            assertEquals(JsonPrimitive(1), receipt["validated_app_owned_public_suffixes"])
            assertEquals(JsonPrimitive("EXACT_REGISTERED_SYSTEM_VALUES"), receipt["app_owned_public_suffix_scope"])
            assertEquals(JsonPrimitive("TEXT_SURFACES_EXCLUDING_VALIDATED_APP_OWNED_PUBLIC_SUFFIXES"), receipt["known_value_scope"])
            assertEquals(JsonPrimitive(wire.toString().encodeToByteArray().size), receipt["wire_bytes"])
            assertFalse(receipt.toString().contains("My name is"))
            assertFalse(receipt.toString().contains("App local substitution protocol"))
        }
        val full = proof.validate(chatWire(system, user).toString(), ":privacy-7")
        val withoutPublicSuffix = proof.validate(chatWire("Follow the requested format.", user).toString(), ":privacy-7")
        assertNotEquals(full["payload_hmac"], withoutPublicSuffix["payload_hmac"])
        assertEquals(JsonPrimitive(0), withoutPublicSuffix["validated_app_owned_public_suffixes"])
    }

    @Test fun registeredPublicProtocolDoesNotExemptReintroducedNamesOrCopiedUserAndToolText() {
        val (proof, system, user) = shortNameFixture()
        val wires = listOf(
            chatWire(system, "An"),
            chatWire(system, "AN"),
            chatWire(system, system),
            JsonObject(mapOf("messages" to JsonArray(listOf(message("assistant", JsonPrimitive(system)), message("user", JsonPrimitive(user)))))),
            JsonObject(mapOf("tools" to JsonArray(listOf(JsonObject(mapOf("description" to JsonPrimitive(system))))))),
            JsonObject(mapOf("tools" to JsonArray(listOf(JsonObject(mapOf("arguments" to JsonPrimitive(chatWire(system, user).toString()))))))),
            JsonObject(mapOf("messages" to JsonArray(listOf(message("system", JsonPrimitive(system)), message("assistant", JsonPrimitive("An")), message("user", JsonPrimitive(user))))))
        )
        wires.forEach { wire ->
            assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), reject { proof.validate(wire.toString(), ":privacy-7") }.receipt["outcome"])
        }
        assertEquals(
            JsonPrimitive("KNOWN_VALUE_REINTRODUCED"),
            reject { proof.validate("{\"tools\":[{\"arguments\":\"{\\\"name\\\":\\\"\\\\u0041n\\\"}\"}]}", ":privacy-7") }.receipt["outcome"]
        )
    }

    @Test fun changedSplitOrUntrustedSystemPrefixesLoseThePublicSuffixException() {
        val (proof, system, user) = shortNameFixture()
        val wires = listOf(
            chatWire("An\n\n$system", user),
            chatWire("$system\nAn", user),
            chatWire("$system\nUnregistered transform", user),
            JsonObject(mapOf("system" to JsonArray(listOf(textPart(system.take(system.length / 2)), textPart(system.drop(system.length / 2))))))
        )
        wires.forEach { wire ->
            assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), reject { proof.validate(wire.toString(), ":privacy-7") }.receipt["outcome"])
        }
        listOf("The report belongs to An.", PrivateMarkerProtocol(emptySet()).systemPrompt(null)).forEach { untrustedPrefix ->
            val (bound, complete, maskedUser) = shortNameFixture(untrustedPrefix)
            val failure = reject { bound.validate(chatWire(complete, maskedUser).toString(), ":privacy-7") }
            assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), failure.receipt["outcome"])
            assertEquals(JsonPrimitive(1), failure.receipt["validated_app_owned_public_suffixes"])
        }
    }

    private fun shortNameFixture(systemPrefix: String? = "Follow the requested format."): Triple<PrivateDispatchContext, String, String> {
        val input = "My name is An."
        val mask = PrivateTextMask(setOf(PrivacyCategory.IDENTITIES), emptyList())
        val protected = mask.protectWithReceipt(input)
        val builder = PrivateDispatchContext.Builder(7, setOf(PrivacyCategory.IDENTITIES))
        builder.record(PrivateDispatchSurfaceKind.USER_MESSAGE, input, protected)
        val system = builder.systemPrompt(PrivateMarkerProtocol(mask.issuedMarkers()), systemPrefix)
        return Triple(builder.seal(mask.summary(), mask.issuedMarkers()), system, protected.text)
    }

    private fun textPart(text: String) = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))

    private fun message(role: String, content: kotlinx.serialization.json.JsonElement) =
        JsonObject(mapOf("role" to JsonPrimitive(role), "content" to content))

    private fun chatWire(system: String, user: String, systemRole: String = "system") =
        JsonObject(mapOf("messages" to JsonArray(listOf(message(systemRole, JsonPrimitive(system)), message("user", JsonPrimitive(user))))))

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
