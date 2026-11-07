package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.engineeringGatewayProfile
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayDashboardTargetTest {
    private val inference = PlatformV2(uid = "inference", name = "Model server", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.2:8080/v1", token = "model-token", model = "selected-model")
    private val gateway = inference.copy(uid = "gateway", name = "Engineering gateway", apiUrl = "http://192.168.1.2:8090/v1", token = "gateway-token", model = "gateway-default")

    @Test fun explicitSelectionPreservesTheGatewayRouteAndCredentialsAmongMultipleConnections() {
        val other = gateway.copy(uid = "other", apiUrl = "http://192.168.1.3:9090/custom/v1", token = "other-token")
        val profiles = listOf(other, inference, gateway)
        val target = requireNotNull(gatewayDashboardTarget(inference.uid, profiles, gateway.uid))
        assertEquals(gateway, engineeringGatewayProfile(profiles, gateway.uid))
        assertEquals(inference.uid, target.selectedProfileUid)
        assertEquals(inference.model, target.model)
        assertEquals(gateway.uid, target.config?.profileUid)
        assertEquals(gateway.apiUrl, target.config?.apiUrl)
        assertEquals(gateway.token, target.config?.token)
        assertEquals("192.168.1.2:8090", target.host)
        assertTrue(target.explicit)

        // A port is not service identity: an explicit gateway may use any configured port/path.
        val selectedOnModelPort = requireNotNull(gatewayDashboardTarget(inference.uid, profiles, inference.uid))
        assertEquals(inference.apiUrl, selectedOnModelPort.config?.apiUrl)
        assertEquals(inference.token, selectedOnModelPort.config?.token)
    }

    @Test fun absentSelectionShowsSetupGuidanceWithoutProbingAnyConfiguredServer() = runTest {
        val monitor = GatewayDashboardMonitor(noRequests())
        for (selection in listOf(null, "", "  ")) {
            val target = requireNotNull(gatewayDashboardTarget(inference.uid, listOf(gateway, inference), selection))
            assertFalse(target.explicit)
            assertNull(target.config)
            var state = GatewayDashboardState()
            monitor.observe(target, state) { state = it }
            assertEquals(GatewayDashboardPhase.UNAVAILABLE, state.phase)
            assertTrue(state.message.orEmpty().contains("Select an engineering gateway connection in GitHub settings"))
        }
    }

    @Test fun missingDisabledAmbiguousOrIneligibleSelectionNeverChoosesAnAlternative() = runTest {
        val candidates = listOf(
            emptyList(),
            listOf(gateway.copy(enabled = false)),
            listOf(gateway, gateway.copy(name = "Conflicting record")),
            listOf(gateway.copy(compatibleType = ClientType.OPENAI)),
            listOf(gateway.copy(apiUrl = "https://external.example/v1")),
            listOf(gateway.copy(apiUrl = "http://user:secret@192.168.1.2:8090/v1")),
            listOf(gateway.copy(apiUrl = "http://192.168.1.2:8090/v1?token=secret")),
            listOf(gateway.copy(apiUrl = "http://192.168.1.2:8090/v1#fragment"))
        )
        val alternative = gateway.copy(uid = "alternative", apiUrl = "http://192.168.1.3:8090/v1")
        val monitor = GatewayDashboardMonitor(noRequests())
        for (configured in candidates) {
            val target = requireNotNull(gatewayDashboardTarget(inference.uid, listOf(inference, alternative) + configured, gateway.uid))
            assertNull(target.config)
            var state = GatewayDashboardState()
            monitor.observe(target, state) { state = it }
            assertEquals(GatewayDashboardPhase.UNAVAILABLE, state.phase)
            assertTrue(state.message.orEmpty().contains("GitHub settings"))
        }
    }

    private fun noRequests() = object : GatewayDashboardSource {
        override suspend fun readiness(config: ProviderRequestConfig, model: String?): GatewayReadinessObservation? = throw AssertionError("Unselected readiness request: ${config.profileUid}")
        override suspend fun read(config: ProviderRequestConfig): GatewayDashboardObservation? = throw AssertionError("Unselected dashboard request: ${config.profileUid}")
    }
}
