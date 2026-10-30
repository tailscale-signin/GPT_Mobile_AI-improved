package dev.chungjungsoo.gptmobile.data.dto

import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import kotlinx.serialization.Serializable

data class ThemeSetting(
    val dynamicTheme: DynamicTheme = DynamicTheme.OFF,
    val themeMode: ThemeMode = ThemeMode.DARK,
    val customPrimaryArgb: Long? = null,
    val customPalette: CustomThemePalette? = null,
    val savedProfiles: List<SavedThemeProfile> = emptyList(),
    val recoveryWarning: String? = null,
    val activeProfileId: String? = null,
    val overlayMaterial: String = "glass",
    val overlayBlur: String = "auto",
    val textScale: Float = 1f,
    val adaptiveAnswerText: Boolean = true,
    val hapticsEnabled: Boolean = true,
    val hapticStrength: Float = 0.5f,
    val themeHistory: List<ThemeSnapshot> = emptyList()
)

@Serializable
data class ThemeSnapshot(
    val mode: ThemeMode,
    val dynamic: DynamicTheme,
    val primary: Long?,
    val palette: CustomThemePalette?,
    val savedAt: Long = 0L
)

fun ThemeSetting.snapshot() = ThemeSnapshot(themeMode, dynamicTheme, customPrimaryArgb, customPalette, System.currentTimeMillis())

fun ThemeSetting.recordPrevious(previous: ThemeSetting): ThemeSetting {
    val changed = themeMode != previous.themeMode || dynamicTheme != previous.dynamicTheme || customPalette != previous.customPalette || customPrimaryArgb != previous.customPrimaryArgb
    return copy(
        textScale = textScale.takeIf { it.isFinite() }?.coerceIn(1f, 1.5f) ?: 1f,
        hapticStrength = hapticStrength.takeIf { it.isFinite() }?.coerceIn(0.1f, 1f) ?: 0.5f,
        themeHistory = if (changed && themeHistory == previous.themeHistory) (previous.themeHistory + previous.snapshot()).takeLast(10) else themeHistory.takeLast(10)
    )
}

@Serializable
data class CustomThemePalette(
    val primary: Long,
    val secondary: Long,
    val background: Long,
    val surface: Long
)

@Serializable
data class SavedThemeProfile(val name: String, val palette: CustomThemePalette, val mode: ThemeMode = ThemeMode.DARK, val id: String = "")

/** Stable even before the first write, including duplicate entries in legacy backups. */
fun ThemeSetting.withStableProfileIds(): ThemeSetting {
    val used = ThemePresets.profiles.map { it.id }.toMutableSet()
    return copy(
        savedProfiles = savedProfiles.mapIndexed { index, profile ->
            val existing = profile.id.takeIf { it.isNotBlank() && it !in used }
            val id = existing ?: "saved:" + java.util.UUID.nameUUIDFromBytes(
                "$index:${profile.name}:${profile.mode}:${profile.palette}".toByteArray(Charsets.UTF_8)
            )
            used += id
            profile.copy(id = id)
        }
    ).let { value ->
        val profiles = ThemePresets.profiles + value.savedProfiles
        val matches = profiles.filter { it.palette == value.customPalette && it.mode == value.themeMode && value.dynamicTheme == DynamicTheme.OFF }
        val id = value.activeProfileId
        value.copy(
            activeProfileId = when {
                id == "custom" -> id
                id != null -> id.takeIf { candidate -> matches.any { it.id == candidate } } ?: "custom"
                matches.size == 1 -> matches.single().id
                else -> "custom"
            }
        )
    }
}

