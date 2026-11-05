package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
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
