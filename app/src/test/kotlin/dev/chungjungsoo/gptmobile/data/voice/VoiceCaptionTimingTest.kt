package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun aResumedTrackWaitsForItsFirstNewPresentedFrameBeforeAdvancingAnAheadCaption() {
        val clock = VoicePlaybackClock(1_000)
        val captions = VoicePcmCaptions()
        captions.append(0, "one", listOf(0..2), intArrayOf(0, 1_000), 1_000)
        captions.consumed(clock.position(1_000, null, null, 0, true).frames)
        captions.append(1, "two", listOf(0..2), intArrayOf(0, 200), 300)
        // The focus loss and gain can both occur between clock polls.
        val primed = clock.position(1_000, null, null, 2_000_000, true, interrupted = true)
        assertFalse(primed.presenting)
        val ahead = VoicePlaybackClock.captionFrames(primed.frames, 1_000, -150, acceptedFrames = 1_300)
        assertNull(captions.consumed(ahead, advancing = primed.presenting))
        assertFalse(clock.position(1_000, null, null, 5_000_000, true).presenting)
        val resumed = clock.position(1_001, null, null, 6_000_000, true)
        assertTrue(resumed.presenting)
        assertEquals(1, captions.consumed(VoicePlaybackClock.captionFrames(resumed.frames, 1_000, -150), resumed.presenting)?.segment)
        clock.routeChanged()
        assertFalse(clock.position(1_001, null, null, 7_000_000, true).presenting)
        assertTrue(clock.position(1_002, null, null, 8_000_000, true).presenting)
    }

    @Test fun aSealedShortTailCanRestartAfterUnderrunAndCompleteOnPresentationTime() {
        val drain = VoicePlaybackDrain()
        val written = 1_040L
        var consumed = 1_000L
        var threshold = 100
        var releases = 0
        fun poll(presented: Long, nowMs: Long): Boolean {
            if (written - consumed >= threshold) consumed = written
            return drain.complete(written, consumed, presented, nowMs, 50) {
                threshold = 1
                releases++
            }
        }
        assertFalse(poll(1_000, 0))
        assertEquals(1_000L, consumed)
        assertFalse(poll(1_035, 5))
        assertEquals(written, consumed)
        assertNull(drain.startedAt)
        assertFalse(poll(written, 10))
        assertFalse(poll(written, 59))
        assertTrue(poll(written, 60))
        assertEquals(1, releases)
    }

    @Test fun aheadOffsetDoesNotConsumeAWordBeforeFirstPresentedFrame() {
        val captions = VoicePcmCaptions()
        captions.append(0, "one", listOf(0..2), intArrayOf(100, 300), 500)
        assertNull(captions.consumed(150, advancing = false))
        assertNull(captions.active())
        assertEquals(0..2, captions.consumed(151, advancing = true)?.range)
    }
}
