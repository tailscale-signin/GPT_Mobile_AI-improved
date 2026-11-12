package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechTextTest {
    @Test fun linksUseTheirLabelsAndNeverSpeakAddressesOrQueryValues() {
        val input = "See [the guide](https://example.test/a_(b)?token=private) and <https://example.test/secret>. Visit https://example.test/path?token=secret now."
        val speech = VoiceSpeechText.project(input, final = true)
        assertEquals("See the guide and link. Visit link now.", speech)
        assertFalse(speech.contains("secret"))
        assertFalse(speech.contains("private"))
        assertEquals("link", VoiceSpeechText.project("[https://example.test/private](https://example.test/other)", true))
    }

    @Test fun markupReferencesAndCodeDoNotBecomeSpokenSyntax() {
        val input = "# **Answer**\nUse `fast mode`. [1, 2] <b>Done</b>.\n```json\n{\"key\":\"private\"}\n```\nRead [the source][ref].\n[ref]: https://example.test/private\n"
        val speech = VoiceSpeechText.project(input, true)
        assertEquals(" Answer\nUse fast mode.  Done.\nCode is available in the chat.\nRead the source.\n\n", speech)
        assertFalse(speech.contains("private"))
        assertEquals("Ready. ", VoiceSpeechText.project("Ready. \uE200cite\uE202private_source_id\uE201", true))
        assertEquals("Reference reference", VoiceSpeechText.project("Reference 0123456789abcdef0123456789abcdef", true))
    }

    @Test fun everyStreamingSplitPreservesASafeStablePrefix() {
        val input = "Here are four useful words. Read [the complete guide](https://example.test/hidden_(part)) at https://example.test/private now.\n```kotlin\nprivateCall()\n```\nFinished."
        val expected = VoiceSpeechText.project(input, true)
        var previous = ""
        val buffer = VoiceSpeechBuffer()
        val spoken = mutableListOf<String>()
        for (end in 0 until input.length) {
            val snapshot = input.substring(0, end)
            val projected = VoiceSpeechText.project(snapshot, false)
            assertTrue("Unstable speech at $end", projected.startsWith(previous))
            previous = projected
            spoken += requireNotNull(buffer.append(snapshot, false, earlyStart = true))
            assertTrue(requireNotNull(buffer.append(snapshot, false, earlyStart = true)).isEmpty())
        }
        spoken += requireNotNull(buffer.append(input, true, earlyStart = true))
        assertEquals(expected, spoken.joinToString(""))
        assertFalse(spoken.joinToString("").contains("https"))
        assertFalse(spoken.joinToString("").contains("private"))
    }

    @Test fun partialUrlsLinksAndCodeNeverLeakTheirDestination() {
        for (text in listOf("https://example.test/private", "[guide](https://example.test/private", "<https://example.test/private", "`privateCall(")) {
            assertEquals("", VoiceSpeechText.project(text, false))
            assertFalse(VoiceSpeechText.project(text, true).contains("private"))
        }
        assertEquals("Code is available in the chat.", VoiceSpeechText.project("```kotlin\nprivateCall(", false))
    }

    @Test fun firstUnpunctuatedChunkStartsAfterFourCompleteWords() {
        val buffer = VoiceSpeechBuffer()
        assertTrue(requireNotNull(buffer.append("A short useful ", false, earlyStart = true)).isEmpty())
        assertEquals(listOf("A short useful answer "), buffer.append("A short useful answer ", false, earlyStart = true))
        assertEquals(listOf("continues"), buffer.append("A short useful answer continues", true, earlyStart = true))
    }

    @Test fun comparisonsRemainSpokenWhileActualHtmlTagsAreOmitted() {
        val input = "Keep 2 < 3 and 4 > 1. Then x<y. <b>Done</b>."
        val expected = "Keep 2 less than 3 and 4 greater than 1. Then x less than y. Done."
        assertEquals(expected, VoiceSpeechText.project(input, true))
        var previous = ""
        for (end in 0 until input.length) {
            val current = VoiceSpeechText.project(input.substring(0, end), false)
            assertTrue("Unstable comparison at $end", current.startsWith(previous))
            previous = current
        }
        assertEquals(" Quote.", VoiceSpeechText.project("> Quote.", true))
    }

    @Test fun comparisonWhitespaceAndCommentsRemainStableAtEverySplit() {
        for (separator in listOf(" ", "\n", "\t")) {
            val input = "Value 4 >${separator}1 and 2 <${separator}3. <!-- private metadata --> Done."
            val expected = VoiceSpeechText.project(input, true)
            val buffer = VoiceSpeechBuffer()
            val spoken = mutableListOf<String>()
            for (end in 0 until input.length) spoken += requireNotNull(buffer.append(input.substring(0, end), false, earlyStart = true))
            spoken += requireNotNull(buffer.append(input, true, earlyStart = true))
            assertEquals(expected, spoken.joinToString(""))
            assertFalse(expected.contains("private"))
        }
    }
}
