package dev.chungjungsoo.gptmobile.data.privacy

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class PrivateModalDispatchTest {
    @Test fun copiedOrdinaryModalPassesRealFinalValidationAndRestoresProtectedNames() {
        val fixture = Fixture()
        val protected = fixture.protected.single()
        assertEquals(2, protected.receipt.ranges.size)
        assertFalse(protected.text.contains("Clara May"))
        assertFalse(protected.text.contains("May said"))
        assertTrue(protected.text.contains("I may request a refund."))
        val receipt = fixture.proof.validate(fixture.wire(), REVISION)
        assertEquals(JsonPrimitive("KNOWN_VALUES_ABSENT"), receipt["outcome"])
        assertEquals(JsonPrimitive(1), receipt["retained_ordinary_modal_occurrences"])
        assertEquals(JsonPrimitive(1), receipt["validated_ordinary_modal_surfaces"])
        assertEquals(JsonPrimitive("TEXT_SURFACES_EXCLUDING_VALIDATED_PUBLIC_SUFFIXES_AND_PROVEN_ORDINARY_MODAL_OCCURRENCES"), receipt["known_value_scope"])
        assertEquals(INPUT, fixture.mask.sealWithReceipts()(protected.text).text)
        assertFalse(receipt.toString().contains("Clara"))
        assertFalse(receipt.toString().contains("I may request"))
        assertFalse(receipt.toString().contains("PRIVATE_"))
    }

    @Test fun actualTextBlockAndGooglePartShapesKeepTheSameOccurrenceBinding() {
        val fixture = Fixture()
        val text = fixture.protected.single().text
        val wires = listOf(
            fixture.wire(),
            wire(message("user", JsonArray(listOf(textPart(text))))),
            JsonObject(mapOf("contents" to JsonArray(listOf(JsonObject(mapOf("role" to JsonPrimitive("user"), "parts" to JsonArray(listOf(JsonObject(mapOf("text" to JsonPrimitive(text))))))))))).toString()
        )
        wires.forEach { payload ->
            val receipt = fixture.proof.validate(payload, REVISION)
            assertEquals(JsonPrimitive(1), receipt["retained_ordinary_modal_occurrences"])
        }
        assertEquals(3, wires.map { fixture.proof.validate(it, REVISION)["payload_hmac"] }.distinct().size)
    }

    @Test fun repeatedValidationResetsQuotaButKeepsFullPayloadHmacSensitive() {
        val fixture = Fixture()
        val payload = fixture.wire()
        val first = fixture.proof.validate(payload, REVISION)
        repeat(3) {
            val next = fixture.proof.validate(payload, REVISION)
            assertEquals(first["payload_hmac"], next["payload_hmac"])
            assertEquals(JsonPrimitive(1), next["retained_ordinary_modal_occurrences"])
        }
        val changedEnvelope = payload.dropLast(1) + ",\"temperature\":0.1}"
        val changed = fixture.proof.validate(changedEnvelope, REVISION)
        assertNotEquals(first["payload_hmac"], changed["payload_hmac"])
        assertEquals(JsonPrimitive(changedEnvelope.encodeToByteArray().size), changed["wire_bytes"])
    }

    @Test fun recognizingATextBlockPreservesTheExistingScanOfItsOtherFields() {
        val fixture = Fixture(listOf("My name is Text. Please draft a reply."))
        assertEquals(JsonPrimitive("KNOWN_VALUES_ABSENT"), fixture.proof.validate(fixture.wire(), REVISION)["outcome"])
        // The prior content-surface scan also examines block metadata. Recognizing
        // a text leaf must not silently remove those fields from known-value checks.
        rejected(fixture.proof, wire(message("user", JsonArray(listOf(textPart(fixture.protected.single().text))))))
    }

    @Test fun changedSourceAndPrivateValueReinsertionCannotBorrowTheModalException() {
        val fixture = Fixture()
        val text = fixture.protected.single().text
        val token = fixture.mask.issuedMarkers().first()
        listOf(
            text.replace(token, "Clara May"),
            "$text May",
            text.replace("I may request", "I MAY request"),
            text.replace("I may request", "I may  request"),
            "Moved prefix. $text",
            "$text M${token}ay"
        ).forEach { changed -> rejected(fixture.proof, wire(message("user", JsonPrimitive(changed)))) }
    }

    @Test fun movedRolesUnknownFieldsAndNestedJsonDoNotInheritOriginalSurfaceAuthority() {
        val fixture = Fixture()
        val text = fixture.protected.single().text
        listOf("assistant", "system", "developer", "tool").forEach { role ->
            rejected(fixture.proof, wire(message(role, JsonPrimitive(text))))
        }
        val embedded = JsonObject(mapOf("text" to JsonPrimitive(text))).toString()
        listOf(
            JsonObject(mapOf("input" to JsonPrimitive(text))).toString(),
            JsonObject(mapOf("tools" to JsonArray(listOf(JsonObject(mapOf("arguments" to JsonPrimitive(embedded))))))).toString(),
            wire(message("user", JsonPrimitive(embedded))),
            wire(message("user", JsonArray(listOf(JsonObject(mapOf("type" to JsonPrimitive("tool_result"), "content" to JsonPrimitive(text)))))))
        ).forEach { payload -> rejected(fixture.proof, payload) }
    }

    @Test fun duplicateWholeMessagesAndDuplicateTextPartsExceedTheRecordedOccurrenceQuota() {
        val fixture = Fixture()
        val text = fixture.protected.single().text
        rejected(fixture.proof, wire(message("user", JsonPrimitive(text)), message("user", JsonPrimitive(text))))
        rejected(fixture.proof, wire(message("user", JsonArray(listOf(textPart(text), textPart(text))))))
    }

    @Test fun sameRoleReorderingCannotMoveAnApprovedOccurrenceToAnotherOrdinal() {
        val fixture = Fixture(listOf("Keep this public preface.", INPUT))
        assertEquals(JsonPrimitive(1), fixture.proof.validate(fixture.wire(), REVISION)["retained_ordinary_modal_occurrences"])
        rejected(fixture.proof, wire(*fixture.protected.reversed().map { message("user", JsonPrimitive(it.text)) }.toTypedArray()))
    }

    @Test fun registeredAssistantHistoryKeepsItsRoleAndOrdinal() {
        val fixture = Fixture(kinds = listOf(PrivateDispatchSurfaceKind.ASSISTANT_HISTORY))
        assertEquals(JsonPrimitive(1), fixture.proof.validate(fixture.wire(), REVISION)["retained_ordinary_modal_occurrences"])
        rejected(fixture.proof, wire(message("user", JsonPrimitive(fixture.protected.single().text))))
    }

    @Test fun forgedOrMissingLocalCapabilityCannotApproveAnUnprotectedGap() {
        val fixture = Fixture()
        val original = fixture.protected.single()
        val absent = original.copy(ordinaryModalProof = null)
        val withoutProof = PrivateDispatchContext.Builder(7, CATEGORIES).apply {
            record(PrivateDispatchSurfaceKind.USER_MESSAGE, INPUT, absent)
        }.seal(fixture.mask.summary(), fixture.mask.issuedMarkers())
        val fakeWireMetadata = fixture.wire().dropLast(1) + ",\"ordinaryModalProof\":{\"approved\":true}}"
        rejected(withoutProof, fakeWireMetadata)
        val start = original.text.indexOf("may request")
        val fake = PrivateOrdinaryModalProof(
            Any(),
            original.receipt.contentDigest,
            original.text,
            "[PRIVATE_",
            fixture.mask.issuedMarkers(),
            listOf(PrivacyProtectedRange(INPUT.indexOf("may request"), INPUT.indexOf("may request") + 3) to PrivacyProtectedRange(start, start + 3))
        )
        rejectedRecord("PRIVATE_MODAL_SOURCE_INVALID") {
            PrivateDispatchContext.Builder(7, CATEGORIES).record(PrivateDispatchSurfaceKind.USER_MESSAGE, INPUT, original.copy(ordinaryModalProof = fake))
        }
    }

    @Test fun changedMaskedBytesCannotReuseTheOriginalCopyCapability() {
        val fixture = Fixture()
        val original = fixture.protected.single()
        rejectedRecord("PRIVATE_MODAL_SOURCE_INVALID") {
            PrivateDispatchContext.Builder(7, CATEGORIES).record(PrivateDispatchSurfaceKind.USER_MESSAGE, INPUT, original.copy(text = original.text + " May"))
        }
    }

    @Test fun foreignRequestCapabilityAndForeignTokensCannotBorrowCurrentAuthorization() {
        val first = Fixture()
        val second = Fixture()
        rejectedRecord("PRIVATE_MODAL_REQUEST_MISMATCH") {
            PrivateDispatchContext.Builder(7, CATEGORIES).apply {
                record(PrivateDispatchSurfaceKind.USER_MESSAGE, INPUT, first.protected.single())
            }.seal(second.mask.summary(), second.mask.issuedMarkers())
        }
        rejected(second.proof, first.wire())
    }

    @Test fun explicitPrivateTermsAndEveryProtectedOverlapWinOverOrdinaryGrammar() {
        listOf("may", "ay").forEach { privateTerm ->
            val fixture = Fixture(categories = CATEGORIES + PrivacyCategory.CUSTOM, terms = listOf(privateTerm))
            assertNull(fixture.protected.single().ordinaryModalProof)
            assertFalse(fixture.protected.single().text.contains("I may request"))
            assertEquals(JsonPrimitive(0), fixture.proof.validate(fixture.wire(), REVISION)["retained_ordinary_modal_occurrences"])
            rejected(fixture.proof, wire(message("user", JsonPrimitive(fixture.protected.single().text + " may"))))
        }
    }

    @Test fun aKnownAddressOrUnclassifiedSurnameMissedOnAnotherSurfaceStillRejects() {
        val address = "Home address: Willowbank; please draft a note."
        listOf("Please consider Willowbank.", "May remains relevant.").forEach { missed ->
            val fixture = Fixture(listOf(INPUT, address, missed), CATEGORIES + PrivacyCategory.LOCATIONS)
            assertTrue(fixture.protected[1].receipt.ranges.any { address.substring(it.start, it.endExclusive) == "Willowbank" })
            assertEquals(missed, fixture.protected.last().text)
            assertNull(fixture.protected.last().ordinaryModalProof)
            rejected(fixture.proof, fixture.wire())
        }
    }

    @Test fun unsupportedModalGrammarAndAbsentSurnameDoNotMintPublicEvidence() {
        val unsupported = Fixture(listOf(INPUT.replace("may request", "may ask")))
        assertNull(unsupported.protected.single().ordinaryModalProof)
        rejected(unsupported.proof, unsupported.wire())
        val noSurname = PrivateTextMask(CATEGORIES, emptyList()).protectWithReceipt("I may request a refund.")
        assertNull(noSurname.ordinaryModalProof)
        val explicitGivenName = Fixture(listOf("My name is May. I may request a refund."))
        assertNull(explicitGivenName.protected.single().ordinaryModalProof)
        assertFalse(explicitGivenName.protected.single().text.contains("may request"))
    }

    @Test fun adjacentProtectedRangesKeepActualCopyOffsetsWithoutAssumingOneMarkerPerReceiptRange() {
        val input = "$INPUT §¤"
        val fixture = Fixture(listOf(input), CATEGORIES + PrivacyCategory.CUSTOM, listOf("§", "¤"))
        val last = fixture.protected.single().receipt.ranges.last()
        assertEquals("§¤", input.substring(last.start, last.endExclusive))
        assertEquals(4, fixture.mask.issuedMarkers().size)
        assertEquals(JsonPrimitive(1), fixture.proof.validate(fixture.wire(), REVISION)["retained_ordinary_modal_occurrences"])
        assertEquals(input, fixture.mask.sealWithReceipts()(fixture.protected.single().text).text)
    }

    private class Fixture(
        inputs: List<String> = listOf(INPUT),
        categories: Set<PrivacyCategory> = CATEGORIES,
        terms: List<String> = emptyList(),
        private val kinds: List<PrivateDispatchSurfaceKind> = inputs.map { PrivateDispatchSurfaceKind.USER_MESSAGE }
    ) {
        val mask = PrivateTextMask(categories, terms).also { it.seedIdentities(inputs) }
        val protected = inputs.map(mask::protectWithReceipt)
        val proof = PrivateDispatchContext.Builder(7, categories).apply {
            inputs.forEachIndexed { index, input -> record(kinds[index], input, protected[index]) }
        }.seal(mask.summary(), mask.issuedMarkers())

        fun wire(): String = wire(*protected.mapIndexed { index, text -> message(if (kinds[index] == PrivateDispatchSurfaceKind.ASSISTANT_HISTORY) "assistant" else "user", JsonPrimitive(text.text)) }.toTypedArray())
    }

    companion object {
        private const val INPUT = "My name is Clara May. May said that I may request a refund."
        private const val REVISION = "source-1:privacy-7"
        private val CATEGORIES = setOf(PrivacyCategory.IDENTITIES)

        private fun message(role: String, content: JsonElement) = JsonObject(mapOf("role" to JsonPrimitive(role), "content" to content))
        private fun textPart(text: String) = JsonObject(mapOf("type" to JsonPrimitive("text"), "text" to JsonPrimitive(text)))
        private fun wire(vararg messages: JsonObject) = JsonObject(mapOf("messages" to JsonArray(messages.toList()))).toString()

        private fun rejected(proof: PrivateDispatchContext, payload: String) {
            try {
                proof.validate(payload, REVISION)
                fail("Expected known-value rejection")
            } catch (expected: PrivateDispatchRejected) {
                assertEquals(JsonPrimitive("KNOWN_VALUE_REINTRODUCED"), expected.receipt["outcome"])
            }
        }

        private fun rejectedRecord(code: String, action: () -> Unit) {
            try {
                action()
                fail("Expected invalid local provenance")
            } catch (expected: IllegalArgumentException) {
                assertEquals(code, expected.message)
            }
        }
    }
}
