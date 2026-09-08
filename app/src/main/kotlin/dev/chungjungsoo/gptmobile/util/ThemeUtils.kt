package dev.chungjungsoo.gptmobile.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode

@Composable
fun getDynamicThemeTitle(dynamicTheme: DynamicTheme): String = when (dynamicTheme) {
    DynamicTheme.ON -> stringResource(R.string.on)
    DynamicTheme.OFF -> stringResource(R.string.off)
}

@Composable
fun getThemeModeTitle(themeMode: ThemeMode): String = when (themeMode) {
    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
    ThemeMode.DARK -> stringResource(R.string.theme_dark)
    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
}
