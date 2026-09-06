package dev.melo.gptmobile.improved.data.dto

import dev.melo.gptmobile.improved.data.model.DynamicTheme
import dev.melo.gptmobile.improved.data.model.ThemeMode

data class ThemeSetting(
    val dynamicTheme: DynamicTheme = DynamicTheme.OFF,
    val themeMode: ThemeMode = ThemeMode.SYSTEM
)
