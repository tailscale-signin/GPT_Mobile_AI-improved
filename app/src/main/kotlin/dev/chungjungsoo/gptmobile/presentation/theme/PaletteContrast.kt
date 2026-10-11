package dev.chungjungsoo.gptmobile.presentation.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import dev.chungjungsoo.gptmobile.data.dto.CustomThemePalette
import kotlin.math.max
import kotlin.math.min

internal data class ContrastCheck(val role: String, val ratio: Double, val minimum: Double = 4.5) {
    val passes: Boolean get() = ratio >= minimum
}

/** Alpha is blended before measuring WCAG relative luminance. */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    val opaqueBackground = background.compositeOver(Color.White)
    val text = foreground.compositeOver(opaqueBackground).luminance().toDouble()
    val surface = opaqueBackground.luminance().toDouble()
    return (max(text, surface) + 0.05) / (min(text, surface) + 0.05)
}

internal fun paletteContrastChecks(scheme: ColorScheme): List<ContrastCheck> = listOf(
    ContrastCheck("Body / surface", contrastRatio(scheme.onSurface, scheme.surface)),
    ContrastCheck("Secondary text / surface", contrastRatio(scheme.onSurfaceVariant, scheme.surface)),
    ContrastCheck("Secondary text / raised surface", contrastRatio(scheme.onSurfaceVariant, scheme.surfaceContainerHigh)),
    ContrastCheck("Body / background", contrastRatio(scheme.onBackground, scheme.background)),
    ContrastCheck("Button label / accent", contrastRatio(scheme.onPrimary, scheme.primary)),
    ContrastCheck("Links / surface", contrastRatio(scheme.primary, scheme.surface)),
    ContrastCheck("Links / background", contrastRatio(scheme.primary, scheme.background)),
    ContrastCheck("Secondary links / surface", contrastRatio(scheme.secondary, scheme.surface)),
    ContrastCheck("Card label / container", contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer)),
    ContrastCheck("Control outline / surface", contrastRatio(scheme.outline, scheme.surface), 3.0)
)

/** Proposes draft colours; never mutates the saved theme. */
internal fun suggestPaletteAccent(palette: CustomThemePalette): CustomThemePalette {
    fun improve(argb: Long): Long {
        val background = Color(palette.background)
        val surface = Color(palette.surface)
        val original = Color(argb)
        if (contrastRatio(original, background) >= 4.5 && contrastRatio(original, surface) >= 4.5) return argb
        val candidates = listOf(Color.Black, Color.White).map { target ->
            (1..100).map { step -> androidx.compose.ui.graphics.lerp(original, target, step / 100f) }
                .firstOrNull { contrastRatio(it, background) >= 4.5 && contrastRatio(it, surface) >= 4.5 }
        }
        return candidates.filterNotNull().minByOrNull { candidate ->
            kotlin.math.abs(candidate.luminance() - original.luminance())
        }?.toArgb()?.toLong()?.and(0xFFFFFFFF) ?: argb
    }
    return palette.copy(primary = improve(palette.primary), secondary = improve(palette.secondary))
}
