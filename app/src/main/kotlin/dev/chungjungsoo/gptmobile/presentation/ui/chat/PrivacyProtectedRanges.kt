package dev.chungjungsoo.gptmobile.presentation.ui.chat

internal data class PrivacyVerbatimRun(val sourceStart: Int, val sourceEnd: Int, val displayStart: Int, val displayEnd: Int)

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
