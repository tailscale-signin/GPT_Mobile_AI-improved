package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroSpeechTextTest {
    @Test fun anOutOfLexiconContractionUsesKnownWordsAtItsOriginalCaptionRange() {
        val projection = KokoroSpeechText.timed("We're here") { it in setOf("we", "are", "here") }
        assertEquals(listOf(KokoroCaptionPart("we are", 0..4), KokoroCaptionPart("here", 6..9)), projection.captionParts)
        assertNull(KokoroSpeechText.projectedParts("we're") { it == "we" })
    }

    @Test fun technicalInitialismsContinueWithAlignedCaptionsAndLexiconLetters() {
        val projection = KokoroSpeechText.timed("VR GPU NPU") { it.length == 1 }
        assertEquals("v r g p u n p u", projection.parts?.joinToString(" "))
        assertEquals(listOf(0..1, 3..5, 7..9), projection.captionParts.map { it.range })
    }

    @Test fun projectedStreamChunksKeepNumbersAtChunkStartsInsteadOfReparsingListMarkup() {
        val buffer = VoiceSpeechBuffer()
        val input = "Here is the build 1. Continue with details."
        val chunks = mutableListOf<String>()
        for (end in 1..input.length) chunks += requireNotNull(buffer.append(input.take(end), false, earlyStart = true))
        chunks += requireNotNull(buffer.append(input, true, earlyStart = true))
        assertTrue(chunks.any { it.trim() == "1." })
        val spoken = chunks.flatMap { requireNotNull(KokoroSpeechText.projectedParts(it) { true }) }.joinToString(" ")
        assertEquals("here is the build one. continue with details.", spoken)
        assertEquals("one. continue", KokoroSpeechText.projectedParts("1. Continue") { true }?.joinToString(" "))
        assertEquals("continue", normalize("1. Continue"))
    }

    @Test fun bareAddressesAndCodeUseTheSameSpokenProjectionAsMarkdownLinks() {
        assertEquals(
            "read the guide or link. code is available in the chat. the answer remains available.",
            normalize("Read [the guide](https://private.example/a_(b)?token=opaque) or <HTTP://other.example/path>.\n```kotlin\nopaqueIdentifier()\n```\nThe answer remains available.")
        )
        assertEquals("link", normalize("https://private.example/opaque"))
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
            assertEquals(
                listOf(KokoroCaptionPart("here", 0..3), KokoroCaptionPart("g r a p h g e a r,", 5..13), KokoroCaptionPart("here.", 16..19)),
                projection.captionParts
            )
            assertEquals(projection.captionParts.map { it.speech }, projection.parts)
            assertEquals(listOf("here g r a p h g e a r, here."), KokoroSpeechText.projectedParts(text, words::contains))
            assertEquals(KokoroPronunciationObservation(2, 0, 1, 9, 0, null), projection.observation)
        }
    }

    @Test fun unspellableAndOversizedWordsUseABoundedSpokenNotice() {
        val words = setOf("here", "a", "unknown", "word")
        val longWord = "a".repeat(300)
        val projection = KokoroSpeechText.timed("ab $longWord here", words::contains)
        assertEquals(listOf("unknown word", "unknown word", "here"), projection.parts)
        assertEquals(listOf(0..1, 3..302, 304..307), projection.captionParts.map { it.range })
        assertEquals(KokoroPronunciationObservation(1, 0, 2, 0, 0, null), projection.observation)
        assertEquals(listOf("word here"), KokoroSpeechText.projectedParts("ab here") { it in words && it != "unknown" })
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
        assertEquals(KokoroPronunciationObservation(1, 1, 1, 3, 0, KokoroPronunciationFailure.UNKNOWN_WORD, 18), projection.observation)
        assertTrue(!projection.observation.toString().contains("unknownprivateword"))
        assertEquals(KokoroPronunciationFailure.UNSUPPORTED_SCRIPT, KokoroSpeechText.projected("已完成") { true }.observation.failure)
        assertEquals(KokoroPronunciationFailure.MISSING_LETTER, KokoroSpeechText.projected("GPU") { it != "g" }.observation.failure)
    }

    @Test fun declinesScriptsAndLettersTheEnglishLexiconCannotSpeak() {
        assertNull(KokoroSpeechText.parts("Build 141 已完成。") { true })
        assertNull(KokoroSpeechText.parts("Build ١٤١.") { true })
        assertNull(KokoroSpeechText.parts("Emanuel") { false })
    }

    @Test fun boundsLongSentencesAndPreservesTheirWords() {
        val input = List(250) { "turn" }.joinToString(" ")
        val parts = requireNotNull(KokoroSpeechText.parts(input) { true })
        assertTrue(parts.all { it.length <= 128 && it.split(' ').size <= 16 })
        assertEquals(input, parts.joinToString(" "))
        assertEquals(emptyList<String>(), KokoroSpeechText.parts("**✨**") { true })
        assertEquals("turn ".repeat(2400) + "the rest of this response is available in the chat.", normalize("turn ".repeat(3000)))
    }

    private fun normalize(text: String, isKnown: (String) -> Boolean = { true }) =
        KokoroSpeechText.parts(text, isKnown)?.joinToString(" ")
}
