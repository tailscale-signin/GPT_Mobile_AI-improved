package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** Persisting/starting a synthesis returns before Room publishes its new run ID. */
internal class CombinedSynthesisGate(private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Reservation(
        val sources: List<CombinedModelResponse>,
        // Null means already admitted/committed; only explicitly abandoned
        // pre-commit attempts are ever eligible to retry.
        val retryAfterMillis: Long? = null
    )

    private val attempts = mutableMapOf<Int, Reservation>()

    fun reserve(userId: Int, sources: List<CombinedModelResponse>): Boolean {
        if (userId <= 0) return false
        val previous = attempts[userId]
        if (previous?.sources == sources) {
            val retryAt = previous.retryAfterMillis ?: return false
            if (nowMillis() < retryAt) return false
        }
        attempts[userId] = Reservation(sources.toList())
        return true
    }

    /** Retain bounded backoff for matching *pre-commit* failures only. */
    fun abandonUncommitted(userId: Int, sources: List<CombinedModelResponse>): Boolean {
        val previous = attempts[userId] ?: return false
        if (previous.sources != sources) return false
        val now = nowMillis()
        val retryAt = if (now > Long.MAX_VALUE - RETRY_DELAY_MS) Long.MAX_VALUE else now + RETRY_DELAY_MS
        attempts[userId] = Reservation(sources.toList(), retryAt)
        return true
    }

    fun reset(userId: Int) {
        attempts.remove(userId)
    }

    private companion object {
        const val RETRY_DELAY_MS = 5_000L
    }
}

internal fun persistedTurnIndex(messages: List<MessageV2>, optimistic: MessageV2, persisted: MessageV2): Int? =
    messages.indexOfFirst { it.id > 0 && it.id == persisted.id }.takeIf { it >= 0 }
        ?: messages.indexOfFirst { it == optimistic }.takeIf { it >= 0 }
