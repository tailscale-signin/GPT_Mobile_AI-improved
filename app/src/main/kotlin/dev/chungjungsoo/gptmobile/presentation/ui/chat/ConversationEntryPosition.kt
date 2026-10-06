package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Midpoint of the readable region in the lazy list's content coordinates. */
internal fun conversationEntryCenter(viewportStart: Int, viewportEnd: Int, topInset: Int, bottomInset: Int): Int {
    val visibleStart = viewportStart + topInset
    val visibleEnd = (viewportEnd - bottomInset).coerceAtLeast(visibleStart)
    return visibleStart + (visibleEnd - visibleStart) / 2
}