object ThemePresets {
    val defaultProfile = SavedThemeProfile("Cyan", CustomThemePalette(0xFF00FFDE, 0xFFCC00FF, 0xFF001219, 0xFF0B2028), id = "builtin:cyan")
    val profiles = listOf(
        defaultProfile,
        SavedThemeProfile("OLED", CustomThemePalette(0xFF8CD5FF, 0xFFCEB6FF, 0xFF000000, 0xFF101114)),
        SavedThemeProfile("Cobalt", CustomThemePalette(0xFFA8C5FF, 0xFF7FE0CF, 0xFF0B1224, 0xFF17213A)),
        SavedThemeProfile("Espresso", CustomThemePalette(0xFFE8BF98, 0xFFC4D5A1, 0xFF1A1410, 0xFF2A211C)),
        SavedThemeProfile("Plum", CustomThemePalette(0xFFE8B3EC, 0xFFFFC4AC, 0xFF201023, 0xFF311D35)),
        SavedThemeProfile("Paper", CustomThemePalette(0xFF365C89, 0xFF725B42, 0xFFFCFBF8, 0xFFF1F0EB), ThemeMode.LIGHT),
        SavedThemeProfile("Porcelain", CustomThemePalette(0xFF4B5A91, 0xFF386960, 0xFFF8FAFF, 0xFFEDF0F8), ThemeMode.LIGHT),
        SavedThemeProfile("Arctic", CustomThemePalette(0xFF55DFF2, 0xFF9EDCE5, 0xFF001A1F, 0xFF062329)),
        SavedThemeProfile("Orchid", CustomThemePalette(0xFFD0BCFF, 0xFFE8B9D5, 0xFF191323, 0xFF251D33)),
        SavedThemeProfile("Forest", CustomThemePalette(0xFF8CD9AD, 0xFFC2D69A, 0xFF101C16, 0xFF1B2B22)),
        SavedThemeProfile("Ember", CustomThemePalette(0xFFFFB59C, 0xFFFFD38C, 0xFF241711, 0xFF34231D)),
        SavedThemeProfile("Midnight", CustomThemePalette(0xFF91B5FF, 0xFFC2C8FF, 0xFF0C1020, 0xFF181E34)),
        SavedThemeProfile("Rose", CustomThemePalette(0xFFFFB0CD, 0xFFD4B7FA, 0xFF21121C, 0xFF332030)),
        SavedThemeProfile("Ocean", CustomThemePalette(0xFF68CFFF, 0xFF80E5D4, 0xFF071C2A, 0xFF102D3E)),
        SavedThemeProfile("Amber", CustomThemePalette(0xFFFFCD70, 0xFFE3B98B, 0xFF201A10, 0xFF332B1B)),
        SavedThemeProfile("Graphite", CustomThemePalette(0xFFC8D0DA, 0xFFA3C2D0, 0xFF151719, 0xFF25282C)),
        SavedThemeProfile("Neon", CustomThemePalette(0xFFA7F36B, 0xFF72E3E0, 0xFF10170E, 0xFF1E2A18)),
        SavedThemeProfile("Lavender", CustomThemePalette(0xFF7651A6, 0xFF8F557A, 0xFFFAF5FF, 0xFFF0E7FA), ThemeMode.LIGHT),
        SavedThemeProfile("Sand", CustomThemePalette(0xFF82612D, 0xFF74634A, 0xFFFFF9EE, 0xFFF2E8D5), ThemeMode.LIGHT),
        SavedThemeProfile("Mint", CustomThemePalette(0xFF216B55, 0xFF43685E, 0xFFF1FCF6, 0xFFE0F1E8), ThemeMode.LIGHT),
        SavedThemeProfile("Sky", CustomThemePalette(0xFF245C9E, 0xFF53688F, 0xFFF4F9FF, 0xFFE5EEFA), ThemeMode.LIGHT),
        SavedThemeProfile("Daylight", CustomThemePalette(0xFF006A7A, 0xFF65558F, 0xFFF5FAFC, 0xFFEAF1F5), ThemeMode.LIGHT)
    ).map { it.copy(id = "builtin:" + it.name.lowercase(java.util.Locale.ROOT)) } + RenovationPalettes.profiles
}
