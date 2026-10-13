package dev.chungjungsoo.gptmobile.data.assist

import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptBoundary
import dev.chungjungsoo.gptmobile.data.context.PhysicalAttemptRejected
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Queue/preparation and actual generation use different clocks. Neither can extend the parent Job. */
internal suspend fun <T : Any> withAssistAttemptDeadline(
    generationMillis: Long,
    preparationMillis: Long = 180_000L,
    authorize: suspend (String) -> Unit,
    block: suspend () -> T
): T? = coroutineScope {
    require(generationMillis > 0 && preparationMillis > 0)
    val dispatched = CompletableDeferred<Unit>()
    val result = async {
        withContext(
            PhysicalAttemptBoundary { attempt ->
                authorize(attempt)
                dispatched.complete(Unit)
            }
        ) { block() }
    }
    try {
        val ready = withTimeoutOrNull(preparationMillis) {
            select {
                result.onAwait { true }
                dispatched.onAwait { true }
            }
        }
        if (ready == null) throw PhysicalAttemptRejected("PRE_DISPATCH_DEADLINE")
        if (result.isCompleted) result.await() else withTimeoutOrNull(generationMillis) { result.await() }
    } finally {
        // Structured cleanup retains the native lease until the runtime stops or is quarantined.
        result.cancel()
    }
}
