package dev.chungjungsoo.gptmobile.data.context

/** Capacity is supplied only by the admitted artifact/runtime. All arithmetic is overflow-safe. */
internal object LocalOutputAllocation {
    fun reserve(context: Int, inputUpperBound: Long, safety: Int, requested: Int?, workloadCeiling: Int = 4096, minimum: Int = 1): Int {
        require(context > 0 && inputUpperBound >= 0 && safety >= 0 && workloadCeiling > 0 && minimum > 0)
        require(inputUpperBound < context.toLong()) { "LOCAL_CONTEXT_EXHAUSTED: estimated required input=$inputUpperBound tokens exceeds context=$context; the input was retained. Reduce system instructions or select a larger context." }
        val available = context.toLong() - inputUpperBound - safety.toLong()
        require(available > 0) { "LOCAL_CONTEXT_EXHAUSTED: estimated input=$inputUpperBound, safety/tool-result reserve=$safety, context=$context; no output headroom remains. The input was retained." }
        require(available >= minOf(minimum, requested?.takeIf { it > 0 } ?: minimum)) { "LOCAL_CONTEXT_EXHAUSTED: the fully rendered request leaves only $available reply tokens; at least $minimum are needed for this step. Select fewer tools, shorten context or choose a compatible larger-context model. The input was retained." }
        return minOf(requested?.takeIf { it > 0 } ?: minOf(1024, maxOf(1, context / 4)), workloadCeiling, available.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }
}
