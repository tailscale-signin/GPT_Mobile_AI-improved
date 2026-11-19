package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class GitHubTopFadeGeometryTest {
    @Test fun defaultAddsThreePercentAndMeasuredExpansionIsAddedExactlyOnce() {
        val collapsed = gitHubTopFadeHeight(48f, 800f, true)
        assertEquals(72f, collapsed, 0f)
        assertEquals(collapsed + 96f, gitHubTopFadeHeight(144f, 800f, true), 0f)
        assertEquals(collapsed + 48f, gitHubTopFadeHeight(96f, 800f, true), 0f)
        assertEquals(collapsed, gitHubTopFadeHeight(48f, 800f, true), 0f)
    }

    @Test fun profilesInterpolateFromTheSameCollapsedDepth() {
        GitHubFadeProfile.entries.forEach { profile ->
            assertEquals(72f, gitHubTopFadeHeight(48f, 800f, true, 0f, profile), .001f)
            assertEquals(48f + profile.expandedExtra * 800f, gitHubTopFadeHeight(48f, 800f, true, 1f, profile), .001f)
        }
        assertEquals(236f, gitHubTopFadeHeight(48f, 800f, true, .5f), .001f)
    }

    @Test fun expandedFadeRestoresTheExactCollapsedDepth() {
        val collapsed = gitHubTopFadeHeight(48f, 800f, true, 0f)
        assertEquals(72f, collapsed, .001f)
        assertEquals(400f, gitHubTopFadeHeight(48f, 800f, true, 1f), .001f)
        assertEquals(collapsed, gitHubTopFadeHeight(48f, 800f, true, 0f), .001f)
    }

    @Test fun expandedTranscriptFadeIsStrongerAtTopWithSharperFalloff() {
        val start = 0.10f
        val stop = 0.50f
        val collapsed = chatTopFadeStops(start, stop, 0f)
        val expanded = chatTopFadeStops(start, stop, 1f)
        assertEquals(start, collapsed.first, 0f)
        assertEquals(stop, collapsed.second, 0f)
        org.junit.Assert.assertTrue(expanded.first > collapsed.first)
        org.junit.Assert.assertTrue(expanded.second < collapsed.second)
        org.junit.Assert.assertTrue(expanded.second - expanded.first < collapsed.second - collapsed.first)
        assertEquals(collapsed.first, chatTopFadeStops(start, stop, 0f).first, 0f)
        assertEquals(start, chatTopFadeStops(start, start, 1f).first, 0f)
        assertEquals(stop, chatTopFadeStops(stop, stop, Float.NaN).second, 0f)
    }

    @Test fun keyboardDoesNotEnterTheWindowReferenceAndDisabledFadesStayDisabled() {
        // The same stable pre-IME window is used throughout keyboard animation.
        listOf(800f, 600f, 400f).forEach { viewport ->
            assertEquals(72f, gitHubTopFadeHeight(48f, 800f, true), 0f)
            assertEquals(0f, gitHubTopFadeHeight(144f, viewport, false), 0f)
        }
        assertEquals(60f, gitHubTopFadeHeight(48f, 400f, true), 0f)
        // A tall header must not leave the entire small viewport faded.
        assertEquals(220f, gitHubTopFadeHeight(200f, 240f, true, 1f, GitHubFadeProfile.CINEMATIC), .001f)
    }
}
