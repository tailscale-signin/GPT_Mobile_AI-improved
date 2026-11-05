package dev.chungjungsoo.gptmobile.data.agent.tool

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernMcpRecoveryTest {
    @org.junit.Before fun resetEndpointHealth() = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()

    @org.junit.After fun clearEndpointHealth() = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()

    private val config = McpConnectionConfig("recovery", "https://example.com/mcp", false, null)

    @Test fun truncatedCatalogPageRetriesOnlyThatPage() = runBlocking {
        val requestedCursors = mutableListOf<String?>()
        HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val cursor = body.getValue("params").jsonObject["cursor"]?.jsonPrimitive?.content
                requestedCursors += cursor
                if (requestedCursors.size == 2) {
                    respond("""{"jsonrpc":"2.0","result":{"tools":[""", headers = headersOf("Content-Type", "application/json"))
                } else {
                    val result = if (cursor == null) {
                        """{"tools":[{"name":"first","inputSchema":{"type":"object"}}],"nextCursor":"page-two"}"""
                    } else {
                        """{"tools":[{"name":"second","inputSchema":{"type":"object"}}]}"""
                    }
                    respond(reply(body, result), headers = headersOf("Content-Type", "application/json"))
                }
            }
        ).use { client ->
            val tools = ModernMcpTransport(client).listTools(config)
            assertEquals(listOf("first", "second"), tools.map { it.name })
            assertEquals(listOf(null, "page-two", "page-two"), requestedCursors)
        }
    }

    @Test fun interruptedToolResultIsAnUnknownOutcomeAndNeverReplayed() = runBlocking {
        val methods = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                methods += method
                if (method == "tools/list") {
                    respond(reply(body, """{"tools":[{"name":"write","inputSchema":{"type":"object"}}]}"""), headers = headersOf("Content-Type", "application/json"))
                } else {
                    respond("data: {\"jsonrpc\":\"2.0\",\"result\":", headers = headersOf("Content-Type", "text/event-stream"))
                }
            }
        ).use { client ->
            val failure = runCatching { ModernMcpTransport(client).callTool(config, "write", JsonObject(emptyMap())) }.exceptionOrNull()
            assertTrue(failure is McpIncompleteResponseException)
            assertTrue(requireNotNull(failure).message.orEmpty().contains("UNKNOWN_OUTCOME"))
            assertTrue((failure as McpIncompleteResponseException).receivedBytes > 0)
            assertEquals(listOf("tools/list", "tools/call"), methods)
        }
    }

    private fun reply(request: JsonObject, result: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.getValue("id"))
        put("result", Json.parseToJsonElement(result))
    }.toString()
}
