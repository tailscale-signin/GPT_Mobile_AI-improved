package dev.chungjungsoo.gptmobile.data.dto

import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode
import kotlinx.serialization.Serializable

data class ThemeSetting(
    val dynamicTheme: DynamicTheme = DynamicTheme.OFF,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val customPrimaryArgb: Long? = null,
    val customPalette: CustomThemePalette? = null
)

@Serializable
data class CustomThemePalette(
    val primary: Long,
    val secondary: Long,
    val background: Long,
    val surface: Long
)
