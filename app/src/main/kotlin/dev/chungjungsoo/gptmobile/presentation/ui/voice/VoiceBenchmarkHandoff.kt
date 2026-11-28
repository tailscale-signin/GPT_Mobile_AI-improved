package dev.chungjungsoo.gptmobile.presentation.ui.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Every release is requested before waiting; a repeated or late callback cannot resume twice. */
internal suspend fun awaitVoiceBenchmarkRetirement(vararg request: ((() -> Unit) -> Unit)) {
    var registrationFailure: Throwable? = null
    val completions = request.map { release ->
        CompletableDeferred<Unit>().also { completion ->
            try {
                release { completion.complete(Unit) }
            } catch (failure: Throwable) {
                if (registrationFailure == null) registrationFailure = failure
                completion.completeExceptionally(failure)
            }
        }
    }
    // A release that calls back and then throws is still a failed handoff.
    registrationFailure?.let { throw it }
    completions.awaitAll()
}

internal class VoiceBenchmarkHandoffException(cause: Throwable) :
    IllegalStateException(
        "The active voice models could not confirm cleanup. Restart the app before starting the benchmark.",
        cause
    )

/** Cancellation may stop the campaign, but it cannot release ownership while retirement is pending. */
internal suspend fun runAfterVoiceBenchmarkRetirement(
    lease: VoiceSpeechBenchmarkOwnership.Lease,
    beforeStart: suspend () -> Unit,
    cancelled: () -> Boolean,
    retainUnconfirmedOwner: () -> Unit,
    timeoutMs: Long = 30_000L,
    run: suspend () -> Unit
): Boolean {
    require(timeoutMs > 0)
    withContext(NonCancellable) {
        try {
            withTimeout(timeoutMs) { beforeStart() }
        } catch (failure: Throwable) {
            // Quarantine precedes propagation, including cancellation of the surrounding owner.
            lease.quarantine()
            retainUnconfirmedOwner()
            throw VoiceBenchmarkHandoffException(failure)
        }
    }
    currentCoroutineContext().ensureActive()
    if (cancelled()) return false
    run()
    return true
}
