package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.backup.CompleteBackupSection
import dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette
import dev.chungjungsoo.gptmobile.data.dto.ThemeBackupDto
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ThemeAndBackupDefaultsTest {
    @Test fun readabilityFeedbackAndThemeHistorySurviveBackupRoundTrip() {
        val history = listOf(dev.chungjungsoo.gptmobile.data.dto.ThemeSetting().let { dev.chungjungsoo.gptmobile.data.dto.ThemeSnapshot(it.themeMode, it.dynamicTheme, it.customPrimaryArgb, it.customPalette, 10L) })
        val theme = ThemeBackupDto(textScale = 1.3f, adaptiveAnswerText = false, hapticsEnabled = false, hapticStrength = 0.2f, themeHistory = history)
        assertEquals(theme, Json.decodeFromString<ThemeBackupDto>(Json.encodeToString(theme)))
        val ui = dev.chungjungsoo.gptmobile.data.backup.UiPreferencesBackupDto(textScale = 1.3f, adaptiveAnswerText = false, hapticsEnabled = false, hapticStrength = 0.2f, themeHistory = history)
        assertEquals(ui, Json.decodeFromString<dev.chungjungsoo.gptmobile.data.backup.UiPreferencesBackupDto>(Json.encodeToString(ui)))
        val legacy = Json.decodeFromString<ThemeBackupDto>("{}")
        assertTrue(legacy.adaptiveAnswerText)
        assertEquals(1f, legacy.textScale, 0f)
        assertTrue(legacy.themeHistory.isEmpty())
    }

    @Test fun backupAndRestoreOptionsStartWithLargeOptionalSectionsDisabled() {
        val state = SettingViewModelV2.BackupUiState()
        listOf(CompleteBackupSection.AGENT_HISTORY, CompleteBackupSection.ATTACHMENTS, CompleteBackupSection.LOCAL_MODELS).forEach {
            assertFalse(state.selection.includes(it))
        }
        assertTrue(state.selection.includes(CompleteBackupSection.TOOLS))
        assertFalse(state.passwordProtectionEnabled)
    }

    @Test fun customPaletteRoundTripsAndOldBackupsKeepDefaultPalette() {
        val theme = ThemeBackupDto(savedProfiles = dev.chungjungsoo.gptmobile.data.dto.ThemePresets.profiles, customPrimaryArgb = 0xFF123456, customPalette = CustomThemePalette(0xFF123456, 0xFFABCDEF, 0xFF101010, 0xFF202020))
        assertEquals(theme, Json.decodeFromString<ThemeBackupDto>(Json.encodeToString(theme)))
        assertNull(Json.decodeFromString<ThemeBackupDto>("""{"themeMode":0,"dynamicTheme":false}""").customPalette)
    }
}
