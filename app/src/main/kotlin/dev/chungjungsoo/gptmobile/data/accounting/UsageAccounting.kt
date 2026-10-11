package dev.chungjungsoo.gptmobile.data.accounting

/** Provider counters may be cumulative snapshots or deltas. Negative/overflow values are never valid usage. */
internal fun mergeUsageCount(previous: Int?, incoming: Int?, cumulative: Boolean): Int? {
    if (incoming == null || incoming < 0) return previous
    return if (cumulative) maxOf(previous ?: 0, incoming) else
        ((previous ?: 0).toLong() + incoming).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
}
