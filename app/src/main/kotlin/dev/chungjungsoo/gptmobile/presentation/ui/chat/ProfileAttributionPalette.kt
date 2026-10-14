package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import dev.chungjungsoo.gptmobile.data.model.ProfileColorIdentity

/** Resolve against the actual opaque surface, including custom themes, with WCAG normal-text contrast. */
object ProfileAttributionPalette {
    fun foreground(identity: ProfileColorIdentity, surface: Color): Color {
        val hue = ((identity.hue % 360 + 360) % 360).toFloat()
        val seed = Color.hsv(hue, 0.72f, 0.78f)
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
