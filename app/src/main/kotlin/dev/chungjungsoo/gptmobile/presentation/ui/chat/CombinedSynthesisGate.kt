package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** Persisting/starting a synthesis returns before Room publishes its new run ID. */
internal class CombinedSynthesisGate {
    private val attempts = mutableMapOf<Int, List<CombinedModelResponse>>()

    fun reserve(userId: Int, sources: List<CombinedModelResponse>): Boolean {
        if (attempts[userId] == sources) return false
        attempts[userId] = sources.toList()
        return true
    }

    fun reset(userId: Int) {
        attempts.remove(userId)
    }
}

internal fun persistedTurnIndex(messages: List<MessageV2>, optimistic: MessageV2, persisted: MessageV2): Int? =
    messages.indexOfFirst { it.id > 0 && it.id == persisted.id }.takeIf { it >= 0 }
        ?: messages.indexOfFirst { it == optimistic }.takeIf { it >= 0 }
