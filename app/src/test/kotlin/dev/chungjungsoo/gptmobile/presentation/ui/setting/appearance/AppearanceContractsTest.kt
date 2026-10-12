package dev.chungjungsoo.gptmobile.presentation.ui.setting.appearance

import dev.chungjungsoo.gptmobile.data.dto.ThemePresets
import dev.chungjungsoo.gptmobile.data.dto.ThemeSetting
import dev.chungjungsoo.gptmobile.data.dto.withStableProfileIds
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppearanceContractsTest {
    @Test fun legacyDuplicateNamesReceiveStableDistinctIds() {
        val profile = ThemePresets.defaultProfile.copy(name = "Duplicate")
        val old = ThemeSetting(savedProfiles = listOf(profile, profile))
        val upgraded = old.withStableProfileIds()
        assertEquals(2, upgraded.savedProfiles.map { it.id }.distinct().size)
        assertEquals(upgraded, old.withStableProfileIds())
        assertEquals(upgraded, upgraded.withStableProfileIds())
    }

    @Test fun applyingDraftPreservesConcurrentSavedLibraryChanges() {
        val source = ThemeSetting()
        val draft = AppearanceDraft.from(source).copy(mode = ThemeMode.LIGHT)
        val newest = source.copy(savedProfiles = listOf(ThemePresets.defaultProfile))
        assertEquals(newest.savedProfiles, draft.applyTo(newest).savedProfiles)
        assertEquals(ThemeMode.LIGHT, draft.applyTo(newest).themeMode)
    }

    @Test fun hexRejectsOverlongInputInsteadOfTruncating() {
        assertEquals(0xFF00FFDEL, parseThemeHex("#00ffde"))
        assertNull(parseThemeHex("#00FFDE00"))
        assertNull(parseThemeHex("#000"))
        assertNull(parseThemeHex("#GGGGGG"))
    }

    @Test fun namesCountUnicodeCodepoints() {
        assertEquals("😀".repeat(40), validName("😀".repeat(40)))
        assertTrue(runCatching { validName("😀".repeat(41)) }.isFailure)
        assertTrue(runCatching { validName("   ") }.isFailure)
    }
}
