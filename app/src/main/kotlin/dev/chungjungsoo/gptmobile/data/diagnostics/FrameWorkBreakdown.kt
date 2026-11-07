package dev.chungjungsoo.gptmobile.data.diagnostics

/** Phase measurements help target a trace; phases may overlap and do not establish a cause. */
internal class FrameWorkBreakdown {
    enum class Phase { INPUT, ANIMATION, LAYOUT_MEASURE, DRAW, SYNC, COMMAND_ISSUE, UNKNOWN_DELAY }
    private val maxima = LongArray(Phase.entries.size)
    private val slowTotals = LongArray(Phase.entries.size)
    private val observations = LongArray(Phase.entries.size)

    fun observe(phase: Phase, durationNanos: Long, slowFrame: Boolean) {
        if (durationNanos < 0) return // Android uses a negative value when a metric is unavailable.
        val index = phase.ordinal
        maxima[index] = maxOf(maxima[index], durationNanos)
        observations[index]++
        if (slowFrame) slowTotals[index] = if (Long.MAX_VALUE - slowTotals[index] < durationNanos) Long.MAX_VALUE else slowTotals[index] + durationNanos
    }

    fun maximumNanos(): Map<String, Long?> = values(maxima)
    fun slowFrameNanos(): Map<String, Long?> = values(slowTotals)

    private fun values(values: LongArray): Map<String, Long?> = Phase.entries.associate { it.name to values[it.ordinal].takeIf { _ -> observations[it.ordinal] > 0 } }

    fun clear() {
        maxima.fill(0)
        slowTotals.fill(0)
        observations.fill(0)
    }
}
