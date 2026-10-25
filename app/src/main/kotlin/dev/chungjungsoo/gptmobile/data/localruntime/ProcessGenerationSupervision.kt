package dev.chungjungsoo.gptmobile.data.localruntime

import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class LocalProcessDeadlineException(val kind: String) :
    IllegalStateException(
        "$kind: The inference worker exceeded its process deadline. Partial output and observed counters are retained."
    )

/** The owning flow is cancelled by its child on expiry, including while a collector is suspended. */
internal suspend fun <T> withProcessGenerationWatchdog(
    deadline: ProcessGenerationDeadline,
    terminalAcknowledged: () -> Boolean,
    onDeadline: (String) -> Nothing,
    nowMs: () -> Long,
    monitorContext: CoroutineContext = Dispatchers.IO,
    block: suspend () -> T
): T = coroutineScope {
    val monitor = launch(monitorContext) {
        while (!terminalAcknowledged()) {
            val now = nowMs()
            deadline.expiration(now)?.takeIf { !terminalAcknowledged() }?.let(onDeadline)
            delay(minOf(deadline.remainingMs(now).coerceAtLeast(1), 500))
        }
    }
    try {
        block()
    } finally {
        // A child deadline failure or caller cancellation must still join this child
        // before the outer flow performs the existing bounded native cleanup.
        withContext(NonCancellable) { monitor.cancelAndJoin() }
    }
}
