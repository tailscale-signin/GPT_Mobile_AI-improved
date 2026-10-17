package dev.chungjungsoo.gptmobile.data.localruntime

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Cancel stalled native prefill, but never apply its deadline to productive decoding. */
internal suspend fun <T : Any> withGgufPrefillDeadline(
    budgetMs: Long,
    cancelNative: () -> Unit,
    block: suspend (onFirstOutput: () -> Unit) -> T
): T? = coroutineScope {
    val firstOutput = CompletableDeferred<Unit>()
    val awaitingOutput = AtomicBoolean(true)
    val timedOut = AtomicBoolean(false)
    val watchdog = launch {
        if (withTimeoutOrNull(budgetMs) { firstOutput.await() } == null && awaitingOutput.compareAndSet(true, false)) {
            timedOut.set(true)
            cancelNative()
        }
    }
    try {
        val result = block {
            awaitingOutput.set(false)
            firstOutput.complete(Unit)
        }
        if (timedOut.get()) null else result
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        if (timedOut.get()) null else throw failure
    } finally {
        withContext(NonCancellable) { watchdog.cancelAndJoin() }
    }
}
