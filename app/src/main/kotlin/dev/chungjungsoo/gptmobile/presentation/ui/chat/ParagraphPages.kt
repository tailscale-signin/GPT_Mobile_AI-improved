package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.ui.text.AnnotatedString

internal const val PARAGRAPH_PAGE_CHARACTERS = 3072

/** Bound each layout input without dropping text, spans, links or UTF-16 pairs. */
internal fun paragraphPages(text: AnnotatedString, limit: Int = PARAGRAPH_PAGE_CHARACTERS): List<AnnotatedString> {
    require(limit >= 2)
    if (text.length <= limit) return listOf(text)
    val pages = mutableListOf<AnnotatedString>()
    var start = 0
    while (start < text.length) {
        var end = minOf(text.length, start + limit)
        if (end < text.length) {
            // Prefer a word boundary in the latter half; a single long word still
            // receives a bounded layout. Keep whitespace in the original page.
            val boundary = (end - 1 downTo start + limit / 2).firstOrNull { text[it].isWhitespace() }
            if (boundary != null) end = boundary + 1
            if (text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()) end--
        }
        pages += text.subSequence(start, end)
        start = end
    }
    return pages
}
