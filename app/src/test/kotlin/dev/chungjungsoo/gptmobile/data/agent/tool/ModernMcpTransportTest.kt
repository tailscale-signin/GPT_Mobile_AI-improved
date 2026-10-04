package dev.chungjungsoo.gptmobile.data.agent.tool

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModernMcpTransportTest {
    private fun obj(value: String) = Json.parseToJsonElement(value).jsonObject
    private val config = McpConnectionConfig("modern", "https://example.com/mcp", false, "Bearer secret")

    @Test fun modernRequestsCarryMetadataAndMirroredHeadersAndDecodeRequestScopedSse() = runBlocking {
        val methods = mutableListOf<String>()
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val method = body.getValue("method").jsonPrimitive.content
            methods += method
            assertEquals(method, request.headers["Mcp-Method"])
            assertEquals(ModernMcpTransport.VERSION, request.headers["MCP-Protocol-Version"])
            assertEquals(ModernMcpTransport.VERSION, body.getValue("params").jsonObject.getValue("_meta").jsonObject.getValue("io.modelcontextprotocol/protocolVersion").jsonPrimitive.content)
            val result = when (method) {
                "server/discover" -> obj("""{"resultType":"complete","supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""")
                "tools/list" -> obj("""{"resultType":"complete","tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object","properties":{"region":{"type":"string","x-mcp-header":"Region"}}}}]}""")
                else -> {
                    assertEquals("=?base64?5p2x5Lqs?=", request.headers["Mcp-Param-Region"])
                    assertEquals("echo", request.headers["Mcp-Name"])
                    obj("""{"resultType":"complete","content":[{"type":"text","text":"complete"}]}""")
                }
            }
            val reply = buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", body.getValue("id"))
                put("result", result)
            }.toString()
            if (method == "tools/call") {
                respond(": keep alive\n\ndata: $reply\n\n", headers = headersOf("Content-Type", "text/event-stream"))
            } else {
                respond(reply, headers = headersOf("Content-Type", "application/json"))
            }
        }
        HttpClient(engine).use { client ->
            val modern = ModernMcpTransport(client)
            val manager = McpClientManager(client, modern = modern)
            assertEquals(listOf("echo"), manager.listTools(config).map { it.name })
            manager.callTool(config, "echo", obj("""{"region":"東京"}"""))
            assertEquals(listOf("server/discover", "tools/list", "tools/call"), methods)
            manager.closeAll()
        }
    }

    @Test fun legacyProbeFallsBackButModernHeaderErrorsDoNot() = runBlocking {
        HttpClient(MockEngine { respond("legacy session missing", HttpStatusCode.BadRequest) }).use { client ->
            assertFalse(ModernMcpTransport(client).supports(config))
        }
        HttpClient(MockEngine { respond("""{"error":{"code":-32020,"message":"Header mismatch"}}""", HttpStatusCode.BadRequest) }).use { client ->
            assertTrue(runCatching { ModernMcpTransport(client).supports(config) }.exceptionOrNull() is ModernMcpError)
        }
    }

    @Test fun localDiscoveryTransportFailureFallsBackAndCachesLegacyDecision() = runBlocking {
        var requests = 0
        val localConfig = McpConnectionConfig("local-modern", "http://127.0.0.1:8101/mcp", true)
        HttpClient(
            MockEngine {
                requests++
                error("local discovery unavailable")
            }
        ).use { client ->
            val modern = ModernMcpTransport(client)
            assertFalse(modern.supports(localConfig))
            assertFalse(modern.supports(localConfig))
            assertEquals(1, requests)
        }

        HttpClient(MockEngine { error("remote discovery unavailable") }).use { client ->
            assertTrue(runCatching { ModernMcpTransport(client).supports(config) }.isFailure)
        }
    }

    @Test fun invalidMirroringIsRejectedAndNullValuesAreOmitted() {
        for (schema in listOf(
            """{"properties":{"x":{"type":"string","x-mcp-header":"bad\r\nheader"}}}""",
            """{"properties":{"x":{"type":"number","x-mcp-header":"X"}}}""",
            """{"items":{"type":"string","x-mcp-header":"X"}}""",
            """{"properties":{"x":{"type":"string","x-mcp-header":"X"},"y":{"type":"string","x-mcp-header":"x"}}}"""
        )) {
            assertTrue(runCatching { ModernMcpTransport.mirroredHeaders(obj(schema), null) }.isFailure)
        }
        val schema = obj("""{"properties":{"x":{"type":"string","x-mcp-header":"X"}}}""")
        assertTrue(ModernMcpTransport.mirroredHeaders(schema, obj("""{"x":null}""")).isEmpty())
        assertEquals("=?base64?IGEg?=", ModernMcpTransport.headerValue(" a "))
        assertTrue(ModernMcpTransport.headerValue("=?base64?literal?=").startsWith("=?base64?PT"))
    }

    @Test fun ambiguousWriteFailureIsNotReplayed() = runBlocking {
        var calls = 0
        val engine = MockEngine { request ->
            val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
            val result = when (body.getValue("method").jsonPrimitive.content) {
                "server/discover" -> obj("""{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""")
                "tools/list" -> obj("""{"tools":[{"name":"write","inputSchema":{"type":"object","properties":{}}}]}""")
                else -> {
                    calls++
                    return@MockEngine respond("outcome unknown", HttpStatusCode.InternalServerError)
                }
            }
            respond(
                buildJsonObject {
                    put("id", body.getValue("id"))
                    put("result", result)
                }.toString(),
                headers = headersOf("Content-Type", "application/json")
            )
        }
        HttpClient(engine).use { client ->
            val manager = McpClientManager(client, modern = ModernMcpTransport(client))
            assertTrue(runCatching { manager.callTool(config, "write", JsonObject(emptyMap())) }.isFailure)
            assertEquals(1, calls)
        }
    }
}
