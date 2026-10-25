package dev.chungjungsoo.gptmobile.data.localruntime

/** Monotonic policy: an absolute limit, useful-work stall, and slow sustained work are distinct. */
internal class GgufGenerationDeadline(
    private val startedAtMs: Long,
    private val prefillStallMs: Long,
    private val absoluteMs: Long = 600_000,
    private val decodeStallMs: Long = 45_000,
    private val minimumPrefillTokensPerSecond: Double = 0.25,
    private val throughputWindowMs: Long = 120_000
) {
    val policy = NativeGenerationDeadlinePolicy(
        absoluteMs = absoluteMs,
        prefillStallMs = prefillStallMs,
        decodeStallMs = decodeStallMs,
        minimumPrefillTokensPerSecond = minimumPrefillTokensPerSecond,
        throughputWindowMs = throughputWindowMs
    )

    private var lastUsefulAtMs = startedAtMs
    private var prefillStartedAtMs: Long? = null
    private var reusedTokens = 0L
    private var processedTokens = -1L
    private var sampledTokens = -1L
    private var prefillFinished = false

    @Synchronized fun observe(
        progress: GgufNativeProgress,
        nowMs: Long
    ) {
        if (progress.stage == "PREFILL_BEGIN") {
            prefillStartedAtMs = nowMs
            reusedTokens = progress.processedTokens.coerceAtLeast(0)
        }
        if (progress.processedTokens > processedTokens ||
            progress.sampledTokens > sampledTokens ||
            progress.stage in setOf("RENDER_END", "TOKENIZE_END")
        ) {
            lastUsefulAtMs = nowMs
        }
        if (progress.processedTokens >= 0) processedTokens = maxOf(processedTokens, progress.processedTokens)
        if (progress.sampledTokens >= 0) sampledTokens = maxOf(sampledTokens, progress.sampledTokens)
        if (progress.stage in setOf("PREFILL_END", "FIRST_SAMPLE", "FIRST_BYTES", "DECODING", "NATIVE_RETURN")) prefillFinished = true
    }

    @Synchronized fun expiration(nowMs: Long): String? {
        if (nowMs - startedAtMs >= absoluteMs) return "GGUF_OPERATION_DEADLINE"
        val decoding = sampledTokens > 0 || prefillFinished
        if (nowMs - lastUsefulAtMs >=
            if (decoding) decodeStallMs else prefillStallMs
        ) {
            return if (decoding) "GGUF_DECODE_STALL" else "GGUF_NATIVE_STALL"
        }
        val prefillDuration = prefillStartedAtMs?.let { nowMs - it } ?: return null
        val newTokens = (processedTokens - reusedTokens).coerceAtLeast(0)
        if (!prefillFinished &&
            prefillDuration >= throughputWindowMs &&
            newTokens > 0 &&
            newTokens * 1000.0 / prefillDuration < minimumPrefillTokensPerSecond
        ) {
            return "GGUF_PREFILL_THROUGHPUT_DEADLINE"
        }
        return null
    }
}
