package dev.chungjungsoo.gptmobile.presentation.ui.chat

/**
 * Index mapped profile-colour ranges once on a worker instead of scanning every
 * range for each Markdown token on the composition/UI thread.
 *
 * Prefix maximum ends preserve correctness even if ranges overlap, such as
 * a large attributed paragraph and a smaller nested Markdown source run.
 */
internal class ProfileSpanIndex(sourceSpans: List<MappedProfileSpan>) {
    private val spans = sourceSpans.sortedBy { it.start }
    private val prefixMaxEnd = IntArray(spans.size)

    init {
        var largestEnd = Int.MIN_VALUE
        spans.forEachIndexed { index, span ->
            largestEnd = maxOf(largestEnd, span.end)
            prefixMaxEnd[index] = largestEnd
        }
    }

    fun overlapping(start: Int, end: Int): List<MappedProfileSpan> {
        if (start >= end || spans.isEmpty()) return emptyList()

        var low = 0
        var high = spans.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (prefixMaxEnd[mid] <= start) low = mid + 1 else high = mid
        }
        val first = low
        low = first
        high = spans.size
        while (low < high) {
            val mid = (low + high) ushr 1
            if (spans[mid].start < end) low = mid + 1 else high = mid
        }
        val last = low
        if (first >= last) return emptyList()
        return spans.subList(first, last).filter { it.end > start }
    }
}
