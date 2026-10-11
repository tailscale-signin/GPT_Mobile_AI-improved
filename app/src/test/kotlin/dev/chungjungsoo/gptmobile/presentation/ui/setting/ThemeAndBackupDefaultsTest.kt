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

    @Test
    fun newAppearancePreferencesRoundTripWithoutChangingLegacyArgb() {
        val palette = CustomThemePalette(0x80123456, 0xFFAABBCC, 0xFF112233, 0xFF445566)
        val theme = ThemeBackupDto(themeFamily = "Iris", reducedMotion = true, customPalette = palette)
        assertEquals(theme, Json.decodeFromString<ThemeBackupDto>(Json.encodeToString(theme)))
        val legacy = Json.decodeFromString<ThemeBackupDto>("""{"themeMode":2,"customPrimaryArgb":4279383126}""")
        assertNull(legacy.themeFamily)
        assertFalse(legacy.reducedMotion)
        assertEquals(4279383126L, legacy.customPrimaryArgb)
    }
}
