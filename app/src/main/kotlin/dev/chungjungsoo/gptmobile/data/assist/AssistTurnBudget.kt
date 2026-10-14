package dev.chungjungsoo.gptmobile.data.assist

/** Shared physical local-generation allowance across evidence wrappers and typed research stages. */
internal object AssistTurnBudget {
    private val attempts = linkedMapOf<String, Pair<Long, Int>>()

    @Synchronized fun available(turnId: String, limit: Int): Boolean {
        val now = System.nanoTime() / 1_000_000
        attempts.entries.removeAll { now - it.value.first > 86_400_000 }
        return (attempts[turnId]?.second ?: 0) < limit && (turnId in attempts || attempts.size < 128)
    }

    @Synchronized fun reserve(turnId: String, limit: Int): Boolean {
        if (!available(turnId, limit)) return false
        val prior = attempts[turnId]
        attempts[turnId] = (prior?.first ?: (System.nanoTime() / 1_000_000)) to ((prior?.second ?: 0) + 1)
        return true
    }
}
