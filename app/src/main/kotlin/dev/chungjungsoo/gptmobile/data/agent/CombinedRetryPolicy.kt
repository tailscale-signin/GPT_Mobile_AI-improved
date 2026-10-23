package dev.chungjungsoo.gptmobile.data.agent

/** Provider failures may rotate an accepted writer; invariant and permission failures never do. */
internal fun permitsCombinedWriterFallback(status: String, reason: String?): Boolean {
    if (status !in setOf("FAILED", "INTERRUPTED")) return false
    // Compatibility for saved runs predating typed timeline receipts. New runs use the receipt.
    return CombinedRecoveryFailure.fromCode(reason?.substringBefore(':')).rotateWriter
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
