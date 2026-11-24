package dev.chungjungsoo.gptmobile.data.privacy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivateStreamingRestorerTest {
    @Test
    fun everyMarkerSplitRestoresExactlyOnceWithoutPublishingAPartialToken() {
        val mask = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Zoë 李"))
        val token = mask.protect("Zoë 李")
        val issued = mask.issuedMarkers()
        val restore = mask.sealWithReceipts()
        for (split in 0..token.length) {
            val stream = PrivateStreamingRestorer(PrivateMarkerProtocol(issued), restore)
            val first = stream.accept("Hello " + token.take(split))
            val second = stream.accept(token.drop(split) + "!")
            val last = stream.finish()
            assertEquals("split=$split", "Hello Zoë 李!", first.text + second.text + last.text)
            listOf(first, second, last).forEach { result ->
                assertFalse(result.text.contains("[PRIVATE_"))
                assertTrue(result.receipt.validFor(result.text))
            }
            assertTrue(stream.maximumHeldCharacters <= PrivateMarkerProtocol.MAX_BUFFERED_MARKER_CHARS)
        }
    }

    @Test
    fun ordinaryProseIsPublishedImmediatelyWhileOnlyTheUnfinishedTokenWaits() {
        val fixture = fixture()
        val prefix = "Public answer first. "
        assertEquals(prefix, fixture.stream.accept(prefix + fixture.token.dropLast(2)).text)
        assertEquals("Alice", fixture.stream.accept(fixture.token.takeLast(2)).text)
        assertEquals(" Done.", fixture.stream.accept(" Done.").text)
        assertEquals("", fixture.stream.finish().text)
    }

    @Test
    fun legacyAndTypedTokensHaveTheSameStreamingBoundaryContract() {
        for (token in listOf("[PRIVATE_" + "a".repeat(32) + "_7]", "[PRIVATE_" + "b".repeat(32) + "_PERSON_7]")) {
            val stream = PrivateStreamingRestorer(PrivateMarkerProtocol(setOf(token)), { raw ->
                val text = raw.replace(token, "Alice")
                val start = text.indexOf("Alice")
                PrivateProtectedText(text, PrivacyProtectionReceipt.create(text, if (start < 0) emptyList() else listOf(PrivacyProtectedRange(start, start + 5))))
            })
            val output = token.map { stream.accept(it.toString()).text }.joinToString("") + stream.finish().text
            assertEquals("Alice", output)
        }
    }

    @Test
    fun foreignAndUnknownNamespacesStayLiteralAndCannotClaimLocalValues() {
        val fixture = fixture()
        val foreign = "[PRIVATE_" + "f".repeat(32) + "_PERSON_0]"
        val first = fixture.stream.accept(foreign.take(23))
        val second = fixture.stream.accept(foreign.drop(23))
        assertEquals(foreign, first.text + second.text)
        assertTrue(first.receipt.ranges.isEmpty() && second.receipt.ranges.isEmpty())
    }

    @Test
    fun changedTypeOrIndexInAnOwnedTokenFailsWithoutGuessingTheOriginal() {
        for (alter in listOf<(String) -> String>({ it.replace("_CUSTOM_", "_PERSON_") }, { it.replace("_0]", "_999]") })) {
            val fixture = fixture()
            val failure = runCatching { fixture.stream.accept(alter(fixture.token)) }.exceptionOrNull()
            assertTrue(failure is PrivateOutputProtocolException)
            assertEquals(PrivateMarkerProtocolStatus.REJECTED, fixture.protocol.status())
        }
    }

    @Test
    fun formattingRepairRequiresTheExactIssuedTokenAndIsBounded() {
        val fixture = fixture()
        val formatted = fixture.token.replace("_CUSTOM_", "_CUS\nTOM_")
        val first = fixture.stream.accept(formatted.dropLast(1))
        val last = fixture.stream.accept("]")
        assertEquals("Alice", first.text + last.text)
        assertEquals(1, fixture.protocol.repairedMarkers)

        val excessive = fixture()
        assertTrue(runCatching { excessive.stream.accept(excessive.token.replace("_CUSTOM_", "_CUSTOM" + " ".repeat(9) + "_")) }.exceptionOrNull() is PrivateOutputProtocolException)
    }

    @Test
    fun truncationOfOwnedMarkerFailsButAnOrdinaryUnclosedBracketRemainsText() {
        val fixture = fixture()
        assertEquals("", fixture.stream.accept(fixture.token.dropLast(1)).text)
        assertTrue(runCatching { fixture.stream.finish() }.exceptionOrNull() is PrivateOutputProtocolException)
        val ordinary = fixture()
        assertEquals("Array starts ", ordinary.stream.accept("Array starts [").text)
        assertEquals("[", ordinary.stream.finish().text)
    }

    @Test
    fun cancelledStreamDiscardsItsHeldTokenAndCannotResumeOrAffectAnotherStream() {
        val first = fixture()
        assertEquals("", first.stream.accept(first.token.take(40)).text)
        first.stream.clear()
        assertTrue(runCatching { first.stream.finish() }.exceptionOrNull() is IllegalStateException)
        assertTrue(runCatching { first.stream.accept(first.token.drop(40)) }.exceptionOrNull() is IllegalStateException)
        val second = fixture()
        assertEquals("Alice", second.stream.accept(second.token).text)
        assertFalse(first.token == second.token)
    }

    @Test
    fun protocolExplainsKnownValuesAfterMaskingAndDraftSignalsRemainUnverified() {
        val token = "[PRIVATE_" + "a".repeat(32) + "_PERSON_0]"
        val protocol = PrivateMarkerProtocol(setOf(token), "Write the complete letter.")
        val system = protocol.systemPrompt("The sender is $token.")
        assertTrue(system.startsWith("The sender is $token."))
        assertTrue(system.contains("Never ask the user to reveal"))
        assertTrue(system.contains("copy its exact token"))
        protocol.prepare("Regards, [Your full name].")
        assertTrue(PrivateOutputAnomaly.POSSIBLE_PROTECTED_FIELD_OMISSION in protocol.anomalyCodes())
        assertEquals(PrivateMarkerProtocolStatus.VALIDATED_MARKER_SYNTAX, protocol.status())
        val template = PrivateMarkerProtocol(setOf(token), "Write an example template.")
        template.prepare("Regards, [Your full name].")
        assertTrue(template.anomalyCodes().isEmpty())
    }

    @Test
    fun reasoningTextDoesNotCreateAnAnswerCompletenessFinding() {
        val token = "[PRIVATE_" + "a".repeat(32) + "_PERSON_0]"
        val protocol = PrivateMarkerProtocol(setOf(token), "Write the complete letter.")
        protocol.prepare("Do not write [Your full name].", assessAnswer = false)
        assertTrue(protocol.anomalyCodes().isEmpty())
    }

    @Test
    fun firstReasoningUpstreamAnswerAndRestoredPublicationHaveSeparateContentFreeTimes() {
        var now = 1_000_000L
        val timing = PrivateStreamTiming { now }
        now += 3_000_000L
        timing.reasoning()
        now += 4_000_000L
        timing.answer()
        now += 2_000_000L
        timing.restoredAnswer()
        now += 100_000_000L
        timing.answer()
        val fixture = fixture()
        fixture.stream.accept(fixture.token)
        val reasoning = PrivateStreamingRestorer(fixture.protocol, { PrivateProtectedText(it, PrivacyProtectionReceipt.create(it, emptyList())) }, assessAnswer = false)
        val observation = timing.observation(fixture.stream, reasoning, fixture.protocol, completed = true)
        assertEquals(3L, observation.firstReasoningMillis)
        assertEquals(7L, observation.firstAnswerMillis)
        assertEquals(9L, observation.firstRestoredAnswerMillis)
        val evidence = observation.evidence().toString()
        assertFalse(evidence.contains("Alice") || evidence.contains(fixture.token))
        assertTrue(evidence.contains("UNASSESSED"))
    }

    private data class Fixture(val token: String, val protocol: PrivateMarkerProtocol, val stream: PrivateStreamingRestorer)

    private fun fixture(): Fixture {
        val mask = PrivateTextMask(setOf(PrivacyCategory.CUSTOM), listOf("Alice"))
        val token = mask.protect("Alice")
        val protocol = PrivateMarkerProtocol(mask.issuedMarkers())
        return Fixture(token, protocol, PrivateStreamingRestorer(protocol, mask.sealWithReceipts()))
    }
}
