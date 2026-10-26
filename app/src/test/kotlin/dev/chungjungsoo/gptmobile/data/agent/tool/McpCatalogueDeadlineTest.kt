package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCatalogueDeadlineTest {
    @org.junit.Before fun reset() = LocalServiceHealth.networkChanged()

    @org.junit.After fun cleanup() = LocalServiceHealth.networkChanged()

    @Test fun independentDiscoveryIsConcurrentAndInteractiveTimeoutRetainsSingleFlightProducer() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = ConcurrentHashMap<String, AtomicInteger>()
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                val result = if (method == "server/discover") {
                    Json.parseToJsonElement("""{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""")
                } else {
                    calls.getOrPut(request.url.host) { AtomicInteger() }.incrementAndGet()
                    release.await()
                    Json.parseToJsonElement("""{"tools":[{"name":"read","inputSchema":{"type":"object"}}]}""")
                }
                respond(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", body.getValue("id"))
                        put("result", result)
                    }.toString(),
                    headers = headersOf("Content-Type", "application/json")
                )
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client))
        val configs = (1..3).map { McpConnectionConfig("c$it", "https://catalogue$it.example/mcp", false, "Bearer same-scope") }
        try {
            val start = System.nanoTime()
            val jobs = configs.map { async { manager.listTools(it) } }
            withTimeout(1500) { while (calls.size < 3) delay(10) }
            assertTrue(jobs.awaitAll().all { it.isEmpty() })
            assertTrue((System.nanoTime() - start) / 1_000_000 < 3500)
            assertTrue(configs.all { manager.catalogueAvailability(it).refreshing })
            release.complete(Unit)
            withTimeout(2000) { while (configs.any { manager.peekTools(it) == null }) delay(10) }
            configs.forEach { config ->
                assertEquals(listOf("read"), manager.listTools(config).map { it.name })
                assertEquals(listOf("read"), manager.peekToolsForConnection(config.connectionUid, config.endpointUrl, config.allowCleartext)?.map { it.name })
                assertTrue(manager.catalogueAvailability(config).schemaKnown)
            }
            assertTrue(calls.values.all { it.get() == 1 })
        } finally {
            release.complete(Unit)
            manager.closeAll()
            client.close()
        }
    }

    @Test fun totalDiscoveryDeadlineCancelsProducerAndRecordsExplicitHealthWithoutInventingSchema() = runBlocking {
        val canceled = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                if (body.getValue("method").jsonPrimitive.content == "server/discover") {
                    respond(
                        buildJsonObject {
                            put("jsonrpc", "2.0")
                            put("id", body.getValue("id"))
                            put("result", Json.parseToJsonElement("""{"supportedVersions":["2026-07-28"]}"""))
                        }.toString(),
                        headers = headersOf("Content-Type", "application/json")
                    )
                } else {
                    try {
                        delay(30_000)
                    } finally {
                        canceled.complete(Unit)
                    }
                    error("Discovery deadline must cancel this page")
                }
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client), catalogueTimeoutMs = 8000L)
        val config = McpConnectionConfig("deadline", "https://deadline.example/mcp", false)
        try {
            assertTrue(manager.listTools(config).isEmpty())
            withTimeout(9000) { canceled.await() }
            withTimeout(1000) { while (manager.catalogueAvailability(config).refreshing) delay(10) }
            assertFalse(manager.catalogueAvailability(config).schemaKnown)
            assertFalse(manager.catalogueAvailability(config).executionAvailable)
            assertTrue(manager.healthSnapshot(config.connectionUid).lastError.orEmpty().contains("8000"))
        } finally {
            manager.closeAll()
            client.close()
        }
    }

    @Test fun healthCheckWaitsForSlowCatalogueInsteadOfReportingZeroTools() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val result = if (body.getValue("method").jsonPrimitive.content == "server/discover") {
                    Json.parseToJsonElement("""{"supportedVersions":["2026-07-28"]}""")
                } else {
                    release.await()
                    Json.parseToJsonElement("""{"tools":[{"name":"read","inputSchema":{"type":"object"}}]}""")
                }
                respond(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", body.getValue("id"))
                        put("result", result)
                    }.toString(),
                    headers = headersOf("Content-Type", "application/json")
                )
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client))
        val config = McpConnectionConfig("health-slow", "https://health-slow.example/mcp", false)
        try {
            val health = async { manager.listTools(config, awaitCompletion = true) }
            // The interactive deadline expires while discovery is still running.
            assertTrue(manager.listTools(config).isEmpty())
            assertFalse(health.isCompleted)
            release.complete(Unit)
            assertEquals(listOf("read"), withTimeout(2000) { health.await() }.map { it.name })
            assertTrue(manager.catalogueAvailability(config).executionAvailable)
        } finally {
            release.complete(Unit)
            manager.closeAll()
            client.close()
        }
    }

    @Test fun healthCheckDeadlineIsAReportedFailureAndDoesNotCancelCaller() = runBlocking {
        val client = HttpClient(
            MockEngine {
                delay(30_000)
                error("Must time out")
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client), catalogueTimeoutMs = 100L)
        val config = McpConnectionConfig("health-timeout", "https://health-timeout.example/mcp", false)
        try {
            val failure = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
            assertTrue(failure is McpCatalogueTimeoutException)
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            assertFalse(manager.catalogueAvailability(config).executionAvailable)
        } finally {
            manager.closeAll()
            client.close()
        }
    }
}
