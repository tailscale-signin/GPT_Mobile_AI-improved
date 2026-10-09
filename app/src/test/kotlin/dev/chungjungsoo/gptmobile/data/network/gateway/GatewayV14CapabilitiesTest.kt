package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV14CapabilitiesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun sharedPythonCapabilityFixtureIsAccepted() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("gateway-v14-capabilities.json"))
            .bufferedReader().use { it.readText() }
        val capabilities = json.decodeFromString<GatewayCapabilities>(fixture)
        assertTrue(capabilities.supportsKeyFreeConnection)
        assertTrue(capabilities.memory?.authority == "client")
    }

    @Test
    fun keyFreeRequiresExplicitVersionedAdvertisement() {
        assertFalse(json.decodeFromString<GatewayCapabilities>("""{"version":"13.1.0"}""").supportsKeyFreeConnection)
        assertFalse(json.decodeFromString<GatewayCapabilities>("""{"gatewayVersion":"14.0.0","contractVersion":1}""").supportsKeyFreeConnection)
        assertTrue(json.decodeFromString<GatewayCapabilities>("""{"gatewayVersion":"14.0.0","contractVersion":1,"auth":{"mode":"loopback","apiKeyRequired":false}}""").supportsKeyFreeConnection)
    }
}
