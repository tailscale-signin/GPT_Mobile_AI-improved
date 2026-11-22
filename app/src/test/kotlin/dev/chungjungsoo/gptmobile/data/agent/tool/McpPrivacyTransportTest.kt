package dev.chungjungsoo.gptmobile.data.agent.tool

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.PrivateMetadataGate
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class McpPrivacyTransportTest {
    @Before fun initialize() {
        PrivacyModeStore.initialize(ApplicationProvider.getApplicationContext<Application>())
        privacy(false)
        LocalServiceHealth.networkChanged()
    }

    @After fun reset() {
        privacy(false)
        LocalServiceHealth.networkChanged()
    }

    private fun privacy(enabled: Boolean) {
        val preferences = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("device_privacy", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean("privacy_mode_enabled", enabled).putLong("privacy_revision", preferences.getLong("privacy_revision", 0) + 1).commit())
    }

    private fun client(flipOnSend: AtomicBoolean): HttpClient {
        val policyChange = createClientPlugin("PolicyChangeBeforeTransport") {
            on(SendingRequest) { _, _ -> if (flipOnSend.compareAndSet(true, false)) privacy(true) }
        }
        return HttpClient(CIO) {
            followRedirects = false
            install(policyChange)
            install(PrivateMetadataGate)
            install(HttpTimeout) { requestTimeoutMillis = 5_000 }
        }
    }

    @Test fun policyChangeAtTheSendBoundaryRetainsTheCatalogueAndDoesNotCreateRecovery() = runBlocking {
        FixtureServer().use { server ->
            val flipOnSend = AtomicBoolean(true)
            val http = client(flipOnSend)
            val manager = McpClientManager(http, modern = ModernMcpTransport(http))
            val config = McpConnectionConfig("privacy-race", server.url, true)
            try {
                val original = manager.healthSnapshot(config.connectionUid)
                val first = runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
                assertTrue(first?.isLocalPrivacyPolicyRejection() == true)
                assertEquals(original, manager.healthSnapshot(config.connectionUid))
                assertTrue(server.methods.isEmpty())
                assertEquals(0L, LocalServiceHealth.retryAfterMillis(server.url))

                privacy(false)
                assertEquals(listOf("echo"), manager.listTools(config, awaitCompletion = true).map { it.name })
                val ready = manager.healthSnapshot(config.connectionUid)
                assertTrue(ready.toolsReady)
                assertEquals(listOf("server/discover", "tools/list"), server.methods.toList())

                flipOnSend.set(true)
                val denied = runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
                assertTrue(denied?.isLocalPrivacyPolicyRejection() == true)
                assertEquals(ready, manager.healthSnapshot(config.connectionUid))
                assertEquals(listOf("echo"), manager.peekTools(config)?.map { it.name })
                assertEquals(2, server.methods.size)
                assertEquals(0L, LocalServiceHealth.retryAfterMillis(server.url))
                val presentation = mcpFailurePresentation(requireNotNull(denied), manager.healthSnapshot(config.connectionUid))
                assertEquals("POLICY_BLOCKED", presentation.outcome)
                assertEquals("NOT_DISPATCHED", presentation.dispatchEvidence)
                assertEquals(false, presentation.retryable)
                val reportFields = manager.failureReportFields(config.connectionUid, denied, "discovery")
                assertEquals(kotlinx.serialization.json.JsonPrimitive("POLICY_BLOCKED"), reportFields["mcp_failure_category"])
                assertEquals(kotlinx.serialization.json.JsonPrimitive("local_validation"), reportFields["mcp_failure_phase"])
                assertEquals("PRIVACY_SERVICE_UNVERIFIED", mcpConnectFailureReportCode(denied))

                privacy(false)
                assertEquals(listOf("echo"), manager.listTools(config, forceRefresh = true, awaitCompletion = true).map { it.name })
                assertEquals(4, server.methods.size)
                assertEquals(0, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
            } finally {
                manager.closeAll()
                http.close()
            }
        }
    }

    @Test fun aPolicyRaceAndBlockedProbePreserveARealFailureUntilLiveRecovery() = runBlocking {
        FixtureServer().use { server ->
            server.unavailable = true
            val flipOnSend = AtomicBoolean(false)
            val http = client(flipOnSend)
            val manager = McpClientManager(http, modern = ModernMcpTransport(http), retryJitterMs = { 0 })
            val config = McpConnectionConfig("privacy-existing-failure", server.url, true)
            try {
                assertTrue(runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.isFailure)
                val previous = manager.healthSnapshot(config.connectionUid)
                val recoveryWindow = LocalServiceHealth.retryAfterMillis(server.url)
                assertEquals(503, previous.lastHttpStatus)
                assertTrue(recoveryWindow > 0)
                assertEquals(1, server.methods.size)

                server.unavailable = false
                flipOnSend.set(true)
                val policy = runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
                assertTrue(policy?.isLocalPrivacyPolicyRejection() == true)
                assertEquals(previous, manager.healthSnapshot(config.connectionUid))
                assertTrue(LocalServiceHealth.retryAfterMillis(server.url) in 1..recoveryWindow)
                assertFalse(manager.probe(config))
                assertEquals(previous, manager.healthSnapshot(config.connectionUid))
                assertEquals(1, server.methods.size)

                privacy(false)
                val waiting = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
                assertTrue(waiting is McpBackoffException)
                assertEquals(1, server.methods.size)
                assertEquals(listOf("echo"), manager.listTools(config, forceRefresh = true, awaitCompletion = true).map { it.name })
                assertEquals(3, server.methods.size)
                assertEquals(null, manager.healthSnapshot(config.connectionUid).nextRetryAtMs)
                assertEquals(0L, LocalServiceHealth.retryAfterMillis(server.url))
            } finally {
                manager.closeAll()
                http.close()
            }
        }
    }

    @Test fun policyDenialOfToolsListRetainsTheEarlierDiscoveryResponse() = runBlocking {
        FixtureServer().use { server ->
            server.afterDiscovery = { privacy(true) }
            val http = client(AtomicBoolean(false))
            val manager = McpClientManager(http, modern = ModernMcpTransport(http))
            val config = McpConnectionConfig("privacy-compound-response", server.url, true)
            try {
                val started = System.currentTimeMillis()
                val failure = runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
                val presentation = mcpFailurePresentation(requireNotNull(failure), manager.healthSnapshot(config.connectionUid))
                assertEquals("PRIVACY_SERVICE_UNVERIFIED", presentation.errorCode)
                assertEquals("POLICY_BLOCKED", presentation.outcome)
                assertEquals("CONFIRMED", presentation.dispatchEvidence)
                assertTrue(requireNotNull(presentation.observedAtEpochMs) >= started)
                assertEquals(null, presentation.httpStatus)
                assertEquals(listOf("server/discover"), server.methods.toList())
                assertEquals(0, manager.healthSnapshot(config.connectionUid).consecutiveFailures)

                server.afterDiscovery = null
                privacy(false)
                assertEquals(listOf("echo"), manager.listTools(config, forceRefresh = true, awaitCompletion = true).map { it.name })
                assertEquals(3, server.methods.size)
            } finally {
                manager.closeAll()
                http.close()
            }
        }
    }

    @Test fun localRejectionOfAReadRetryCannotEraseTheEarlierUncertainAttempt() = runBlocking {
        var attempts = 0
        val http = HttpClient(
            MockEngine {
                attempts++
                privacy(true)
                throw java.io.EOFException("fixture lost response")
            }
        ) { install(PrivateMetadataGate) }
        val manager = McpClientManager(http, modern = ModernMcpTransport(http))
        val config = McpConnectionConfig("privacy-uncertain-retry", "https://example.com/mcp", false)
        try {
            val failure = runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
            val presentation = mcpFailurePresentation(requireNotNull(failure), manager.healthSnapshot(config.connectionUid))
            assertEquals("POLICY_BLOCKED", presentation.outcome)
            assertEquals("UNKNOWN", presentation.dispatchEvidence)
            assertEquals(null, presentation.observedAtEpochMs)
            assertEquals(null, presentation.httpStatus)
            assertEquals(1, attempts)
        } finally {
            manager.closeAll()
            http.close()
        }
    }

    private class FixtureServer : AutoCloseable {
        val methods = CopyOnWriteArrayList<String>()

        @Volatile var unavailable = false

        @Volatile var afterDiscovery: (() -> Unit)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp", ::handle)
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/mcp"

        private fun handle(exchange: HttpExchange) {
            try {
                val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                if (method == "server/discover") afterDiscovery?.invoke()
                val result = when (method) {
                    "server/discover" -> """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                    "tools/list" -> """{"resultType":"complete","tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
                    else -> error("Unexpected fixture method: $method")
                }
                val body = if (unavailable) {
                    "{}"
                } else {
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", request.getValue("id"))
                        put("result", Json.parseToJsonElement(result))
                    }.toString()
                }
                val bytes = body.toByteArray()
                exchange.responseHeaders.set("Content-Type", "application/json")
                exchange.sendResponseHeaders(if (unavailable) 503 else 200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } finally {
                exchange.close()
            }
        }

        override fun close() = server.stop(0)
    }
}
