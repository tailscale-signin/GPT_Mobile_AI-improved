package dev.chungjungsoo.gptmobile.data.agent

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** One in-flight write and one newest pending snapshot. Terminal owners drain or supersede it. */
internal class ConflatedCheckpointWriter<T>(
    scope: CoroutineScope,
    private val minimumIntervalMillis: Long = 0,
    private val write: suspend (T) -> Unit
) {
    init {
        require(minimumIntervalMillis >= 0)
    }
    private data class Pending<T>(val value: T, val queuedAt: Long)
    private val queue = Channel<Pending<T>>(Channel.CONFLATED)
    private val failure = AtomicReference<Throwable?>()
    private val draining = CompletableDeferred<Unit>()

    @Volatile var lastQueueAgeMillis = 0L
        private set

    @Volatile var lastWriteMillis = 0L
        private set
    private val job = scope.launch {
        try {
            var first = true
            for (queued in queue) {
                // A fast database must not cause a full message serialization per token.
                // Keep at most the newest snapshot while waiting; drain still flushes it.
                if (!first && minimumIntervalMillis > 0) withTimeoutOrNull(minimumIntervalMillis) { draining.await() }
                first = false
                var pending = queued
                while (true) pending = queue.tryReceive().getOrNull() ?: break
                val start = System.nanoTime()
                lastQueueAgeMillis = (start - pending.queuedAt) / 1_000_000L
                write(pending.value)
                lastWriteMillis = (System.nanoTime() - start) / 1_000_000L
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            failure.set(error)
            queue.close(error)
        }
    }

    fun offer(value: T) {
        failure.get()?.let { throw it }
        check(queue.trySend(Pending(value, System.nanoTime())).isSuccess) { "Checkpoint writer is closed" }
    }

    suspend fun drain() {
        draining.complete(Unit)
        queue.close()
        job.join()
        failure.get()?.let { throw it }
    }

    suspend fun cancelAndJoin() {
        queue.cancel()
        job.cancelAndJoin()
    }
}
