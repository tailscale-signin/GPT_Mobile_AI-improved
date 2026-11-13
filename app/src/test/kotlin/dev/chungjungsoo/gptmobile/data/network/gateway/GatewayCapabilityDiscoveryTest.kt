package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.io.EOFException
import java.net.SocketTimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCapabilityDiscoveryTest {
    @Test fun concurrentCapabilityObserversShareOneReadButAuditsKeepTheirOwnReceipt() = kotlinx.coroutines.test.runTest {
        val selected = config.copy(apiUrl = "https://coalesced-capability.example/v1", profileRevision = "a".repeat(64))
        val calls = java.util.concurrent.atomic.AtomicInteger()
        HttpClient(
            MockEngine {
                calls.incrementAndGet()
                kotlinx.coroutines.delay(20)
                respond("""{"gatewayVersion":"16.0.0","instanceId":"${"a".repeat(32)}"}""")
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network.gatewayReadClient() } returns http
            val api = GatewayAPIImpl(network)
            val observations = kotlinx.coroutines.coroutineScope {
                (1..4).map { async { api.getCapabilities(selected) } }.map { it.await() }
            }
            assertEquals(4, observations.size)
            assertTrue(observations.all { it?.gatewayVersion == "16.0.0" })
            assertEquals(1, calls.get())
            assertEquals("16.0.0", api.getCapabilities(selected)?.gatewayVersion)
            assertEquals(1, calls.get())
            api.getCapabilities(selected, forceRefresh = true)
            assertEquals(2, calls.get())
            kotlinx.coroutines.withContext(GatewayCapabilityReadContext()) {
                api.getCapabilities(selected)
                api.getCapabilities(selected)
            }
            assertEquals(4, calls.get())
            coVerify(exactly = 0) { network.evictIdleConnections() }
        }
    }

    @Test fun capabilityCacheIsBoundToEndpointCredentialsConfigurationAndObservedRuntime() = runBlocking {
        var calls = 0
        var instance = "a".repeat(32)
        val selected = config.copy(apiUrl = "https://scoped-capability.example/v1", profileRevision = "a".repeat(64))
        HttpClient(
            MockEngine {
                calls++
                respond("""{"gatewayVersion":"16.0.0","instanceId":"$instance"}""")
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network.gatewayReadClient() } returns http
            val api = GatewayAPIImpl(network)
            repeat(2) { api.getCapabilities(selected) }
            assertEquals(1, calls)
            api.getCapabilities(selected.copy(profileRevision = "b".repeat(64)))
            api.getCapabilities(selected.copy(token = "replacement-credential"))
            api.getCapabilities(selected.copy(extraHeaders = mapOf("X-Api-Key" to "replacement-header")))
            api.getCapabilities(selected.copy(apiUrl = "https://other-scoped-capability.example/v1"))
            assertEquals(5, calls)
            instance = "b".repeat(32)
            GatewayClientDiagnostics.observeInstance(selected, instance)
            assertEquals(instance, api.getCapabilities(selected)?.instanceId)
            assertEquals(6, calls)
        }
    }

    private val config = ProviderRequestConfig("https://capability-discovery.example/v1", "credential", extraHeaders = mapOf("X-Api-Key" to "custom credential"))

    @org.junit.Before fun clearRememberedSurfaces() = GatewaySurfacePolicy.shared.invalidate()

    @Test fun unsupportedCapabilityProbeIsCachedPerCredentialAndEndpoint() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine {
                calls++
                respond("not found", HttpStatusCode.NotFound)
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            every { network.gatewayReadClient() } returns http
            val api = GatewayAPIImpl(network)
            repeat(3) { assertNull(api.getCapabilities(config)) }
            assertEquals(1, calls)
            assertNull(api.getCapabilities(config.copy(token = "another credential")))
            assertNull(api.getCapabilities(config.copy(apiUrl = "https://another-route.example/v1")))
            assertEquals(3, calls)
        }
    }

    @Test fun fullRouteCapabilitiesDecodeAfterOneMetadataGetWithoutInference() = runBlocking {
        for (name in listOf("gateway-v15-capabilities-legacy-mixed.json", "gateway-v15-capabilities-full.json")) {
            val fixture = requireNotNull(javaClass.classLoader?.getResourceAsStream(name)).bufferedReader().use { it.readText() }
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    respond(fixture, HttpStatusCode.OK)
                }
            ).use { http ->
                val network = mockk<NetworkClient>()
                every { network() } returns http
                every { network.gatewayReadClient() } returns http
                val capabilities = requireNotNull(GatewayAPIImpl(network).getCapabilities(config))
                assertEquals("15.0.0", capabilities.gatewayVersion)
                assertTrue(capabilities.supportsKeyFreeConnection)
                assertTrue(capabilities.supportsFeature("validatedToolArguments"))
                assertFalse(capabilities.supportsFeature("gateway_server_icon"))
                assertFalse(capabilities.supportsFeature("disconnect_grace"))
                assertEquals(1, calls)
                coVerify(exactly = 0) { network.evictIdleConnections() }
            }
        }
    }

    @Test fun invalidCapabilityFieldsExposeOnlySchemaReasonAndPath() = runBlocking {
        val privateValue = "Bearer private-credential https://private-host.invalid/path?token=private-token"
        val cases = listOf(
            Triple("""{"gatewayVersion":"15.0.0","auth":{"mode":"loopback","apiKeyRequired":"$privateValue"}}""", "EXPECTED_BOOLEAN", "$.auth.apiKeyRequired"),
            Triple("""{"gatewayVersion":"15.0.0","contractVersion":"$privateValue"}""", "EXPECTED_INTEGER", "$.contractVersion"),
            Triple("""{"gatewayVersion":"15.0.0","auth":{"apiKeyRequired":false}}""", "MISSING_REQUIRED_FIELD", "$.auth.mode"),
            Triple("""{"gatewayVersion":"15.0.0","contracts":{"$privateValue":true}}""", "EXPECTED_ARRAY", "$.contracts[*]"),
            Triple("""{"gatewayVersion":"15.0.0","accepted_request_headers":false,"private":"$privateValue"}""", "EXPECTED_ARRAY", "$.accepted_request_headers"),
            Triple("""{"gatewayVersion":"15.0.0","private":"$privateValue"""", "MALFORMED_JSON", "$")
        )
        for ((body, reason, path) in cases) {
            var calls = 0
            HttpClient(
                MockEngine { request ->
                    calls++
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    respond(body)
                }
            ).use { http ->
                val network = mockk<NetworkClient>()
                every { network() } returns http
                every { network.gatewayReadClient() } returns http
                val failure = runCatching { GatewayAPIImpl(network).getCapabilities(config) }.exceptionOrNull()
                assertTrue(failure is GatewayCapabilityDiscoveryException)
                val diagnostic = failure as GatewayCapabilityDiscoveryException
                assertEquals(reason, diagnostic.decodeReason)
                assertEquals(path, diagnostic.fieldPath)
                assertTrue(diagnostic.message.orEmpty().contains("no model request was sent"))
                assertNull(diagnostic.cause)
                val exposed = diagnostic.stackTraceToString()
                for (privateText in listOf(privateValue, "private-host", "private-credential", "private-token", "custom credential", "capability-discovery.example")) {
                    assertFalse(privateText, exposed.contains(privateText))
                }
                assertTrue(diagnostic.message.orEmpty().length < 512)
                assertEquals(1, calls)
                coVerify(exactly = 0) { network.evictIdleConnections() }
            }
        }
    }

    @Test fun capabilityCancellationPropagatesWithoutDiscoveryFailureOrRetry() = runBlocking {
        val cancellation = CancellationException("caller stopped")
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("GET", request.method.value)
                assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                throw cancellation
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            every { network.gatewayReadClient() } returns http
            val failure = runCatching { GatewayAPIImpl(network).getCapabilities(config) }.exceptionOrNull()
            assertTrue(failure is CancellationException)
            assertEquals(cancellation.message, failure?.message)
            // Coroutine stack-trace recovery may copy an exception across a suspension boundary.
            assertTrue(
                "The caller cancellation is retained directly or as a cause",
                generateSequence(failure) { it.cause }.take(8).any { it === cancellation }
            )
            assertEquals(1, calls)
            coVerify(exactly = 0) { network.evictIdleConnections() }
        }
    }

    @Test fun oneStaleCapabilityReadEvictsOnlyIdleSocketsBeforeOneReadRetry() = runBlocking {
        var calls = 0
        val selected = config.copy(apiUrl = "http://127.0.0.1:29784/v1")
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("GET", request.method.value)
                assertEquals("Bearer credential", request.headers["Authorization"])
                assertEquals("custom credential", request.headers["X-Api-Key"])
                if (calls == 1) throw EOFException("broken pipe")
                assertEquals("close", request.headers["Connection"])
                respond("""{"gatewayVersion":"15.0.0","contractVersion":1}""")
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            every { network.gatewayReadClient() } returns http
            coEvery { network.evictIdleConnections() } returns Unit
            assertEquals("15.0.0", GatewayAPIImpl(network).getCapabilities(selected)?.gatewayVersion)
            assertEquals(2, calls)
            coVerify(exactly = 1) { network.evictIdleConnections() }
        }
    }

    @Test fun lostCancellationResponseIsUnknownAndNeverReplayedOrCountedAsASafeRead() = runBlocking {
        var calls = 0
        HttpClient(
            MockEngine { request ->
                calls++
                assertEquals("POST", request.method.value)
                assertTrue(request.url.encodedPath.endsWith("/job-one/cancel"))
                throw EOFException("unexpected end of stream")
            }
        ).use { http ->
            val network = mockk<NetworkClient>()
            every { network() } returns http
            every { network.gatewayReadClient() } returns http
            assertNull(GatewayAPIImpl(network).cancelJob("job-one", config))
            assertEquals(1, calls)
            coVerify(exactly = 0) { network.evictIdleConnections() }
        }
    }

    @Test fun onlyDefiniteUnsupportedStatusesRepresentADirectInferenceRoute() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.NotImplemented, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)) {
            GatewaySurfacePolicy.shared.invalidate()
            val client = HttpClient(
                MockEngine { request ->
                    assertEquals("GET", request.method.value)
                    assertEquals("/v1/gateway/capabilities", request.url.encodedPath)
                    assertEquals("Bearer credential", request.headers["Authorization"])
                    assertEquals("custom credential", request.headers["X-Api-Key"])
                    respond("private server response", status)
                }
            )
            val network = mockk<NetworkClient>()
            every { network() } returns client
            every { network.gatewayReadClient() } returns client
            try {
                val result = runCatching { GatewayAPIImpl(network).getCapabilities(config) }
                if (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.NotImplemented)) {
                    assertNull(result.getOrThrow())
                } else {
                    assertTrue(result.exceptionOrNull() is GatewayCapabilityDiscoveryException)
                    assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("private"))
                }
            } finally {
                client.close()
            }
        }
    }

    @Test fun timeoutAndMalformedCapabilitiesCannotSilentlyRemoveIsolationControls() = runBlocking {
        for (malformed in listOf(false, true)) {
            val client = HttpClient(
                MockEngine {
                    if (malformed) respond("""{"unrelated":"private server body"}""") else throw SocketTimeoutException("private endpoint detail")
                }
            )
            val network = mockk<NetworkClient>()
            every { network() } returns client
            every { network.gatewayReadClient() } returns client
            try {
                val result = runCatching { GatewayAPIImpl(network).getCapabilities(config) }
                assertTrue(result.exceptionOrNull() is GatewayCapabilityDiscoveryException)
                assertFalse(result.exceptionOrNull()?.message.orEmpty().contains("private"))
            } finally {
                client.close()
            }
        }
    }
}
