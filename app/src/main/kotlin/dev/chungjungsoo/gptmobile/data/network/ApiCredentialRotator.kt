package dev.chungjungsoo.gptmobile.data.network

import io.ktor.client.plugins.ClientRequestException
import io.ktor.client.plugins.ServerResponseException
import io.ktor.http.HttpStatusCode
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Utility for parsing, serializing, classifying, and rotating through multiple API credentials.
 *
 * Supports round-robin rotation across multiple keys/tokens separated by newlines (`\n`)
 * or comma sequences. Detects quota exhaustion, rate limits, credit limits, and authentication
 * failures to trigger seamless fallback to alternative credentials.
 */
object ApiCredentialRotator {

    private val KEY_DELIMITERS = Regex("[\\r\\n,]+")

    /**
     * Splits a raw credential/token string into a list of distinct non-blank API keys.
     */
    fun parseKeys(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(KEY_DELIMITERS)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
    }

    /**
     * Combines multiple API keys into a newline-delimited string for storage.
     */
    fun formatKeys(keys: List<String>): String {
        return keys.map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString("\n")
    }

    /**
     * Checks if a Throwable represents an API failure that should trigger rotation to the next key.
     *
     * Rotation conditions:
     * - HTTP 429: Too Many Requests / Rate limit exceeded
     * - HTTP 402: Payment Required / Insufficient credits
     * - HTTP 401: Unauthorized / Expired or invalid API key
     * - HTTP 403: Forbidden / Quota or permission exceeded
     * - Error messages referencing quota, rate limit, credits, or balance exhaustion.
     */
    fun isRotatableError(throwable: Throwable): Boolean {
        var current: Throwable? = throwable
        while (current != null) {
            when (current) {
                is ClientRequestException -> {
                    val status = current.response.status
                    if (status == HttpStatusCode.TooManyRequests ||
                        status == HttpStatusCode.PaymentRequired ||
                        status == HttpStatusCode.Unauthorized ||
                        status == HttpStatusCode.Forbidden
                    ) {
                        return true
                    }
                }
                is ServerResponseException -> {
                    val status = current.response.status
                    // Some custom proxies or gateways return 503 or 502 with quota/rate messages
                    if (containsQuotaOrRateLimitMessage(current.message)) {
                        return true
                    }
                }
            }

            if (containsQuotaOrRateLimitMessage(current.message)) {
                return true
            }

            current = current.cause
        }
        return false
    }

    /**
     * Checks if an error message string contains keywords indicating quota, credit, or rate limit issues.
     */
    fun containsQuotaOrRateLimitMessage(message: String?): Boolean {
        if (message.isNullOrBlank()) return false
        val lower = message.lowercase()
        return lower.contains("rate_limit") ||
            lower.contains("rate limit") ||
            lower.contains("ratelimit") ||
            lower.contains("quota") ||
            lower.contains("insufficient_quota") ||
            lower.contains("credit") ||
            lower.contains("credits") ||
            lower.contains("balance") ||
            lower.contains("billing") ||
            lower.contains("payment_required") ||
            lower.contains("payment required") ||
            lower.contains("exceeded your current quota") ||
            lower.contains("token limit") ||
            lower.contains("tpm") ||
            lower.contains("rpm") ||
            lower.contains("429") ||
            lower.contains("402")
    }

    /**
     * Executes a suspending action with round-robin fallback across the parsed candidate keys.
     *
     * @param rawCredentials The raw token string containing one or multiple keys.
     * @param startIndex Optional starting offset for round-robin balancing.
     * @param action Lambda taking an individual API key and returning a result.
     * @return Result of the first successful action execution.
     */
    suspend fun <T> executeWithRotation(
        rawCredentials: String?,
        startIndex: Int = 0,
        action: suspend (apiKey: String) -> T
    ): T {
        val keys = parseKeys(rawCredentials)
        if (keys.isEmpty()) {
            return action("")
        }
        if (keys.size == 1) {
            return action(keys.first())
        }

        var lastException: Throwable? = null
        val count = keys.size
        val normalizedStart = (startIndex % count + count) % count

        for (i in 0 until count) {
            val keyIndex = (normalizedStart + i) % count
            val currentKey = keys[keyIndex]
            try {
                return action(currentKey)
            } catch (t: Throwable) {
                lastException = t
                if (!isRotatableError(t) && i < count - 1) {
                    // Even if not strictly categorized as 429/401/402, if an IOException or network failure
                    // occurs, check if message has rate/quota indication before giving up.
                    if (!containsQuotaOrRateLimitMessage(t.message)) {
                        throw t
                    }
                }
            }
        }

        throw lastException ?: IOException("Failed to execute request with rotated credentials")
    }

    /**
     * Thread-safe index provider for distributing round-robin across requests.
     */
    class KeyRotator(private val rawCredentials: String?) {
        private val counter = AtomicInteger(0)

        fun getKeys(): List<String> = parseKeys(rawCredentials)

        fun hasMultipleKeys(): Boolean = getKeys().size > 1

        suspend fun <T> execute(action: suspend (apiKey: String) -> T): T {
            val start = counter.getAndIncrement()
            return executeWithRotation(rawCredentials, start, action)
        }
    }
}
