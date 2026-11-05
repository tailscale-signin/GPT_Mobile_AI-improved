package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpCatalogueExecutionSafetyTest {
    @org.junit.Before fun resetEndpointHealth() = LocalServiceHealth.networkChanged()

    @org.junit.After fun clearEndpointHealth() = LocalServiceHealth.networkChanged()

    @Test fun aToolRemovedAfterReconnectCannotExecuteFromTheOldSchema() = runBlocking {
        var toolName = "old_write"
        var calls = 0
        var discoveries = 0
        HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val result = when (body.getValue("method").jsonPrimitive.content) {
                    "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                    "tools/list" -> {
                        discoveries++
                        """{"tools":[{"name":"$toolName","inputSchema":{"type":"object"}}]}"""
                    }
                    else -> {
                        calls++
                        """{"content":[]}"""
                    }
                }
                respond(
                    buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", body.getValue("id"))
                        put("result", Json.parseToJsonElement(result))
                    }.toString(),
                    headers = headersOf("Content-Type", "application/json")
                )
            }
        ).use { client ->
            val manager = McpClientManager(client, modern = ModernMcpTransport(client))
            val config = McpConnectionConfig("catalogue-safety", "https://catalogue.example/mcp", false)
            try {
                assertEquals(listOf("old_write"), manager.listTools(config, awaitCompletion = true).map { it.name })
                assertTrue(manager.catalogueAvailability(config).executionAvailable)
                manager.close(config.connectionUid)
                assertTrue(manager.catalogueAvailability(config).schemaKnown)
                assertFalse(manager.catalogueAvailability(config).executionAvailable)
                toolName = "new_read"
                val failure = runCatching { manager.callTool(config, "old_write", JsonObject(emptyMap())) }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("MCP_TOOL_UNAVAILABLE"))
                assertEquals(2, discoveries)
                assertEquals(0, calls)
                assertEquals(listOf("new_read"), manager.peekTools(config)?.map { it.name })
                assertTrue(manager.catalogueAvailability(config).executionAvailable)
            } finally {
                manager.closeAll()
            }
        }
    }

    @Test fun connectedMetadataDoesNotAdvertiseToolsReadyUntilCatalogueValidationFinishes() = runBlocking {
        val listing = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val client = catalogueClient {
            listing.complete(Unit)
            release.await()
            """{"tools":[{"name":"read","inputSchema":{"type":"object"}}]}"""
        }
        client.use {
            val manager = McpClientManager(it, modern = ModernMcpTransport(it))
            val config = McpConnectionConfig("readiness-gate", "https://readiness.example/mcp", false)
            try {
                val pending = async { manager.listTools(config, awaitCompletion = true) }
                listing.await()
                assertFalse(manager.healthSnapshot(config.connectionUid).toolsReady)
                assertFalse(manager.catalogueAvailability(config).executionAvailable)
                release.complete(Unit)
                pending.await()
                assertTrue(manager.healthSnapshot(config.connectionUid).toolsReady)
                manager.close(config.connectionUid)
                assertFalse(manager.healthSnapshot(config.connectionUid).toolsReady)
            } finally {
                release.complete(Unit)
                manager.closeAll()
            }
        }
    }

    @Test fun duplicateToolNamesCannotPublishSuccessfulReadiness() = runBlocking {
        catalogueClient { """{"tools":[{"name":"write","inputSchema":{"type":"object"}},{"name":"write","inputSchema":{"type":"object"}}]}""" }.use { client ->
            val manager = McpClientManager(client, modern = ModernMcpTransport(client))
            val config = McpConnectionConfig("duplicate-catalog", "https://duplicate.example/mcp", false)
            try {
                val failure = runCatching { manager.listTools(config, awaitCompletion = true) }.exceptionOrNull()
                assertTrue(failure?.message.orEmpty().contains("Duplicate MCP tool names"))
                assertFalse(manager.healthSnapshot(config.connectionUid).toolsReady)
                assertFalse(manager.catalogueAvailability(config).executionAvailable)
                assertTrue(manager.healthSnapshot(config.connectionUid).availableToolCount == null)
            } finally {
                manager.closeAll()
            }
        }
    }

    private fun catalogueClient(listing: suspend () -> String): HttpClient = HttpClient(
        MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val result = when (body.getValue("method").jsonPrimitive.content) {
                "server/discover" -> """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}"""
                "tools/list" -> listing()
                else -> error("Unexpected request")
            }
            respond(
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", body.getValue("id"))
                    put("result", Json.parseToJsonElement(result))
                }.toString(),
                headers = headersOf("Content-Type", "application/json")
            )
        }
    )
}
