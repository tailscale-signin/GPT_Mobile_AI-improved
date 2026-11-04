package dev.chungjungsoo.gptmobile.data.agent

/** Bounded recovery of the current model round, retaining tool receipts and authorization. */
internal object GitHubTaskRecovery {
    const val MAX_RECOVERIES = 6
    const val MAX_CONNECTION_RECOVERIES = 1

    fun isConnectionUnavailable(message: String): Boolean = message.contains("LOCAL_SERVICE_UNREACHABLE", true)

    fun recoveryLimit(message: String): Int = if (isConnectionUnavailable(message) ||
        listOf("unable to resolve", "unknownhost", "dns_recovery", "circuit_breaker_open").any { message.contains(it, true) }
    ) {
        MAX_CONNECTION_RECOVERIES
    } else {
        MAX_RECOVERIES
    }
    private val retryAfter = Regex("(?i)retryAfterMs\\s*[=:]\\s*(\\d+)")
    private val blockedStatus = Regex("(?<!\\d)(?:401|403|429)(?!\\d)")

    fun delayMillis(message: String, recoveries: Int, errorCode: String? = null): Long? {
        if (GatewayOutcomeRecovery.requiresReconciliation(message, errorCode)) return null
        val code = errorCode.orEmpty().lowercase()
        if (ProfileFailureRecord.classify(message).category in setOf("ENDPOINT_RETIRED", "BILLING_UNAVAILABLE") || code in setOf("payment_required", "billing_unavailable", "insufficient_credits", "insufficient_quota", "budget_exceeded", "credit_limit_exceeded")) return null
        if (code.startsWith("client_tool_") || code.startsWith("terminal_job_") || code.startsWith("invalid_") || code.startsWith("completion_repair_") || code in setOf("context_capacity_unverified", "reasoning_off_unsupported", "reasoning_on_unsupported", "tool_batch_oversize", "tool_stream_invalid")) return null
        if (Regex("REASONING_(?:ON|OFF)_UNSUPPORTED", RegexOption.IGNORE_CASE).containsMatchIn(message)) return null
        if (recoveries >= recoveryLimit(message)) return null
        val text = message.lowercase()
        if (listOf("policy_restricted", "no endpoints found matching your data policy", "personal_data_", "precheck_rejected", "permission_denied", "permission_revoked", "auth_required", "auth_failed", "invalid_arguments", "unsupported_operation").any { it in text }) return null
        if (blockedStatus.containsMatchIn(text) || listOf("rate limit", "certificate", "handshake", "unauthorized", "context limit", "cancelled by user").any { it in text }) return null
        if (!isRecoverableStreamFailure(message) && listOf("local_service_unreachable", "dns_recovery", "stream was reset", "connection refused").none { it in text }) return null
        val cooldown = retryAfter.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (cooldown > 60_000L) return null
        return maxOf((1000L shl recoveries.coerceAtMost(5)).coerceAtMost(30_000L), cooldown)
    }
}
