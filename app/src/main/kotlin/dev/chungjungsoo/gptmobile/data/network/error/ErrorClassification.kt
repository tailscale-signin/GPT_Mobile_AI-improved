package dev.chungjungsoo.gptmobile.data.network.error

/**
 * Severity level of an error according to the resilient error handling architecture.
 */
enum class ErrorSeverity {
    LOW,
    MEDIUM,
    HIGH,
    CRITICAL
}

/**
 * High-level error classification categories.
 */
enum class ErrorCategory {
    NETWORK_TIMEOUT,
    NETWORK_DISCONNECTED,
    RATE_LIMIT_EXCEEDED,
    AUTHENTICATION_FAILED,
    CONTEXT_WINDOW_EXCEEDED,
    SERVICE_UNAVAILABLE,
    CIRCUIT_BREAKER_OPEN,
    SERIALIZATION_ERROR,
    CLIENT_CANCELLED,
    UNKNOWN
}

/**
 * Result of classifying an error with recovery guidance.
 */
data class ClassifiedError(
    val category: ErrorCategory,
    val severity: ErrorSeverity,
    val isRetryable: Boolean,
    val suggestedBackoffMs: Long,
    val userMessage: String,
    val originalThrowable: Throwable? = null
)

/**
 * Classifier to translate raw network, API, or runtime throwables into actionable classified errors.
 */
object ErrorClassification {

    fun classify(throwable: Throwable): ClassifiedError {
        val msg = throwable.message?.lowercase().orEmpty()

        return when {
            throwable is CircuitBreakerOpenException -> ClassifiedError(
                category = ErrorCategory.CIRCUIT_BREAKER_OPEN,
                severity = ErrorSeverity.HIGH,
                isRetryable = false,
                suggestedBackoffMs = throwable.cooldownRemainingMs,
                userMessage = "Service temporarily unavailable due to high error rates. Please wait a moment before trying again.",
                originalThrowable = throwable
            )

            msg.contains("timeout") || msg.contains("timed out") || throwable is java.net.SocketTimeoutException -> ClassifiedError(
                category = ErrorCategory.NETWORK_TIMEOUT,
                severity = ErrorSeverity.MEDIUM,
                isRetryable = true,
                suggestedBackoffMs = 2_000L,
                userMessage = "Connection timed out. Retrying may resolve the issue.",
                originalThrowable = throwable
            )

            msg.contains("unable to resolve host") || msg.contains("network is unreachable") || throwable is java.net.UnknownHostException -> ClassifiedError(
                category = ErrorCategory.NETWORK_DISCONNECTED,
                severity = ErrorSeverity.MEDIUM,
                isRetryable = true,
                suggestedBackoffMs = 5_000L,
                userMessage = "No internet connection. Please check your Wi-Fi or mobile data.",
                originalThrowable = throwable
            )

            msg.contains("429") || msg.contains("rate limit") || msg.contains("too many requests") -> ClassifiedError(
                category = ErrorCategory.RATE_LIMIT_EXCEEDED,
                severity = ErrorSeverity.HIGH,
                isRetryable = true,
                suggestedBackoffMs = 10_000L,
                userMessage = "Rate limit reached. Please wait a few moments before sending another message.",
                originalThrowable = throwable
            )

            msg.contains("401") || msg.contains("403") || msg.contains("unauthorized") || msg.contains("invalid api key") -> ClassifiedError(
                category = ErrorCategory.AUTHENTICATION_FAILED,
                severity = ErrorSeverity.CRITICAL,
                isRetryable = false,
                suggestedBackoffMs = 0L,
                userMessage = "Authentication failed. Please verify your API key in Settings.",
                originalThrowable = throwable
            )

            msg.contains("maximum context length") || msg.contains("context_length_exceeded") || msg.contains("too many tokens") -> ClassifiedError(
                category = ErrorCategory.CONTEXT_WINDOW_EXCEEDED,
                severity = ErrorSeverity.MEDIUM,
                isRetryable = false,
                suggestedBackoffMs = 0L,
                userMessage = "Conversation context limit exceeded. Consider starting a new conversation or summarizing previous messages.",
                originalThrowable = throwable
            )

            msg.contains("500") || msg.contains("502") || msg.contains("503") || msg.contains("504") || msg.contains("overloaded") -> ClassifiedError(
                category = ErrorCategory.SERVICE_UNAVAILABLE,
                severity = ErrorSeverity.HIGH,
                isRetryable = true,
                suggestedBackoffMs = 5_000L,
                userMessage = "The AI service is currently experiencing issues. Retrying shortly.",
                originalThrowable = throwable
            )

            throwable is kotlinx.coroutines.CancellationException -> ClassifiedError(
                category = ErrorCategory.CLIENT_CANCELLED,
                severity = ErrorSeverity.LOW,
                isRetryable = false,
                suggestedBackoffMs = 0L,
                userMessage = "Request was cancelled.",
                originalThrowable = throwable
            )

            else -> ClassifiedError(
                category = ErrorCategory.UNKNOWN,
                severity = ErrorSeverity.MEDIUM,
                isRetryable = false,
                suggestedBackoffMs = 1_000L,
                userMessage = "An unexpected error occurred: ${throwable.localizedMessage ?: "Unknown error"}",
                originalThrowable = throwable
            )
        }
    }
}
