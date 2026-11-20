package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoicePcmCaptionsTest {
    @Test fun highlightingWaitsForPlayedVoicedFramesAndStopsAtSilence() {
        val clock = VoicePcmCaptions()
        val text = "go go"
        for (range in listOf(0..1, 3..4)) {
            clock.beginWord()
            clock.append(0, text, range, floatArrayOf(0f, 0f, .3f, -.3f, 0f))
            clock.endWord(0, text)
        }
        assertNull(clock.consumed(2))
        assertEquals(0..1, clock.consumed(3)?.range)
        assertNull(clock.consumed(3))
        assertEquals(IntRange.EMPTY, clock.consumed(5)?.range)
        assertEquals(3..4, clock.consumed(8)?.range)
        assertEquals(IntRange.EMPTY, clock.consumed(10)?.range)
    }

    @Test fun expandedNumbersAndInitialismsKeepTheirSourceWord() {
        val parts = kokoroCaptionParts("API v2.5, go!", isKnown = { true })
        assertEquals(listOf("a p i", "version two point five,", "go!"), parts.map { it.speech })
        assertEquals(listOf(0..2, 4..7, 10..11), parts.map { it.range })
    }
}
