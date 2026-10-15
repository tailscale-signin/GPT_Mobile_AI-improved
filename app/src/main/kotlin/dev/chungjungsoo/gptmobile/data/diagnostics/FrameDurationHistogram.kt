package dev.chungjungsoo.gptmobile.data.diagnostics

/** Sampled window quantiles, 5 ms upper bounds; the final bucket explicitly reports overflow. */
internal class FrameDurationHistogram {
    private val counts = IntArray(201)
    private var total = 0
    var stallsOver100Ms = 0
        private set
    val overflow: Boolean get() = counts.last() > 0

    fun add(nanos: Long) {
        val value = nanos.coerceAtLeast(0)
        counts[(value / 5_000_000L).coerceAtMost(200).toInt()]++
        total++
        if (value > 100_000_000L) stallsOver100Ms++
    }

    fun percentileUpperMs(percent: Int): Int {
        require(percent in 1..100)
        if (total == 0) return 0
        val target = (total.toLong() * percent + 99) / 100
        var seen = 0L
        for (index in counts.indices) {
            seen += counts[index]
            if (seen >= target) return (index + 1) * 5
        }
        return 1005
    }

    fun clear() {
        counts.fill(0)
        total = 0
        stallsOver100Ms = 0
    }
}
