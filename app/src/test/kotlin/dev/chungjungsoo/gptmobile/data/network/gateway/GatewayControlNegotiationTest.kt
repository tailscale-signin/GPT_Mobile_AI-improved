package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.diagnostics.LocalDiagnosticsPolicy
import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.mockk.every
import io.mockk.mockk
import java.util.Base64
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayControlNegotiationTest {
    @Test fun onlyPersistentPrimaryRequestsCanNegotiateFullConversationRetention() = runBlocking {
        val header = "X-Gateway-Conversation-Retention"
        val api = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", acceptedRequestHeaders = listOf(header, "X-Gateway-Profile-ID")))
        val negotiation = GatewayControlNegotiation(api)
        val config = ProviderRequestConfig("https://retention-negotiation.example/v1", "selected-account", profileUid = "saved-profile", queuePersistent = true, extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024"))
        assertEquals("full", negotiation.prepare(config).extraHeaders[header])
        for (request in listOf(
            config.copy(queuePersistent = false),
            config.copy(requestRole = "delegate"),
            config.copy(profileUid = null),
            config.copy(profileUid = "unsafe profile"),
            config.copy(extraHeaders = config.extraHeaders + ("X-Gateway-Delegated-Worker" to "true")),
            config.copy(extraHeaders = config.extraHeaders + ("X-Gateway-Request-Role" to "reviewer"))
        )) {
            val permitted = api.capability!!.copy(acceptedRequestHeaders = listOf(header, "X-Gateway-Profile-ID", "X-Gateway-Delegated-Worker", "X-Gateway-Request-Role"))
            assertEquals("none", GatewayControlNegotiation(Fake(permitted)).prepare(request.copy(extraHeaders = request.extraHeaders + (header.lowercase() to "full"))).extraHeaders[header])
        }
    }

    @Test fun retentionHeadersCannotTriggerDiscoveryOrReachAnUnnegotiatedRoute() = runBlocking {
        val header = "X-Gateway-Conversation-Retention"
        val api = Fake()
        val direct = ProviderRequestConfig("https://direct-retention.example/v1", null, profileUid = "saved", queuePersistent = true)
        assertEquals(direct, GatewayControlNegotiation(api).prepare(direct.copy(extraHeaders = mapOf(header to "full"))))
        assertEquals(0, api.probes)
        val old = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", acceptedRequestHeaders = listOf("X-Gateway-Profile-ID")))
        val request = direct.copy(apiUrl = "https://old-retention.example/v1", extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024", header to "full"))
        assertEquals(mapOf("X-Gateway-Profile-ID" to "saved"), GatewayControlNegotiation(old).prepare(request).extraHeaders)
    }

    @Test fun fullServerRouteFixturesNegotiateTheSelectedConnectionWithoutLosingIsolation() = runBlocking {
        for ((index, name) in listOf("gateway-v15-capabilities-legacy-mixed.json", "gateway-v15-capabilities-full.json").withIndex()) {
            val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
            val endpoint = "https://full-route-capabilities-$index.example/v1"
            val isolation = mapOf("X-Gateway-Allow-Local-Tools" to "false", "X-Gateway-Delegated-Worker" to "true", "X-Gateway-Request-Role" to "reviewer")
            val config = ProviderRequestConfig(endpoint, "selected-account", profileUid = "selected-profile", lockedApiUrl = endpoint, lockedModel = "selected-model", extraHeaders = isolation + mapOf("X-Gateway-Unknown" to "true", "X-Api-Key" to "selected-key"))
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    assertEquals("Bearer selected-account", request.headers["Authorization"])
                    assertEquals("selected-key", request.headers["X-Api-Key"])
                    respond(fixture)
                }
            ).use { http ->
                val network = mockk<NetworkClient>()
                every { network() } returns http
                val prepared = GatewayControlNegotiation(GatewayAPIImpl(network)).prepare(config)
                val negotiatedMetadata = if (name == "gateway-v15-capabilities-full.json") {
                    mapOf("X-Gateway-Profile-ID" to "selected-profile", "X-Gateway-Conversation-Retention" to "none")
                } else {
                    emptyMap()
                }
                assertEquals(config.copy(extraHeaders = isolation + ("X-Api-Key" to "selected-key") + negotiatedMetadata), prepared)
                assertEquals(1, calls)
            }
        }
    }

    @Test fun schemaFailureFromTheRealDecoderCannotBecomeDirectInference() = runBlocking {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("gateway-v15-capabilities-full.json")).bufferedReader().use { it.readText() }
        val malformed = JsonObject(Json.parseToJsonElement(fixture).jsonObject + ("contractVersion" to JsonPrimitive("private payload"))).toString()
        val config = ProviderRequestConfig("https://schema-negotiation.example/v1", "selected-account", extraHeaders = mapOf("X-Gateway-Allow-Local-Tools" to "false", "X-Gateway-Delegated-Worker" to "true"))
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("GET", request.method.value)
                assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                respond(malformed)
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            val failure = runCatching { GatewayControlNegotiation(GatewayAPIImpl(network)).prepare(config) }.exceptionOrNull()
            assertTrue(failure is GatewayCapabilityDiscoveryException)
            assertEquals("EXPECTED_INTEGER", (failure as GatewayCapabilityDiscoveryException).decodeReason)
            assertEquals("$.contractVersion", failure.fieldPath)
            assertEquals(null, failure.cause)
            assertEquals(1, calls)
        }
    }

    private class Fake(var capability: GatewayCapabilities? = null) : GatewayAPI {
        var probes = 0
        val configurations = mutableListOf<ProviderRequestConfig>()
        var discoveredInstance: String? = null
        var failure: Exception? = null
        override suspend fun getCapabilities(config: ProviderRequestConfig, forceRefresh: Boolean): GatewayCapabilities? {
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
            val config = ProviderRequestConfig("https://${provider.lowercase()}.headerless-direct.example/v1", "selected-account", providerType = provider, profileUid = "saved-profile", profileName = "Local · 東京 🦙", extraHeaders = mapOf("X-Client" to "app"))
            assertEquals(config, negotiation.prepare(config))
            assertEquals(config, negotiation.prepare(config.copy(extraHeaders = config.extraHeaders + mapOf("x-gateway-profile-id" to "untrusted", "X-Gateway-Profile-Name-Base64" to "dW50cnVzdGVk"))))
        }
        assertEquals(0, api.probes)
    }

    @Test fun unicodeDisplayLabelsAreCapturedOnlyAfterTheRealGatewayAcceptsTheirHeaders() = runBlocking {
        val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream("gateway-v15-capabilities-full.json")).bufferedReader().use { it.readText() }
        val accepted = listOf("X-Gateway-Profile-ID", "x-gateway-profile-name-base64")
        val payload = JsonObject(Json.parseToJsonElement(fixture).jsonObject + ("accepted_request_headers" to JsonArray(accepted.map(::JsonPrimitive)))).toString()
        val config = ProviderRequestConfig(
            "https://profile-label-negotiation.example/v1",
            "selected-account",
            profileUid = "selected-profile_2",
            profileName = " Local · 東京 🦙 ",
            extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024", "x-gateway-profile-id" to "untrusted", "X-Gateway-Profile-Name-Base64" to "dW50cnVzdGVk")
        )
        var probes = 0
        HttpClient(
            MockEngine { request ->
                probes++
                assertEquals("GET", request.method.value)
                assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                assertEquals("Bearer selected-account", request.headers["Authorization"])
                assertEquals(null, request.headers["X-Gateway-Profile-ID"])
                assertEquals(null, request.headers["X-Gateway-Profile-Name-Base64"])
                respond(payload)
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            val prepared = GatewayControlNegotiation(GatewayAPIImpl(network)).prepare(config)
            assertEquals("selected-profile_2", prepared.extraHeaders["X-Gateway-Profile-ID"])
            val encoded = requireNotNull(prepared.extraHeaders["X-Gateway-Profile-Name-Base64"])
            assertTrue(encoded.matches(Regex("[A-Za-z0-9_-]+")))
            assertEquals(config.profileName, Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8))
            assertEquals(2, prepared.extraHeaders.size)
            assertEquals(GatewayClientDiagnostics.routeIdentity(config), GatewayClientDiagnostics.routeIdentity(prepared))
            assertEquals(config.token, prepared.token)
            assertEquals(config.profileUid, prepared.profileUid)
            assertEquals(1, probes)
        }
    }

    @Test fun eachDisplayHeaderRequiresItsOwnAcceptanceAndDirectRoutesNeverReceiveThem() = runBlocking {
        val possibilities = listOf(null, emptyList(), listOf("X-Gateway-Profile-ID"), listOf("X-Gateway-Profile-Name-Base64"))
        for ((index, headers) in possibilities.withIndex()) {
            val api = Fake(headers?.let { GatewayCapabilities(gatewayVersion = "15.0.0", acceptedRequestHeaders = it) })
            val config = ProviderRequestConfig("https://profile-label-unaccepted-$index.example/v1", null, profileUid = "saved", profileName = "Saved profile", extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024"))
            val prepared = GatewayControlNegotiation(api).prepare(config)
            assertEquals(headers.orEmpty().toSet(), prepared.extraHeaders.keys)
            assertEquals(1, api.probes)
        }
    }

    @Test fun displayMetadataBoundsUseUtf8BytesAndUnsafeIdentifiersCannotBecomeHeaders() = runBlocking {
        val accepted = listOf("X-Gateway-Profile-ID", "X-Gateway-Profile-Name-Base64")
        val api = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", acceptedRequestHeaders = accepted))
        val negotiation = GatewayControlNegotiation(api)
        val config = ProviderRequestConfig("https://profile-label-bounds.example/v1", null, profileUid = "p".repeat(160), profileName = "é".repeat(512), extraHeaders = mapOf("X-Gateway-Cache-Reuse" to "1024"))
        val prepared = negotiation.prepare(config)
        assertEquals(config.profileUid, prepared.extraHeaders[accepted[0]])
        val bytes = Base64.getUrlDecoder().decode(prepared.extraHeaders[accepted[1]])
        assertEquals(1024, bytes.size)
        assertEquals(config.profileName, bytes.toString(Charsets.UTF_8))
        for (uid in listOf("", "p".repeat(161), "unsafe\r\nX-Injected: yes", "東京", "with space")) {
            val bounded = negotiation.prepare(config.copy(profileUid = uid, profileName = "é".repeat(513)))
            assertTrue(bounded.extraHeaders.isEmpty())
        }
        for (name in listOf(" ", "\uD800", "a".repeat(1025), "name\nsecond line", "name\u0085")) {
            assertEquals(setOf(accepted[0]), negotiation.prepare(config.copy(profileName = name)).extraHeaders.keys)
        }
        assertEquals(1, api.probes)
    }

    @Test fun capturedDisplayMetadataDoesNotReenableDiagnosticsOrChangeCapabilityIdentity() = runBlocking {
        val enabled = LocalDiagnosticsPolicy.enabled
        try {
            LocalDiagnosticsPolicy.setEnabled(true)
            val headers = listOf("X-Gateway-Profile-ID", "X-Gateway-Profile-Name-Base64", GatewayClientDiagnostics.CLIENT_HEADER, "traceparent")
            val api = Fake(GatewayCapabilities(gatewayVersion = "15.0.0", acceptedRequestHeaders = headers))
            val negotiation = GatewayControlNegotiation(api)
            val captured = GatewayClientDiagnostics.withHeaders(ProviderRequestConfig("https://profile-label-diagnostics.example/v1", "account", profileUid = "first", profileName = "First", providerType = "LLAMA"), "model")
            GatewayClientDiagnostics.observeInstance(captured, "8".repeat(32))
            LocalDiagnosticsPolicy.setEnabled(false)
            val first = negotiation.prepare(captured)
            val renamed = negotiation.prepare(captured.copy(profileUid = "second", profileName = "Renamed 東京"))
            assertEquals(setOf(headers[0], headers[1]), first.extraHeaders.keys)
            assertEquals(setOf(headers[0], headers[1]), renamed.extraHeaders.keys)
            assertEquals("second", renamed.extraHeaders[headers[0]])
            assertEquals("Renamed 東京", Base64.getUrlDecoder().decode(renamed.extraHeaders[headers[1]]).toString(Charsets.UTF_8))
            assertEquals(GatewayClientDiagnostics.routeIdentity(first), GatewayClientDiagnostics.routeIdentity(renamed))
            assertFalse(api.configurations.single().extraHeaders.keys.any { it.startsWith("X-Gateway-", true) || it.equals("traceparent", true) || it.equals("X-Request-ID", true) })
            assertEquals(1, api.probes)
        } finally {
            LocalDiagnosticsPolicy.setEnabled(enabled)
        }
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
