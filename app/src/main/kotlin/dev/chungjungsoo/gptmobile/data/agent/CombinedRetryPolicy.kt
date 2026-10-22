package dev.chungjungsoo.gptmobile.data.agent

/** Provider failures may rotate an accepted writer; invariant and permission failures never do. */
internal fun permitsCombinedWriterFallback(status: String, reason: String?): Boolean {
    if (reason.isNullOrBlank()) return false
    if (reason.startsWith("COMBINED_WRITER_STALLED:") && status in setOf("FAILED", "INTERRUPTED")) return true
    if (status != "FAILED") return false
    val value = reason.lowercase()
    if (listOf("identity", "snapshot", "integrity", "permission", "unauthorized", "forbidden", "denied", "authorization", "profile_", "dispatch_policy", "configuration_changed", "budget", "ceiling", "hard limit", "cancel", "no_authorized_writer", "401", "403").any(value::contains)) return false
    return listOf("timeout", "timed out", "connection reset", "connection refused", "network", "rate limit", "429", "500", "502", "503", "504", "service unavailable", "provider unavailable", "stream ended without completion").any(value::contains)
}

internal fun CombinedBudgetSnapshot.permitsFinalizationAttempt(nowMillis: Long = System.currentTimeMillis()): Boolean {
    if (closed || cancelled || plan.hardLimits.deadlineEpochMillis?.let { nowMillis >= it } == true) return false
    val usage = charged
    val limits = plan.hardLimits
    return (limits.inputTokens == null || usage.inputTokens < limits.inputTokens) &&
        (limits.generatedTokens == null || usage.generatedTokens < limits.generatedTokens) &&
        (limits.totalTokens == null || usage.inputTokens + usage.generatedTokens < limits.totalTokens) &&
        (limits.costMicros == null || costMicros?.let { it < limits.costMicros } == true)
}
