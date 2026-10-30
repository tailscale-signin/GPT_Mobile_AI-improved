package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.chungjungsoo.gptmobile.data.model.DynamicTheme
import dev.chungjungsoo.gptmobile.data.model.ThemeMode

val LocalOverlayMaterial = compositionLocalOf { "glass" }
val LocalOverlayBlur = compositionLocalOf { "auto" }
val LocalDynamicTheme = compositionLocalOf { DynamicTheme.OFF }
val LocalThemeMode = compositionLocalOf { ThemeMode.SYSTEM }
val LocalCustomPalette = compositionLocalOf<dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette?> { null }
val LocalCustomPrimaryArgb = compositionLocalOf<Long?> { null }
val LocalThemeViewModel = compositionLocalOf<ThemeViewModel> {
    error("CompositionLocal LocalThemeViewModel is not present")
}

@Composable
fun ThemeSettingProvider(
    themeViewModel: ThemeViewModel? = null,
    enabled: Boolean = true,
    content: @Composable () -> Unit
) {
    if (!enabled) {
        content()
        return
    }
    val activeViewModel = themeViewModel ?: hiltViewModel<ThemeViewModel>()
    activeViewModel.themeSetting.collectAsStateWithLifecycle().value.run {
        CompositionLocalProvider(
            LocalThemeViewModel provides activeViewModel,
            LocalDynamicTheme provides dynamicTheme,
            LocalThemeMode provides themeMode,
            LocalCustomPrimaryArgb provides customPrimaryArgb,
            LocalCustomPalette provides customPalette,
            LocalOverlayMaterial provides overlayMaterial,
            LocalOverlayBlur provides overlayBlur,
            content = { AccessibilityProvider(this) { content() } }
        )
    }
}
