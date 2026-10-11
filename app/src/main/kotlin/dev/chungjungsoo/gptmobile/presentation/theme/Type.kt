package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
    headlineMedium = defaultTypography.headlineMedium.copy(fontFamily = ProximaNovaFontFamily, fontSize = 28.sp, lineHeight = 34.sp),
    headlineSmall = defaultTypography.headlineSmall.copy(fontFamily = ProximaNovaFontFamily, fontSize = 24.sp, lineHeight = 30.sp),
    titleLarge = defaultTypography.titleLarge.copy(fontFamily = ProximaNovaFontFamily, fontSize = 20.sp, lineHeight = 26.sp),
    titleMedium = defaultTypography.titleMedium.copy(fontFamily = ProximaNovaFontFamily, fontSize = 16.sp, lineHeight = 22.sp),
    titleSmall = defaultTypography.titleSmall.copy(fontFamily = ProximaNovaFontFamily),
    bodyLarge = defaultTypography.bodyLarge.copy(fontFamily = ProximaNovaFontFamily, fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = defaultTypography.bodyMedium.copy(fontFamily = ProximaNovaFontFamily, fontSize = 14.sp, lineHeight = 20.sp),
    bodySmall = defaultTypography.bodySmall.copy(fontFamily = ProximaNovaFontFamily, fontSize = 14.sp, lineHeight = 20.sp),
    labelLarge = defaultTypography.labelLarge.copy(fontFamily = ProximaNovaFontFamily),
    labelMedium = defaultTypography.labelMedium.copy(fontFamily = ProximaNovaFontFamily),
    labelSmall = defaultTypography.labelSmall.copy(fontFamily = ProximaNovaFontFamily, fontSize = 12.sp, lineHeight = 16.sp)
)
