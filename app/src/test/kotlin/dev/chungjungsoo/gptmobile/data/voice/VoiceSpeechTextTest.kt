package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpeechTextTest {
    @Test fun linkLabelsSurviveWithoutRepeatedAddressPlaceholders() {
        val input = "Read [the guide](https://example.com/a_(b) \"A ) title\"), ![the chart](https://example.com/image.png) and [the notes][source]. HTTP://EXAMPLE.COM/raw www.example.com/raw <https://example.com/raw> [https://example.com/raw](https://example.com/raw)\n[source]: https://example.com/reference\nDone."
        assertEquals("Read the guide, the chart and the notes. Done.", words(VoiceSpeechText.project(input, final = true)))
        assertAllSnapshots(input)
    }

    @Test fun descriptiveLinkLabelsKeepNumbersWhileDocumentListsOmitTheirMarkers() {
        val examples = listOf(
            "Read [1. **Build guide**](https://example.test/private)." to "Read 1. Build guide.",
            "Read [2) _Setup guide_](https://example.test/private)." to "Read 2) Setup guide."
        )
        for ((input, expected) in examples) {
            assertEquals(expected, VoiceSpeechText.project(input, final = true))
            assertAllSnapshots(input)
        }
        val document = "1. Build guide\n2) Setup guide"
        assertEquals("Build guide Setup guide", words(VoiceSpeechText.project(document, final = true)))
        assertAllSnapshots(document)
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
        assertEquals("Results Build 141 is ready. Read carefully. Keep nested list words. Say café and 🗣 clearly. Use A & B and finish.", words(VoiceSpeechText.project(input, final = true)))
        assertAllSnapshots(input)
    }

    @Test fun aHiddenPrefixDoesNotDelayTheOpeningReadablePhrase() {
        val prefix = "[source]: https://example.com/raw\n<!-- hidden metadata -->\n\uE200cite\uE202private_source_id\uE201\n"
        val buffer = VoiceSpeechBuffer()
        assertEquals(emptyList<String>(), buffer.append(prefix, final = false, earlyStart = true))
        val opening = requireNotNull(buffer.append(prefix + "Here are the eight clear words to speak ", final = false, earlyStart = true))
        assertEquals("Here are the eight clear words to speak", words(opening.joinToString("")))
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
        assertTrue(VoiceSpeechText.hasDetails(prefix + "extraordinary"))
        assertEquals(words(prefix + VoiceSpeechText.LIMIT_NOTICE), words(VoiceSpeechText.project(prefix + "extraordinary")))
        assertFalse(VoiceSpeechText.project(prefix + "extraordinary", final = false).contains(VoiceSpeechText.LIMIT_NOTICE))
    }

    @Test fun readableInlineProseSurvivesWhileCodeReferencesAndOpaqueCitationsDoNot() {
        val input = "Use `fast mode`. [1, 2] <b>Done</b>.\n```json\n{\"key\":\"private\"}\n```\nReference 0123456789abcdef0123456789abcdef and 12345678-abcd-1234-abcd-123456789abc. Ready. \uE200cite\uE202private_source_id\uE201"
        val expected = "Use fast mode. Done. Code is available in the chat. Reference reference and reference. Ready."
        assertEquals(expected, words(VoiceSpeechText.project(input, true)))
        assertAllSnapshots(input)
        assertEquals("Code is available in the chat.", words(VoiceSpeechText.project("```kotlin\nprivateCall(", false)))
        assertEquals("Code is available in the chat.", words(VoiceSpeechText.project("`privateCall()` ".repeat(500), true)))
    }

    @Test fun genericUrisStayOnScreenWithoutNarratingRepeatedPlaceholders() {
        val input = "See ftp://example.test/private. Use custom+scheme://example.test/private now."
        assertEquals("See . Use now.", words(VoiceSpeechText.project(input, true)))
        assertAllSnapshots(input)
        val boundedToken = "a-".repeat(5900)
        assertEquals("$boundedToken now.", words(VoiceSpeechText.project("$boundedToken custom+scheme://example.test/private now.", true)))
        assertEquals(".", VoiceSpeechText.project(".https://example.test/private", true))
        assertEquals(".", VoiceSpeechText.project(".www.example.test/private", true))
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

    @Test fun explicitReasoningNeverReachesLiveReplayOrFullDetailSpeech() {
        val input = "<think>private planning <think>nested</think> more private</think><answer>The answer is ready.</answer><analysis>private notes</analysis>"
        assertEquals("The answer is ready.", VoiceSpeechText.project(input))
        assertEquals("The answer is ready.", VoiceSpeechText.project(input, includeDetails = true))
        assertAllSnapshots(input)
        for (tag in listOf("think", "thinking", "analysis", "reasoning")) {
            assertEquals("Before. ", VoiceSpeechText.project("Before. <$tag>private unfinished"))
            assertEquals("Before. ", VoiceSpeechText.withoutReasoning("Before. <$tag>private unfinished"))
        }
        assertEquals("Let me think about the answer. The user can choose.", VoiceSpeechText.project("Let me think about the answer. The user can choose."))
    }

    @Test fun detailsAndTablesStayVisibleButSpeechRequiresExplicitFullDetails() {
        val input = "The faster result is ready.\n<details><summary>Measurements</summary>\n| Backend | Time |\n| --- | ---: |\n| CPU | 120 ms |\n| GPU | 90 ms |\n</details>\nYou can continue."
        val brief = "The faster result is ready. You can continue."
        val detailed = "The faster result is ready. Measurements Backend: CPU. Time: 120 ms. Backend: GPU. Time: 90 ms. You can continue."
        assertEquals(brief, words(VoiceSpeechText.project(input)))
        assertEquals(detailed, words(VoiceSpeechText.project(input, includeDetails = true)))
        assertTrue(VoiceSpeechText.hasDetails(input))
        assertAllSnapshots(input)
        assertProjectionPrefixes(input, includeDetails = true)
        val rich = VoiceSpeechText.richDetails(input)
        assertTrue(rich.contains("| CPU | 120 ms |"))
        assertFalse(rich.contains("<details>"))
        assertFalse(rich.contains("<summary>"))
    }

    @Test fun tableProjectionWaitsForCompleteRowsAndLeavesLiteralBarsAlone() {
        val input = "Results.\n| Name | Value |\n| --- | --- |\n| first | 2 < 3 |\n| second | 4 > 1 |\n\nDone."
        assertEquals("Results. Done.", words(VoiceSpeechText.project(input)))
        assertEquals("Results. Name: first. Value: 2 less than 3. Name: second. Value: 4 greater than 1. Done.", words(VoiceSpeechText.project(input, includeDetails = true)))
        assertAllSnapshots(input)
        assertProjectionPrefixes(input, includeDetails = true)
        assertEquals("|x| = 2", VoiceSpeechText.project("|x| = 2").replace("equals", "="))
        val bare = "A long bare header | another column\n--- | ---\nfirst | second\n"
        assertAllSnapshots(bare)
        assertTrue(VoiceSpeechText.project(bare).contains("A long bare header"))
    }

    @Test fun sourceSectionsDoNotReadLinksOrLoseTheFollowingAnswerSection() {
        val input = "The answer is ready.\n## Sources\n- [Official guide](https://example.test/guide)\n- https://example.test/other\n## Next steps\nContinue here."
        assertEquals("The answer is ready. Next steps Continue here.", words(VoiceSpeechText.project(input)))
        assertEquals("The answer is ready. Sources Official guide Next steps Continue here.", words(VoiceSpeechText.project(input, includeDetails = true)))
        assertAllSnapshots(input)
        assertProjectionPrefixes(input, includeDetails = true)
    }

    @Test fun reasoningOnlyRepliesNeverOfferHiddenMaterialAsMoreDetails() {
        val input = "<analysis>private https://example.test\n| Name | Value |\n| --- | --- |\n| hidden | value |\n</analysis>Ready."
        assertFalse(VoiceSpeechText.hasDetails(input))
        assertEquals("Ready.", VoiceSpeechText.withoutReasoning(input))
        assertEquals("Ready.", VoiceSpeechText.richDetails(input))
        val literal = "Use `<think>` as a literal code example.\n```xml\n<analysis>literal example</analysis>\n```"
        assertEquals(literal, VoiceSpeechText.withoutReasoning(literal))
        assertEquals(literal, VoiceSpeechText.richDetails(literal))
    }

    @Test fun richDetailsPreserveQuotedEscapedIndentedAndTildeFencedTagExamples() {
        val examples = listOf(
            "The literal \"<think>example</think>\" is quoted.",
            "The literal '<analysis>example</analysis>' is quoted.",
            "The literal “<think>example</think>” and ‘<analysis>example</analysis>’ are quoted.",
            "~~~xml\n<analysis>literal example</analysis>\n~~~",
            "    <think>indented example</think>\nReady.",
            "Use \\<think> as an escaped literal.",
            "A quoted \"<details>example</details>\" is not an expandable block."
        )
        for (literal in examples) {
            assertEquals(literal, VoiceSpeechText.withoutReasoning(literal))
            assertEquals(literal, VoiceSpeechText.richDetails(literal))
        }
        assertEquals("\"<think>literal</think>\"  Ready.", VoiceSpeechText.richDetails("\"<think>literal</think>\" <analysis>private</analysis> Ready."))
        assertEquals("The token 'opening think tag' starts a block.", VoiceSpeechText.project("The token '<think>' starts a block."))
        assertAllSnapshots("The token '<think>' starts a block.")
    }

    @Test fun inequalitiesKeepEqualityAndUncertainWordsAreNotGuessedAway() {
        val input = "Keep 2 <= 3, 4 >= 1, 5 ≠ 6, and 2 × 3 = 6. News and trance."
        assertEquals("Keep 2 less than or equal to 3, 4 greater than or equal to 1, 5 does not equal 6, and 2 times 3 equals 6. News and trance.", words(VoiceSpeechText.project(input)))
        assertAllSnapshots(input)
        val products = "Keep **the meaning**: 2 * 3 = 6, x*y, and an *important* result."
        assertEquals("Keep the meaning: 2 times 3 equals 6, x times y, and an important result.", words(VoiceSpeechText.project(products)))
        assertAllSnapshots(products)
    }

    private fun assertProjectionPrefixes(input: String, includeDetails: Boolean) {
        val complete = VoiceSpeechText.project(input, includeDetails = includeDetails)
        for (end in 1..input.length) {
            val partial = VoiceSpeechText.project(input.take(end), final = false, includeDetails = includeDetails)
            assertTrue("Projection revised at $end: $partial", complete.startsWith(partial))
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
