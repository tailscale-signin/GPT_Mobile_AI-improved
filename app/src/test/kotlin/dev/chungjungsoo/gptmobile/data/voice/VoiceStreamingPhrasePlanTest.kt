package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceStreamingPhrasePlanTest {
    @Test fun aLongSavedReplyIsSplitBeforeInferenceWithoutLosingItsText() {
        val text = "A complete sentence with useful details. ".repeat(120).trimEnd()
        val plan = VoiceStreamingPhrasePlan(text, emptyMap(), .5f)
        val phrases = generateSequence { plan.next(8) }.toList()
        assertTrue(phrases.size > 50)
        assertTrue(phrases.all { it.text.length <= 64 })
        assertEquals(text, phrases.joinToString("") { it.text })
        for (phrase in phrases) {
            assertEquals(phrase.text, phrase.sourceCharacters.joinToString("") { text.substring(it) })
        }
    }

    @Test fun pronunciationExpansionsKeepOriginalCaptionOffsetsInLaterPhrases() {
        val text = "Here are the first words. Sentoria is ready."
        val plan = VoiceStreamingPhrasePlan(text, mapOf("sentoria" to "sen toria"), 1f)
        val phrases = generateSequence { plan.next(4) }.toList()
        assertTrue(phrases.size > 1)
        assertEquals("Here are the first words. sen toria is ready.", phrases.joinToString("") { it.text })
        val phrase = phrases.first { "sen" in it.text }
        val start = phrase.text.indexOf("sen")
        val startByte = phrase.text.substring(0, start).toByteArray(Charsets.UTF_8).size
        val mapped = VoiceStreamFrames(phrase.text, sourceCharacters = phrase.sourceCharacters).accept(
            VoicePcmChunk(floatArrayOf(.1f), wordCues = listOf(VoiceWordCue(startByte, startByte + 3, 0, 1)))
        )
        val original = text.indexOf("Sentoria")
        assertEquals(original until original + "Sentoria".length, mapped.single().range)
    }

    @Test fun unbrokenUnicodeTextDoesNotSplitSurrogatesOrDropCombiningMarks() {
        val text = "🙂xe\u0301".repeat(100)
        val plan = VoiceStreamingPhrasePlan(text, emptyMap(), .5f)
        val phrases = generateSequence { plan.next(8) }.toList()
        assertEquals(text, phrases.joinToString("") { it.text })
        assertTrue(phrases.size > 1)
        assertTrue(phrases.all { it.text.length <= 64 })
        assertTrue(phrases.all { !it.text.first().isLowSurrogate() && !it.text.last().isHighSurrogate() })
        assertTrue(phrases.all { it.text.first() != '\u0301' })
        assertFalse(phrases.any { it.text.isBlank() })
    }
}
