package dev.chungjungsoo.gptmobile.data.voice

import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceCaptionWordCoverageTest {
    private fun cue(range: IntRange, final: Boolean = true) = VoiceStreamCue(range, 0, 1, VoiceWordCueProvenance.ESTIMATED, finalEnd = final)

    @Test fun firstAndLastCuesCannotHideAMissingMiddleWord() {
        val coverage = VoiceCaptionWordCoverage("Café voices follow.")
        coverage.observe(listOf(cue(0..3), cue(12..17)))
        assertEquals(3, coverage.result().expectedWords)
        assertEquals(2, coverage.result().individuallyTimedWords)
        assertFalse(coverage.result().complete)
        coverage.observe(listOf(cue(5..10)))
        assertTrue(coverage.result().complete)
    }

    @Test fun aGroupedPhraseDoesNotQualifyIndividualWordHighlighting() {
        val coverage = VoiceCaptionWordCoverage("one two three")
        coverage.observe(listOf(cue(0..6), cue(8..12)))
        val result = coverage.result()
        assertEquals(3, result.coveredWords)
        assertEquals(1, result.individuallyTimedWords)
        assertEquals(1, result.groupedWordSpans)
        assertFalse(result.complete)
    }

    @Test fun truncationAndProvisionalOnlyWordsRemainIncomplete() {
        val coverage = VoiceCaptionWordCoverage("Café ready")
        coverage.observe(listOf(cue(0..2), cue(5..9, final = false)))
        assertEquals(1, coverage.result().partialWordSpans)
        assertEquals(1, coverage.result().provisionalWordSpans)
        assertFalse(coverage.result().complete)
        coverage.observe(listOf(cue(5..9)))
        assertEquals(0, coverage.result().provisionalWordSpans)
        assertFalse(coverage.result().complete)
    }

    @Test fun unicodePunctuationAndRepeatedRevisionsDoNotInflateCoverage() {
        val text = "“Café”, nai\u0308ve l’été 42 🙂"
        val ranges = listOf(1..4, 8..13, 15..19, 21..22)
        val coverage = VoiceCaptionWordCoverage(text)
        repeat(4) { coverage.observe(ranges.map(::cue)) }
        assertEquals(4, coverage.result().expectedWords)
        assertEquals(4, coverage.result().individuallyTimedWords)
        assertTrue(coverage.result().complete)
        val emoji = text.indexOf("🙂")
        assertThrows(IllegalArgumentException::class.java) { coverage.observe(listOf(cue(emoji..emoji))) }
    }

    @Test fun normalizedMultiwordPronunciationsMapEveryCueBackToTheOriginalSource() {
        val source = "Café Sentoria is ready."
        val phrase = requireNotNull(VoiceStreamingPhrasePlan(source, mapOf("Sentoria" to "sen toria"), 1f).next(24))
        assertEquals("Café sen toria is ready.", phrase.text)
        val normalized = VoiceCaptionWordCoverage(phrase.text)
        val original = VoiceCaptionWordCoverage(source)
        val raw = VoiceStreamFrames(phrase.text)
        val projected = VoiceStreamFrames(phrase.text, sourceCharacters = phrase.sourceCharacters)
        val chunk = words(phrase.text)
        normalized.observe(raw.accept(chunk))
        original.observe(projected.accept(chunk))
        raw.finish()
        projected.finish()
        assertTrue(normalized.result().complete)
        assertTrue(original.result().complete)
        assertEquals(5, normalized.result().expectedWords)
        assertEquals(4, original.result().expectedWords)
    }

    @Test fun completeUnicodeWordMappingSurvivesEverySupportedTempoAndTailDrain() {
        val text = "Café naïve voices follow."
        for (speed in listOf(.5f, .75f, 1f, 1.25f, 1.5f, 2f)) {
            val processor = VoiceTempoProcessor(speed)
            val frames = VoiceStreamFrames(text)
            val coverage = VoiceCaptionWordCoverage(text)
            val consume: (VoicePcmChunk) -> Unit = { coverage.observe(frames.accept(it)) }
            processor.accept(words(text), consume)
            processor.finish(consume)
            frames.finish()
            assertTrue("Source mapping lost at $speed×", coverage.result().complete)
            assertEquals(4, coverage.result().individuallyTimedWords)
        }
    }

    private fun words(text: String): VoicePcmChunk {
        val spans = Regex("\\S+").findAll(text).map { it.range }.toList()
        val frames = spans.size * 6_000
        return VoicePcmChunk(
            FloatArray(frames) { (.2 * sin(2 * PI * 220 * it / VOICE_PCM_SAMPLE_RATE)).toFloat() },
            wordCues = spans.mapIndexed { index, range ->
                VoiceWordCue(
                    text.substring(0, range.first).toByteArray(Charsets.UTF_8).size,
                    text.substring(0, range.last + 1).toByteArray(Charsets.UTF_8).size,
                    index * 6_000L,
                    (index + 1) * 6_000L
                )
            }
        )
    }
}
