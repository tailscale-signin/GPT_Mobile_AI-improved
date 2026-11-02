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
        assertTrue(capabilities.supportsContract("gatewayProgress", "gpt-mobile-gateway-progress/2"))
        assertTrue(capabilities.supportsFeature("validatedToolArguments"))
        assertFalse(capabilities.supportsContract("evidence", "gptmobile.evidence.v1"))
    }

    @Test
    fun keyFreeRequiresExplicitVersionedAdvertisement() {
        assertFalse(json.decodeFromString<GatewayCapabilities>("""{"version":"13.1.0"}""").supportsKeyFreeConnection)
        assertFalse(json.decodeFromString<GatewayCapabilities>("""{"gatewayVersion":"14.0.0","contractVersion":1}""").supportsKeyFreeConnection)
        assertTrue(json.decodeFromString<GatewayCapabilities>("""{"gatewayVersion":"14.0.0","contractVersion":1,"auth":{"mode":"loopback","apiKeyRequired":false}}""").supportsKeyFreeConnection)
        assertFalse(json.decodeFromString<GatewayCapabilities>("""{"gatewayVersion":"14.0.0","contractVersion":1}""").supportsContract("run", "gptmobile.gateway-run.v1"))
    }

    @Test
    fun sharedGateway15FixtureIsAccepted() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("gateway-v15-capabilities.json"))
            .bufferedReader().use { it.readText() }
        val capabilities = json.decodeFromString<GatewayCapabilities>(fixture)
        assertTrue(capabilities.supportsKeyFreeConnection)
        assertTrue(capabilities.desktop?.maintenanceProtocol == 1)
    }

    @Test
    fun acceptsGateway15AndRejectsUnknownOrMalformedContracts() {
        val valid = """{"gatewayVersion":"15.0.0","contractVersion":1,"auth":{"mode":"trusted_proxy","apiKeyRequired":false},"desktop":{"managed":true,"maintenanceProtocol":1,"stateSchema":1,"idleUpdates":true,"updateAuthority":"local-manager"}}"""
        val caps = json.decodeFromString<GatewayCapabilities>(valid)
        assertTrue(caps.supportsKeyFreeConnection)
        assertTrue(caps.desktop?.managed == true)
        assertTrue(caps.desktop?.idleUpdates == true)
        for (version in listOf("16.0.0", "15.evil", "15.0.0-preview", "15.00.0", "15.0.0.extra")) {
            assertFalse(json.decodeFromString<GatewayCapabilities>(valid.replace("15.0.0", version)).supportsKeyFreeConnection)
        }
        assertFalse(json.decodeFromString<GatewayCapabilities>(valid.replace("trusted_proxy", "public")).supportsKeyFreeConnection)
        assertFalse(json.decodeFromString<GatewayCapabilities>(valid.replace("contractVersion\":1", "contractVersion\":2")).supportsKeyFreeConnection)
    }

    @Test
    fun openNetworkIsExplicitlyKeyFreeWithSharedIdentity() {
        val caps = json.decodeFromString<GatewayCapabilities>(
            """{"gatewayVersion":"15.0.0","contractVersion":1,"auth":{"mode":"open_network","apiKeyRequired":false},"identityScope":"shared-network"}"""
        )
        assertTrue(caps.supportsKeyFreeConnection)
        assertTrue(caps.identityScope == "shared-network")
        assertFalse(caps.copy(auth = caps.auth?.copy(apiKeyRequired = true)).supportsKeyFreeConnection)
    }
}
