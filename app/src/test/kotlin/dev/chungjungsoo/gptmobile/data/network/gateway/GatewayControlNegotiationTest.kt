package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayControlNegotiationTest {
    private class Fake(var capability: GatewayCapabilities? = null) : GatewayAPI {
        var probes = 0
        val configurations = mutableListOf<ProviderRequestConfig>()
        var discoveredInstance: String? = null
        var failure: Exception? = null
        override suspend fun getCapabilities(config: ProviderRequestConfig): GatewayCapabilities? {
            probes++
            configurations += config
            discoveredInstance?.let { GatewayClientDiagnostics.observeInstance(config, it) }
            failure?.let { throw it }
            return capability
        }
        override fun resumeEvents(jobId: String, afterSequence: Int, timeoutSeconds: Int, config: ProviderRequestConfig, expectedGeneration: String?) = emptyFlow<GatewayProgress>()
        override suspend fun getJobResult(jobId: String, config: ProviderRequestConfig): GatewayJobResult? = null
        override suspend fun cancelJob(jobId: String, config: ProviderRequestConfig): GatewayCancelResult? = null
    }

    @Test fun directRoutesWithoutGatewayControlsDoNotRequireGatewaySoftware() = runBlocking {
        val api = Fake().apply { failure = GatewayCapabilityDiscoveryException("Direct inference has no gateway service") }
        val negotiation = GatewayControlNegotiation(api)
        for (provider in listOf("LLAMA", "OLLAMA", "CUSTOM", "OPENAI")) {
            val config = ProviderRequestConfig("https://${provider.lowercase()}.headerless-direct.example/v1", "selected-account", providerType = provider, extraHeaders = mapOf("X-Client" to "app"))
            assertEquals(config, negotiation.prepare(config))
        }
        assertEquals(0, api.probes)
    }

    @Test fun knownGatewaysWithoutControlsStillVerifyTheirSelectedRoute() = runBlocking {
        for (provider in listOf("LLAMA", "OLLAMA", "CUSTOM", "OPENAI")) {
            val instance = "a".repeat(32)
            val api = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", instanceId = instance))
            val negotiation = GatewayControlNegotiation(api)
            val endpoint = "https://${provider.lowercase()}.headerless-gateway.example/v1"
            val config = ProviderRequestConfig(endpoint, "selected-account", providerType = provider, profileUid = "selected-profile", lockedApiUrl = endpoint, lockedModel = "selected-model", extraHeaders = mapOf("X-Client" to "app"))
            GatewayClientDiagnostics.observe(config, 200) { if (it == "X-Gateway-Instance-ID") instance else null }
            repeat(2) { assertEquals(config, negotiation.prepare(config)) }
            assertEquals(1, api.probes)
            assertEquals(listOf(config), api.configurations)
        }
    }

    @Test fun knownGatewayWithoutControlsCannotBecomeDirectWhenCapabilitiesDisappear() = runBlocking {
        val api = Fake()
        val config = ProviderRequestConfig("https://headerless-missing-capabilities.example/v1", null, providerType = "OLLAMA")
        GatewayClientDiagnostics.observeInstance(config, "b".repeat(32))
        val failure = runCatching { GatewayControlNegotiation(api).prepare(config) }.exceptionOrNull()
        assertTrue(failure is GatewayCapabilityDiscoveryException)
        assertTrue(failure?.message.orEmpty().startsWith("GATEWAY_CAPABILITY_UNAVAILABLE:"))
        assertEquals(1, api.probes)
        assertEquals(listOf(config), api.configurations)
    }

    @Test fun knownGatewayWithoutControlsPreservesDiscoveryFailures() = runBlocking {
        val failure = GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The gateway is unreachable")
        val api = Fake().apply { this.failure = failure }
        val config = ProviderRequestConfig("https://headerless-discovery-failure.example/v1", null, providerType = "OLLAMA")
        GatewayClientDiagnostics.observeInstance(config, "c".repeat(32))
        assertSame(failure, runCatching { GatewayControlNegotiation(api).prepare(config) }.exceptionOrNull())
        assertEquals(1, api.probes)
    }

    @Test fun knownGatewayWithoutControlsRefreshesCapabilitiesAfterAnObservedRestart() = runBlocking {
        val instance = "d".repeat(32)
        val api = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", instanceId = instance))
        val negotiation = GatewayControlNegotiation(api)
        val config = ProviderRequestConfig("https://headerless-restart.example/v1", null, providerType = "OLLAMA")
        GatewayClientDiagnostics.observeInstance(config, instance)
        assertEquals(config, negotiation.prepare(config))
        GatewayClientDiagnostics.observeInstance(config, "e".repeat(32))
        api.capability = null
        assertTrue(runCatching { negotiation.prepare(config) }.exceptionOrNull() is GatewayCapabilityDiscoveryException)
        assertEquals(2, api.probes)
    }

    @Test fun observedGatewayIdentityDoesNotApplyToAnotherRouteOrAccount() = runBlocking {
        val config = ProviderRequestConfig("https://headerless-identity.example/v1", "gateway-account", providerType = "OLLAMA", extraHeaders = mapOf("X-Api-Key" to "gateway-key"))
        GatewayClientDiagnostics.observeInstance(config, "f".repeat(32))
        val api = Fake()
        val negotiation = GatewayControlNegotiation(api)
        for (direct in listOf(config.copy(apiUrl = "https://another-headerless-identity.example/v1"), config.copy(token = "direct-account"), config.copy(extraHeaders = mapOf("X-Api-Key" to "direct-key")))) {
            assertEquals(direct, negotiation.prepare(direct))
        }
        assertEquals(0, api.probes)
    }

    @Test fun gatewayIdentifiedByUnsupportedCapabilityResponseCannotBecomeDirect() = runBlocking {
        val api = Fake().apply { discoveredInstance = "1".repeat(32) }
        val config = ProviderRequestConfig("https://first-gateway-capability-missing.example/v1", null, extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024"))
        val failure = runCatching { GatewayControlNegotiation(api).prepare(config) }.exceptionOrNull()
        assertTrue(failure is GatewayCapabilityDiscoveryException)
        assertTrue(failure?.message.orEmpty().startsWith("GATEWAY_CAPABILITY_UNAVAILABLE:"))
        assertEquals(1, api.probes)
    }

    @Test fun gatewayIdentifiedDuringDiagnosticOnlyDiscoveryPreservesFailure() = runBlocking {
        val enabled = LocalDiagnosticsPolicy.enabled
        LocalDiagnosticsPolicy.setEnabled(true)
        try {
            val failure = GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE: The gateway rejected discovery")
            val api = Fake().apply {
                discoveredInstance = "2".repeat(32)
                this.failure = failure
            }
            val config = GatewayClientDiagnostics.withHeaders(ProviderRequestConfig("https://first-gateway-discovery-failure.example/v1", null, providerType = "LLAMA"), "selected-model")
            assertSame(failure, runCatching { GatewayControlNegotiation(api).prepare(config) }.exceptionOrNull())
            assertEquals(1, api.probes)
        } finally {
            LocalDiagnosticsPolicy.setEnabled(enabled)
        }
    }

    @Test fun inferenceEndpointNeverReceivesUnnegotiatedGatewaySettings() = runBlocking {
        val api = Fake()
        val negotiation = GatewayControlNegotiation(api)
        val config = ProviderRequestConfig("http://192.168.1.2:8080/v1", "secret", extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024", "X-Client" to "app"))
        repeat(3) { assertEquals(mapOf("X-Client" to "app"), negotiation.prepare(config).extraHeaders) }
        assertEquals(1, api.probes)
        negotiation.prepare(config.copy(token = "different-account"))
        assertEquals(2, api.probes)
    }

    @Test fun advertisedControlNamesDoNotImplyEffectiveSettings() = runBlocking {
        val negotiation = GatewayControlNegotiation(Fake(GatewayCapabilities(gatewayVersion = "15.0.0", acceptedRequestHeaders = listOf("X-Gateway-Cache-Reuse"))))
        val config = ProviderRequestConfig("http://192.168.1.2:8090/v1", null, extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024", "X-Gateway-Unknown" to "true"))
        assertEquals(mapOf("X-Gateway-Cache-Reuse" to "1024"), negotiation.prepare(config).extraHeaders)
    }

    @Test fun profilesOnOneAuthenticatedGatewayShareCapabilityProbeUntilAnObservedRestart() = runBlocking {
        val api = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", instanceId = "c".repeat(32), acceptedRequestHeaders = listOf("X-Gateway-Cache-Reuse")))
        val negotiation = GatewayControlNegotiation(api)
        val first = ProviderRequestConfig("http://cache-reuse.example:8090/v1", "same-account", profileUid = "first", extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024"))
        val second = first.copy(profileUid = "second")
        repeat(2) { negotiation.prepare(first) }
        val initialProbes = api.probes
        repeat(3) {
            assertEquals(first.extraHeaders, negotiation.prepare(first).extraHeaders)
            assertEquals(second.extraHeaders, negotiation.prepare(second).extraHeaders)
        }
        assertEquals(initialProbes, api.probes)
        GatewayClientDiagnostics.observeInstance(second, "d".repeat(32))
        api.capability = api.capability?.copy(instanceId = "d".repeat(32))
        negotiation.prepare(first)
        assertEquals(initialProbes + 1, api.probes)
        negotiation.prepare(first.copy(extraHeaders = first.extraHeaders + ("X-Api-Key" to "different")))
        assertEquals(initialProbes + 2, api.probes)
    }

    @Test fun initialDiscoveryFailureStopsBeforeRemovingRequestedIsolation() = runBlocking {
        val api = Fake().apply { failure = GatewayCapabilityDiscoveryException("GATEWAY_CAPABILITY_UNAVAILABLE") }
        val negotiation = GatewayControlNegotiation(api)
        val config = ProviderRequestConfig("https://initial-capability-failure.example/v1", null, extraHeaders = mapOf("X-Gateway-Delegated-Worker" to "true", "X-Gateway-Allow-Local-Tools" to "false"))
        assertTrue(runCatching { negotiation.prepare(config) }.exceptionOrNull() is GatewayCapabilityDiscoveryException)
        assertEquals(1, api.probes)
    }

    @Test fun definiteDirectRouteKeepsIsolationButNeverReceivesOptionalGatewayControls() = runBlocking {
        val negotiation = GatewayControlNegotiation(Fake())
        val isolation = mapOf("X-Gateway-Delegated-Worker" to "true", "X-Gateway-Allow-Local-Tools" to "false", "X-Gateway-Request-Role" to "reviewer")
        val config = ProviderRequestConfig("https://direct-isolation.example/v1", null, extraHeaders = isolation + ("X-Gateway-Cache-Reuse" to "1024"))
        assertEquals(isolation, negotiation.prepare(config).extraHeaders)
    }

    @Test fun aKnownGatewayCannotLoseRequiredControlsAfterAnObservedRestart() = runBlocking {
        val header = "X-Gateway-Allow-Local-Tools"
        val api = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", instanceId = "5".repeat(32), acceptedRequestHeaders = listOf(header)))
        val negotiation = GatewayControlNegotiation(api)
        val config = ProviderRequestConfig("https://restart-controls.example/v1", null, extraHeaders = mapOf(header to "false"))
        assertEquals(config.extraHeaders, negotiation.prepare(config).extraHeaders)
        GatewayClientDiagnostics.observeInstance(config, "6".repeat(32))
        api.capability = GatewayCapabilities(gatewayVersion = "15.0.0", instanceId = "6".repeat(32))
        assertTrue(runCatching { negotiation.prepare(config) }.exceptionOrNull() is GatewayCapabilityDiscoveryException)
        GatewayClientDiagnostics.observeInstance(config, "7".repeat(32))
        api.capability = null
        assertTrue(runCatching { negotiation.prepare(config) }.exceptionOrNull() is GatewayCapabilityDiscoveryException)
    }
}
