package dev.chungjungsoo.gptmobile.data.network

/** Cooldowns are credential scoped. Recovery admits one request at a time instead of a synchronized retry burst. */
internal class ProviderThrottleRegistry(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val jitterMillis: () -> Long = { kotlin.random.Random.nextLong(0, 251) }
) {
    private data class State(val blockedUntil: Long, val recoveryUntil: Long, var nextPermit: Long, val authoritativeReset: Boolean)
    private val states = linkedMapOf<String, State>()
    private data class Rejection(val count: Int, val rememberUntil: Long)
    private val rejections = linkedMapOf<String, Rejection>()
    private val activeProbes = mutableSetOf<String>()

    data class Admission(val waitMillis: Long = 0, val ownsProbe: Boolean = false, val rejectionCount: Int = 0)

    @Synchronized
    fun admit(identity: String): Admission {
        val now = nowMillis()
        val count = rejections[identity]?.takeIf { it.rememberUntil > now }?.count ?: 0
        val state = states[identity]
        if (count == 0 && state?.authoritativeReset != true) return Admission()
        val remaining = state?.let { ProviderRetryPolicy.remainingMillis(it.blockedUntil, now) } ?: 0L
        if (remaining > 0) return Admission(waitMillis = remaining, rejectionCount = count)
        if (identity in activeProbes) return Admission(waitMillis = 1_000, rejectionCount = count)
        if (state == null || state.recoveryUntil <= now) return Admission()
        activeProbes += identity
        return Admission(ownsProbe = true, rejectionCount = count)
    }

    @Synchronized
    fun releaseProbe(identity: String) {
        activeProbes.remove(identity)
    }

    @Synchronized
    fun accepted(identity: String) {
        val state = states[identity] ?: return
        if (state.blockedUntil <= nowMillis()) {
            states.remove(identity)
            rejections.remove(identity)
        }
    }

    @Synchronized
    fun reject(identity: String, serverWaitMillis: Long?): Pair<Long, Int> {
        val now = nowMillis()
        rejections.entries.removeIf { it.value.rememberUntil <= now }
        val count = ((rejections[identity]?.count ?: 0) + 1).coerceAtMost(9)
        val wait = ProviderRetryPolicy.deadlineMillis(
            maxOf(1_000L, serverWaitMillis ?: minOf(600_000L, 30_000L shl minOf(5, count - 1))),
            jitterMillis().coerceIn(0, 250)
        )
        defer(identity, wait)
        rejections[identity] = Rejection(count, ProviderRetryPolicy.deadlineMillis(states.getValue(identity).blockedUntil, 1_800_000L))
        return ProviderRetryPolicy.remainingMillis(states.getValue(identity).blockedUntil, now) to count
    }

    @Synchronized
    fun restore(identity: String, remainingMillis: Long, count: Int) {
        defer(identity, remainingMillis, authoritativeReset = true)
        if (count < 1) return
        val now = nowMillis()
        val rememberUntil = ProviderRetryPolicy.deadlineMillis(ProviderRetryPolicy.deadlineMillis(now, remainingMillis), 1_800_000L)
        val previous = rejections[identity]
        rejections[identity] = Rejection(maxOf(previous?.count ?: 0, count), maxOf(previous?.rememberUntil ?: 0L, rememberUntil))
    }

    @Synchronized
    fun blockedMillis(identity: String): Long =
        states[identity]?.let { ProviderRetryPolicy.remainingMillis(it.blockedUntil, nowMillis()) } ?: 0

    @Synchronized
    fun rejectionCount(identity: String): Int = rejections[identity]?.count ?: 0

    @Synchronized
    fun defer(identity: String, delayMillis: Long, authoritativeReset: Boolean = false) {
        val now = nowMillis()
        states.entries.removeIf { it.value.recoveryUntil <= now }
        check(identity in states || states.size < 512) { "PROVIDER_THROTTLE_CAPACITY: wait for existing provider cooldowns to expire." }
        val blockedUntil = maxOf(states[identity]?.blockedUntil ?: now, ProviderRetryPolicy.deadlineMillis(now, delayMillis))
        val prior = states[identity]
        states[identity] = State(blockedUntil, ProviderRetryPolicy.deadlineMillis(blockedUntil, 30_000L), maxOf(blockedUntil, prior?.nextPermit ?: now), authoritativeReset || prior?.authoritativeReset == true)
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
        val remaining = ProviderRetryPolicy.remainingMillis(maxOf(state.blockedUntil, state.nextPermit), now)
        if (remaining > 0) return remaining
        state.nextPermit = ProviderRetryPolicy.deadlineMillis(now, 1000L + jitterMillis().coerceIn(0L, 250L))
        return 0
    }
}
