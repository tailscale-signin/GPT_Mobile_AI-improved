package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.runtime.Composable
import dev.chungjungsoo.gptmobile.presentation.common.LocalCustomPalette
import dev.chungjungsoo.gptmobile.presentation.common.LocalCustomPrimaryArgb
import dev.chungjungsoo.gptmobile.presentation.common.LocalDynamicTheme
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeFamily
import dev.chungjungsoo.gptmobile.presentation.common.LocalThemeMode
import dev.chungjungsoo.gptmobile.presentation.common.ThemeSettingProvider

@Composable
internal fun UserAppearance(content: @Composable () -> Unit) {
    ThemeSettingProvider {
        GPTMobileTheme(dynamicTheme = LocalDynamicTheme.current, themeMode = LocalThemeMode.current, customPrimaryArgb = LocalCustomPrimaryArgb.current, customPalette = LocalCustomPalette.current, themeFamily = LocalThemeFamily.current, content = content)
    }
}
