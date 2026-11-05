package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubBottomFollowMotionTest {
    @Test fun streamedGrowthConvergesWithoutOvershootingOrReversing() {
        var remaining = 120f
        var moved = 0f
        repeat(120) {
            val step = gitHubBottomFollowStep(remaining, 16_000_000)
            assertTrue(step >= 0f && step <= remaining)
            remaining -= step
            moved += step
        }
        assertEquals(0f, remaining, .001f)
        assertEquals(120f, moved, .01f)
        assertEquals(0f, gitHubBottomFollowStep(-40f, 16_000_000), 0f)
        assertEquals(0f, gitHubBottomFollowStep(0f, 16_000_000), 0f)
    }

    @Test fun equivalentFrameTimeProducesTheSameMotionAtDifferentRefreshRates() {
        fun remaining(frames: Int, nanos: Long): Float {
            var distance = 1000f
            repeat(frames) { distance -= gitHubBottomFollowStep(distance, nanos) }
            return distance
        }
        assertEquals(remaining(10, 16_000_000), remaining(20, 8_000_000), .01f)
    }

    @Test fun stalledFramesCannotCreateACatchupJump() {
        val bounded = gitHubBottomFollowStep(1000f, 32_000_000)
        assertEquals(bounded, gitHubBottomFollowStep(1000f, 600_000_000), 0f)
        assertTrue(bounded in 1f..400f)
        assertEquals(0f, gitHubBottomFollowStep(1000f, 0), 0f)
        assertEquals(0f, gitHubBottomFollowStep(Float.NaN, 16_000_000), 0f)
    }

    @Test fun revisedLayoutTargetsKeepEachCorrectionWithinTheCurrentGap() {
        val gaps = listOf(40f, 5f, -4f, 0f, 75f, .25f)
        gaps.forEach { gap ->
            val step = gitHubBottomFollowStep(gap, 16_000_000)
            assertTrue(step >= 0f && step <= gap.coerceAtLeast(0f))
        }
        assertEquals(.25f, gitHubBottomFollowStep(.25f, 16_000_000), 0f)
    }
}
