package dev.chungjungsoo.gptmobile.data.agent.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubEngineeringCiPollingTest {
    private val state = EngineeringCiPollingState(startedAtEpochMs = 1_000L)

    @Test
    fun passingStopsPolling() {
        assertEquals(
            EngineeringCiPollingDecision.Complete,
            GitHubEngineeringCiPolling.next(EngineeringCiVerdict.PASSED, state, 2_000L),
        )
    }

    @Test
    fun failedStopsPolling() {
        assertEquals(
            EngineeringCiPollingDecision.StopFailed,
            GitHubEngineeringCiPolling.next(EngineeringCiVerdict.FAILED, state, 2_000L),
        )
    }

    @Test
    fun pendingSchedulesRetry() {
        assertEquals(
            EngineeringCiPollingDecision.RetryAfter(30_000L, 1),
            GitHubEngineeringCiPolling.next(EngineeringCiVerdict.PENDING, state, 2_000L),
        )
    }

    @Test
    fun oldPendingTimesOut() {
        assertEquals(
            EngineeringCiPollingDecision.TimedOut,
            GitHubEngineeringCiPolling.next(EngineeringCiVerdict.PENDING, state, 86_401_000L),
        )
    }

    @Test
    fun retryDelayIsCapped() {
        val result = GitHubEngineeringCiPolling.next(
            EngineeringCiVerdict.PENDING,
            state.copy(attempt = 20),
            2_000L,
        )
        assertTrue(result is EngineeringCiPollingDecision.RetryAfter)
        assertEquals(900_000L, (result as EngineeringCiPollingDecision.RetryAfter).delayMs)
    }
}
