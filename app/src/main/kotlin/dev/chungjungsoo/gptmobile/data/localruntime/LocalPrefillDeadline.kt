package dev.chungjungsoo.gptmobile.data.localruntime

/**
 * Time to the first native output token is separately bounded from productive decoding.
 * Longer prompts receive more prefill time; an empty or short "hi" must not hang forever.
 * This deadline never retries a native request, because side effects are not replay-safe.
 */
internal object LocalPrefillDeadline {
    private const val SHORT_PROMPT_MS = 75_000L
    private const val MAX_PREFILL_MS = 300_000L

    fun budgetMs(promptCharacters: Int): Long {
        val extra = promptCharacters.coerceAtLeast(0).toLong() / 512L * 8_000L
        return (SHORT_PROMPT_MS + extra).coerceAtMost(MAX_PREFILL_MS)
    }

    fun remainingMs(startElapsedMs: Long, nowElapsedMs: Long, promptCharacters: Int): Long =
        (budgetMs(promptCharacters) - (nowElapsedMs - startElapsedMs).coerceAtLeast(0L)).coerceAtLeast(0L)
}
