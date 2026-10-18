package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.research.researchHash

/**
 * Relocates soft-wrapped prose without a regex node for every word. The normalized search
 * retains a raw UTF-16 boundary map, so even very long claims bind to the complete original
 * text and its digest. Only the whitespace accepted by the previous `\s+` matcher collapses.
 */
internal class CombinedTextSpanIndex(private val source: String) {
    private val rawOffsets = IntArray(source.length + 1)
    private val normalized = buildString(source.length) {
        var offset = 0
        while (offset < source.length) {
            rawOffsets[length] = offset
            val character = source[offset++]
            if (matchingWhitespace(character)) {
                append(' ')
                while (offset < source.length && matchingWhitespace(source[offset])) offset++
            } else {
                append(character)
            }
        }
        rawOffsets[length] = source.length
    }

    fun find(text: String, startUtf16: Int = 0): CombinedCoverageLink? {
        require(startUtf16 in 0..source.length)
        val needle = buildString(text.length) {
            var whitespace = false
            text.forEach { character ->
                if (matchingWhitespace(character)) {
                    if (!whitespace) append(' ')
                    whitespace = true
                } else {
                    append(character)
                    whitespace = false
                }
            }
        }
        if (needle.isEmpty()) return null
        val boundary = rawOffsets.binarySearch(startUtf16, toIndex = normalized.length + 1)
        // A cursor within collapsed whitespace may still match the rest of that whitespace.
        val from = if (boundary >= 0) boundary else (-boundary - 2).coerceAtLeast(0)
        val found = normalized.indexOf(needle, from)
        if (found < 0) return null
        val start = rawOffsets[found].coerceAtLeast(startUtf16)
        val end = rawOffsets[found + needle.length]
        return CombinedCoverageLink(start, end, researchHash(source.substring(start, end)))
    }

    private fun matchingWhitespace(character: Char): Boolean = character == ' ' || character in '\t'..'\r'
}
