package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.GitHubChatProfileOverride
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayClientDiagnostics
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardClient
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardObservation
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDashboardPhase
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayDiagnosticStatus
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayNetworkRecoveryMonitor
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayReadinessObservation
import dev.chungjungsoo.gptmobile.data.network.gateway.dashboardFixture
import dev.chungjungsoo.gptmobile.data.network.gateway.parseGatewayDashboard
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubGatewayStatusViewModelTest {
    private val inference = PlatformV2(uid = "inference", name = "Model server", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.2:8080/v1", token = "model-token", model = "selected-model")
    private val gateway = inference.copy(uid = "gateway", name = "Gateway", apiUrl = "http://192.168.1.2:8090/v1", token = "gateway-token")
    private val other = gateway.copy(uid = "other", name = "Other gateway", apiUrl = "http://192.168.1.3:9090/v1", token = "other-token")
    private val profiles = MutableStateFlow(listOf(other, inference, gateway))
    private val features = MutableStateFlow(AppFeatureSettings())
    private val requested = mutableListOf<Pair<String, ProviderRequestConfig>>()
    private val client = mockk<GatewayDashboardClient>()

    @Before fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
        mockkObject(GatewayClientDiagnostics)
        every { GatewayClientDiagnostics.diagnosticStatus } returns MutableStateFlow(GatewayDiagnosticStatus())
        coEvery { GatewayClientDiagnostics.diagnosticStatusFor(any()) } returns GatewayDiagnosticStatus()
        every { GatewayClientDiagnostics.refreshDiagnostics(any()) } returns Unit
        justRun { client.refresh() }
        coEvery { client.readiness(any(), any()) } coAnswers {
            requested += "ready" to firstArg<ProviderRequestConfig>()
            assertEquals(inference.model, secondArg<String?>())
            ready()
        }
        coEvery { client.read(any()) } coAnswers {
            requested += "dashboard" to firstArg<ProviderRequestConfig>()
            observed()
        }
    }

    @After fun tearDown() {
        unmockkObject(GatewayClientDiagnostics)
        Dispatchers.resetMain()
    }

    @Test fun absentSelectionDoesNotProbeTheModelAndConfiguredGatewayRemainsVisible() = runTest {
        val model = model()
        try {
            backgroundScope.launch { model.observe(inference.uid) }
            runCurrent()
            assertTrue("No gateway was selected, so readiness and dashboard must not target the inference connection.", requested.isEmpty())
            assertEquals(GatewayDashboardPhase.UNAVAILABLE, model.state.value.phase)
            assertTrue(model.state.value.message.orEmpty().contains("Select an engineering gateway"))
            model.refresh()
            advanceTimeBy(900_001)
            runCurrent()
            assertTrue(requested.isEmpty())
            coVerify(exactly = 0) { client.readiness(any(), any()) }
            coVerify(exactly = 0) { client.read(any()) }

            select(gateway.uid)
            runCurrent()
            assertEquals(GatewayDashboardPhase.LIVE, model.state.value.phase)
            assertEquals(inference.uid, model.state.value.target?.selectedProfileUid)
            assertEquals(gateway.uid, model.state.value.target?.config?.profileUid)
            assertEquals(listOf("ready", "dashboard"), requested.map { it.first })
            assertTrue(requested.all { it.second.apiUrl == gateway.apiUrl && it.second.token == gateway.token })
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun selectingAnotherGatewayCancelsTheOldRequestAndClearsItsMeasurements() = runTest {
        select(gateway.uid)
        val oldCancelled = CompletableDeferred<Unit>()
        val newReady = CompletableDeferred<GatewayReadinessObservation>()
        var oldReads = 0
        coEvery { client.read(any()) } coAnswers {
            val config = firstArg<ProviderRequestConfig>()
            requested += "dashboard" to config
            if (config.profileUid == gateway.uid && ++oldReads > 1) {
                try {
                    awaitCancellation()
                } finally {
                    oldCancelled.complete(Unit)
                }
            }
            observed()
        }
        coEvery { client.readiness(match { it.profileUid == other.uid }, any()) } coAnswers {
            requested += "ready" to firstArg<ProviderRequestConfig>()
            newReady.await()
        }
        val model = model()
        try {
            backgroundScope.launch { model.observe(inference.uid) }
            runCurrent()
            assertNotNull(model.state.value.observation)
            model.refresh()
            runCurrent()
            select(other.uid)
            runCurrent()
            assertTrue(oldCancelled.isCompleted)
            assertEquals(other.uid, model.state.value.target?.config?.profileUid)
            assertEquals(GatewayDashboardPhase.CONNECTING, model.state.value.phase)
            assertNull(model.state.value.observation)
            newReady.complete(ready())
            runCurrent()
            assertEquals(GatewayDashboardPhase.LIVE, model.state.value.phase)
            assertEquals(other.apiUrl, requested.last().second.apiUrl)
            assertEquals(other.token, requested.last().second.token)
            assertTrue(requested.none { it.second.profileUid == inference.uid })
        } finally {
            model.viewModelScope.cancel()
        }
    }

    @Test fun editedAndRemovedConnectionsCannotLeaveTheOldGatewayPolling() = runTest {
        select(gateway.uid)
        val model = model()
        try {
            backgroundScope.launch { model.observe(inference.uid) }
            runCurrent()
            val oldIdentity = model.state.value.target?.identity
            val edited = gateway.copy(apiUrl = "http://192.168.1.4:9443/proxy/v1", token = "new-account")
            profiles.value = listOf(inference, other, edited)
            runCurrent()
            assertNotEquals(oldIdentity, model.state.value.target?.identity)
            assertEquals(edited.apiUrl, requested.last().second.apiUrl)
            assertEquals(edited.token, requested.last().second.token)

            profiles.value = listOf(inference, other)
            runCurrent()
            assertEquals(GatewayDashboardPhase.UNAVAILABLE, model.state.value.phase)
            assertNull(model.state.value.target?.config)
            assertNull(model.state.value.observation)
            val requestsAfterRemoval = requested.size
            model.refresh()
            advanceTimeBy(900_001)
            runCurrent()
            assertEquals(requestsAfterRemoval, requested.size)
            assertTrue(requested.none { it.second.profileUid in setOf(inference.uid, other.uid) })
        } finally {
            model.viewModelScope.cancel()
        }
    }

    private fun select(uid: String) {
        features.value = features.value.copy(githubProfileOverrides = mapOf(inference.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = uid)))
    }

    private fun model(): GitHubGatewayStatusViewModel {
        val settings = mockk<SettingRepository>()
        every { settings.observePlatformV2s() } returns profiles
        every { settings.observeFeatureSettings() } returns features
        val recovery = mockk<GatewayNetworkRecoveryMonitor>()
        every { recovery.routeAvailable } returns MutableStateFlow(true)
        return GitHubGatewayStatusViewModel(settings, client, recovery)
    }

    private fun ready() = GatewayReadinessObservation("a".repeat(32), true, true, true, false, 0)
    private fun observed() = GatewayDashboardObservation(parseGatewayDashboard(dashboardFixture()), 0)
}
