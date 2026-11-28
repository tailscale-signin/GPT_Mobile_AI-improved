package dev.chungjungsoo.gptmobile.presentation.ui.setting

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubHeaderStateTest {
    @Test fun allHeaderContentUsesTheSameVisibilityAndSettingsStayAccessible() {
        val state = GitHubHeaderState()
        assertFalse(state.isExpanded(collapsible = true))
        assertTrue(state.isExpanded(collapsible = false))
        state.toggle()
        assertTrue(state.isExpanded(collapsible = true))
        state.toggle()
        assertFalse(state.isExpanded(collapsible = true))
    }

    @Test fun delayedPreferenceCannotUndoTheUsersChoice() {
        val state = GitHubHeaderState()
        state.restorePreference(true)
        assertTrue(state.expanded)
        state.toggle()
        state.restorePreference(true)
        assertFalse(state.expanded)
    }
}
