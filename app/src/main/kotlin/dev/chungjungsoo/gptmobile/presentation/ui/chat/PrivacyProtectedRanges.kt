package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal data class PrivacyVerbatimRun(val sourceStart: Int, val sourceEnd: Int, val displayStart: Int, val displayEnd: Int)

private val privacyThinkingBlock = Regex("<think>(.*?)(</think>|$)", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

/** Record the actual retained slices; matching the final string cannot prove where its characters came from. */
internal fun privacyProtectedValuesAfterThinking(source: String, response: String, protectedValues: List<String>): List<String> {
    if (protectedValues.isEmpty() || response.isEmpty()) return emptyList()
    val runs = mutableListOf<PrivacyVerbatimRun>()
    val projected = StringBuilder()
    fun appendSource(start: Int, end: Int) {
        if (start >= end) return
        val outputStart = projected.length
        projected.append(source, start, end)
        runs += PrivacyVerbatimRun(start, end, outputStart, projected.length)
    }
    val removesThinking = source.contains("<think>", ignoreCase = true)
    var cursor = 0
    if (removesThinking) {
        privacyThinkingBlock.findAll(source).forEach { hidden ->
            appendSource(cursor, hidden.range.first)
            cursor = hidden.range.last + 1
        }
    }
    appendSource(cursor, source.length)
    val start = if (removesThinking) projected.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: projected.length else 0
    val end = if (removesThinking) (projected.indexOfLast { !it.isWhitespace() } + 1).coerceAtLeast(start) else projected.length
    // If the real parser changes its transformation, withhold evidence until its source mapping is updated.
    if (projected.substring(start, end) != response) return emptyList()
    val retained = runs.mapNotNull { run ->
        val from = maxOf(run.displayStart, start)
        val to = minOf(run.displayEnd, end)
        if (from >= to) null else PrivacyVerbatimRun(run.sourceStart + from - run.displayStart, run.sourceStart + to - run.displayStart, from - start, to - start)
    }
    val protectedRanges = privacyProtectedRangesFromSource(response, protectedValues, source, retained)
    return protectedValues.filter(String::isNotBlank).distinct().filter { value ->
        var occurrence = response.indexOf(value)
        var proven = occurrence >= 0
        while (occurrence >= 0 && proven) {
            proven = protectedRanges.any { it.first <= occurrence && it.last >= occurrence + value.length - 1 }
            occurrence = response.indexOf(value, occurrence + 1)
        }
        proven
    }
}

/** Transformed text may use proof only within an unchanged, visibly appended source run. */
internal fun privacyProtectedRangesFromSource(text: String, protectedValues: List<String>, source: String, runs: List<PrivacyVerbatimRun>): List<IntRange> =
    runs.flatMap { run ->
        if (run.sourceStart < 0 ||
            run.sourceEnd < run.sourceStart ||
            run.sourceEnd > source.length ||
            run.displayStart < 0 ||
            run.displayEnd < run.displayStart ||
            run.displayEnd > text.length ||
            run.sourceEnd - run.sourceStart != run.displayEnd - run.displayStart ||
            !source.regionMatches(run.sourceStart, text, run.displayStart, run.displayEnd - run.displayStart)
        ) {
            emptyList()
        } else {
            privacyProtectedRanges(text.substring(run.displayStart, run.displayEnd), protectedValues).map { range ->
                range.first + run.displayStart..range.last + run.displayStart
            }
        }
    }.distinct().sortedBy { it.first }

/** Exact values come from validated local masking receipts, never from UI-side detection. */
internal fun privacyProtectedRanges(text: String, protectedValues: List<String>): List<IntRange> {
    if (text.isEmpty() || protectedValues.isEmpty()) return emptyList()
    val ranges = mutableListOf<IntRange>()
    protectedValues.asSequence().filter(String::isNotBlank).distinct().forEach { value ->
        var start = text.indexOf(value)
        while (start >= 0) {
            ranges += start until start + value.length
            start = text.indexOf(value, start + 1)
        }
    }
    if (ranges.size < 2) return ranges
    val sorted = ranges.sortedWith(compareBy<IntRange> { it.first }.thenBy { it.last })
    return buildList {
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.first <= current.last + 1) {
                current = current.first..maxOf(current.last, next.last)
            } else {
                add(current)
                current = next
            }
        }
        add(current)
    }
}
