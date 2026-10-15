package dev.chungjungsoo.gptmobile.data.network

/** Cooldowns are credential scoped. Recovery admits one request at a time instead of a synchronized retry burst. */
internal class ProviderThrottleRegistry(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val jitterMillis: () -> Long = { kotlin.random.Random.nextLong(0, 251) }
) {
    private data class State(val blockedUntil: Long, val recoveryUntil: Long, var nextPermit: Long)
    private val states = linkedMapOf<String, State>()

    @Synchronized
    fun defer(identity: String, delayMillis: Long) {
        val now = nowMillis()
        states.entries.removeIf { it.value.recoveryUntil <= now }
        check(identity in states || states.size < 512) { "PROVIDER_THROTTLE_CAPACITY: wait for existing provider cooldowns to expire." }
        val blockedUntil = maxOf(states[identity]?.blockedUntil ?: now, now + delayMillis.coerceIn(0L, 86_400_000L))
        states[identity] = State(blockedUntil, blockedUntil + 30_000L, maxOf(blockedUntil, states[identity]?.nextPermit ?: now))
    }

    /** Zero admits this attempt atomically; a positive value is a cancellable wait, not a reserved dispatch. */
    @Synchronized
    fun remainingMillis(identity: String): Long {
        val state = states[identity] ?: return 0
        val now = nowMillis()
        if (now >= state.recoveryUntil) {
            states.remove(identity)
            return 0
        }
        val remaining = maxOf(state.blockedUntil, state.nextPermit) - now
        if (remaining > 0) return remaining
        state.nextPermit = now + 1000L + jitterMillis().coerceIn(0L, 250L)
        return 0
    }
}
