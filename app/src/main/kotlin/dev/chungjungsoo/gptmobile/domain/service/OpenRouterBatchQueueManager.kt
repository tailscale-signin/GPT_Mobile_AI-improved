package dev.chungjungsoo.gptmobile.domain.service

import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchItemResult
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterBatchRequestItem
import dev.chungjungsoo.gptmobile.domain.model.OpenRouterSettings
import java.util.Timer
import java.util.TimerTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class OpenRouterBatchQueueManager(
    private val settings: OpenRouterSettings,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO),
    private val onBatchReady: suspend (List<OpenRouterBatchRequestItem>) -> Unit
) {
    private val queue = mutableListOf<OpenRouterBatchRequestItem>()
    private val mutex = Mutex()
    private var timer: Timer? = null

    suspend fun enqueue(request: OpenRouterBatchRequestItem) {
        val shouldFlush: Boolean
        val batchToFlush = mutableListOf<OpenRouterBatchRequestItem>()

        mutex.withLock {
            queue.add(request)
            if (queue.size >= settings.batchSize) {
                cancelTimer()
                batchToFlush.addAll(queue.take(settings.batchSize))
                // Remove flushed items
                repeat(batchToFlush.size) {
                    if (queue.isNotEmpty()) queue.removeAt(0)
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
        timer?.cancel()
        timer = Timer("OpenRouterBatchTimer", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    scope.launch {
                        flush()
                    }
                }
            }, settings.flushTimeoutMs)
        }
    }

    private fun cancelTimer() {
        timer?.cancel()
        timer = null
    }

    suspend fun flush(): List<OpenRouterBatchRequestItem> {
        val batch = mutableListOf<OpenRouterBatchRequestItem>()
        mutex.withLock {
            cancelTimer()
            if (queue.isNotEmpty()) {
                val count = minOf(queue.size, settings.batchSize)
                batch.addAll(queue.take(count))
                repeat(count) {
                    queue.removeAt(0)
                }
            }
        }
        if (batch.isNotEmpty()) {
            onBatchReady(batch)
        }
        return batch
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

        while (attempt <= maxRetries) {
            val result = block()
            if (result.isSuccess) {
                return result
            }
            lastException = result.exceptionOrNull() ?: RuntimeException("Execution failed")
            if (attempt == maxRetries) {
                break
            }
            val delayMs = baseDelayMs * (1L shl attempt)
            kotlinx.coroutines.delay(delayMs)
            attempt++
        }

        return Result.failure(lastException)
    }
}

fun handleBatchFailure(
    requests: List<OpenRouterBatchRequestItem>,
    error: Throwable
): List<OpenRouterBatchItemResult> {
    return requests.map { request ->
        OpenRouterBatchItemResult(
            requestId = request.id,
            content = null,
            error = error.message ?: "Unknown batch error",
            usage = null
        )
    }
}
