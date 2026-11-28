package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketCaptionTimingTest {
    @Test
    fun canonicalWordsKeepOriginalUtf8SpansAndAudioDerivedFrames() {
        val text = "  “Café” can't!  "
        val result = PocketCaptionPolicy.align(
            text,
            24_000,
            PocketCaptionTranscript(listOf(PocketCaptionWord("CAFE\u0301", .125, .375), PocketCaptionWord("can’t.", .5, .875)), true)
        )
        assertEquals(PocketCaptionStatus.ALIGNED, result.status)
        assertEquals(listOf("“Café”", "can't!"), result.cues.map { text.toByteArray().copyOfRange(it.startByte, it.endByte).decodeToString() })
        assertEquals(listOf(3_000L to 9_000L, 12_000L to 21_000L), result.cues.map { it.startFrame to it.endFrame })
        assertTrue(result.cues.all { it.provenance == VoiceWordCueProvenance.ASR_ALIGNMENT && it.finalEnd && it.revision == 0 })
    }

    @Test
    fun lexicalMismatchAndUnresolvedNumberReadingsNeverReceiveInventedCues() {
        val pairs = listOf("It was." to listOf("There", "was"), "42." to listOf("forty", "two"), "hello world" to listOf("hello"))
        for ((text, words) in pairs) {
            val transcript = PocketCaptionTranscript(words.mapIndexed { index, word -> PocketCaptionWord(word, index * .2, (index + 1) * .2) }, true)
            val result = PocketCaptionPolicy.align(text, 24_000, transcript)
            assertEquals(PocketCaptionStatus.TEXT_MISMATCH, result.status)
            assertTrue(result.cues.isEmpty())
        }
    }

    @Test
    fun invalidOrOverlappingNativeIntervalsInvalidateTheWholePhrase() {
        val invalid = listOf(
            PocketCaptionWord("two", Double.NaN, .9),
            PocketCaptionWord("two", .5, Double.POSITIVE_INFINITY),
            PocketCaptionWord("two", .5, .5),
            PocketCaptionWord("two", .5, 1.01),
            PocketCaptionWord("two", .2, .8)
        )
        for (word in invalid) {
            val result = PocketCaptionPolicy.align("one two", 24_000, PocketCaptionTranscript(listOf(PocketCaptionWord("one", 0.0, .4), word), true))
            assertEquals(PocketCaptionStatus.INVALID_TIMING, result.status)
            assertTrue(result.cues.isEmpty())
        }
        assertEquals(PocketCaptionStatus.INVALID_TIMING, PocketCaptionPolicy.align("one", 24_000, PocketCaptionTranscript(listOf(PocketCaptionWord("one", 0.0, .5)), false)).status)
    }

    @Test
    fun phraseBoundsPreserveAllTextAndOriginalByteOffsets() {
        val text = "  Café one two three four five six seven eight nine ten. More words here.  "
        val parts = requireNotNull(PocketCaptionPolicy.parts(text))
        assertEquals(text, parts.joinToString("") { it.text })
        var bytes = 0
        for (part in parts) {
            assertEquals(bytes, part.startByte)
            assertTrue(part.text.length <= PocketCaptionPolicy.MAX_PHRASE_CHARACTERS)
            assertTrue(Regex("\\S+").findAll(part.text).count() <= PocketCaptionPolicy.MAX_PHRASE_WORDS)
            bytes += part.text.toByteArray().size
        }
        assertTrue(parts.size >= 2)
        assertNull(PocketCaptionPolicy.parts("a".repeat(PocketCaptionPolicy.MAX_PHRASE_CHARACTERS + 1)))
        assertNull(PocketCaptionPolicy.parts("a ".repeat(PocketCaptionPolicy.MAX_INPUT_CHARACTERS)))
        assertFalse(parts.any { it.text.isBlank() })
    }
}
