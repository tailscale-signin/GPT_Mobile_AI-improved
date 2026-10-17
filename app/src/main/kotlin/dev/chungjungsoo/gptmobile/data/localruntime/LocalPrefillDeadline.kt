package dev.chungjungsoo.gptmobile.data.localruntime

import dev.chungjungsoo.gptmobile.data.model.LocalRuntimeBackend

/**
 * Time to the first native output token is separately bounded from productive decoding.
 * Longer prompts receive more prefill time; an empty or short "hi" must not hang forever.
 * This deadline never retries a native request, because side effects are not replay-safe.
 */
internal object LocalPrefillDeadline {
    private const val SHORT_PROMPT_MS = 75_000L
    private const val MAX_PREFILL_MS = 300_000L
    private const val GGUF_WATCHDOG_COMPLETION_GRACE_MS = 30_000L

    fun budgetMs(promptCharacters: Int): Long {
        val extra = promptCharacters.coerceAtLeast(0).toLong() / 512L * 8_000L
        return (SHORT_PROMPT_MS + extra).coerceAtMost(MAX_PREFILL_MS)
    }

    fun remainingMs(startElapsedMs: Long, nowElapsedMs: Long, promptCharacters: Int): Long =
        (budgetMs(promptCharacters) - (nowElapsedMs - startElapsedMs).coerceAtLeast(0L)).coerceAtLeast(0L)

    /**
     * The process client must not preempt GGUF's own prompt-aware watchdog, which
     * accounts for the complete conversation history. Keep a final bound in case
     * native cancellation or terminal delivery stalls after that watchdog fires.
     */
    fun processWatchdogBudgetMs(promptCharacters: Int, backend: LocalRuntimeBackend?): Long =
        if (backend == LocalRuntimeBackend.LLAMA_CPP || backend == LocalRuntimeBackend.GENIEX_LLAMA_CPP) {
            MAX_PREFILL_MS + GGUF_WATCHDOG_COMPLETION_GRACE_MS
        } else {
            budgetMs(promptCharacters)
        }

    fun processWatchdogRemainingMs(
        startElapsedMs: Long,
        nowElapsedMs: Long,
        promptCharacters: Int,
        backend: LocalRuntimeBackend?
    ): Long = (processWatchdogBudgetMs(promptCharacters, backend) - (nowElapsedMs - startElapsedMs).coerceAtLeast(0L)).coerceAtLeast(0L)
}
