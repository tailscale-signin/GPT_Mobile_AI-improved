package dev.chungjungsoo.gptmobile.data.voice

import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Limits request cancellation; they never establish that native ownership has retired. */
internal data class VoiceSpeechBenchmarkDeadlines(
    val preparationMs: Long = 30_000,
    val chunkMs: Long = 30_000,
    val backendMs: Long = 600_000
) {
    init {
        require(preparationMs in 1..300_000 && chunkMs in 1..300_000 && backendMs in 1..7_200_000)
    }

    companion object {
        fun forMode(mode: VoiceSpeechBenchmarkMode) = VoiceSpeechBenchmarkDeadlines(
            backendMs = when (mode) {
                VoiceSpeechBenchmarkMode.QUICK -> 600_000
                VoiceSpeechBenchmarkMode.FULL -> 1_200_000
                VoiceSpeechBenchmarkMode.STABILITY -> 3_600_000
            }
        )
    }
}

internal enum class VoiceSpeechBenchmarkDeadlineScope { PREPARATION, CHUNK, BACKEND }
internal enum class VoiceSpeechBenchmarkStopReason { AUDIO_REFERENCE_MISMATCH, DEADLINE }
internal enum class VoiceSpeechBenchmarkCleanupState { PENDING, RETIRED, FAILED }

/** Relative observations are safe to persist; the monotonic request instant remains runtime-only. */
internal data class VoiceSpeechBenchmarkTimeout(
    val scope: VoiceSpeechBenchmarkDeadlineScope,
    val limitMs: Long,
    val elapsedMs: Long,
    val stage: VoiceSpeechStage? = null
)

internal fun interface VoiceSpeechBenchmarkScheduler {
    fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable
}

/**
 * One watchdog belongs to one backend owner. At most one stop callback is delivered.
 * The callback may request cancellation and publish a snapshot, but must not close
 * a handle or release its model lease. The synchronous inference owner does that
 * only after the actual call returns. A stale phase timer cannot stop a later phase.
 */
internal class VoiceSpeechBenchmarkWatchdog(
    private val deadlines: VoiceSpeechBenchmarkDeadlines,
    private val nanoTime: () -> Long = System::nanoTime,
    private val stage: () -> VoiceSpeechStage? = { null },
    private val onTimeout: (VoiceSpeechBenchmarkTimeout) -> Unit,
    private val scheduler: VoiceSpeechBenchmarkScheduler = DefaultScheduler
) : AutoCloseable {
    private val lock = Any()
    private var backendStarted: Long? = null
    private var phaseStarted: Long? = null
    private var phaseScope: VoiceSpeechBenchmarkDeadlineScope? = null
    private var phaseSequence = 0L
    private var backendTimer: AutoCloseable? = null
    private var phaseTimer: AutoCloseable? = null
    private var closed = false
    private var requested: VoiceSpeechBenchmarkTimeout? = null
    private var requestedAt: Long? = null

    val timeout: VoiceSpeechBenchmarkTimeout? get() = synchronized(lock) { requested }
    val requestedAtNanos: Long? get() = synchronized(lock) { requestedAt }

    fun beginBackend(): Boolean = synchronized(lock) {
        if (closed) return@synchronized false
        check(backendStarted == null)
        backendStarted = nanoTime()
        backendTimer = scheduler.schedule(deadlines.backendMs) { checkpoint() }
        true
    }

    fun beginPhase(scope: VoiceSpeechBenchmarkDeadlineScope): Boolean = synchronized(lock) {
        require(scope != VoiceSpeechBenchmarkDeadlineScope.BACKEND)
        if (closed) return@synchronized false
        check(backendStarted != null)
        phaseTimer?.close()
        phaseSequence++
        phaseStarted = nanoTime()
        phaseScope = scope
        val sequence = phaseSequence
        val limit = if (scope == VoiceSpeechBenchmarkDeadlineScope.PREPARATION) deadlines.preparationMs else deadlines.chunkMs
        phaseTimer = scheduler.schedule(limit) { checkpoint(sequence) }
        true
    }

    fun endPhase(): VoiceSpeechBenchmarkTimeout? {
        val observed = checkpoint()
        synchronized(lock) {
            phaseSequence++
            phaseTimer?.close()
            phaseTimer = null
            phaseStarted = null
            phaseScope = null
        }
        return observed
    }

    fun checkpoint(): VoiceSpeechBenchmarkTimeout? = checkpoint(null)

    private fun checkpoint(expectedPhase: Long?): VoiceSpeechBenchmarkTimeout? {
        var notify: VoiceSpeechBenchmarkTimeout? = null
        val result = synchronized(lock) {
            if (closed || requested != null || expectedPhase != null && expectedPhase != phaseSequence) return@synchronized requested
            val now = nanoTime()
            val backend = backendStarted ?: return@synchronized null
            val candidates = mutableListOf(Triple(VoiceSpeechBenchmarkDeadlineScope.BACKEND, backend, deadlines.backendMs))
            val started = phaseStarted
            val scope = phaseScope
            if (started != null && scope != null) {
                candidates += Triple(scope, started, if (scope == VoiceSpeechBenchmarkDeadlineScope.PREPARATION) deadlines.preparationMs else deadlines.chunkMs)
            }
            val expired = candidates.filter { (_, start, limit) -> elapsed(now, start) >= limit }
                .minByOrNull { (_, start, limit) -> start + limit * 1_000_000 }
                ?: return@synchronized null
            val nativeStage = runCatching(stage).getOrNull() ?: when (expired.first) {
                VoiceSpeechBenchmarkDeadlineScope.PREPARATION -> VoiceSpeechStage.PREPARING
                VoiceSpeechBenchmarkDeadlineScope.CHUNK -> VoiceSpeechStage.SYNTHESIS
                VoiceSpeechBenchmarkDeadlineScope.BACKEND -> null
            }
            VoiceSpeechBenchmarkTimeout(expired.first, expired.third, elapsed(now, expired.second), nativeStage).also {
                requested = it
                requestedAt = now
                notify = it
                phaseTimer?.close()
                backendTimer?.close()
            }
        }
        notify?.let(onTimeout)
        return result
    }

    override fun close() = synchronized(lock) {
        closed = true
        phaseSequence++
        phaseTimer?.close()
        backendTimer?.close()
        phaseTimer = null
        backendTimer = null
    }

    private fun elapsed(now: Long, start: Long) = (now - start).coerceAtLeast(0) / 1_000_000

    private object DefaultScheduler : VoiceSpeechBenchmarkScheduler {
        private val executor = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "speech-benchmark-deadline").apply { isDaemon = true } }.apply {
            removeOnCancelPolicy = true
        }

        override fun schedule(delayMs: Long, task: () -> Unit): AutoCloseable {
            val future = executor.schedule(task, delayMs, TimeUnit.MILLISECONDS)
            return AutoCloseable { future.cancel(false) }
        }
    }
}
