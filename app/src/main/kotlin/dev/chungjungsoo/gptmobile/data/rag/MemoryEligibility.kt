package dev.chungjungsoo.gptmobile.data.rag

/** Shared record gates for preview, dispatch, revalidation, and synthetic evaluation. Scope is resolved by the caller. */
internal object MemoryEligibility {
    fun accepts(fact: VaultFact, settings: FactVaultSettings, chatId: Int, messageId: Int, isLocal: Boolean, includeLocalOnly: Boolean, now: Long, history: Boolean = false): Boolean {
        val cutoff = if (settings.retentionDays > 0) now - settings.retentionDays * 86_400_000L else 0L
        return settings.recallEnabled &&
            (isLocal || settings.allowCloudRecall) &&
            (fact.enabled || history && fact.supersededBy != null) &&
            !fact.awaitingReview &&
            (history || fact.supersededBy == null) &&
            (!fact.localOnly || isLocal && includeLocalOnly) &&
            (fact.validFromMillis == null || fact.validFromMillis <= now) &&
            (fact.validUntilMillis == null || fact.validUntilMillis > now) &&
            (!settings.sameChatOnly || fact.sourceChatId == chatId) &&
            !(messageId > 0 && fact.sourceChatId == chatId && fact.sourceMessageId == messageId) &&
            (fact.source == "manual" || fact.pinned || fact.savedAtMillis == 0L || maxOf(fact.savedAtMillis, fact.lastSeenMillis) >= cutoff)
    }
}
