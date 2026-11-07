package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.ConnectionHealth
import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.io.IOException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

class McpFailureDiagnosticsTest {
    @Before fun resetHealth() = LocalServiceHealth.networkChanged()

    @After fun clearHealth() = LocalServiceHealth.networkChanged()

    @Test fun protocolFailureKeepsItsCodeWhilePausedAndClearsAfterVerifiedReconnect() = runBlocking {
        var valid = false
        var requests = 0
        val client = HttpClient(
            MockEngine { request ->
                requests++
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val result = when (body.getValue("method").jsonPrimitive.content) {
                    "server/discover" -> if (valid) """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""" else """{"supportedVersions":[]}"""
                    else -> """{"tools":[]}"""
                }
                respond(reply(body, result), headers = headersOf("Content-Type", "application/json"))
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client))
        val config = McpConnectionConfig("protocol", "https://protocol.example/mcp", false)
        try {
            val first = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
            assertTrue(first is McpProtocolConfigurationException)
            assertEquals("MCP_DISCOVERY_SCHEMA_INVALID", manager.healthSnapshot(config.connectionUid).lastErrorCode)
            assertEquals(false, manager.healthSnapshot(config.connectionUid).failureRetryable)
            repeat(3) {
                assertTrue(runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull() === first)
            }
            assertEquals(1, requests)
            assertEquals(1, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
            assertEquals("MCP_DISCOVERY_SCHEMA_INVALID", manager.healthSnapshot(config.connectionUid).lastErrorCode)

            valid = true
            assertTrue(manager.listTools(config, forceRefresh = true, awaitCompletion = true).isEmpty())
            assertEquals(3, requests)
            val recovered = manager.healthSnapshot(config.connectionUid)
            assertTrue(recovered.toolsReady)
            assertEquals(null, recovered.lastErrorCode)
            assertEquals(null, recovered.failureRetryable)
        } finally {
            manager.closeAll()
            client.close()
        }
    }

    @Test fun tlsFailurePausesDiscoveryUntilExplicitReconnectAndNeverSelectsLegacyTransport() = runBlocking {
        var requests = 0
        var now = 1_000L
        val client = HttpClient(
            MockEngine {
                requests++
                throw IOException("MCP initialization failed", SSLHandshakeException("Certificate verification failed"))
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client), nowMs = { now })
        val config = McpConnectionConfig("tls", "https://certificate.example/mcp", false)
        try {
            assertTrue(runCatching { manager.listTools(config, awaitCompletion = true) }.isFailure)
            val failed = manager.healthSnapshot(config.connectionUid)
            assertEquals(1, requests)
            assertEquals(ConnectionHealth.TLS_FAILED, failed.connectionHealth)
            assertEquals("tls", failed.failedStage)
            assertEquals("TLS_FAILURE", failed.lastErrorCode)
            assertEquals(false, failed.failureRetryable)
            assertEquals(Long.MAX_VALUE, failed.nextRetryAtMs)
            assertFalse(failed.toolsReady)
            now += 24 * 60 * 60_000L
            repeat(3) {
                assertTrue(runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull() is McpBackoffException)
            }
            assertEquals(1, requests)
            assertEquals(1, manager.healthSnapshot(config.connectionUid).consecutiveFailures)

            assertTrue(runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.isFailure)
            assertEquals(2, requests)
            assertEquals("TLS_FAILURE", manager.healthSnapshot(config.connectionUid).lastErrorCode)
            assertEquals(false, manager.healthSnapshot(config.connectionUid).failureRetryable)
        } finally {
            manager.closeAll()
            client.close()
        }
    }

    private fun reply(request: JsonObject, result: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.getValue("id"))
        put("result", Json.parseToJsonElement(result))
    }.toString()
}
