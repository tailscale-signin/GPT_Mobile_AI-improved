package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Proxima Nova font family definition.
 * Uses generic font family with "Proxima Nova" name and sans-serif fallback
 * so that any system or bundled Proxima Nova font is prioritized.
 */
val ProximaNovaFontFamily = FontFamily(
    androidx.compose.ui.text.font.GenericFontFamily(
        android.graphics.Typeface.create("proxima-nova", android.graphics.Typeface.NORMAL)
    )
)

private val defaultTypography = Typography()

val AppTypography = Typography(
    displayLarge = defaultTypography.displayLarge.copy(fontFamily = ProximaNovaFontFamily),
    displayMedium = defaultTypography.displayMedium.copy(fontFamily = ProximaNovaFontFamily),
    displaySmall = defaultTypography.displaySmall.copy(fontFamily = ProximaNovaFontFamily),
    headlineLarge = defaultTypography.headlineLarge.copy(fontFamily = ProximaNovaFontFamily),
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = ProximaNovaFontFamily),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = ProximaNovaFontFamily),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = ProximaNovaFontFamily),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = ProximaNovaFontFamily),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = ProximaNovaFontFamily),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = ProximaNovaFontFamily),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = ProximaNovaFontFamily),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = ProximaNovaFontFamily),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = ProximaNovaFontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = ProximaNovaFontFamily),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = ProximaNovaFontFamily)
)
