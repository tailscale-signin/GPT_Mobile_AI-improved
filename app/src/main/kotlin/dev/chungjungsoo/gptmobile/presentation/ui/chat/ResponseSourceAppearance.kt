package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseSource
import dev.chungjungsoo.gptmobile.data.database.entity.ResponseTransport

internal val LocalAnswerSource = staticCompositionLocalOf<ResponseSource?> { null }
internal val LocalAnswerSurface = staticCompositionLocalOf { Color.White }

internal object ResponseSourceAppearance {
    fun isGateway(source: ResponseSource?): Boolean = source?.transport == ResponseTransport.GATEWAY && source.gatewayVerified

    /** Preserve the theme's accent as far as normal-text contrast permits. */
    fun gatewayForeground(accent: Color, surface: Color): Color {
        val seed = accent.compositeOver(surface)
        if (ProfileAttributionPalette.contrast(seed, surface) >= 4.5) return seed
        val target = if (ProfileAttributionPalette.contrast(Color.Black, surface) >= ProfileAttributionPalette.contrast(Color.White, surface)) Color.Black else Color.White
        var low = 0f
        var high = 1f
        repeat(24) {
            val middle = (low + high) / 2
            if (ProfileAttributionPalette.contrast(lerp(seed, target, middle), surface) >= 4.5) high = middle else low = middle
        }
        return lerp(seed, target, high)
    }
}
