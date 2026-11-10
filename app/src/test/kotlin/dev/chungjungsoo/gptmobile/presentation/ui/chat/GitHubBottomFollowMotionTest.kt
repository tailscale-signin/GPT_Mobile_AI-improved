package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubBottomFollowMotionTest {
    @Test fun relativeStepsReachTheMeasuredBottomAtTheEndOfOnePointFiveSeconds() {
        assertEquals(1500, GITHUB_BOTTOM_FOLLOW_MILLIS)
        var remaining = 120f
        var previous = 0f
        repeat(90) { frame ->
            val fraction = (frame + 1) / 90f
            val step = gitHubBottomFollowStep(remaining, previous, fraction)
            assertTrue(step in 0f..remaining)
            remaining -= step
            if (frame < 89) assertTrue(remaining > 0f)
            previous = fraction
        }
        assertEquals(0f, remaining, .001f)
    }

    @Test fun newPreviewGrowthIsMeasuredWithinTheSameAnimation() {
        var remaining = 100f - gitHubBottomFollowStep(100f, 0f, .5f)
        remaining += 80f
        remaining -= gitHubBottomFollowStep(remaining, .5f, .75f)
        assertEquals(65f, remaining, .001f)
        remaining -= gitHubBottomFollowStep(remaining, .75f, 1f)
        assertEquals(0f, remaining, .001f)
    }

    @Test fun shrinkingLayoutAndInvalidMeasurementsNeverReverseTheScroll() {
        for (gap in listOf(-4f, 0f, Float.NaN, Float.POSITIVE_INFINITY)) assertEquals(0f, gitHubBottomFollowStep(gap, .3f, .5f), 0f)
        assertEquals(0f, gitHubBottomFollowStep(100f, .5f, .3f), 0f)
        assertEquals(0f, gitHubBottomFollowStep(100f, .5f, Float.NaN), 0f)
        assertEquals(100f, gitHubBottomFollowStep(100f, .9f, 1f), .001f)
    }
}
