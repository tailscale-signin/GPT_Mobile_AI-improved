package dev.chungjungsoo.gptmobile.presentation.ui.voice

/** Source offsets identify a line independently of its current visual position or scale. */
internal data class VoiceCaptionLine(val start: Int, val end: Int)

internal fun List<VoiceCaptionLine>.lineForOffset(offset: Int): Int {
    val index = binarySearchBy(offset) { it.start }
    return (if (index >= 0) index else -index - 2).coerceIn(0, lastIndex.coerceAtLeast(0))
}
