package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KokoroPhrasePlanTest {
    @Test fun replanningPreservesEveryExpandedWordAndItsOriginalCaptionRange() {
        val projection = KokoroSpeechText.withPolicy("Sentoria is ready for the next question and you can speak now.", { true }, mapOf("sentoria" to "sen toria"), 24, true)
        val original = requireNotNull(projection.phrases)
        val plan = KokoroPhrasePlan(original)
        val pieces = mutableListOf<KokoroPhrase>()
        listOf(4, 6, 24).forEach { limit -> plan.next(limit)?.let(pieces::add) }
        while (true) pieces += plan.next(6) ?: break
        assertEquals(original.flatMap { it.speech.split(' ') }, pieces.flatMap { it.speech.split(' ') })
        assertEquals(original.flatMap { it.wordRanges }, pieces.flatMap { it.wordRanges })
        assertEquals(listOf(0..7, 0..7), pieces.first().wordRanges.take(2))
        assertTrue(pieces.all { it.speech.toByteArray(Charsets.UTF_8).size <= 128 })
    }

    @Test fun measuredWordCostConstrainsTheNextPhraseToAvailablePcm() {
        val adaptive = VoiceAdaptivePlayback(VoicePerformanceMode.AUTO)
        assertEquals(6, adaptive.phraseWords(0))
        adaptive.observe(4_000, 5_000, spokenWords = 16)
        assertEquals(6, adaptive.phraseWords(1_000))
        assertEquals(10, adaptive.phraseWords(3_000))
        assertEquals(16, adaptive.phraseWords(6_000))
        adaptive.observe(8_000, 4_000, spokenWords = 16)
        assertTrue(adaptive.phraseWords(3_000) <= 6)
    }

    @Test fun nativeCaptionAndCaptionlessPlansKeepExpandedTermsAndSavedNamesWhole() {
        val input = "Dr. Chen checked the FP32 GPU for 3.5 milliseconds before Sentoria replied."
        for (timed in listOf(false, true)) {
            val projected = KokoroSpeechText.withPolicy(input, { true }, mapOf("sentoria" to "sen toria"), 7, timed).phrases!!
            val plan = KokoroPhrasePlan(projected)
            val phrases = generateSequence { plan.next(6) }.toList()
            for (term in listOf("doctor chen", "f p thirty two", "g p u", "three point five milliseconds", "sen toria")) {
                assertTrue(term, phrases.any { term in it.speech })
            }
            assertTrue(phrases.all { it.speech.toByteArray().size <= 128 })
            assertEquals(projected.flatMap { it.wordRanges }, phrases.flatMap { it.wordRanges })
            if (!timed) assertTrue(phrases.all { it.wordRanges.isEmpty() })
        }
    }
}
