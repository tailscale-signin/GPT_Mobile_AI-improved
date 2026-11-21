package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePcmCaptionsTest {
    @Test fun modelBoundariesIncludePaddingAndSpacesButHighlightOnlyTheWords() {
        // PAD, word, space, word, punctuation, PAD. Each value is in model duration units.
        val frames = kokoroWordFrames(longArrayOf(2, 4, 1, 3, 1, 2), intArrayOf(1, 2, 3, 4), 2, 7800)
        assertArrayEquals(intArrayOf(1200, 3600, 4200, 6000), frames)
        val clock = VoicePcmCaptions()
        clock.append(0, "Go go.", listOf(0..1, 3..4), frames, 7800)
        assertNull(clock.consumed(1199))
        assertEquals(0..1, clock.consumed(1200)?.range)
        assertNull(clock.consumed(3400))
        assertEquals(IntRange.EMPTY, clock.consumed(3600)?.range)
        assertEquals(3..4, clock.consumed(4200)?.range)
        assertEquals(IntRange.EMPTY, clock.consumed(6000)?.range)
        assertNull(clock.consumed(7800))
    }

    @Test fun expansionsKeepOneHighlightAcrossSpokenWordsAndLimitersRemapEveryBoundary() {
        val limiter = VoiceQuietLimiter(1000, 2)
        val clock = VoicePcmCaptions()
        val frames = intArrayOf(2, 4, 7, 9, 10, 11)
        val samples = limiter.filter(floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 1f, 0f, 1f, 0f, 0f), frames)
        assertArrayEquals(intArrayOf(2, 4, 6, 8, 9, 10), frames)
        clock.append(0, "We're here", listOf(0..4, 0..4, 6..9), frames, samples.size)
        assertEquals(0..4, clock.consumed(2)?.range)
        assertNull(clock.consumed(6))
        assertEquals(IntRange.EMPTY, clock.consumed(8)?.range)
        assertEquals(6..9, clock.consumed(9)?.range)
        assertEquals(IntRange.EMPTY, clock.consumed(10)?.range)

        val next = intArrayOf(3, 4)
        val nextSamples = limiter.filter(floatArrayOf(0f, 0f, 0f, 1f), next)
        assertArrayEquals(intArrayOf(0, 1), next)
        clock.append(1, "Ok", listOf(0..1), next, nextSamples.size)
        assertEquals(1, clock.consumed(samples.size.toLong())?.segment)
        assertEquals(IntRange.EMPTY, clock.consumed(samples.size + 1L)?.range)
    }

    @Test fun oneInferencePhraseKeepsTheOriginalOffsetsThroughNormalizationAndExpansion() {
        val text = "API v2.5, café cafe\u0301 hol\u00ADiday #12!"
        val timed = KokoroSpeechText.timed(text) { true }.phrases!!.single()
        assertEquals("a p i version two point five, cafe cafe holiday number twelve!", timed.speech)
        assertEquals(
            List(3) { "API" } + List(4) { "v2.5" } + listOf("café", "cafe\u0301", "hol\u00ADiday", "#", "12"),
            timed.wordRanges.map { text.substring(it) }
        )
    }

    @Test fun expansionCanCrossPhraseLimitWithoutLosingItsOriginalRange() {
        val text = "go ".repeat(15) + "API done."
        val phrases = KokoroSpeechText.timed(text) { true }.phrases!!
        assertEquals(listOf("go ".repeat(15) + "a", "p i done."), phrases.map { it.speech })
        assertEquals(listOf("API", "API", "API", "done"), phrases.flatMap { it.wordRanges }.takeLast(4).map { text.substring(it) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedTimingCannotReleaseMisalignedAudio() {
        kokoroWordFrames(longArrayOf(1, 2, 1), intArrayOf(1, 2), 1, 2401)
    }
}
