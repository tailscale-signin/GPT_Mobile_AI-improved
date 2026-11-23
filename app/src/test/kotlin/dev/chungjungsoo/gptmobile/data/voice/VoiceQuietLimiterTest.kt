package dev.chungjungsoo.gptmobile.data.voice

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class VoiceQuietLimiterTest {
    @Test fun shortPauseKeeps140MillisecondsAcrossCallbacksAndFollowingUtterances() {
        assertQuietContinuity(140)
    }

    @Test fun minimalPauseKeeps70MillisecondsAcrossCallbacksAndFollowingUtterances() {
        assertQuietContinuity(70)
    }

    @Test fun completePhraseBoundaryRemappingAndRemovedSpanReportingAgree() {
        val limiter = VoiceQuietLimiter(1_000, 2)
        val original = floatArrayOf(.1f, 0f, 0f, 0f, 0f, .2f, 0f, 0f, 0f)
        val samples = original.copyOf()
        val boundaries = intArrayOf(0, 1, 3, 5, 8, 9)
        val removed = mutableListOf<IntRange>()
        val retained = limiter.filter(samples, boundaries) { start, end -> removed += start until end }
        assertArrayEquals(floatArrayOf(.1f, 0f, 0f, .2f, 0f, 0f), retained, 0f)
        assertArrayEquals(intArrayOf(0, 1, 3, 3, 6, 6), boundaries)
        assertEquals(listOf(3..4, 8..8), removed)
        assertArrayEquals(original, samples, 0f)
    }

    private fun assertQuietContinuity(maxQuietMs: Int) {
        val quietFrames = 24_000 * maxQuietMs / 1_000
        val limiter = VoiceQuietLimiter(24_000, maxQuietMs)
        val first = floatArrayOf(.25f) + FloatArray(quietFrames - 1)
        assertSame(first, limiter.filter(first))
        val second = FloatArray(200) + floatArrayOf(.01f) + FloatArray(quietFrames + 32)
        assertArrayEquals(floatArrayOf(0f, .01f) + FloatArray(quietFrames), limiter.filter(second), 0f)
        val followingUtterance = FloatArray(256) + floatArrayOf(-.5f) + FloatArray(quietFrames / 2)
        assertArrayEquals(floatArrayOf(-.5f) + FloatArray(quietFrames / 2), limiter.filter(followingUtterance), 0f)
    }
}
