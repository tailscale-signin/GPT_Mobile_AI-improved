package dev.chungjungsoo.gptmobile.data.agent

import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One in-flight write and one newest pending snapshot. Terminal owners drain or supersede it. */
internal class ConflatedCheckpointWriter<T>(scope: CoroutineScope, private val write: suspend (T) -> Unit) {
    private data class Pending<T>(val value: T, val queuedAt: Long)
    private val queue = Channel<Pending<T>>(Channel.CONFLATED)
    private val failure = AtomicReference<Throwable?>()

    @Volatile var lastQueueAgeMillis = 0L
        private set

    @Volatile var lastWriteMillis = 0L
        private set
    private val job = scope.launch {
        try {
            for (pending in queue) {
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
        queue.close()
        job.join()
        failure.get()?.let { throw it }
    }

    suspend fun cancelAndJoin() {
        queue.cancel()
        job.cancelAndJoin()
    }
}
