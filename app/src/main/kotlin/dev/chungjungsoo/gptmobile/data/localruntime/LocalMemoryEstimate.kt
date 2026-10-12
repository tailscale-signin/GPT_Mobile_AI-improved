package dev.chungjungsoo.gptmobile.data.localruntime

/** Conservative admission estimate, not a measured peak or a model qualification. */
internal data class LocalMemoryEstimate(
    val lowerBytes: Long,
    val upperBytes: Long,
    val weightsBytes: Long,
    val cacheBytes: Long,
    val workspaceBytes: Long,
    val speculativeBytes: Long,
    val imageBytes: Long,
    val confidence: String = "conservative_unmeasured"
) {
    companion object {
        fun estimate(artifactBytes: Long, contextTokens: Int, warm: Boolean, speculative: Boolean, vision: Boolean): LocalMemoryEstimate {
            require(artifactBytes >= 0 && contextTokens > 0)
            if (warm) return LocalMemoryEstimate(64L * MIB, 128L * MIB, 0, 0, 128L * MIB, 0, 0, "warm_incremental_unmeasured")
            val cache = contextTokens.toLong() * 256 * 1024
            val workspace = 128L * MIB
            val drafter = if (speculative) artifactBytes / 3 else 0
            val images = if (vision) 256L * MIB else 0
            val upper = listOf(artifactBytes, cache, workspace, drafter, images).fold(0L) { sum, value ->
                if (Long.MAX_VALUE - sum < value) Long.MAX_VALUE else sum + value
            }
            val lower = minOf(upper, maxOf(workspace, artifactBytes / 2))
            return LocalMemoryEstimate(lower, upper, artifactBytes, cache, workspace, drafter, images)
        }
        private const val MIB = 1024L * 1024
    }
}
