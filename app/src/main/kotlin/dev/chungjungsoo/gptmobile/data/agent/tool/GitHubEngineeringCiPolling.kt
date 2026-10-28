package dev.chungjungsoo.gptmobile.data.agent.tool

internal data class EngineeringCiPollingState(
    val attempt: Int = 0,
    val startedAtEpochMs: Long
)

internal sealed interface EngineeringCiPollingDecision {
    data object Complete : EngineeringCiPollingDecision
    data object StopFailed : EngineeringCiPollingDecision
    data object TimedOut : EngineeringCiPollingDecision
    data class RetryAfter(val delayMs: Long, val nextAttempt: Int) : EngineeringCiPollingDecision
}

/** Pure scheduler policy. A WorkManager adapter can persist and execute these decisions. */
internal object GitHubEngineeringCiPolling {
    private const val MAX_ATTEMPTS = 48
    private const val MAX_AGE_MS = 24L * 60L * 60L * 1000L

    fun next(
        verdict: EngineeringCiVerdict,
        state: EngineeringCiPollingState,
        nowEpochMs: Long
    ): EngineeringCiPollingDecision {
        if (verdict == EngineeringCiVerdict.PASSED) return EngineeringCiPollingDecision.Complete
        if (verdict == EngineeringCiVerdict.FAILED) return EngineeringCiPollingDecision.StopFailed
        if (state.attempt >= MAX_ATTEMPTS ||
            nowEpochMs < state.startedAtEpochMs ||
            nowEpochMs - state.startedAtEpochMs >= MAX_AGE_MS
        ) {
            return EngineeringCiPollingDecision.TimedOut
        }
        val delay = (30_000L * (1L shl minOf(state.attempt, 5))).coerceAtMost(15L * 60L * 1000L)
        return EngineeringCiPollingDecision.RetryAfter(delay, state.attempt + 1)
    }
}
