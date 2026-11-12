package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroSpeechTextTest {
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

    @Test fun spellsUnknownNamesAndPronouncesKnownPartsOfIdentifiers() {
        assertEquals(
            "e m a n u e l used g p u and git hub.",
            normalize("Emanuél used GPU and [GitHub](https://github.com/example).") { it != "emanuel" && it != "github" }
        )
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
