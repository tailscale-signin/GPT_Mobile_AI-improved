package dev.chungjungsoo.gptmobile.data.assist

/** Shared physical local-generation allowance across evidence wrappers and typed research stages. */
internal object AssistTurnBudget {
    // A global 128-turn limit previously denied every new helper after 128
    // independent requests. Never evict an unexpired attempt count: doing so
    // would allow an old turn to exceed its shared physical dispatch allowance.
    // Retain entries for 24 hours, then expire them by monotonic first-attempt time.
    private const val RETENTION_MILLIS = 86_400_000L
    private val attempts = linkedMapOf<String, Pair<Long, Int>>()

    private fun nowMillis(): Long = System.nanoTime() / 1_000_000

    private fun expireOldTurns(now: Long) {
        // Insertion order is the first physical attempt. Stop once the oldest
        // remaining turn is unexpired; existing turn timestamps are immutable.
        val entries = attempts.entries.iterator()
        while (entries.hasNext()) {
            if (now - entries.next().value.first < RETENTION_MILLIS) break
            entries.remove()
        }
    }

    @Synchronized fun available(turnId: String, limit: Int): Boolean {
        expireOldTurns(nowMillis())
        return (attempts[turnId]?.second ?: 0) < limit
    }

    @Synchronized fun reserve(turnId: String, limit: Int): Boolean {
        if (!available(turnId, limit)) return false
        val previous = attempts[turnId]
        attempts[turnId] = (previous?.first ?: nowMillis()) to ((previous?.second ?: 0) + 1)
        return true
    }
}
