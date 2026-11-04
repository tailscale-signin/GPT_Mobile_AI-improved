package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class GatewayControlNegotiationTest {
    private class Fake(var capability: GatewayCapabilities? = null) : GatewayAPI {
        var probes = 0
        override suspend fun getCapabilities(config: ProviderRequestConfig): GatewayCapabilities? {
            probes++
            return capability
        }
        override fun resumeEvents(jobId: String, afterSequence: Int, timeoutSeconds: Int, config: ProviderRequestConfig, expectedGeneration: String?) = emptyFlow<GatewayProgress>()
        override suspend fun getJobResult(jobId: String, config: ProviderRequestConfig): GatewayJobResult? = null
        override suspend fun cancelJob(jobId: String, config: ProviderRequestConfig): GatewayCancelResult? = null
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
}
