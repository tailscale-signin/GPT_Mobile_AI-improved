package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal enum class VoiceNativeRetirementState { PENDING, RETIRED }

/**
 * A bounded foreground wait with an independently owned native-worker quarantine.
 * A timeout or cancelled observer never establishes retirement. The caller keeps
 * its audio/model lease until [awaitRetired] completes, even after its UI job ends.
 * [awaitClosed] must only observe executor retirement with its supplied time bound;
 * it must never close a JNI handle that a worker may still be using.
 */
internal class VoiceNativeRetirement(
    private val awaitClosed: (Long) -> Boolean,
    scope: CoroutineScope = reaperScope,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private val started = nanoTime()
    private val ended = AtomicLong(-1)
    private val failures = AtomicInteger(0)
    private val retired = CompletableDeferred<Unit>()
    private val mutable = MutableStateFlow(VoiceNativeRetirementState.PENDING)
    val state = mutable.asStateFlow()
    val observationFailures: Int get() = failures.get()
    val elapsedMs: Long get() = ((ended.get().takeIf { it >= 0 } ?: nanoTime()) - started).coerceAtLeast(0) / 1_000_000

    init {
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                val closed = try {
                    awaitClosed(OBSERVATION_SLICE_MS)
                } catch (_: Exception) {
                    // An unsuccessful observation cannot release another user's
                    // native/audio ownership. Keep it quarantined and retry.
                    failures.incrementAndGet()
                    false
                }
                if (closed) {
                    ended.compareAndSet(-1, nanoTime())
                    mutable.value = VoiceNativeRetirementState.RETIRED
                    retired.complete(Unit)
                    break
                }
                // Some executor implementations return immediately while work
                // remains pending. Avoid a hot loop without delaying audio stop.
                delay(OBSERVATION_BACKOFF_MS)
            }
        }
    }

    suspend fun awaitWithinBudget(timeoutMs: Long = DEFAULT_WAIT_MS): VoiceNativeRetirementState {
        require(timeoutMs in 0..MAX_WAIT_MS)
        if (!retired.isCompleted && timeoutMs > 0) withTimeoutOrNull(timeoutMs) { retired.await() }
        return state.value
    }

    suspend fun awaitRetired() = retired.await()

    private companion object {
        const val OBSERVATION_SLICE_MS = 100L
        const val OBSERVATION_BACKOFF_MS = 25L
        const val DEFAULT_WAIT_MS = 5_000L
        const val MAX_WAIT_MS = 30_000L
        val reaperScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
