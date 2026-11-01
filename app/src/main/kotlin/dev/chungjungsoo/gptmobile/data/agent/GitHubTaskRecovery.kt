package dev.chungjungsoo.gptmobile.data.agent

/** Bounded recovery of the current model round, retaining tool receipts and authorization. */
internal object GitHubTaskRecovery {
    const val MAX_RECOVERIES = 6
    private val retryAfter = Regex("(?i)retryAfterMs\\s*[=:]\\s*(\\d+)")
    private val blockedStatus = Regex("(?<!\\d)(?:401|403|429)(?!\\d)")

    fun delayMillis(message: String, recoveries: Int): Long? {
        if (recoveries >= MAX_RECOVERIES) return null
        val text = message.lowercase()
        if (listOf("personal_data_", "precheck_rejected", "permission_denied", "permission_revoked", "auth_required", "auth_failed", "invalid_arguments", "unsupported_operation").any { it in text }) return null
        if (blockedStatus.containsMatchIn(text) || listOf("rate limit", "certificate", "handshake", "unauthorized", "context limit", "cancelled by user").any { it in text }) return null
        if (!isRecoverableStreamFailure(message) && listOf("local_service_unreachable", "dns_recovery", "stream was reset", "connection refused").none { it in text }) return null
        val cooldown = retryAfter.find(message)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (cooldown > 60_000L) return null
        return maxOf((1000L shl recoveries.coerceAtMost(5)).coerceAtMost(30_000L), cooldown)
    }
}
