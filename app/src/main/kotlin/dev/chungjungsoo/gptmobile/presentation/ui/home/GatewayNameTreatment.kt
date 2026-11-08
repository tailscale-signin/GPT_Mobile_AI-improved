package dev.chungjungsoo.gptmobile.presentation.ui.home

import kotlin.math.pow

internal const val GATEWAY_NAME_FADE_HALF_CYCLE_MS = 3_000
internal const val GATEWAY_NAME_MINIMUM_OPACITY = 0.86f

internal data class GatewayNameTreatment(val backgroundArgb: Int, val minimumOpacity: Float)

/** Keep the exact theme primary color; constrain its fade before losing text contrast. */
internal fun gatewayNameTreatment(primaryArgb: Int, backgroundArgb: Int): GatewayNameTreatment {
    val background = if (gatewayColorContrast(primaryArgb, backgroundArgb) >= 4.5) {
        backgroundArgb
    } else {
        // A custom palette can have insufficient contrast even at full opacity.
        // A backing behind the name preserves the requested primary color.
        listOf(0xFF000000.toInt(), 0xFFFFFFFF.toInt()).maxBy { gatewayColorContrast(primaryArgb, it) }
    }
    val minimum = (86..100).firstOrNull { gatewayColorContrast(primaryArgb, background, it / 100f) >= 4.5 } ?: 100
    return GatewayNameTreatment(background, minimum / 100f)
}

internal fun gatewayColorContrast(foregroundArgb: Int, backgroundArgb: Int, opacity: Float = 1f): Double {
    val alpha = ((foregroundArgb ushr 24 and 0xFF) / 255.0) * opacity.coerceIn(0f, 1f)
    fun channel(argb: Int, shift: Int) = (argb ushr shift and 0xFF) / 255.0
    fun linear(value: Double) = if (value <= 0.04045) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    fun luminance(foreground: Boolean): Double = listOf(16 to 0.2126, 8 to 0.7152, 0 to 0.0722).sumOf { (shift, weight) ->
        val background = channel(backgroundArgb, shift)
        val value = if (foreground) channel(foregroundArgb, shift) * alpha + background * (1 - alpha) else background
        weight * linear(value)
    }
    val foreground = luminance(true)
    val background = luminance(false)
    return (maxOf(foreground, background) + 0.05) / (minOf(foreground, background) + 0.05)
}
