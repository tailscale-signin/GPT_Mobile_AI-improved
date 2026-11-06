package dev.chungjungsoo.gptmobile.data.network.gateway

import dev.chungjungsoo.gptmobile.data.network.NetworkClient
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.EOFException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCapabilityDiscoveryTest {
    private val config = ProviderRequestConfig("https://capability-discovery.example/v1", "credential", extraHeaders = mapOf("X-Api-Key" to "custom credential"))

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
            every { network.evictIdleConnections() } returns Unit
            assertEquals("15.0.0", GatewayAPIImpl(network).getCapabilities(selected)?.gatewayVersion)
            assertEquals(2, calls)
            verify(exactly = 1) { network.evictIdleConnections() }
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
            assertNull(GatewayAPIImpl(network).cancelJob("job-one", config))
            assertEquals(1, calls)
            verify(exactly = 0) { network.evictIdleConnections() }
        }
    }

    @Test fun onlyDefiniteUnsupportedStatusesRepresentADirectInferenceRoute() = runBlocking {
        for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.NotImplemented, HttpStatusCode.Forbidden, HttpStatusCode.ServiceUnavailable)) {
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
