package dev.melo.gptmobile.improved.util

import android.content.Context
import dev.melo.gptmobile.improved.R
import dev.melo.gptmobile.improved.data.model.ClientType
import dev.melo.gptmobile.improved.data.model.DynamicTheme
import dev.melo.gptmobile.improved.data.model.ThemeMode

fun getThemeModeName(context: Context, themeMode: ThemeMode): String = when (themeMode) {
    ThemeMode.AUTO -> context.getString(R.string.theme_system)
    ThemeMode.LIGHT -> context.getString(R.string.theme_light)
    ThemeMode.DARK -> context.getString(R.string.theme_dark)
}

fun getDynamicThemeName(context: Context, dynamicTheme: DynamicTheme): String = when (dynamicTheme) {
    DynamicTheme.OFF -> context.getString(R.string.dynamic_theme_off)
    DynamicTheme.FOLLOW_THEME -> context.getString(R.string.dynamic_theme_follow_theme)
    DynamicTheme.ALWAYS_ON -> context.getString(R.string.dynamic_theme_always_on)
}
