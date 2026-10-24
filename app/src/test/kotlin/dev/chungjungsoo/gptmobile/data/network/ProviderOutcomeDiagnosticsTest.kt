package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.free.FreeCatalogState
import dev.chungjungsoo.gptmobile.data.free.FreeGenerationReadiness
import dev.chungjungsoo.gptmobile.data.free.FreeRequestDiagnostics
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderOutcomeDiagnosticsTest {
    @Test fun reasoningOnlyLengthTerminationIsAnErrorRatherThanPartialAnswer() {
        val result = requireNotNull(freeResponseOutcome(FreeAiProvider.BLOCKRUN, false, false, false, true, true))
        assertEquals("reasoning_budget_exhausted", result.error?.type)
        assertNull(result.applicationNotice)
        assertEquals("no_visible_answer", freeResponseOutcome(FreeAiProvider.KILO, false, false, false, false)?.error?.type)
        assertNull(freeResponseOutcome(FreeAiProvider.KILO, true, false, false, true)?.error)
        assertNull(freeResponseOutcome(FreeAiProvider.KILO, false, true, false, true, true))
    }

    @Test fun attemptedDispatchDoesNotProveTransmissionAndDnsHasItsOwnCategory() {
        val state = FreeRequestDiagnostics()
        state.attempted()
        assertTrue(state.attemptedDispatch)
        assertNull(state.requestSent)
        state.dnsFailed("example.invalid")
        assertEquals(false, state.requestSent)
        assertFalse(state.responseReceived)
        assertEquals(FreeFailureCategory.DNS_RESOLUTION_FAILED, freeFailureCategory(java.net.UnknownHostException()))
        state.responded()
        assertEquals(true, state.requestSent)
        assertTrue(state.responseReceived)
        state.attempted()
        assertNull(state.requestSent)
        assertFalse(state.responseReceived)
        assertEquals(FreeFailureCategory.DNS_RESOLUTION_FAILED, freeFailureCategory(java.io.IOException("Dispatch failed", java.net.UnknownHostException())))
    }

    @Test fun simultaneousDnsFailuresShareAnEpisodeUntilNetworkChangesOrBurstEnds() {
        var now = 1_000L
        val episodes = NetworkFailureEpisodes { now }
        episodes.networkChanged("network-1", "VALIDATED")
        val first = episodes.dnsFailure("one.invalid")
        now += 40
        val second = episodes.dnsFailure("two.invalid")
        assertEquals(first.episodeId, second.episodeId)
        assertEquals(2, second.distinctHosts)
        assertNull(episodes.current().episodeId)
        now += 2_001
        assertNotEquals(first.episodeId, episodes.dnsFailure("three.invalid").episodeId)
        episodes.networkChanged("network-2", "UNVALIDATED")
        val changed = episodes.dnsFailure("one.invalid")
        assertNotEquals(first.changeId, changed.changeId)
        assertEquals(1, changed.distinctHosts)
    }

    @Test fun networkChangeClearsReachabilityWithoutClearingProviderRateLimits() {
        try {
            FreeCatalogState.recordFailure(FreeAiProvider.KILO, "DNS", FreeGenerationReadiness.UNREACHABLE, false)
            FreeCatalogState.recordFailure(FreeAiProvider.CEHPOINT, "429", FreeGenerationReadiness.RATE_LIMITED, true, 99_999)
            FreeCatalogState.networkChanged()
            assertEquals(FreeGenerationReadiness.UNTESTED, FreeCatalogState.status.value[FreeAiProvider.KILO]?.readiness)
            assertNull(FreeCatalogState.status.value[FreeAiProvider.KILO]?.reachable)
            assertEquals(FreeGenerationReadiness.RATE_LIMITED, FreeCatalogState.status.value[FreeAiProvider.CEHPOINT]?.readiness)
            assertEquals(99_999L, FreeCatalogState.status.value[FreeAiProvider.CEHPOINT]?.retryAt)
        } finally {
            FreeCatalogState.resetForTest()
        }
    }
}
