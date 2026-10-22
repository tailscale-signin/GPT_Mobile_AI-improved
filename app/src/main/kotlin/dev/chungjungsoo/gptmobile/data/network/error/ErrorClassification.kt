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
    INVALID_OUTPUT_CAP,
    POLICY_STALE,
    MEMORY_STALE,
    PERMISSION_REQUIRED,
    NETWORK_TIMEOUT,
    NETWORK_DISCONNECTED,
    RATE_LIMIT_EXCEEDED,
    AUTHENTICATION_FAILED,
    ACCESS_FORBIDDEN,
    BUDGET_RESTRICTED,
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
        val causes = mutableListOf<Throwable>()
        val seen = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Throwable, Boolean>())
        var next: Throwable? = throwable
        while (next != null && causes.size < 16 && seen.add(next)) {
            causes += next
            next = next.cause
        }
        val msg = causes.joinToString(" ") { it.message.orEmpty() }.lowercase()
        val dnsFailure = causes.any { it is java.net.UnknownHostException } || "unable to resolve host" in msg || "dns_recovery" in msg

        return when {
            throwable is kotlinx.coroutines.CancellationException -> ClassifiedError(ErrorCategory.CLIENT_CANCELLED, ErrorSeverity.LOW, false, 0, "Request was cancelled.", throwable)
            "memory_stale" in msg || "memory changed before sending" in msg -> ClassifiedError(ErrorCategory.MEMORY_STALE, ErrorSeverity.MEDIUM, false, 0, "Memory changed before sending. Retry to prepare with current authorized memory, or disable recall for this profile and retry.", throwable)
            "dispatch_policy_changed" in msg || "profile_configuration_changed" in msg || "request_identity_changed" in msg -> ClassifiedError(ErrorCategory.POLICY_STALE, ErrorSeverity.MEDIUM, false, 0, "Permissions, budgets or profile settings changed. Retry to prepare a fresh request; no stale request was sent.", throwable)
            "source_permission_revoked" in msg || "blocked_permission" in msg -> ClassifiedError(ErrorCategory.PERMISSION_REQUIRED, ErrorSeverity.MEDIUM, false, 0, "Saved evidence permission is missing. Restore its source/tool grants in Plugins/Tools and retry, or start a fresh request without protected evidence. Completed actions will not be replayed.", throwable)
            "research_output_budget_insufficient" in msg -> ClassifiedError(ErrorCategory.INVALID_OUTPUT_CAP, ErrorSeverity.MEDIUM, false, 0, "RESEARCH_OUTPUT_BUDGET_INSUFFICIENT: The admitted output allowance cannot hold this scientific artifact. Resume with smaller validated parts or a higher-capacity model.", throwable)
            "output_capacity_exceeded" in msg || "invalid_output_cap" in msg -> ClassifiedError(ErrorCategory.INVALID_OUTPUT_CAP, ErrorSeverity.MEDIUM, false, 0, "The output allowance exceeds this route. Lower the profile output limit or retry with automatic limits; retain the full request for continuation.", throwable)
            msg.contains("402") || msg.contains("budget or credit restriction") -> ClassifiedError(ErrorCategory.BUDGET_RESTRICTED, ErrorSeverity.HIGH, false, 0, "The provider reported a budget or credit restriction.", throwable)
            msg.contains("403") -> ClassifiedError(ErrorCategory.ACCESS_FORBIDDEN, ErrorSeverity.HIGH, false, 0, "HTTP 403: access forbidden. The provider's specific cause is unavailable.", throwable)
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

            dnsFailure || msg.contains("network is unreachable") || msg.contains("local_service_unreachable") || causes.any { it is java.net.ConnectException } -> ClassifiedError(
                category = ErrorCategory.NETWORK_DISCONNECTED,
                severity = ErrorSeverity.MEDIUM,
                isRetryable = true,
                suggestedBackoffMs = 5_000L,
                userMessage = if (dnsFailure) "DNS resolution failed for this endpoint. Check its address and network DNS settings." else "This endpoint is unreachable. Check its address, service and network connection.",
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

            msg.contains("401") || msg.contains("unauthorized") || msg.contains("invalid api key") -> ClassifiedError(
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
