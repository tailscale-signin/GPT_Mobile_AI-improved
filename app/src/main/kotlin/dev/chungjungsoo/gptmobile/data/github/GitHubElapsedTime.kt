package dev.chungjungsoo.gptmobile.data.github

/** Wall-clock run timestamps survive recreation, tool rounds, pauses and process recovery. */
internal fun githubElapsedTime(startMillis: Long, endMillis: Long): String {
    val seconds = ((endMillis.coerceAtLeast(0) - startMillis.coerceAtLeast(0)).coerceAtLeast(0) / 1000)
    val hours = seconds / 3600
    val minutes = (seconds / 60) % 60
    return "${hours.toString().padStart(2, '0')}:${minutes.toString().padStart(2, '0')}:${(seconds % 60).toString().padStart(2, '0')}"
}

/** Union timed operations within the observed window. Parallel reads and nested model/tool calls count once. */
internal fun observedGitHubWorkMillis(startMs: Long, endMs: Long, intervals: List<Pair<Long, Long>>): Long? {
    if (endMs < startMs) return null
    val spans = intervals.mapNotNull { (first, last) ->
        (maxOf(startMs, first) to minOf(endMs, last)).takeIf { (left, right) -> right > left }
    }.sortedBy { it.first }
    if (spans.isEmpty()) return null
    var through = startMs
    var total = 0L
    for ((left, right) in spans) {
        total += (right - maxOf(through, left)).coerceAtLeast(0)
        through = maxOf(through, right)
    }
    return total
}
