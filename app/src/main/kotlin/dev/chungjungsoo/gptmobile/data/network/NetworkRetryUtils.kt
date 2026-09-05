package dev.chungjungsoo.gptmobile.data.network

import io.ktor.client.plugins.HttpRequestTimeoutException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.channels.UnresolvedAddressException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/**
 * Utility for handling transient network errors and retrying operations with exponential backoff and jitter.
 */
object NetworkRetryUtils {

    /**
     * Determines whether an exception indicates a transient failure suitable for retry
     * (e.g. temporary DNS resolution failure, socket timeouts, connection reset).
     */
    fun isTransientNetworkError(throwable: Throwable): Boolean {
        return when (throwable) {
            is CancellationException -> false
            is SocketTimeoutException,
            is HttpRequestTimeoutException,
            is ConnectException,
            is UnknownHostException,
            is UnresolvedAddressException,
            is SSLException -> true
            is IOException -> {
                val message = throwable.message?.lowercase() ?: ""
                message.contains("connection reset") ||
                    message.contains("software caused connection abort") ||
                    message.contains("broken pipe") ||
                    message.contains("timeout")
            }
            else -> false
        }
    }

    /**
     * Executes the given block with retry logic for transient network failures.
     *
     * @param maxRetries Maximum number of retry attempts (default: 2).
     * @param initialDelayMs Initial delay before first retry in milliseconds (default: 500ms).
     * @param maxDelayMs Maximum capped delay between retries (default: 2500ms).
     * @param backoffMultiplier Multiplier for subsequent retries (default: 2.0).
     * @param block Suspend lambda to execute.
     */
    suspend fun <T> withRetry(
        maxRetries: Int = 2,
        initialDelayMs: Long = 500L,
        maxDelayMs: Long = 2500L,
        backoffMultiplier: Double = 2.0,
        onRetry: ((attempt: Int, exception: Throwable) -> Unit)? = null,
        block: suspend () -> T
    ): T {
        var currentAttempt = 0
        var currentDelay = initialDelayMs

        while (true) {
            try {
                return block()
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                if (currentAttempt >= maxRetries || !isTransientNetworkError(e)) {
                    throw e
                }
                currentAttempt++
                onRetry?.invoke(currentAttempt, e)

                // Add small jitter (±15%) to prevent thundering herd
                val jitter = (currentDelay * (0.85 + Math.random() * 0.3)).toLong()
                delay(jitter.coerceAtMost(maxDelayMs))
                currentDelay = (currentDelay * backoffMultiplier).toLong().coerceAtMost(maxDelayMs)
            }
        }
    }
}
