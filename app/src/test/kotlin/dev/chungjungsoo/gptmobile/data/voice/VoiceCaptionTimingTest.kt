package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceCaptionTimingTest {
    @Test fun interpolationStopsAtAcceptedAudioAndFreezesDuringRefill() {
        val timing = VoiceCaptionTiming("one", 100, 400, 150, 1_000, 1_000_000_000, true, bufferedEndFrame = 600)
        assertEquals(350, timing.frameAt(1_200_000_000))
        assertEquals(600, timing.frameAt(2_000_000_000))
        assertEquals(150, timing.copy(playing = false).frameAt(2_000_000_000))
        assertEquals(150, timing.frameAt(900_000_000))
    }

    @Test fun cueRetainsExactWordDurationAndCannotRewindAcrossRouteCorrection() {
        val captions = VoicePcmCaptions()
        captions.append(0, "One two", listOf(0..2, 4..6), intArrayOf(100, 200, 220, 400), 500)
        val word = requireNotNull(captions.consumed(150))
        assertEquals(100, word.frame)
        assertEquals(200, word.endFrame)
        assertEquals(word, captions.active())
        assertNull(captions.consumed(120))
        assertEquals(word, captions.active())
        assertEquals(4..6, captions.consumed(240)?.range)
        assertNull(captions.consumed(150))
        assertEquals(4..6, captions.active()?.range)
        assertEquals(IntRange.EMPTY, captions.consumed(500)?.range)
    }

    @Test(expected = IllegalArgumentException::class)
    fun invalidRemappedWordDurationCannotBeQueued() {
        VoicePcmCaptions().append(0, "bad", listOf(0..2), intArrayOf(2, 12), 10)
    }

    @Test fun newlyProducedWordCannotAdvanceDuringRefillEvenAtThePreviousCueBoundary() {
        val captions = VoicePcmCaptions()
        captions.append(0, "one", listOf(0..2), intArrayOf(0, 1_000), 1_000)
        captions.consumed(1_000)
        captions.append(1, "two", listOf(0..2), intArrayOf(0, 200), 300)
        assertNull(captions.consumed(1_150, advancing = false))
        assertEquals(IntRange.EMPTY, captions.active()?.range)
        assertEquals(1_000L, VoicePlaybackClock.captionFrames(1_000, 1_000, -150, acceptedFrames = 1_000))
        val resumed = captions.consumed(VoicePlaybackClock.captionFrames(1_001, 1_000, -150, acceptedFrames = 1_300), advancing = true)
        assertEquals(1, resumed?.segment)
        assertEquals(0..2, resumed?.range)
    }

    @Test fun aheadOffsetDoesNotConsumeAWordBeforeFirstPresentedFrame() {
        val captions = VoicePcmCaptions()
        captions.append(0, "one", listOf(0..2), intArrayOf(100, 300), 500)
        assertNull(captions.consumed(150, advancing = false))
        assertNull(captions.active())
        assertEquals(0..2, captions.consumed(151, advancing = true)?.range)
    }
}
