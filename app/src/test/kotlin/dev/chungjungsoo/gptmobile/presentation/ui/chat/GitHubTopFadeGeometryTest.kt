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

    @Test fun keyboardDoesNotEnterTheWindowReferenceAndDisabledFadesStayDisabled() {
        // The same stable pre-IME window is used throughout keyboard animation.
        listOf(800f, 600f, 400f).forEach { viewport ->
            assertEquals(72f, gitHubTopFadeHeight(48f, 800f, true), 0f)
            assertEquals(0f, gitHubTopFadeHeight(144f, viewport, false), 0f)
        }
        assertEquals(60f, gitHubTopFadeHeight(48f, 400f, true), 0f)
    }
}
