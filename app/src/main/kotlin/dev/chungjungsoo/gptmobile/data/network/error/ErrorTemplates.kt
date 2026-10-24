package dev.chungjungsoo.gptmobile.data.network.error

/**
 * User-facing error message templates with actionable suggestions according to
 * docs/RESILIENT_CONVERSATIONS_LAYOUT.md.
 */
object ErrorTemplates {

    data class UiErrorTemplate(
        val title: String,
        val description: String,
        val actionButtonLabel: String?,
        val autoRetry: Boolean
    )

    fun getTemplateFor(error: ClassifiedError): UiErrorTemplate = when (error.category) {
        ErrorCategory.INVALID_OUTPUT_CAP -> UiErrorTemplate(
            title = "Output Limit Needs Adjustment",
            description = error.userMessage,
            actionButtonLabel = "Open Settings",
            autoRetry = false
        )
        ErrorCategory.POLICY_STALE -> UiErrorTemplate(
            title = "Request Settings Changed",
            description = error.userMessage,
            actionButtonLabel = "Retry",
            autoRetry = false
        )
        ErrorCategory.SAVED_POLICY_UNAVAILABLE -> UiErrorTemplate(
            title = "Saved Request Unavailable",
            description = error.userMessage,
            actionButtonLabel = "Review Saved Data",
            autoRetry = false
        )
        ErrorCategory.MEMORY_STALE -> UiErrorTemplate(
            title = "Memory Changed",
            description = error.userMessage,
            actionButtonLabel = "Retry",
            autoRetry = false
        )
        ErrorCategory.PERMISSION_REQUIRED -> UiErrorTemplate(
            title = "Source Permission Required",
            description = error.userMessage,
            actionButtonLabel = "Review Access",
            autoRetry = false
        )
        ErrorCategory.RESOURCE_DEADLINE -> UiErrorTemplate(
            title = "Resource Wait Expired",
            description = error.userMessage,
            actionButtonLabel = "Resume",
            autoRetry = false
        )
        ErrorCategory.LOCAL_INITIALIZATION_FAILED -> UiErrorTemplate(
            title = "Local Model Initialization Failed",
            description = error.userMessage,
            actionButtonLabel = "Review Model",
            autoRetry = false
        )
        ErrorCategory.REASONING_BUDGET_EXHAUSTED, ErrorCategory.NO_VISIBLE_ANSWER -> UiErrorTemplate(
            title = "Provider Returned No Answer",
            description = error.userMessage,
            actionButtonLabel = "Review Model",
            autoRetry = false
        )
        ErrorCategory.TOOL_CHOICE_MISMATCH, ErrorCategory.PROVIDER_PAYLOAD_ERROR -> UiErrorTemplate(
            title = "Provider Generation Failed",
            description = error.userMessage,
            actionButtonLabel = "Review Settings",
            autoRetry = false
        )
        ErrorCategory.OUTPUT_PROTOCOL_INVALID -> UiErrorTemplate(
            title = "Answer Format Incomplete",
            description = error.userMessage,
            actionButtonLabel = "Review Answer",
            autoRetry = false
        )
        ErrorCategory.NETWORK_TIMEOUT -> UiErrorTemplate(
            title = "Request Timed Out",
            description = "The AI service took too long to respond. You can retry sending your request.",
            actionButtonLabel = "Retry",
            autoRetry = true
        )
        ErrorCategory.NETWORK_DISCONNECTED -> UiErrorTemplate(
            title = "No Internet Connection",
            description = "Please check your network settings and try again.",
            actionButtonLabel = "Retry",
            autoRetry = false
        )
        ErrorCategory.RATE_LIMIT_EXCEEDED -> UiErrorTemplate(
            title = "Rate Limit Reached",
            description = "Too many requests sent in a short window. Please wait a moment before trying again.",
            actionButtonLabel = "Wait & Retry",
            autoRetry = true
        )
        ErrorCategory.ACCESS_FORBIDDEN, ErrorCategory.BUDGET_RESTRICTED -> UiErrorTemplate(
            title = "Provider access blocked",
            description = error.userMessage,
            actionButtonLabel = "Check connection",
            autoRetry = false
        )
        ErrorCategory.AUTHENTICATION_FAILED -> UiErrorTemplate(
            title = "API Key Error",
            description = "Authentication failed. Check your API key under Settings.",
            actionButtonLabel = "Open Settings",
            autoRetry = false
        )
        ErrorCategory.CONTEXT_WINDOW_EXCEEDED -> UiErrorTemplate(
            title = "Conversation Too Long",
            description = "The conversation exceeds the model's token context window. Start a new chat or summarize earlier messages.",
            actionButtonLabel = "New Chat",
            autoRetry = false
        )
        ErrorCategory.SERVICE_UNAVAILABLE, ErrorCategory.CIRCUIT_BREAKER_OPEN -> UiErrorTemplate(
            title = "Service Temporarily Unavailable",
            description = "The upstream model service is having temporary issues. Please try again in a few moments.",
            actionButtonLabel = "Retry Later",
            autoRetry = true
        )
        ErrorCategory.CLIENT_CANCELLED -> UiErrorTemplate(
            title = "Generation Cancelled",
            description = "Generation was stopped.",
            actionButtonLabel = null,
            autoRetry = false
        )
        ErrorCategory.SERIALIZATION_ERROR, ErrorCategory.UNKNOWN -> UiErrorTemplate(
            title = "Something Went Wrong",
            description = error.userMessage,
            actionButtonLabel = "Retry",
            autoRetry = false
        )
    }
}
