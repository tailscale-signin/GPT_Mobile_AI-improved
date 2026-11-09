package dev.chungjungsoo.gptmobile.data.agent

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Bounded recovery of the current model round, retaining tool receipts and authorization. */
internal object GitHubTaskRecovery {
    const val MAX_RECOVERIES = 6
    const val MAX_CONNECTION_RECOVERIES = 1
    private val connectionCodes = setOf("CONNECTION_ABORTED", "DNS_RESOLUTION_FAILED", "NETWORK_DISCONNECTED", "NETWORK_TIMEOUT", "TRANSPORT_EOF", "STREAM_INTERRUPTED", "INCOMPLETE_PROVIDER_STREAM", "LOCAL_SERVICE_UNREACHABLE")

    fun isConnectionUnavailable(failure: ProviderEvent.Failed): Boolean = failure.errorCode?.uppercase() in connectionCodes || isConnectionUnavailable(failure.message)

    /** Permission to continue saved work is independent from permission to repeat its HTTP request. */
    fun delayMillis(failure: ProviderEvent.Failed, recoveries: Int, hasUnknownOutcome: Boolean = false): Long? {
        if (hasUnknownOutcome ||
            failure.providerLimit != null ||
            failure.failureDetails?.get("logical_continuation_allowed") == JsonPrimitive(false) ||
            failure.taskOutcome in setOf("BLOCKED", "CONFIGURATION_INVALIDATED", "WAITING_FOR_APPROVAL") ||
            failure.errorCode == "WAITING_FOR_APPROVAL" ||
            failure.failureDetails?.get("requires_reconciliation") == JsonPrimitive(true)
        ) {
            return null
        }
        if (GatewayOutcomeRecovery.requiresReconciliation(failure.message, failure.errorCode) || failure.errorCode in ToolResultEnvelope.unknownOutcomeCodes) return null
        val code = failure.errorCode?.uppercase()
        // A structured rejection wins over transport words in its display text.
        // Only older events without a specific category use the legacy predicate.
        if (code !in connectionCodes) {
            if (code != null && code !in setOf("CONNECTION", "NETWORK", "PROVIDER_INVOCATION_FAILED", "UNCLASSIFIED_PROVIDER_FAILURE")) return null
            return delayMillis(failure.message, recoveries, failure.errorCode)
        }
        if (recoveries >= MAX_CONNECTION_RECOVERIES) return null
        val cooldown = (failure.failureDetails?.get("retry_after_ms") as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: retryAfter.find(failure.message)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (cooldown !in 0..60_000L) return null
        return maxOf(1000L, cooldown)
    }

    fun recoveryLimit(failure: ProviderEvent.Failed): Int = if (isConnectionUnavailable(failure)) MAX_CONNECTION_RECOVERIES else recoveryLimit(failure.message)

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
