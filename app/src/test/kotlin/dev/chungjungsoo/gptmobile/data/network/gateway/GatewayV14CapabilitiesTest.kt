package dev.chungjungsoo.gptmobile.data.network.gateway

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayV14CapabilitiesTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test fun completeServerRouteFixturesAcceptLegacyMetadataWithoutEnablingIt() {
        for (name in listOf(
            "gateway-v15-capabilities-legacy-mixed.json",
            "gateway-v15-capabilities-full.json",
            "gateway-v16-capabilities-full.json",
            "gateway-v17-capabilities-full.json"
        )) {
            val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
            val capabilities = json.decodeFromString<GatewayCapabilities>(fixture)
            assertTrue(capabilities.supportsKeyFreeConnection)
            assertTrue(capabilities.supportsResume)
            assertTrue(capabilities.supportsFeature("validatedToolArguments"))
            assertTrue(capabilities.supportsContract("gatewayProgress", "gpt-mobile-gateway-progress/2"))
            assertTrue("X-Gateway-Allow-Local-Tools" in capabilities.acceptedRequestHeaders)
            assertFalse(capabilities.supportsFeature("gateway_server_icon"))
            assertFalse(capabilities.supportsFeature("disconnect_grace"))
            if (name.contains("legacy")) {
                assertEquals(JsonPrimitive("gateway_server"), capabilities.features["gateway_server_icon"])
                assertEquals(JsonPrimitive(20), capabilities.features["disconnect_grace"])
            }
        }
    }

    @Test fun onlyLiteralJsonTrueEnablesAFeature() {
        val capabilities = json.decodeFromString<GatewayCapabilities>(
            """{"gatewayVersion":"15.0.0","features":{"enabled":true,"disabled":false,"text":"true","number":1,"zero":0,"absent":null,"array":[true],"object":{"enabled":true}}}"""
        )
        assertTrue(capabilities.supportsFeature("enabled"))
        for (name in listOf("disabled", "text", "number", "zero", "absent", "array", "object", "unadvertised")) {
            assertFalse(name, capabilities.supportsFeature(name))
        }
    }

    @Test fun mixedFeatureCompatibilityDoesNotRelaxOtherCapabilityShapes() {
        for (document in listOf(
            """{"gatewayVersion":"15.0.0","features":[]}""",
            """{"gatewayVersion":"15.0.0","auth":[]}""",
            """{"gatewayVersion":"15.0.0","auth":{"apiKeyRequired":false}}""",
            """{"gatewayVersion":"15.0.0","contracts":{"gatewayProgress":true}}""",
            """{"gatewayVersion":"15.0.0","accepted_request_headers":false}"""
        )) {
            assertTrue(runCatching { json.decodeFromString<GatewayCapabilities>(document) }.isFailure)
        }
    }

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
    fun sharedGateway17FixtureAdvertisesAdditiveSavedWorkAndBenchmarkContracts() {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("gateway-v17-capabilities-full.json"))
            .bufferedReader().use { it.readText() }
        val capabilities = json.decodeFromString<GatewayCapabilities>(fixture)
        assertEquals("17.0.0", capabilities.gatewayVersion)
        assertTrue(capabilities.supportsKeyFreeConnection)
        assertTrue(capabilities.supportsCancellation)
        assertTrue(capabilities.supportsResultRecovery)
        for (feature in listOf("conversation_metadata_v1", "saved_messages_v1", "mutation_identity_preconditions_v1", "durable_benchmarks_v1")) {
            assertTrue(feature, capabilities.supportsFeature(feature))
        }
        assertTrue(capabilities.supportsContract("conversationMetadata", "gptmobile.gateway.conversation-metadata.v1"))
        assertTrue(capabilities.supportsContract("benchmarks", "gateway.benchmark.v1"))
        assertFalse(capabilities.supportsContract("benchmarks", "gateway.benchmark.v2"))
        assertTrue("X-Gateway-Installation-ID" in capabilities.acceptedRequestHeaders)
        assertTrue("X-Gateway-Principal-ID" in capabilities.acceptedRequestHeaders)
        assertEquals(1, capabilities.desktop?.maintenanceProtocol)
        assertEquals(1, capabilities.desktop?.stateSchema)
        assertEquals("client", capabilities.memory?.authority)
    }

    @Test
    fun acceptsGateway14Through17AndRejectsUnknownOrMalformedContracts() {
        val valid = """{"gatewayVersion":"17.0.0","contractVersion":1,"auth":{"mode":"trusted_proxy","apiKeyRequired":false},"desktop":{"managed":true,"maintenanceProtocol":1,"stateSchema":1,"idleUpdates":true,"updateAuthority":"local-manager"}}"""
        val caps = json.decodeFromString<GatewayCapabilities>(valid)
        assertTrue(caps.supportsKeyFreeConnection)
        assertTrue(caps.desktop?.managed == true)
        assertTrue(caps.desktop?.idleUpdates == true)
        for (version in listOf("14.0.0", "15.0.0", "16.0.0", "16.1.2", "17.0.0", "17.1.2")) {
            assertTrue(version, json.decodeFromString<GatewayCapabilities>(valid.replace("17.0.0", version)).supportsKeyFreeConnection)
        }
        for (version in listOf(
            "13.0.0", "18.0.0", "15.evil", "15.0.0-preview", "15.00.0", "15.0.0.extra", "16.0.0-preview", "16.00.0",
            "17.evil", "17.0.0-preview", "17.0.0+build.1", "17.00.0", "17.0.00", "017.0.0", "17.0.0.extra", "17.0", " 17.0.0"
        )) {
            assertFalse(version, json.decodeFromString<GatewayCapabilities>(valid.replace("17.0.0", version)).supportsKeyFreeConnection)
        }
        assertFalse(json.decodeFromString<GatewayCapabilities>(valid.replace("trusted_proxy", "public")).supportsKeyFreeConnection)
        assertFalse(json.decodeFromString<GatewayCapabilities>(valid.replace("contractVersion\":1", "contractVersion\":2")).supportsKeyFreeConnection)
        assertFalse(caps.copy(auth = caps.auth?.copy(apiKeyRequired = true)).supportsKeyFreeConnection)
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
