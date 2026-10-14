package dev.chungjungsoo.gptmobile.data.assist

/** Shared physical local-generation allowance across evidence wrappers and typed research stages. */
internal object AssistTurnBudget {
    // A global 128-turn limit previously disabled every new helper after 128
    // independent requests for an entire day. This is a bounded *history*,
    // never a global dispatch allowance or a new permission gate.
    private const val MAX_RETAINED_TURNS = 4096
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
        if (previous == null && attempts.size >= MAX_RETAINED_TURNS) {
            // Never deny a new authorized turn because past, unrelated turns
            // filled process memory. Evict the oldest accounting-only entry.
            attempts.remove(attempts.keys.first())
        }
        attempts[turnId] = (previous?.first ?: nowMillis()) to ((previous?.second ?: 0) + 1)
        return true
    }
}
