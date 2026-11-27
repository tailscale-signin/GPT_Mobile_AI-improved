package dev.chungjungsoo.gptmobile.data.github

/** Counts the union of durable user waits, excluding overlap and time outside this allowance. */
internal object GitHubDecisionWaitClock {
    /** A user-specified delivery cutoff retains wall-clock meaning. */
    fun allowsPause(task: String): Boolean = !Regex(
        """(?i)\b(?:deadline|no later than)\b|\b(?:finish|complete|deliver)\s+(?:it\s+)?within\s+\d|\bby\s+(?:\d{1,2}(?::\d{2})?(?:\s*[ap]m)?|noon|midnight|tomorrow)\b"""
    ).containsMatchIn(task)

    fun pausedMillis(acceptedAt: Long, observedAt: Long, waits: List<Pair<Long, Long?>>): Long {
        if (acceptedAt < 0 || observedAt < acceptedAt) return 0
        val intervals = waits.mapNotNull { (offered, answered) ->
            if (offered < 0 || answered != null && answered < offered) return@mapNotNull null
            val start = maxOf(offered, acceptedAt)
            val end = minOf(answered ?: observedAt, observedAt)
            (start to end).takeIf { end > start }
        }.sortedBy { it.first }
        var lastEnd = acceptedAt
        var total = 0L
        for ((start, end) in intervals) {
            val uncovered = maxOf(start, lastEnd)
            if (end > uncovered) total += end - uncovered
            lastEnd = maxOf(lastEnd, end)
        }
        return total.coerceAtMost(observedAt - acceptedAt)
    }
}
