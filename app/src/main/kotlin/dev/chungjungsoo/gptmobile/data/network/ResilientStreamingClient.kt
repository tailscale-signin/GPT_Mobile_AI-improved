package dev.chungjungsoo.gptmobile.data.network

import kotlinx.coroutines.delay
import java.io.IOException
import kotlin.math.min
import kotlin.math.pow

/**
 * Resilient retry policy and backoff utility for SSE and streaming HTTP LLM endpoints.
 * Automatically backs off on transient errors (HTTP 429 Too Many Requests, 502/503/504 Bad Gateway/Unavailable,
 * and transient socket disconnects) without dropping active chat context.
 */
object ResilientStreamingClient {

    data class RetryConfig(
        val maxAttempts: Int = 4,
        val initialDelayMs: Long = 1000L,
        val maxDelayMs: Long = 16000L,
        val backoffFactor: Double = 2.0,
        val jitterRatio: Double = 0.2
    )

    /**
     * Executes a streaming operation with exponential backoff and jitter.
     *
     * @param config Retry limits and timings.
     * @param onRetry Callback invoked prior to each retry attempt with attempt count and delay in ms.
     * @param block The suspendable block performing the streaming request.
     */
    suspend fun <T> executeWithRetry(
        config: RetryConfig = RetryConfig(),
        onRetry: ((attempt: Int, delayMs: Long, reason: Throwable) -> Unit)? = null,
        block: suspend () -> T
    ): T {
        var currentAttempt = 0
        var currentDelay = config.initialDelayMs

        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                currentAttempt++
                if (currentAttempt >= config.maxAttempts || !isRetryable(e)) {
                    throw e
                }

                // Calculate exponential backoff with jitter
                val jitter = (currentDelay * config.jitterRatio * (Math.random() * 2 - 1)).toLong()
                val sleepDuration = min(config.maxDelayMs, (currentDelay + jitter).coerceAtLeast(100L))

                onRetry?.invoke(currentAttempt, sleepDuration, e)
                delay(sleepDuration)

                currentDelay = (currentDelay * config.backoffFactor).toLong().coerceAtMost(config.maxDelayMs)
            }
        }
    }

    /**
     * Determine if an exception represents a transient network or server fault suitable for automatic retry.
     */
    fun isRetryable(throwable: Throwable): Boolean {
        val message = throwable.message?.lowercase() ?: ""

        // Network IO disconnects
        if (throwable is IOException) {
            return true
        }

        // Standard rate limits or transient cloud gateway dropouts
        if (message.contains("429") ||
            message.contains("rate limit") ||
            message.contains("503") ||
            message.contains("502") ||
            message.contains("504") ||
            message.contains("stream reset") ||
            message.contains("timeout") ||
            message.contains("connection closed")
        ) {
            return true
        }

        return false
    }
}
