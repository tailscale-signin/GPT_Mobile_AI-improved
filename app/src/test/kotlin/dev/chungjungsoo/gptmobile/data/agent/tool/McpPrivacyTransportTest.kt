package dev.chungjungsoo.gptmobile.data.agent.tool

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import dev.chungjungsoo.gptmobile.data.network.PrivateMetadataDenied
import dev.chungjungsoo.gptmobile.data.network.PrivateMetadataGate
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.api.SendingRequest
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.serialization.kotlinx.json.json
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
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

    private fun client(): HttpClient = HttpClient(CIO) {
        followRedirects = false
        install(PrivateMetadataGate)
        install(HttpTimeout) { requestTimeoutMillis = 5_000 }
    }

    @Test fun privateConversationCannotDiscoverOrUseTheOrdinaryConversationCatalogue() = runBlocking(ConversationPrivacyContext(101, false)) {
        FixtureServer().use { server ->
            val http = client()
            val manager = McpClientManager(http, modern = ModernMcpTransport(http))
            val config = McpConnectionConfig("privacy-race", server.url, true)
            try {
                val original = manager.healthSnapshot(config.connectionUid)
                val first = withContext(ConversationPrivacyContext(102, true)) {
                    runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
                }
                assertTrue(first?.isLocalPrivacyPolicyRejection() == true)
                assertEquals(original, manager.healthSnapshot(config.connectionUid))
                assertTrue(server.methods.isEmpty())
                assertEquals(0L, LocalServiceHealth.retryAfterMillis(server.url))

                // Availability in Options neither protects nor blocks this ordinary chat.
                privacy(true)
                assertEquals(listOf("echo"), manager.listTools(config, awaitCompletion = true).map { it.name })
                val ready = manager.healthSnapshot(config.connectionUid)
                assertTrue(ready.toolsReady)
                assertEquals(listOf("server/discover", "tools/list"), server.methods.toList())

                privacy(false)
                val denied = withContext(ConversationPrivacyContext(102, true)) {
                    val cached = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
                    assertTrue(cached?.isLocalPrivacyPolicyRejection() == true)
                    val tool = runCatching { manager.callTool(config, "echo", buildJsonObject { put("text", "private") }) }.exceptionOrNull()
                    assertTrue(tool?.isLocalPrivacyPolicyRejection() == true)
                    cached
                }
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
                assertEquals(listOf("echo"), manager.listTools(config, awaitCompletion = true).map { it.name })
                assertEquals(2, server.methods.size)
                assertEquals(0, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
            } finally {
                manager.closeAll()
                http.close()
            }
        }
    }

    @Test fun aPrivateConversationProbePreservesAnOrdinaryConnectionFailureUntilLiveRecovery() = runBlocking(ConversationPrivacyContext(201, false)) {
        FixtureServer().use { server ->
            server.unavailable = true
            val http = client()
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
                val policy = withContext(ConversationPrivacyContext(202, true)) {
                    val denied = runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
                    assertFalse(manager.probe(config))
                    denied
                }
                assertTrue(policy?.isLocalPrivacyPolicyRejection() == true)
                assertEquals(previous, manager.healthSnapshot(config.connectionUid))
                assertTrue(LocalServiceHealth.retryAfterMillis(server.url) in 1..recoveryWindow)
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

    @Test fun availabilityChangesDuringDiscoveryCannotChangeTheOriginatingConversationPolicy() = runBlocking(ConversationPrivacyContext(301, false)) {
        FixtureServer().use { server ->
            server.afterDiscovery = { privacy(true) }
            val http = client()
            val manager = McpClientManager(http, modern = ModernMcpTransport(http))
            val config = McpConnectionConfig("privacy-compound-response", server.url, true)
            try {
                assertEquals(listOf("echo"), manager.listTools(config, forceRefresh = true, awaitCompletion = true).map { it.name })
                assertEquals(listOf("server/discover", "tools/list"), server.methods.toList())
                assertEquals(0, manager.healthSnapshot(config.connectionUid).consecutiveFailures)

                server.afterDiscovery = null
                privacy(false)
                withContext(ConversationPrivacyContext(302, true)) {
                    assertTrue(runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()?.isLocalPrivacyPolicyRejection() == true)
                }
                assertEquals(2, server.methods.size)
            } finally {
                manager.closeAll()
                http.close()
            }
        }
    }

    @Test fun privateCallerCannotJoinAnOrdinaryApplicationOwnedDiscovery() = runBlocking {
        FixtureServer().use { server ->
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            server.afterDiscovery = {
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
            }
            val http = client()
            val manager = McpClientManager(http, modern = ModernMcpTransport(http))
            val config = McpConnectionConfig("privacy-shared-producer", server.url, true)
            try {
                val ordinary = async(ConversationPrivacyContext(401, false)) {
                    manager.listTools(config, forceRefresh = true, awaitCompletion = true)
                }
                assertTrue(withContext(Dispatchers.IO) { entered.await(3, TimeUnit.SECONDS) })
                val before = manager.healthSnapshot(config.connectionUid)
                val failure = withContext(ConversationPrivacyContext(402, true)) {
                    runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull()
                }
                assertTrue(failure?.isLocalPrivacyPolicyRejection() == true)
                assertEquals(before, manager.healthSnapshot(config.connectionUid))
                assertEquals(listOf("server/discover"), server.methods.toList())
                privacy(true)
                release.countDown()
                assertEquals(listOf("echo"), ordinary.await().map { it.name })
                assertEquals(listOf("server/discover", "tools/list"), server.methods.toList())
            } finally {
                release.countDown()
                manager.closeAll()
                http.close()
            }
        }
    }

    @Test fun aLaterLocalGateRejectionCannotEraseAnEarlierInterruptedRead() = runBlocking(ConversationPrivacyContext(501, false)) {
        for (first in listOf(java.io.EOFException("fixture lost response"), IllegalStateException("SDK wrapper", java.io.EOFException("fixture lost response")))) {
            val denyRetry = AtomicBoolean(false)
            val localGate = createClientPlugin("RejectInterruptedReadRetry") {
                on(SendingRequest) { _, _ -> if (denyRetry.get()) throw PrivateMetadataDenied() }
            }
            var attempts = 0
            val http = HttpClient(
                MockEngine {
                    attempts++
                    denyRetry.set(true)
                    throw first
                }
            ) {
                install(localGate)
                install(PrivateMetadataGate)
            }
            val manager = McpClientManager(http, modern = ModernMcpTransport(http))
            val config = McpConnectionConfig("privacy-uncertain-retry", "https://example.com/mcp", false)
            try {
                val failure = requireNotNull(runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull())
                val presentation = mcpFailurePresentation(failure, manager.healthSnapshot(config.connectionUid))
                assertEquals("POLICY_BLOCKED", presentation.outcome)
                assertEquals("UNKNOWN", presentation.dispatchEvidence)
                assertEquals(null, presentation.observedAtEpochMs)
                assertEquals(null, presentation.httpStatus)
                assertEquals(1, attempts)
                assertEquals(0, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
            } finally {
                manager.closeAll()
                http.close()
            }
        }
    }

    @Test fun anInterruptedCachedLegacyReadSurvivesALocalReconnectRejection() = runBlocking(ConversationPrivacyContext(601, false)) {
        FixtureServer(legacy = true).use { server ->
            val denyReconnect = AtomicBoolean(false)
            val localGate = createClientPlugin("RejectInterruptedLegacyReconnect") {
                on(SendingRequest) { _, _ -> if (denyReconnect.get()) throw PrivateMetadataDenied() }
            }
            val http = HttpClient(CIO) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(SSE)
                install(localGate)
                install(PrivateMetadataGate)
                install(HttpTimeout) { requestTimeoutMillis = 5_000 }
            }
            val manager = McpClientManager(http, retryJitterMs = { 0 })
            val config = McpConnectionConfig("privacy-legacy-retry", server.url, true)
            try {
                assertEquals(listOf("echo"), manager.listTools(config, awaitCompletion = true).map { it.name })
                server.interruptToolsList = { denyReconnect.set(true) }
                val failure = requireNotNull(runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.exceptionOrNull())
                val presentation = mcpFailurePresentation(failure, manager.healthSnapshot(config.connectionUid))
                assertEquals("POLICY_BLOCKED", presentation.outcome)
                assertEquals("UNKNOWN", presentation.dispatchEvidence)
                assertEquals(null, presentation.observedAtEpochMs)
                assertEquals(null, presentation.httpStatus)
                assertEquals(1, server.methods.count { it == "initialize" })
                assertEquals(2, server.methods.count { it == "tools/list" })
            } finally {
                manager.closeAll()
                http.close()
            }
        }
    }

    private class FixtureServer(private val legacy: Boolean = false) : AutoCloseable {
        val methods = CopyOnWriteArrayList<String>()

        @Volatile var unavailable = false

        @Volatile var afterDiscovery: (() -> Unit)? = null

        @Volatile var interruptToolsList: (() -> Unit)? = null
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/mcp", ::handle)
            start()
        }
        val url = "http://127.0.0.1:${server.address.port}/mcp"

        private fun handle(exchange: HttpExchange) {
            try {
                if (exchange.requestMethod != "POST") {
                    exchange.sendResponseHeaders(if (exchange.requestMethod == "DELETE") 202 else 405, -1)
                    return
                }
                val request = Json.parseToJsonElement(exchange.requestBody.bufferedReader().readText()).jsonObject
                val method = request.getValue("method").jsonPrimitive.content
                methods += method
                if (method == "notifications/initialized") {
                    exchange.sendResponseHeaders(202, -1)
                    return
                }
                if (method == "tools/list" && interruptToolsList != null) {
                    interruptToolsList?.invoke()
                    // The server received the request, but closes before sending
                    // response headers or body. Only the subsequent retry is denied.
                    return
                }
                if (method == "server/discover") afterDiscovery?.invoke()
                val result = when (method) {
                    "server/discover" -> """{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                    "initialize" -> """{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"fixture","version":"1"}}"""
                    "tools/list" -> if (legacy) """{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""" else """{"resultType":"complete","tools":[{"name":"echo","inputSchema":{"type":"object"}}]}"""
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
                if (method == "initialize") exchange.responseHeaders.set("Mcp-Session-Id", "privacy-session")
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
