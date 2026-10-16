package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.chungjungsoo.gptmobile.data.model.ProfileColorIdentity

/** Resolve against the actual opaque surface, including custom themes, with WCAG normal-text contrast. */
object ProfileAttributionPalette {
    // Assign widely separated hues to the first eight contributors, then fill
    // the gaps for larger conversations. The persisted marker, rather than
    // the hash of a name, keeps every colour stable across profile reordering.
    private val separatedHues = floatArrayOf(
        0f, 180f, 90f, 270f, 45f, 225f, 135f, 315f,
        22.5f, 202.5f, 112.5f, 292.5f, 67.5f, 247.5f, 157.5f, 337.5f
    )

    fun foreground(identity: ProfileColorIdentity, surface: Color): Color {
        val hue = separatedHues.getOrNull(identity.marker - 1)
            ?: ((identity.hue % 360 + 360) % 360).toFloat()
        val seed = Color.hsv(hue, 0.92f, 0.95f)
        if (contrast(seed, surface) >= 4.5) return seed
        val endpoint = if (contrast(Color.Black, surface) >= contrast(Color.White, surface)) Color.Black else Color.White
        var lower = 0f
        var upper = 1f
        repeat(24) {
            val middle = (lower + upper) / 2
            if (contrast(lerp(seed, endpoint, middle), surface) >= 4.5) upper = middle else lower = middle
        }
        return lerp(seed, endpoint, upper)
    }

    fun contrast(foreground: Color, surface: Color): Double {
        val a = foreground.luminance().toDouble()
        val b = surface.luminance().toDouble()
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }
}
