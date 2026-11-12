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
    @Test fun refusedDiscoveryCarriesOnePreassignedOperationAndRevisionThroughTransport() = runBlocking {
        val logs = java.util.Collections.synchronizedList(mutableListOf<String>())
        val requests = mutableListOf<String>()
        val revisions = mutableListOf<String?>()
        val client = HttpClient(
            MockEngine {
                requests += checkNotNull(kotlinx.coroutines.currentCoroutineContext()[dev.chungjungsoo.gptmobile.data.network.LocalMetadataReadAttempt]).readId
                throw java.net.ConnectException("Connection refused")
            }
        ) {
            install(io.ktor.client.plugins.logging.Logging) {
                level = io.ktor.client.plugins.logging.LogLevel.HEADERS
                logger = object : io.ktor.client.plugins.logging.Logger {
                    override fun log(message: String) {
                        logs += dev.chungjungsoo.gptmobile.data.network.LocalMetadataReadAttempt.correlateLog(message)
                    }
                }
            }
        }
        val manager = McpClientManager(client, modern = ModernMcpTransport(client), nowMs = { 10_000L })
        val config = McpConnectionConfig("refused", "https://refused.example/mcp", false, configurationRevision = "a".repeat(64))
        try {
            kotlinx.coroutines.withContext(McpDiscoveryContext(McpDiscoveryTrigger.AUDIT_REFRESH, beforeOwnedDispatch = {}, onOwnedConfigurationRevision = { revisions += it })) {
                assertTrue(runCatching { manager.listTools(config, awaitCompletion = true) }.isFailure)
            }
            assertEquals(listOf(config.configurationRevision), revisions)
            assertEquals(1, requests.size)
            assertTrue(requests.single().matches(Regex("[a-f0-9]{32}")))
            assertTrue(logs.single { "failed with exception" in it }.contains("operation_id=${requests.single()}"))
            val health = manager.healthSnapshot(config.connectionUid)
            assertEquals("CONNECTION_REFUSED", health.lastErrorCode)
            assertEquals("connection", health.failedStage)
            assertEquals("UNKNOWN", health.lastDispatchEvidence)
            assertEquals(config.configurationRevision, health.failureConfigurationRevision)
            repeat(3) { assertTrue(runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull() is McpBackoffException) }
            assertEquals(1, requests.size)
            assertTrue(runCatching { manager.listTools(config, forceRefresh = true, awaitCompletion = true) }.isFailure)
            assertEquals(2, requests.size)
            assertEquals(2, requests.distinct().size)
        } finally {
            manager.closeAll()
            client.close()
        }
        assertEquals("outside", dev.chungjungsoo.gptmobile.data.network.LocalMetadataReadAttempt.correlateLog("outside"))
    }

    @Before fun resetHealth() = LocalServiceHealth.networkChanged()

    @After fun clearHealth() = LocalServiceHealth.networkChanged()

    @Test fun connectReportPreservesMcpFailuresBeforeGenericTransportClassification() {
        val schemaFailure = McpProtocolConfigurationException("MCP_INITIALIZATION_SCHEMA_INVALID", "Invalid initialize response", 400)
        val endpointFailure = McpEndpointConfigurationException(404)
        val authFailure = IOException("Connection failed", McpAuthenticationRequiredException())
        val timeoutFailure = McpInitializationTimeoutException()
        assertEquals("MCP_INITIALIZATION_SCHEMA_INVALID", mcpConnectFailureReportCode(IOException("Connection failed", schemaFailure)))
        assertEquals("MCP_ENDPOINT_CONFIGURATION", mcpConnectFailureReportCode(IOException("Connection failed", endpointFailure)))
        assertEquals("MCP_INITIALIZATION_TIMEOUT", mcpConnectFailureReportCode(timeoutFailure))
        assertEquals(ConnectionHealth.MCP_HANDSHAKE_FAILED, LocalServiceHealth.classify(timeoutFailure))
        assertEquals("MCP_AUTHENTICATION_REQUIRED", mcpConnectFailureReportCode(authFailure))
        assertEquals("TLS_FAILURE", mcpConnectFailureReportCode(SSLHandshakeException("TLS handshake failed")))
    }

    @Test fun rejectedSdkInitializationPreservesIts400StatusAndSafeInitializeRule() = runBlocking {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                respond("private-body private-token", io.ktor.http.HttpStatusCode.BadRequest)
            }
        ) { install(io.ktor.client.plugins.sse.SSE) }
        val manager = McpClientManager(client, nowMs = { 7_000L })
        val config = McpConnectionConfig("sdk-schema", "https://sdk-schema.example/mcp", false, configurationRevision = "d".repeat(64))
        try {
            val failure = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
            assertTrue(failure is McpProtocolConfigurationException)
            failure as McpProtocolConfigurationException
            assertEquals(400, failure.httpStatus)
            assertEquals("INITIALIZE_REQUEST_ACCEPTANCE_REQUIRED", failure.validationRule)
            assertEquals("initialize", failure.validationField)
            assertFalse(failure.message.orEmpty().contains("private"))
            val health = manager.healthSnapshot(config.connectionUid)
            assertEquals(400, health.lastHttpStatus)
            assertEquals("initialization", health.failedStage)
            assertEquals(7_000L, health.lastFailureAtMs)
            assertEquals("d".repeat(64), health.failureConfigurationRevision)
            assertEquals(1, requests)
        } finally {
            manager.closeAll()
            client.close()
        }
    }

    @Test fun sdkInitializationRetainsOnlyRecognizedStructuredRejectionDetails() = runBlocking {
        val client = HttpClient(
            MockEngine {
                respond("""{"jsonrpc":"2.0","id":1,"error":{"code":-32602,"message":"private-body private-token","data":{"credential":"private-secret"}}}""", io.ktor.http.HttpStatusCode.BadRequest)
            }
        ) { install(io.ktor.client.plugins.sse.SSE) }
        val manager = McpClientManager(client)
        val config = McpConnectionConfig("sdk-structured", "https://sdk-structured.example/v1", false)
        try {
            val failure = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull() as McpProtocolConfigurationException
            assertEquals(-32602, failure.declaredRpcErrorCode)
            assertEquals(-32602, manager.healthSnapshot(config.connectionUid).lastDeclaredRpcErrorCode)
            assertTrue(failure.message.orEmpty().contains("declared invalid initialize parameters"))
            assertFalse(failure.message.orEmpty().contains("private"))
            assertEquals("https://sdk-structured.example/v1", config.endpointUrl)
            assertEquals(false, manager.healthSnapshot(config.connectionUid).failureRetryable)
        } finally {
            manager.closeAll()
            client.close()
        }
    }

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
        var now = 1_000L
        val manager = McpClientManager(client, modern = ModernMcpTransport(client), nowMs = { now })
        val config = McpConnectionConfig("protocol", "https://protocol.example/mcp", false, configurationRevision = "a".repeat(64))
        try {
            val first = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
            assertTrue(first is McpProtocolConfigurationException)
            assertEquals("MCP_DISCOVERY_SCHEMA_INVALID", manager.healthSnapshot(config.connectionUid).lastErrorCode)
            assertEquals(false, manager.healthSnapshot(config.connectionUid).failureRetryable)
            assertEquals(200, (first as McpProtocolConfigurationException).httpStatus)
            assertEquals("PROTOCOL_VERSIONS_NONEMPTY_REQUIRED", first.validationRule)
            assertEquals("supportedVersions", first.validationField)
            now = 40_000L
            repeat(3) {
                assertTrue(runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull() === first)
            }
            assertEquals(1, requests)
            assertEquals(1, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
            assertEquals("MCP_DISCOVERY_SCHEMA_INVALID", manager.healthSnapshot(config.connectionUid).lastErrorCode)
            assertEquals(1_000L, manager.healthSnapshot(config.connectionUid).lastFailureAtMs)
            assertEquals("a".repeat(64), manager.healthSnapshot(config.connectionUid).failureConfigurationRevision)
            assertEquals(200, manager.healthSnapshot(config.connectionUid).lastHttpStatus)
            val presented = mcpFailurePresentation(requireNotNull(first), manager.healthSnapshot(config.connectionUid))
            assertEquals("outcome=PROTOCOL_CONFIGURATION · errorCode=MCP_DISCOVERY_SCHEMA_INVALID · retryable=false", presented.diagnosticSummary)
            assertTrue(presented.message.contains("nonempty array of implemented protocol revisions"))
            assertTrue(presented.message.contains("tools/list"))
            assertFalse(presented.waitingForRecovery)

            valid = true
            assertTrue(manager.listTools(config, forceRefresh = true, awaitCompletion = true).isEmpty())
            assertEquals(3, requests)
            val recovered = manager.healthSnapshot(config.connectionUid)
            assertTrue(recovered.toolsReady)
            assertEquals(null, recovered.lastErrorCode)
            assertEquals(null, recovered.failureRetryable)
            assertEquals(null, recovered.lastHttpStatus)
            assertEquals(null, recovered.failureConfigurationRevision)
            assertEquals(null, recovered.lastValidationRule)
            assertEquals(null, recovered.lastValidationField)
        } finally {
            manager.closeAll()
            client.close()
        }
    }

    @Test fun tlsFailurePausesDiscoveryUntilExplicitReconnectAndNeverSelectsLegacyTransport() = runBlocking {
        val tlsFailures = listOf(
            SSLHandshakeException("Certificate verification failed"),
            javax.net.ssl.SSLException("Secure stream ended", java.io.EOFException("unexpected end of stream"))
        )
        for (tlsFailure in tlsFailures) {
            LocalServiceHealth.networkChanged()
            var requests = 0
            var now = 1_000L
            val client = HttpClient(
                MockEngine {
                    requests++
                    throw IOException("MCP initialization failed", tlsFailure)
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
                    val blocked = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
                    assertTrue(blocked is McpBackoffException)
                    val presented = mcpFailurePresentation(requireNotNull(blocked), manager.healthSnapshot(config.connectionUid))
                    assertEquals("outcome=TLS_FAILURE · errorCode=TLS_FAILURE · retryable=false", presented.diagnosticSummary)
                    assertFalse(presented.waitingForRecovery)
                    assertTrue(presented.message.contains("reconnect"))
                    assertFalse(presented.message.contains(Long.MAX_VALUE.toString()))
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
    }

    private fun reply(request: JsonObject, result: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.getValue("id"))
        put("result", Json.parseToJsonElement(result))
    }.toString()
}
