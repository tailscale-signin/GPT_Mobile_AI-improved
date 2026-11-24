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
}
