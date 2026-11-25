package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroSpeechTextTest {
    @Test fun anOutOfLexiconContractionUsesKnownWordsAtItsOriginalCaptionRange() {
        val projection = KokoroSpeechText.timed("We're here") { it in setOf("we", "are", "here") }
        assertEquals(listOf(KokoroPhrase("we are here", listOf(0..4, 0..4, 6..9))), projection.phrases)
        assertEquals(listOf("we're"), KokoroSpeechText.projectedParts("we're") { it == "we" })
    }

    @Test fun technicalInitialismsContinueWithAlignedCaptionsAndLexiconLetters() {
        val projection = KokoroSpeechText.timed("VR GPU NPU") { it.length == 1 }
        assertEquals("v r g p u n p u", projection.parts?.joinToString(" "))
        assertEquals(List(2) { 0..1 } + List(3) { 3..5 } + List(3) { 7..9 }, projection.phrases!!.single().wordRanges)
    }

    @Test fun distinguishesSpokenAcronymsFromInitialismsAndNames() {
        val result = KokoroSpeechText.timed("JSON API RAM microvoxel Seeley") { it.length == 1 }
        assertEquals(listOf("jason a p i ram microvoxel seeley"), result.parts)
        assertEquals(listOf(0..3, 5..7, 5..7, 5..7, 9..11, 13..22, 24..29), result.phrases!!.single().wordRanges)
    }

    @Test fun projectedStreamChunksKeepNumbersAtChunkStartsInsteadOfReparsingListMarkup() {
        val buffer = VoiceSpeechBuffer()
        val input = "Here are the details for this new build 1. Continue with details."
        val chunks = mutableListOf<String>()
        for (end in 1..input.length) chunks += requireNotNull(buffer.append(input.take(end), false, earlyStart = true))
        chunks += requireNotNull(buffer.append(input, true, earlyStart = true))
        assertTrue(chunks.any { it.trim() == "1." })
        val spoken = chunks.flatMap { requireNotNull(KokoroSpeechText.projectedParts(it) { true }) }.joinToString(" ")
        assertEquals("here are the details for this new build one. continue with details.", spoken)
        assertEquals("one. continue", KokoroSpeechText.projectedParts("1. Continue") { true }?.joinToString(" "))
        assertEquals("continue", normalize("1. Continue"))
    }

    @Test fun addressesStayOnScreenAndCodeKeepsItsExistingSpokenNotice() {
        assertEquals(
            "read the guide. code is available in the chat. the answer remains available.",
            normalize("Read [the guide](https://private.example/a_(b)?token=opaque).\n<HTTP://other.example/path>\n```kotlin\nopaqueIdentifier()\n```\nThe answer remains available.")
        )
        assertEquals("", normalize("https://private.example/opaque"))
    }

    @Test fun speaksBuildNumbersPrNumbersAndVersions() {
        assertEquals(
            "build one hundred forty one and p r number eight hundred sixteen. version zero point nine point sixty six point seven.",
            normalize("**Build 141** and PR #816. v0.9.66.7.")
        )
        assertEquals(
            "minus one thousand two hundred four point zero five percent",
            normalize("-1,204.05%")
        )
    }

    @Test fun spellsExplicitInitialismsAndPronouncesKnownPartsOfIdentifiers() {
        assertEquals(
            "emanuel used g p u and git hub.",
            normalize("Emanuél used GPU and [GitHub](https://github.com/example).") { it != "github" }
        )
    }

    @Test fun nineLetterLexiconMissKeepsItsCaptionAndTheFollowingWordInBothDialects() {
        for (dialect in listOf("us", "gb")) {
            val words = requireNotNull(javaClass.getResourceAsStream("/voice/kokoro/lexicon-$dialect-en.fixture.txt"))
                .bufferedReader().useLines(KokoroLexicon::words)
            val text = "Here Gráphgear, here."
            val projection = KokoroSpeechText.timed(text, words::contains)
            assertEquals(listOf(0..3, 5..13, 16..19), projection.phrases!!.single().wordRanges)
            assertEquals(KokoroSpeechText.projectedParts(text, words::contains), projection.parts)
            assertEquals(listOf("here graphgear, here."), KokoroSpeechText.projectedParts(text, words::contains))
            assertEquals(KokoroPronunciationObservation(2, 0, 1, 0, 0, null), projection.observation)
        }
    }

    @Test fun onlyOversizedMissesUseABoundedSpokenNotice() {
        val words = setOf("here", "a", "unknown", "word")
        val longWord = "a".repeat(300)
        val projection = KokoroSpeechText.timed("ab $longWord here", words::contains)
        assertEquals(listOf("ab unknown word here"), projection.parts)
        assertEquals(listOf(0..1, 3..302, 3..302, 304..307), projection.phrases!!.single().wordRanges)
        assertEquals(KokoroPronunciationObservation(1, 0, 2, 0, 0, null), projection.observation)
        assertEquals(listOf("ab here"), KokoroSpeechText.projectedParts("ab here") { it in words && it != "unknown" })
    }

    @Test fun invisibleFormattingCannotTurnAWordIntoSeparateLetters() {
        val projection = KokoroSpeechText.projected("h\u200Be\u2060r\u00ADe") { it == "here" }
        assertEquals(listOf("here"), projection.parts)
        assertEquals(3, projection.observation.removedFormatCharacters)
        assertEquals(1, projection.observation.knownWords)
        assertEquals(0, projection.observation.spelledLetters)
        assertEquals("h e r e", normalize("H-E-R-E"))
    }

    @Test fun pronunciationObservationsContainOnlyCountsAndFiniteReasons() {
        val projection = KokoroSpeechText.projected("Here GPU unknownprivateword") { it in setOf("here", "g", "p", "u") }
        assertEquals(KokoroPronunciationObservation(1, 1, 1, 3, 0, null), projection.observation)
        assertTrue(!projection.observation.toString().contains("unknownprivateword"))
        assertEquals(KokoroPronunciationFailure.UNSUPPORTED_SCRIPT, KokoroSpeechText.projected("已完成") { true }.observation.failure)
        assertEquals(KokoroPronunciationFailure.MISSING_LETTER, KokoroSpeechText.projected("GPU") { it != "g" }.observation.failure)
    }

    @Test fun declinesScriptsAndLettersTheEnglishLexiconCannotSpeak() {
        assertNull(KokoroSpeechText.parts("Build 141 已完成。") { true })
        assertNull(KokoroSpeechText.parts("Build ١٤١.") { true })
        assertEquals(listOf("emanuel"), KokoroSpeechText.parts("Emanuel") { false })
    }

    @Test fun boundsLongSentencesAndPreservesTheirWords() {
        val input = List(250) { "turn" }.joinToString(" ")
        val parts = requireNotNull(KokoroSpeechText.parts(input) { true })
        assertTrue(parts.all { it.length <= 128 && it.split(' ').size <= 16 })
        assertEquals(input, parts.joinToString(" "))
        assertEquals(emptyList<String>(), KokoroSpeechText.parts("**✨**") { true })
        assertEquals("turn ".repeat(2400) + "the rest of this response is available in the chat.", normalize("turn ".repeat(3000)))
    }

    @Test fun datesAndClockTimesUseContextWithoutGuessingAmbiguousDateOrder() {
        assertEquals("november twenty second two thousand twenty six", normalize("2026-11-22"))
        assertEquals("november twenty second, two thousand twenty six", normalize("November 22, 2026"))
        assertEquals("zero three slash zero four slash two thousand twenty six", normalize("03/04/2026"))
        assertEquals("at nine oh five and three thirty p m", normalize("At 09:05 and 3:30 PM"))
        assertEquals("ratio twelve colon thirty", normalize("Ratio 12:30"))
        assertEquals("two thousand twenty six dash ninety nine dash ninety nine", normalize("2026-99-99"))
    }

    @Test fun currencyUnitsAndNegativeValuesKeepTheirAmountsAndExactRanges() {
        val text = "-3 °C, 1 ms, 12 MB/s, 5 Mb, 2 μg."
        val projection = KokoroSpeechText.timed(text) { true }
        assertEquals("minus three degrees celsius, one millisecond, twelve megabytes per second, five megabits, two micrograms.", projection.parts?.joinToString(" "))
        val allRanges = projection.phrases!!.flatMap { it.wordRanges }
        assertEquals(listOf(0..1, 0..1, 3..4, 3..4, 7..7, 9..10, 13..14, 16..19, 16..19, 16..19, 22..22, 24..25, 28..28, 30..31), allRanges)
        assertEquals("minus four dollars and fifty cents", normalize("$-4.50"))
        assertEquals("one pound and one penny", normalize("£1.01"))
        assertEquals("zero point zero zero five euros", normalize("€0.005"))
        assertEquals("one degree fahrenheit and two degrees celsius", normalize("1 °F and 2 °C"))
        assertEquals(KokoroPronunciationFailure.UNSUPPORTED_SCRIPT, KokoroSpeechText.timed("μ is a symbol") { true }.observation.failure)
    }

    @Test fun versionsAndAddressesUseDifferentSeparatorsWithoutChangingDigits() {
        assertEquals("gateway i p one nine two dot one six eight dot one dot two zero", normalize("Gateway IP 192.168.1.20"))
        assertEquals("version zero point nine point sixty six point thirteen", normalize("Version 0.9.66.13"))
        assertEquals("version one point twenty point three", normalize("v1.20.3"))
        val input = "IP 192.168.1.20"
        val projection = KokoroSpeechText.timed(input) { true }
        assertEquals(listOf(0..1, 0..1) + List(12) { 3..14 }, projection.phrases!!.flatMap { it.wordRanges })
    }

    @Test fun substitutionsShareTheOriginalDateCurrencyAndAccentedNameRanges() {
        val input = "2026-11-22 $4.50 Emanuél"
        val projection = KokoroSpeechText.timed(input) { true }
        assertEquals("november twenty second two thousand twenty six four dollars and fifty cents emanuel", projection.parts?.joinToString(" "))
        assertEquals(List(7) { 0..9 } + List(5) { 11..15 } + listOf(17..23), projection.phrases!!.flatMap { it.wordRanges })
        val policy = KokoroSpeechText.withPolicy("Seeley Graphgear", { false }, mapOf("seeley" to "see lee"), 16, true)
        assertEquals("see lee graphgear", policy.parts?.joinToString(" "))
        assertEquals(listOf(0..5, 0..5, 7..15), policy.phrases!!.flatMap { it.wordRanges })
        assertEquals(0, policy.observation.spelledLetters)
    }

    @Test fun mathOperatorsAndNegationRemainInSpeech() {
        assertEquals("five minus two equals three; two less than or equal to three; four does not equal five.", normalize("5 - 2 = 3; 2 ≤ 3; 4 ≠ 5."))
        assertEquals("do not remove minus three degrees celsius.", normalize("Do not remove −3 °C."))
        assertEquals("x less than y", normalize("x<y"))
        assertEquals("snapdragon eight elite and build one hundred forty seven", normalize("Snapdragon8Elite and build147"))
        assertEquals("x minus two", normalize("x-2"))
        assertEquals("two times three equals six and x times y", normalize("2*3 = 6 and x*y"))
    }

    @Test fun orderedSegmentsKeepTheSameUnitsDatesTimesAndAddressesAsWholeSpeech() {
        val examples = listOf(
            "Here is the measured transfer rate at 12 MB/s.",
            "Wait 1 ms and use 2 ms.",
            "The temperature is -1 °C and -3 °F.",
            "The date is November 22, 2026.",
            "Meet at 09:05 PM tomorrow.",
            "Use 2 µg and 1 μg.",
            "Version 192.168.1.20 and IP 0.0.0.0."
        )
        for (text in examples) {
            val whole = KokoroSpeechText.withPolicy(text, { true }, emptyMap(), 16, true).parts!!.joinToString(" ")
            for (split in text.indices.filter { text[it].isWhitespace() }.map { it + 1 }) {
                val context = KokoroSpeechContext()
                val segments = listOf(text.substring(0, split), text.substring(split))
                val result = segments.flatMap { segment ->
                    val projection = context.project(segment, { true }, emptyMap(), 16, true)
                    assertNull("Unexpected failure for split $split in $text", projection.observation.failure)
                    for (phrase in projection.phrases!!) {
                        assertTrue(phrase.wordRanges.all { it.first >= 0 && it.last < segment.length })
                    }
                    projection.parts!!
                }.joinToString(" ")
                assertEquals("Changed normalization at $split in $text", whole, result)
            }
        }
    }

    @Test fun theLiveEightWordBoundaryRetainsTheAmountForItsFollowingUnit() {
        val text = "Here is the measured transfer rate at 12 MB/s."
        val buffer = VoiceSpeechBuffer()
        val chunks = mutableListOf<String>()
        for (end in 1..text.length) chunks += requireNotNull(buffer.append(text.take(end), final = false, earlyStart = true))
        chunks += requireNotNull(buffer.append(text, final = true, earlyStart = true))
        assertTrue(chunks.any { "12 MB/s" in it })
        assertFalse(chunks.any { it.trimEnd().endsWith("12") })
        val context = KokoroSpeechContext()
        val actual = chunks.flatMap { context.project(it, { true }, emptyMap(), 16, true).parts!! }.joinToString(" ")
        assertEquals("here is the measured transfer rate at twelve megabytes per second.", actual)
    }

    @Test fun contextNeverAppearsInSpokenWordsOrCurrentSegmentCaptionRanges() {
        val context = KokoroSpeechContext()
        context.project("Private preceding words contain -1 ", { true }, emptyMap(), 16, true)
        val temperature = context.project("°C.", { true }, emptyMap(), 16, true)
        assertEquals(listOf("degree celsius."), temperature.parts)
        assertEquals(listOf(0..1, 0..1), temperature.phrases!!.single().wordRanges)
        val date = KokoroSpeechText.withPolicy("22", { true }, emptyMap(), 16, true, contextBefore = "November ")
        assertEquals(listOf("twenty second"), date.parts)
        assertEquals(listOf(0..1, 0..1), date.phrases!!.single().wordRanges)
        assertFalse(date.toString().contains("November"))
    }

    @Test fun aNewSynthesisStreamCannotInheritThePriorReplyContext() {
        val first = KokoroSpeechContext()
        first.project("At ", { true }, emptyMap(), 16, false)
        assertEquals(listOf("nine oh five"), first.project("09:05", { true }, emptyMap(), 16, false).parts)
        val replacement = KokoroSpeechContext()
        assertEquals(listOf("zero nine colon zero five"), replacement.project("09:05", { true }, emptyMap(), 16, false).parts)
        assertEquals("format twelve colon thirty", normalize("Format 12:30"))
        assertEquals("dismay twenty two", normalize("Dismay 22"))
    }

    @Test fun standaloneRateDegreeAndMicrogramNotationNeverBecomesAnOpaqueNativeWord() {
        val projection = KokoroSpeechText.timed("MB/s °C μg") { false }
        assertNull(projection.observation.failure)
        assertEquals(listOf("megabytes per second degrees celsius micrograms"), projection.parts)
        assertEquals(List(3) { 0..3 } + List(2) { 5..6 } + listOf(8..9), projection.phrases!!.single().wordRanges)
        assertEquals(KokoroPronunciationFailure.UNSUPPORTED_SCRIPT, KokoroSpeechText.timed("fμg") { true }.observation.failure)
    }

    private fun normalize(text: String, isKnown: (String) -> Boolean = { true }) =
        KokoroSpeechText.parts(text, isKnown)?.joinToString(" ")

    @Test fun contextualReadingsUseInstalledWordsAndPreserveExplicitCorrections() {
        fun spoken(input: String, corrections: Map<String, String> = emptyMap()) =
            KokoroSpeechText.withPolicy(input, { true }, corrections, 16, true).parts!!.joinToString(" ")
        assertEquals("i red it yesterday.", spoken("I read it yesterday."))
        assertEquals("i reed it every day.", spoken("I read it every day."))
        assertEquals("a led pipe.", spoken("A lead pipe."))
        assertEquals("the pipe is made of led.", spoken("The pipe is made of lead."))
        assertEquals("we red the minutes last night.", spoken("We read the minutes last night."))
        assertEquals("they lead us.", spoken("They lead us."))
        assertEquals("i reed it yesterday.", spoken("I read it yesterday.", mapOf("read" to "reed")))
        assertEquals("i read it yesterday.", spoken("I read it yesterday.", mapOf("read" to "read")))
    }
}
