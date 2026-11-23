package dev.chungjungsoo.gptmobile.data.github

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

/** One monotonic budget covers preparation, cache work, downloads and parsing of a read-only page. */
internal class GitHubReadWorkBudget(timeoutMs: Long, private val nanoTime: () -> Long = System::nanoTime) {
    private val startedNanos = nanoTime()
    private val durationMs = timeoutMs.coerceAtLeast(1)

    val remainingMs: Long get() = (durationMs - (nanoTime() - startedNanos) / 1_000_000).coerceAtLeast(0)
    val expired: Boolean get() = remainingMs == 0L

    suspend fun <T : Any> run(maximumMs: Long = durationMs, block: suspend () -> T): T? = withContext(Dispatchers.Default) {
        currentCoroutineContext().ensureActive()
        val remaining = minOf(remainingMs, maximumMs)
        if (remaining <= 0) return@withContext null
        withTimeoutOrNull(remaining) { block() }
    }

    suspend fun yieldIfActive() {
        currentCoroutineContext().ensureActive()
        yield()
    }
}
