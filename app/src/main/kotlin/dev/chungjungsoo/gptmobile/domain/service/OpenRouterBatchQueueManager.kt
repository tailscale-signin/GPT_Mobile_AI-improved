package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchItemResult
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchRequestItem
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import java.util.ArrayDeque
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpenRouterBatchQueueManager(
    private val settings: OpenRouterSettings,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val onBatchReady: suspend (List<OpenRouterBatchRequestItem>) -> Unit
) {
    private val queue = ArrayDeque<OpenRouterBatchRequestItem>()
    private val mutex = Mutex()
    private var flushJob: Job? = null

    suspend fun enqueue(request: OpenRouterBatchRequestItem) {
        require(settings.batchingEnabled) { "Background queue is disabled for this connection." }
        val shouldFlush: Boolean
        val batchToFlush = mutableListOf<OpenRouterBatchRequestItem>()

        mutex.withLock {
            queue.addLast(request)
            if (queue.size >= settings.batchSize.coerceIn(1, 100)) {
                cancelFlushJobLocked()
                val count = minOf(queue.size, settings.batchSize.coerceIn(1, 100))
                repeat(count) {
                    queue.pollFirst()?.let { batchToFlush.add(it) }
                }
                shouldFlush = true
            } else {
                scheduleFlushLocked()
                shouldFlush = false
            }
        }

        if (shouldFlush && batchToFlush.isNotEmpty()) {
            onBatchReady(batchToFlush)
        }
    }

    private fun scheduleFlushLocked() {
        if (flushJob?.isActive == true) return
        flushJob = scope.launch {
            delay(settings.flushTimeoutMs.coerceIn(1000, 30000))
            mutex.withLock { flushJob = null }
            flush()
        }
    }

    private fun cancelFlushJobLocked() {
        flushJob?.cancel()
        flushJob = null
    }

    suspend fun flush(): List<OpenRouterBatchRequestItem> {
        val batch = mutableListOf<OpenRouterBatchRequestItem>()
        mutex.withLock {
            cancelFlushJobLocked()
            if (queue.isNotEmpty()) {
                val count = minOf(queue.size, settings.batchSize.coerceIn(1, 100))
                repeat(count) {
                    queue.pollFirst()?.let { batch.add(it) }
                }
                if (queue.isNotEmpty()) scheduleFlushLocked()
            }
        }
        if (batch.isNotEmpty()) {
            onBatchReady(batch)
        }
        return batch
    }

    suspend fun reQueueFailed(requests: List<OpenRouterBatchRequestItem>) {
        mutex.withLock {
            for (item in requests) {
                queue.addFirst(item)
            }
            scheduleFlushLocked()
        }
    }

    suspend fun pendingCount(): Int = mutex.withLock { queue.size }
}

class RetryPolicy(
    private val maxRetries: Int = 3,
    private val baseDelayMs: Long = 1000L
) {
    suspend fun <T> executeWithRetry(block: suspend () -> Result<T>): Result<T> {
        var attempt = 0
        var lastException: Throwable = RuntimeException("Unknown error")

        while (attempt <= maxRetries.coerceIn(0, 10)) {
            val result = block()
            if (result.isSuccess) {
                return result
            }
            lastException = result.exceptionOrNull() ?: RuntimeException("Execution failed")
            if (lastException is kotlinx.coroutines.CancellationException) throw lastException
            attempt++
            if (attempt <= maxRetries.coerceIn(0, 10)) {
                val delayMs = baseDelayMs * (1L shl (attempt - 1))
                delay(delayMs)
            }
        }

        return Result.failure(lastException)
    }
}

fun handleBatchFailure(
    requests: List<OpenRouterBatchRequestItem>,
    error: Throwable
): List<OpenRouterBatchItemResult> = requests.map { request ->
    OpenRouterBatchItemResult(
        requestId = request.id,
        content = null,
        error = error.message ?: "Unknown batch error",
        usage = null
    )
}
