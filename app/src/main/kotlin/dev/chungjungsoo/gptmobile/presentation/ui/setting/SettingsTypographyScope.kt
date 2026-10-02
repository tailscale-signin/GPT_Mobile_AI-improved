package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

private fun TextStyle.settingsSize(): TextStyle =
    copy(fontSize = (fontSize.value + 2f).sp)

/** Applies the Settings-specific +2sp accessibility/readability scale without changing chat typography. */
@Composable
fun SettingsTypographyScope(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    MaterialTheme(
        typography = base.copy(
            displayLarge = base.displayLarge.settingsSize(),
            displayMedium = base.displayMedium.settingsSize(),
            displaySmall = base.displaySmall.settingsSize(),
            headlineLarge = base.headlineLarge.settingsSize(),
            headlineMedium = base.headlineMedium.settingsSize(),
            headlineSmall = base.headlineSmall.settingsSize(),
            titleLarge = base.titleLarge.settingsSize(),
            titleMedium = base.titleMedium.settingsSize(),
            titleSmall = base.titleSmall.settingsSize(),
            bodyLarge = base.bodyLarge.settingsSize(),
            bodyMedium = base.bodyMedium.settingsSize(),
            bodySmall = base.bodySmall.settingsSize(),
            labelLarge = base.labelLarge.settingsSize(),
            labelMedium = base.labelMedium.settingsSize(),
            labelSmall = base.labelSmall.settingsSize()
        ),
        content = content
    )
}
