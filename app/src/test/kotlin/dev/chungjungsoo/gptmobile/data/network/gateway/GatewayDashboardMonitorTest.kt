package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GatewayDashboardMonitorTest {
    private val selected = PlatformV2(uid = "selected", name = "Selected local profile", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.2:8090/v1")
    private fun target(profile: PlatformV2 = selected) = requireNotNull(gatewayDashboardTarget(profile.uid, listOf(profile), null))
    private fun source(read: suspend (ProviderRequestConfig) -> GatewayDashboardObservation?) = object : GatewayDashboardSource {
        override suspend fun read(config: ProviderRequestConfig) = read.invoke(config)
    }

    @Test fun offlineRefreshesNeverDispatchAndReturningRouteRequiresAReadinessObservation() = runTest {
        val route = MutableStateFlow(false)
        val events = mutableListOf<String>()
        val phases = mutableListOf<GatewayDashboardPhase>()
        var state = GatewayDashboardState()
        var failingProbe = true
        val monitor = GatewayDashboardMonitor(
            object : GatewayDashboardSource {
                override suspend fun readiness(config: ProviderRequestConfig, model: String?): GatewayReadinessObservation? {
                    events += "ready"
                    if (failingProbe) throw java.net.ConnectException("refused")
                    return GatewayReadinessObservation("a".repeat(32), true, true, true, false, testScheduler.currentTime)
                }
                override suspend fun read(config: ProviderRequestConfig): GatewayDashboardObservation {
                    events += "dashboard"
                    return GatewayDashboardObservation(parseGatewayDashboard(dashboardFixture()), testScheduler.currentTime)
                }
            },
            route
        )
        backgroundScope.launch {
            monitor.observe(target(), state) {
                state = it
                phases += it.phase
            }
        }
        runCurrent()
        repeat(40) { monitor.refresh() }
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(GatewayDashboardPhase.OFFLINE, state.phase)
        assertTrue(events.isEmpty())
        route.value = true
        runCurrent()
        assertEquals(listOf("ready"), events)
        assertTrue(GatewayDashboardPhase.RECONNECTING in phases)
        assertEquals(GatewayDashboardPhase.UNAVAILABLE, state.phase)
        failingProbe = false
        advanceTimeBy(15_001)
        runCurrent()
        assertEquals(listOf("ready", "ready", "dashboard"), events)
        assertEquals(GatewayDashboardPhase.LIVE, state.phase)
        assertEquals(true, state.readiness?.ready)
    }

    @Test fun routeLossCancelsAnInflightReadAndRetainsSavedJobsWithoutFurtherPolling() = runTest {
        val route = MutableStateFlow(true)
        val saved = GatewayDashboardObservation(parseGatewayDashboard(dashboardFixture()), 0)
        var state = GatewayDashboardState(target(), GatewayDashboardPhase.LIVE, saved)
        var calls = 0
        var cancelled = false
        val monitor = GatewayDashboardMonitor(
            source {
                calls++
                try {
                    awaitCancellation()
                } finally {
                    cancelled = true
                }
            },
            route
        )
        backgroundScope.launch { monitor.observe(target(), state) { state = it } }
        runCurrent()
        route.value = false
        runCurrent()
        repeat(20) { monitor.refresh() }
        advanceTimeBy(3_600_000)
        runCurrent()
        assertTrue(cancelled)
        assertEquals(1, calls)
        assertEquals(GatewayDashboardPhase.OFFLINE, state.phase)
        assertEquals(saved, state.observation)
        assertNull(state.openJobs(testScheduler.currentTime))
    }

    @Test fun liveDashboardDoesNotTurnAnUnavailableSelectedModelIntoInferenceReadiness() = runTest {
        var state = GatewayDashboardState()
        val monitor = GatewayDashboardMonitor(object : GatewayDashboardSource {
            override suspend fun readiness(config: ProviderRequestConfig, model: String?) = GatewayReadinessObservation("a".repeat(32), false, true, false, false, 0)
            override suspend fun read(config: ProviderRequestConfig) = GatewayDashboardObservation(parseGatewayDashboard(dashboardFixture()), 0)
        })
        backgroundScope.launch { monitor.observe(target(), state) { state = it } }
        runCurrent()
        assertEquals(GatewayDashboardPhase.DEGRADED, state.phase)
        assertEquals(false, state.readiness?.ready)
        assertEquals(false, state.readiness?.selectedModelAvailable)
        assertTrue(state.message.orEmpty().contains("selected model is unavailable"))
        assertEquals(3L, state.openJobs(0))
    }

    @Test fun accessDenialWaitsForExplicitRefreshAndNeverPollsUnchangedCredentials() = runTest {
        var reads = 0
        var state = GatewayDashboardState()
        val monitor = GatewayDashboardMonitor(
            source {
                reads++
                throw GatewayDashboardAccessDenied()
            }
        )
        backgroundScope.launch { monitor.observe(target(), state) { state = it } }
        runCurrent()
        advanceTimeBy(3_600_000)
        runCurrent()
        assertEquals(1, reads)
        assertEquals(GatewayDashboardPhase.DENIED, state.phase)
        monitor.refresh()
        runCurrent()
        assertEquals(2, reads)
    }

    @Test fun unavailableSelectionNeverFallsBackToAnotherEnabledProfile() {
        val other = selected.copy(uid = "other")
        assertNull(gatewayDashboardTarget(selected.uid, listOf(selected.copy(enabled = false), other), null))
        assertNull(gatewayDashboardTarget("missing", listOf(other), null))
        val unavailable = requireNotNull(gatewayDashboardTarget(selected.uid, listOf(selected, other), "missing"))
        assertNull(unavailable.config)
        assertTrue(unavailable.explicit)
    }

    @Test fun explicitGatewayUsesItsOwnCredentialsWithoutChangingTheSelectedAiProfile() {
        val gateway = selected.copy(uid = "gateway", apiUrl = "http://gateway.local:9000/v1", token = "gateway-token")
        val chosen = requireNotNull(gatewayDashboardTarget(selected.uid, listOf(selected, gateway), gateway.uid))
        assertEquals(selected.uid, chosen.selectedProfileUid)
        assertEquals(gateway.uid, chosen.config?.profileUid)
        assertEquals(gateway.token, chosen.config?.token)
        assertEquals(gateway.apiUrl, chosen.config?.apiUrl)
        assertNull(gatewayDashboardTarget("cloud", listOf(selected.copy(uid = "cloud", compatibleType = ClientType.OPENAI, apiUrl = "https://api.provider.example/v1")), null))
    }

    @Test fun failedRefreshKeepsSavedMeasurementsButStopsClaimingLiveJobsAndDevices() = runTest {
        var requests = 0
        var state = GatewayDashboardState()
        val monitor = GatewayDashboardMonitor(
            source { config ->
                assertEquals(selected.uid, config.profileUid)
                if (requests++ > 0) error("offline")
                GatewayDashboardObservation(parseGatewayDashboard(dashboardFixture()), testScheduler.currentTime)
            }
        )
        backgroundScope.launch { monitor.observe(target(), state) { state = it } }
        runCurrent()
        assertEquals(3L, state.openJobs(testScheduler.currentTime))
        assertEquals(1, state.recentDevices(testScheduler.currentTime))
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(GatewayDashboardPhase.STALE, state.phase)
        assertNotNull(state.observation)
        assertNull(state.openJobs(testScheduler.currentTime))
        assertNull(state.recentDevices(testScheduler.currentTime))
        assertEquals(12345L, state.observation?.snapshot?.outputTokens)
    }

    @Test fun revokedAuthorizationClearsPreviouslyVisibleScopedData() = runTest {
        var requests = 0
        var state = GatewayDashboardState()
        val monitor = GatewayDashboardMonitor(
            source {
                if (requests++ > 0) throw GatewayDashboardAccessDenied()
                GatewayDashboardObservation(parseGatewayDashboard(dashboardFixture()), testScheduler.currentTime)
            }
        )
        backgroundScope.launch { monitor.observe(target(), state) { state = it } }
        runCurrent()
        advanceTimeBy(5001)
        runCurrent()
        assertEquals(GatewayDashboardPhase.DENIED, state.phase)
        assertNull(state.observation)
    }

    @Test fun leavingTheScreenCancelsInflightHttpAndFuturePolling() = runTest {
        var requests = 0
        val cancelled = CompletableDeferred<Unit>()
        val monitor = GatewayDashboardMonitor(
            source {
                requests++
                try {
                    awaitCancellation()
                } finally {
                    cancelled.complete(Unit)
                }
            }
        )
        val screen = backgroundScope.launch { monitor.observe(target(), GatewayDashboardState()) {} }
        runCurrent()
        screen.cancelAndJoin()
        assertTrue(cancelled.isCompleted)
        advanceTimeBy(180_000)
        runCurrent()
        assertEquals(1, requests)
    }

    @Test fun credentialChangesDiscardOldMeasurementsBeforeTheNewRequestCompletes() = runTest {
        val previous = GatewayDashboardState(target(), GatewayDashboardPhase.LIVE, GatewayDashboardObservation(parseGatewayDashboard(dashboardFixture()), 0))
        val changed = target(selected.copy(token = "new-account"))
        var state = previous
        val monitor = GatewayDashboardMonitor(source { awaitCancellation() })
        backgroundScope.launch { monitor.observe(changed, previous) { state = it } }
        runCurrent()
        assertEquals(changed.identity, state.target?.identity)
        assertEquals(GatewayDashboardPhase.CONNECTING, state.phase)
        assertNull(state.observation)
    }

    @Test fun missingDeviceStatesAndAgedSnapshotsNeverClaimZeroRecentDevices() {
        val snapshot = parseGatewayDashboard(dashboardFixture())
        val unknown = snapshot.copy(devices = snapshot.devices?.map { it.copy(state = "") })
        assertNull(GatewayDashboardState(target(), GatewayDashboardPhase.LIVE, GatewayDashboardObservation(unknown, 0)).recentDevices(0))
        val aged = GatewayDashboardState(target(), GatewayDashboardPhase.LIVE, GatewayDashboardObservation(snapshot, 0))
        assertNull(aged.recentDevices(15_001))
        assertNull(aged.openJobs(15_001))
    }
}
