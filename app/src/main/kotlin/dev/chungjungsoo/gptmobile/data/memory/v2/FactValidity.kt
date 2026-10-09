package dev.chungjungsoo.gptmobile.data.memory.v2

import kotlin.math.exp
import kotlin.math.ln

/** Half-open valid time, distinct from the time a record was saved. */
data class FactValidity(val from: Long? = null, val until: Long? = null) {
    init {
        require(from == null || until == null || from < until)
    }
    fun contains(at: Long): Boolean = (from == null || from <= at) && (until == null || at < until)
    fun overlaps(other: FactValidity): Boolean =
        (until == null || other.from == null || other.from < until) &&
            (other.until == null || from == null || from < other.until)
}

fun memoryHalfLifeWeight(now: Long, confirmedAt: Long?, halfLifeMillis: Long): Double {
    require(halfLifeMillis > 0)
    if (confirmedAt == null) return 0.0
    return exp(-ln(2.0) * (now.toDouble() - confirmedAt.toDouble()).coerceAtLeast(0.0) / halfLifeMillis)
}
