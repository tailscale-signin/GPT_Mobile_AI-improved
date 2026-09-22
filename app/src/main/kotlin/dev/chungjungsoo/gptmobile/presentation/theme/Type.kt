package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.chungjungsoo.gptmobile.R

/**
 * Proxima Nova font family definition backed by font resources in res/font/.
 */
val ProximaNovaFontFamily = FontFamily(
    Font(R.font.proxima_nova_regular, FontWeight.Normal),
    Font(R.font.proxima_nova_medium, FontWeight.Medium),
    Font(R.font.proxima_nova_bold, FontWeight.Bold)
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
