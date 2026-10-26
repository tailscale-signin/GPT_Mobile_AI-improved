package dev.chungjungsoo.gptmobile.research.reliability

/**
 * Pure state transition logic for Research Mode.
 * Persistence and provider transport are deliberately supplied by the caller.
 */
object ResearchRecovery {
    fun onProviderResult(
        checkpoint: ResearchStageCheckpoint,
        outcome: ProviderOutcome,
        nowEpochMillis: Long,
        retryAfterMillis: Long? = null,
        maxAttempts: Int = 3
    ): ResearchStageCheckpoint {
        require(maxAttempts > 0)
        require(nowEpochMillis >= 0)
        val nextAttempt = checkpoint.attempt + 1
        val status = when {
            outcome == ProviderOutcome.SUCCESS_VISIBLE -> ResearchStageStatus.COMPLETED
            outcome == ProviderOutcome.INTERRUPTED -> ResearchStageStatus.RETRYABLE_FAILED
            outcome == ProviderOutcome.RATE_LIMITED ||
                outcome == ProviderOutcome.ALLOWANCE_EXHAUSTED -> ResearchStageStatus.WAITING_PROVIDER_LIMIT
            outcome == ProviderOutcome.INVALID_REQUEST -> ResearchStageStatus.TERMINAL_FAILED
            nextAttempt >= maxAttempts -> ResearchStageStatus.TERMINAL_FAILED
            else -> ResearchStageStatus.RETRYABLE_FAILED
        }
        val retryAt = if (status == ResearchStageStatus.WAITING_PROVIDER_LIMIT) {
            val delay = (retryAfterMillis ?: DEFAULT_COOLDOWN_MILLIS).coerceAtLeast(0)
            if (Long.MAX_VALUE - nowEpochMillis < delay) Long.MAX_VALUE
            else nowEpochMillis + delay
        } else null
        return checkpoint.copy(status = status, attempt = nextAttempt, retryAtEpochMillis = retryAt)
    }

    const val DEFAULT_COOLDOWN_MILLIS: Long = 60_000L
}

/** Deduplicates source identity while keeping accepted evidence on later failed attempts. */
object ResearchEvidenceLedger {
    private val order = EvidenceStatus.entries.withIndex().associate { it.value to it.index }

    fun merge(
        existing: Collection<ResearchEvidence>,
        incoming: Collection<ResearchEvidence>
    ): List<ResearchEvidence> {
        val merged = linkedMapOf<String, ResearchEvidence>()
        (existing + incoming).forEach { candidate ->
            val old = merged[candidate.sourceId]
            val replace = old == null ||
                (old.status != EvidenceStatus.ACCEPTED &&
                    candidate.status == EvidenceStatus.ACCEPTED) ||
                (old.status != EvidenceStatus.ACCEPTED &&
                    candidate.status != EvidenceStatus.EXCLUDED &&
                    (order[candidate.status] ?: 0) > (order[old.status] ?: 0))
            if (replace) merged[candidate.sourceId] = candidate
        }
        return merged.values.toList()
    }
}
