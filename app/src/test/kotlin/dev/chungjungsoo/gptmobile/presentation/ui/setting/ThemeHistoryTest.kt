package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.dto.recordPrevious
import dev.chungjungsoo.gptmobile.data.dto.snapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeHistoryTest {
    @Test fun appearanceChangesKeepTenPreviousStatesAndReadabilityDoesNotAddHistory() {
        var state = ThemeSetting()
        repeat(20) { state = state.copy(customPrimaryArgb = it.toLong()).recordPrevious(state) }
        assertEquals(10, state.themeHistory.size)
        assertEquals(18L, state.themeHistory.last().primary)
        val resized = state.copy(textScale = 1.3f, adaptiveAnswerText = false).recordPrevious(state)
        assertEquals(state.themeHistory, resized.themeHistory)
        assertTrue(resized.copy(themeHistory = emptyList()).recordPrevious(resized).themeHistory.isEmpty())
    }

    @Test fun importedHistoryIsPreservedAndInvalidPreferencesUseSafeDefaults() {
        val before = ThemeSetting(customPrimaryArgb = 1L)
        val incoming = ThemeSetting(customPrimaryArgb = 2L, themeHistory = listOf(before.snapshot()), textScale = Float.NaN, hapticStrength = Float.POSITIVE_INFINITY)
        val restored = incoming.recordPrevious(before)
        assertEquals(incoming.themeHistory, restored.themeHistory)
        assertEquals(1f, restored.textScale, 0f)
        assertEquals(0.5f, restored.hapticStrength, 0f)
    }
}
