package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp

/** Applies the Settings-specific +2sp accessibility/readability scale without changing chat typography. */
@Composable
fun SettingsTypographyScope(content: @Composable () -> Unit) {
    val base = MaterialTheme.typography
    MaterialTheme(
        typography = base.copy(
            displayLarge = base.displayLarge.copy(fontSize = base.displayLarge.fontSize + 2.sp),
            displayMedium = base.displayMedium.copy(fontSize = base.displayMedium.fontSize + 2.sp),
            displaySmall = base.displaySmall.copy(fontSize = base.displaySmall.fontSize + 2.sp),
            headlineLarge = base.headlineLarge.copy(fontSize = base.headlineLarge.fontSize + 2.sp),
            headlineMedium = base.headlineMedium.copy(fontSize = base.headlineMedium.fontSize + 2.sp),
            headlineSmall = base.headlineSmall.copy(fontSize = base.headlineSmall.fontSize + 2.sp),
            titleLarge = base.titleLarge.copy(fontSize = base.titleLarge.fontSize + 2.sp),
            titleMedium = base.titleMedium.copy(fontSize = base.titleMedium.fontSize + 2.sp),
            titleSmall = base.titleSmall.copy(fontSize = base.titleSmall.fontSize + 2.sp),
            bodyLarge = base.bodyLarge.copy(fontSize = base.bodyLarge.fontSize + 2.sp),
            bodyMedium = base.bodyMedium.copy(fontSize = base.bodyMedium.fontSize + 2.sp),
            bodySmall = base.bodySmall.copy(fontSize = base.bodySmall.fontSize + 2.sp),
            labelLarge = base.labelLarge.copy(fontSize = base.labelLarge.fontSize + 2.sp),
            labelMedium = base.labelMedium.copy(fontSize = base.labelMedium.fontSize + 2.sp),
            labelSmall = base.labelSmall.copy(fontSize = base.labelSmall.fontSize + 2.sp)
        ),
        content = content
    )
}
