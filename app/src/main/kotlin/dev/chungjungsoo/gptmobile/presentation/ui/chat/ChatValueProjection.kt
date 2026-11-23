package dev.chungjungsoo.gptmobile.presentation.ui.chat

/** Grouping may rebuild lists while all of one saved turn's messages remain unchanged. */
internal class ChatValueProjection<T> {
    private class Entry<T>(val value: T)
    private var previous: Entry<T>? = null

    fun retain(value: T): T {
        val current = previous
        if (current != null && current.value == value) return current.value
        previous = Entry(value)
        return value
    }
}
