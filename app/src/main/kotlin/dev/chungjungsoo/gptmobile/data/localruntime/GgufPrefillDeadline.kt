package dev.chungjungsoo.gptmobile.data.localruntime

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
): T? {
    val outcome = withGgufDeadlineReceipt(budgetMs, cancelNative) { accept -> block { accept() } }
    return if (outcome.timedOut) null else outcome.receipt
}

/** Keeps partial receipts after deadline expiry; cancellation is always propagated. */
internal data class GgufDeadlineOutcome<T>(
    val receipt: T?,
    val timedOut: Boolean,
    val failure: Throwable? = null,
    val terminalCode: String? = null
)

internal suspend fun <T : Any> withGgufDeadlineReceipt(
    budgetMs: Long,
    cancelNative: () -> Unit,
    expirationCause: (() -> String?)? = null,
    block: suspend (acceptFirstOutput: () -> Boolean) -> T
): GgufDeadlineOutcome<T> =
    coroutineScope {
        // 0 waiting, 1 first visible output accepted, 2 expired. Exactly one wins.
        val state =
            java.util.concurrent.atomic
                .AtomicInteger(0)
        val firstOutput = CompletableDeferred<Unit>()
        val terminalCode =
            java.util.concurrent.atomic
                .AtomicReference<String?>(null)
        val watchdog =
            launch(kotlinx.coroutines.Dispatchers.Default) {
                if (expirationCause == null) {
                    if (withTimeoutOrNull(budgetMs) { firstOutput.await() } == null && state.compareAndSet(0, 2)) {
                        terminalCode.set("GGUF_PREFILL_TIMEOUT")
                        cancelNative()
                    }
                } else {
                    while (true) {
                        kotlinx.coroutines.delay(minOf(100L, budgetMs.coerceAtLeast(1)))
                        val cause = expirationCause()
                        if (cause != null && state.getAndSet(2) != 2) {
                            terminalCode.set(cause)
                            cancelNative()
                            break
                        }
                    }
                }
            }
        try {
            val receipt =
                block {
                    val accepted = state.compareAndSet(0, 1) || state.get() == 1
                    if (accepted) firstOutput.complete(Unit)
                    accepted
                }
            GgufDeadlineOutcome(receipt, state.get() == 2, terminalCode = terminalCode.get())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (state.get() != 2) throw failure
            GgufDeadlineOutcome(null, true, failure, terminalCode.get())
        } finally {
            withContext(NonCancellable) { watchdog.cancelAndJoin() }
        }
    }
