package dev.chungjungsoo.gptmobile.data.context

/** Capacity is supplied only by the admitted artifact/runtime. All arithmetic is overflow-safe. */
internal object LocalOutputAllocation {
    fun reserve(context: Int, inputUpperBound: Long, safety: Int, requested: Int?, workloadCeiling: Int = 4096): Int {
        require(context > 0 && inputUpperBound >= 0 && safety >= 0 && workloadCeiling > 0)
        require(inputUpperBound < context.toLong()) { "LOCAL_CONTEXT_EXHAUSTED: required input exceeds local context capacity; the input was retained." }
        val available = context.toLong() - inputUpperBound - safety.toLong()
        require(available > 0) { "LOCAL_CONTEXT_EXHAUSTED: no safe output headroom remains in the local context; the input was retained." }
        return minOf(requested?.takeIf { it > 0 } ?: minOf(1024, maxOf(1, context / 4)), workloadCeiling, available.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
