package dev.chungjungsoo.gptmobile.data.benchmark

/** Segments share a fixed 0–100 axis. Lost points extend to the previous score. */
data class ScoreDelta(val retained: Double, val gained: Double, val lost: Double) {
    val change: Double get() = gained - lost
    companion object {
        fun between(current: Double, previous: Double?): ScoreDelta {
            val score = current.coerceIn(0.0, 100.0)
            val prior = previous?.coerceIn(0.0, 100.0) ?: score
            return ScoreDelta(minOf(score, prior), (score - prior).coerceAtLeast(0.0), (prior - score).coerceAtLeast(0.0))
        }
    }
}
