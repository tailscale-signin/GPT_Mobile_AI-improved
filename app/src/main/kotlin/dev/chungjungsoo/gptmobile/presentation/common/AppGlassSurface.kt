package dev.chungjungsoo.gptmobile.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

/** One background owner; labels and artwork never inherit the material opacity. */
data class OverlayMaterialTokens(val background: Color, val foreground: Color, val border: Color)

internal fun overlayMaterialTokens(base: Color, foreground: Color, outline: Color, glass: Boolean, opacity: Float = GLASS_OPACITY): OverlayMaterialTokens {
    val alpha = if (glass) opacity.coerceIn(0f, 1f) else 1f
    fun ratio(a: Color, b: Color): Float = (max(a.luminance(), b.luminance()) + 0.05f) / (min(a.luminance(), b.luminance()) + 0.05f)
    fun worst(text: Color, plate: Color): Float = min(ratio(text, plate.compositeOver(Color.Black)), ratio(text, plate.compositeOver(Color.White)))
    var plate = base.copy(alpha = alpha)
    var text = foreground.copy(alpha = 1f)
    if (worst(text, plate) < 4.5f) {
        text = if (worst(Color.White, plate) >= worst(Color.Black, plate)) Color.White else Color.Black
        val target = if (text == Color.White) Color.Black else Color.White
        for (step in 1..20) {
            if (worst(text, plate) >= 4.5f) break
            val fraction = step / 20f
            plate = Color(
                base.red + (target.red - base.red) * fraction,
                base.green + (target.green - base.green) * fraction,
                base.blue + (target.blue - base.blue) * fraction,
                alpha
            )
        }
    }
    return OverlayMaterialTokens(plate, text, outline.copy(alpha = 0.35f))
}

@Composable
fun appOverlayTokens(opacity: Float = GLASS_OPACITY): OverlayMaterialTokens = with(MaterialTheme.colorScheme) {
    overlayMaterialTokens(surfaceContainerHigh, onSurface, outlineVariant, LocalOverlayMaterial.current != "solid", opacity)
}

@Composable
fun AppGlassSurface(modifier: Modifier = Modifier, shape: Shape = MaterialTheme.shapes.extraLarge, content: @Composable () -> Unit) {
    val tokens = appOverlayTokens()
    Surface(
        modifier = modifier.glassBackdrop(shape, captureAsPopup = LocalOverlayWindow.current),
        shape = shape,
        color = if (hasGlassBackdrop()) Color.Transparent else tokens.background,
        contentColor = tokens.foreground,
        tonalElevation = 0.dp,
        border = BorderStroke(1.dp, tokens.border),
        content = content
    )
}
