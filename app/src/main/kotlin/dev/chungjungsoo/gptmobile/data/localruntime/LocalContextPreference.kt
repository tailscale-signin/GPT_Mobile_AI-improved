package dev.chungjungsoo.gptmobile.data.localruntime

import kotlinx.serialization.Serializable

@Serializable
data class LocalContextPreference(val policy: String = "AUTO", val tokens: Int? = null) {
    fun validated() = apply {
        require(policy in setOf("AUTO", "MANUAL"))
        require(policy != "MANUAL" || tokens != null && tokens in 512..32768)
    }
}

/** Planning uses the same conservative estimate as the fresh pre-allocation admission. */
internal fun memoryFeasibleContext(maximum: Int, modelBytes: Long, availableBytes: Long?, reserveBytes: Long, manual: Int? = null, kvBytesPerToken: Long = 256L * 1024, batch: Int = 256, microBatch: Int = 128, threads: Int = 1, accelerator: String = "cpu"): Int {
    require(maximum > 0 && modelBytes >= 0 && reserveBytes >= 0)
    val candidates = if (manual != null) listOf(manual) else (listOf(maximum) + listOf(32768, 16384, 8192, 4096, 2048, 1024, 512).filter { it <= maximum }).distinct()
    return candidates.firstOrNull { tokens ->
        tokens in 1..maximum && !LocalMemoryBudget(ggufMemoryComponents(modelBytes, tokens, kvBytesPerToken, batch, microBatch, threads, accelerator).totalMemoryBytes(), reserveBytes, availableBytes).hasShortfall
    } ?: throw LocalMemoryEstimate(ggufMemoryComponents(modelBytes, manual ?: candidates.last(), kvBytesPerToken, batch, microBatch, threads, accelerator).totalMemoryBytes(), reserveBytes, availableBytes, manual ?: candidates.last()).rejection("No complete context candidate fits the current memory snapshot. Use Auto context or free device memory.")
}
