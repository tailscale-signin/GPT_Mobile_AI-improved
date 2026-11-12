package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechTextTest {
    @Test fun linkLabelsSurviveWhileAddressesBecomeReadablePlaceholders() {
        val input = "Read [the guide](https://example.com/a_(b) \"A ) title\"), ![the chart](https://example.com/image.png) and [the notes][source]. HTTP://EXAMPLE.COM/raw www.example.com/raw <https://example.com/raw> [https://example.com/raw](https://example.com/raw)\n[source]: https://example.com/reference\nDone."
        assertEquals("Read the guide, the chart and the notes. link link link link Done.", words(VoiceSpeechText.project(input, final = true)))
        assertAllSnapshots(input)
    }

    @Test fun codeBodiesAndUnfinishedMarkupNeverBecomeSpeech() {
        val input = "Before `hidden_inline` after.\n~~~python\nhidden_fenced()\n~~~\n    hidden_indented()\n<pre>hidden_html()</pre><code>hidden_tag()</code>Done."
        assertEquals("Before Code is available in the chat. after. Done.", words(VoiceSpeechText.project(input, final = true)))
        assertAllSnapshots(input)
        for (unfinished in listOf("`hidden", "```kt\nhidden code", "[hidden label](https://example.com/unfinished", "<pre>hidden code", "[hidden label", "<https://example.com/unfinished")) {
            val expected = when {
                unfinished.startsWith('`') || unfinished.startsWith("<pre>") -> "Before. Code is available in the chat."
                unfinished.startsWith("[hidden label](") -> "Before. hidden label"
                else -> "Before."
            }
            assertEquals(expected, words(VoiceSpeechText.project("Before. $unfinished", final = true)))
        }
    }

    @Test fun htmlBoundariesRetainReadableWordsWithoutReadingTagsOrComments() {
        val input = "<p>One<br>two<hr/>three.</p><div>Four<!-- hidden > comment --> five.</div><span title=\"x > hidden value\">Clear label</span> and <span title='y > hidden value'>another label</span>."
        assertEquals("One two three. Four five. Clear label and another label.", words(VoiceSpeechText.project(input, final = true)))
        assertAllSnapshots(input)
    }

    @Test fun readableFormattingPreservesWordsNumbersAndUnicode() {
        val input = "## Results\n> **Build 141** is ready.\n- Read _carefully_.\n    - Keep nested list words.\n1. Say café and 🗣 clearly.\n|Name|State|\n|---|---|\n|Speech|Ready|\n===\nUse A &amp; B and [1] finish."
        assertEquals("Results Build 141 is ready. Read carefully. Keep nested list words. Say café and 🗣 clearly. Name State Speech Ready Use A & B and finish.", words(VoiceSpeechText.project(input, final = true)))
        assertAllSnapshots(input)
    }

    @Test fun aHiddenPrefixDoesNotDelayTheFirstFourReadableWords() {
        val prefix = "[source]: https://example.com/raw\n<!-- hidden metadata -->\n\uE200cite\uE202private_source_id\uE201\n"
        val buffer = VoiceSpeechBuffer()
        assertEquals(emptyList<String>(), buffer.append(prefix, final = false, earlyStart = true))
        assertEquals(listOf("Here are four words "), buffer.append(prefix + "Here are four words ", final = false, earlyStart = true))
    }

    @Test fun boundedInputCannotExposeATruncatedLinkAndTheLimitNoticeIsSpokenOnce() {
        val input = "Read [the guide](https://example.com/" + "path".repeat(4_000)
        val buffer = VoiceSpeechBuffer()
        val spoken = requireNotNull(buffer.append(input, final = true, earlyStart = true)).joinToString(" ")
        assertEquals("Read the guide The rest of this response is available in the chat.", words(spoken))
        assertEquals(emptyList<String>(), buffer.append(input, final = true, earlyStart = true))
        assertFalse(spoken.contains("https"))
    }

    @Test fun captionGrowthPastTheSpeechLimitNeverReplaysOrCutsASurrogatePair() {
        val prefix = "word ".repeat(2399) + "end "
        assertEquals(11999, prefix.length)
        val input = prefix + "🗣 additional caption text"
        val buffer = VoiceSpeechBuffer()
        val chunks = requireNotNull(buffer.append(input.take(12000), final = false, earlyStart = true)).toMutableList()
        assertEquals(emptyList<String>(), buffer.append(input, final = false, earlyStart = true))
        assertEquals(emptyList<String>(), buffer.append(input + " extra".repeat(10000), final = false, earlyStart = true))
        chunks += requireNotNull(buffer.append(input, final = true, earlyStart = true))
        assertEquals(words(prefix + "The rest of this response is available in the chat."), words(chunks.joinToString("")))
        assertTrue(chunks.none { it.lastOrNull()?.isHighSurrogate() == true || it.firstOrNull()?.isLowSurrogate() == true })
    }

    @Test fun aWordCrossingTheSpeechLimitStaysInCaptionsWithoutBeingSpokenInPart() {
        val prefix = "word ".repeat(2399)
        val buffer = VoiceSpeechBuffer()
        val chunks = requireNotNull(buffer.append(prefix + "extraordinary", final = true, earlyStart = true))
        assertEquals(words(prefix + "The rest of this response is available in the chat."), words(chunks.joinToString("")))
        assertTrue(chunks.none { it.contains("extra") })
    }

    @Test fun readableInlineProseSurvivesWhileCodeReferencesAndOpaqueCitationsDoNot() {
        val input = "Use `fast mode`. [1, 2] <b>Done</b>.\n```json\n{\"key\":\"private\"}\n```\nReference 0123456789abcdef0123456789abcdef and 12345678-abcd-1234-abcd-123456789abc. Ready. \uE200cite\uE202private_source_id\uE201"
        val expected = "Use fast mode. Done. Code is available in the chat. Reference reference and reference. Ready."
        assertEquals(expected, words(VoiceSpeechText.project(input, true)))
        assertAllSnapshots(input)
        assertEquals("Code is available in the chat.", words(VoiceSpeechText.project("```kotlin\nprivateCall(", false)))
        assertEquals("Code is available in the chat.", words(VoiceSpeechText.project("`privateCall()` ".repeat(500), true)))
    }

    @Test fun genericUrisKeepOnlyTheLinkPlaceholderAndPunctuation() {
        val input = "See ftp://example.test/private. Use custom+scheme://example.test/private now."
        assertEquals("See link. Use link now.", VoiceSpeechText.project(input, true))
        assertAllSnapshots(input)
        val boundedToken = "a-".repeat(5900)
        assertEquals("$boundedToken link now.", VoiceSpeechText.project("$boundedToken custom+scheme://example.test/private now.", true))
        assertEquals(".link", VoiceSpeechText.project(".https://example.test/private", true))
        assertEquals(".link", VoiceSpeechText.project(".www.example.test/private", true))
        assertFalse(VoiceSpeechText.project("See...https://example.test/private", true).contains("https"))
    }

    @Test fun comparisonsRemainSpokenWhileActualHtmlTagsAreOmitted() {
        val input = "Keep 2 < 3 and 4 > 1. Then x<y. <b>Done</b>."
        val expected = "Keep 2 less than 3 and 4 greater than 1. Then x less than y. Done."
        assertEquals(expected, VoiceSpeechText.project(input, true))
        assertAllSnapshots(input)
        assertEquals("Quote.", VoiceSpeechText.project("> Quote.", true))
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

    private fun assertAllSnapshots(input: String) {
        val buffer = VoiceSpeechBuffer()
        val chunks = mutableListOf<String>()
        for (end in 1..input.length) {
            val snapshot = input.take(end)
            val next = buffer.append(snapshot, final = false, earlyStart = true)
            assertNotNull("Unexpected speech revision at $end", next)
            chunks += requireNotNull(next)
            assertEquals(emptyList<String>(), buffer.append(snapshot, final = false, earlyStart = true))
        }
        chunks += requireNotNull(buffer.append(input, final = true, earlyStart = true))
        assertEquals(words(VoiceSpeechText.project(input, final = true)), words(chunks.joinToString("")))
        assertTrue(chunks.none { it.contains("https://") || it.contains("hidden_") })
    }

    private fun words(text: String): String = text.replace(Regex("\\s+"), " ").trim()
}
