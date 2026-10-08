package dev.chungjungsoo.gptmobile.presentation.ui.amazon

internal fun amazonProductSwipeTarget(index: Int, count: Int, distance: Float, threshold: Float): Int = when {
    count <= 0 -> 0
    distance <= -threshold -> (index + 1).coerceAtMost(count - 1)
    distance >= threshold -> (index - 1).coerceAtLeast(0)
    else -> index.coerceIn(0, count - 1)
}

/** Recolors only chart pixels, keeping plot geometry, text, grids and distinct series intact. */
internal data class AmazonGraphPalette(
    val background: Int,
    val foreground: Int,
    val primary: Int,
    val secondary: Int,
    val tertiary: Int,
    val error: Int
) {
    fun color(pixel: Int): Int {
        val red = (pixel ushr 16) and 255
        val green = (pixel ushr 8) and 255
        val blue = pixel and 255
        val high = maxOf(red, green, blue)
        val low = minOf(red, green, blue)
        val target = when {
            high - low < 24 -> foreground
            red > green * 1.3 && red > blue * 1.3 -> error
            blue > red && blue >= green -> secondary
            green > red && green > blue -> primary
            else -> tertiary
        }
        val coverage = if (high - low < 24) 1f - (red + green + blue) / (3f * 255f) else 1f - low / 255f
        fun component(shift: Int): Int {
            val back = (background ushr shift) and 255
            val front = (target ushr shift) and 255
            return (back + (front - back) * coverage).toInt().coerceIn(0, 255)
        }
        return (pixel and -0x1000000) or (component(16) shl 16) or (component(8) shl 8) or component(0)
    }
}
