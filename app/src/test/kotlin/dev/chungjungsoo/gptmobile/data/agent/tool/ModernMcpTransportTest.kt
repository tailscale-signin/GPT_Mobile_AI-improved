package dev.chungjungsoo.gptmobile.data.agent.tool

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.sse.SSE
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
    @org.junit.Before fun resetEndpointHealth() = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()

    @org.junit.After fun clearEndpointHealth() = dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()

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

    @Test fun legacySessionErrorSelectsOneInitializationWithoutRepeatingModernDiscovery() = runBlocking {
        val methods = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                if (request.method.value == "GET") return@MockEngine respond("", HttpStatusCode.MethodNotAllowed)
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                methods += method
                when (method) {
                    "server/discover" -> respond("""{"error":{"code":-32000,"message":"Session required"}}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
                    "notifications/initialized" -> respond("", HttpStatusCode.Accepted)
                    else -> {
                        val result = when (method) {
                            "initialize" -> obj("""{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"legacy","version":"1"}}""")
                            "tools/list" -> obj("""{"tools":[{"name":"echo","description":"Echo","inputSchema":{"type":"object"}}]}""")
                            else -> error("Unexpected method $method")
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
                }
            }
        ) { install(SSE) }.use { client ->
            val manager = McpClientManager(client, modern = ModernMcpTransport(client))
            try {
                assertEquals(listOf("echo"), manager.listTools(config).map { it.name })
                assertEquals(listOf("echo"), manager.listTools(config).map { it.name })
                assertEquals(listOf("server/discover", "initialize", "notifications/initialized", "tools/list"), methods)
                assertEquals(0, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
            } finally {
                manager.closeAll()
            }
        }
    }

    @Test fun legacyAdvertisedVersionsFallBackAndCacheTheDecision() = runBlocking {
        var requests = 0
        HttpClient(
            MockEngine { request ->
                requests++
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val reply = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", body.getValue("id"))
                    put("result", obj("""{"resultType":"complete","supportedVersions":["2024-11-05","2025-03-26"]}"""))
                }
                respond(reply.toString(), headers = headersOf("Content-Type", "application/json"))
            }
        ).use { client ->
            val transport = ModernMcpTransport(client)
            assertFalse(transport.supports(config))
            assertFalse(transport.supports(config))
            assertEquals(1, requests)
        }
    }

    @Test fun unsupportedDiscoveryIsCachedAcrossTurnsAndExplicitRefreshReprobes() = runBlocking {
        var requests = 0
        var now = 1_000L
        HttpClient(
            MockEngine {
                requests++
                respond("""{"jsonrpc":"2.0","error":{"code":-32601,"message":"Method not found"}}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
            }
        ).use { client ->
            val transport = ModernMcpTransport(client, nowMs = { now })
            assertFalse(transport.supports(config))
            now += 90_000
            assertFalse(transport.supports(config))
            assertEquals(1, requests)
            assertFalse(transport.supports(config, refresh = true))
            assertEquals(2, requests)
        }
    }

    @Test fun missingDiscoveryVersionsPauseUntilExplicitReconnectOrConfigurationChange() = runBlocking {
        val methods = mutableListOf<String>()
        var now = 1_000L
        HttpClient(
            MockEngine { request ->
                if (request.method.value == "GET") return@MockEngine respond("", HttpStatusCode.MethodNotAllowed)
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                methods += method
                if (method == "notifications/initialized") {
                    respond("", HttpStatusCode.Accepted)
                } else {
                    val result = when (method) {
                        "server/discover" -> obj("""{"resultType":"complete"}""")
                        "initialize" -> obj("""{"protocolVersion":"2025-06-18","capabilities":{"tools":{}},"serverInfo":{"name":"legacy-bridge","version":"1"}}""")
                        "tools/list" -> obj("""{"tools":[{"name":"echo","inputSchema":{"type":"object"}}]}""")
                        else -> error("Unexpected MCP method: $method")
                    }
                    val reply = buildJsonObject {
                        put("jsonrpc", "2.0")
                        put("id", body.getValue("id"))
                        put("result", result)
                    }
                    respond(reply.toString(), headers = headersOf("Content-Type", "application/json"))
                }
            }
        ).use { client ->
            val manager = McpClientManager(client, modern = ModernMcpTransport(client), nowMs = { now })
            try {
                repeat(6) {
                    assertTrue(runCatching { manager.listTools(config) }.exceptionOrNull() is McpProtocolConfigurationException)
                    now += 600_000
                    dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth.networkChanged()
                }
                assertEquals(listOf("server/discover"), methods)
                assertEquals(1, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
                assertEquals(Long.MAX_VALUE, manager.healthSnapshot(config.connectionUid).nextRetryAtMs)
                assertTrue(manager.listTools(config, passiveBackoff = true).isEmpty())
                assertTrue(runCatching { manager.listTools(config, forceRefresh = true) }.isFailure)
                assertEquals(2, methods.size)
                val changed = McpConnectionConfig(config.connectionUid, config.endpointUrl, false, "Bearer changed")
                assertTrue(runCatching { manager.listTools(changed) }.isFailure)
                assertEquals(3, methods.size)
                assertTrue(methods.all { it == "server/discover" })
            } finally {
                manager.closeAll()
            }
        }
    }

    @Test fun incompatibleVersionsRemainAnError() = runBlocking {
        HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val reply = buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", body.getValue("id"))
                    put("result", obj("""{"resultType":"complete","supportedVersions":["2099-01-01"]}"""))
                }
                respond(reply.toString(), headers = headersOf("Content-Type", "application/json"))
            }
        ).use { client ->
            assertTrue(runCatching { ModernMcpTransport(client).supports(config) }.isFailure)
        }
    }

    @Test fun discoveryMethodNotFoundFallsBackOnlyWithLegacyHttpStatuses() = runBlocking {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.BadRequest)) {
            var requests = 0
            HttpClient(
                MockEngine {
                    requests++
                    respond(
                        """{"error":{"code":-32601,"message":"Method not found"}}""",
                        status,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            ).use { client ->
                val transport = ModernMcpTransport(client)
                assertFalse(transport.supports(config))
                assertFalse(transport.supports(config))
                assertEquals(1, requests)
            }
        }
    }

    @Test fun authenticationErrorsNeverTriggerMethodNotFoundFallback() = runBlocking {
        for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden)) {
            HttpClient(
                MockEngine {
                    respond(
                        """{"error":{"code":-32601,"message":"Access denied"}}""",
                        status,
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            ).use { client ->
                assertTrue(runCatching { ModernMcpTransport(client).supports(config) }.exceptionOrNull() is ModernMcpError)
            }
        }
    }

    @Test fun nonModernBadRequestFallsBackButModernValidationErrorsNeverDowngrade() = runBlocking {
        HttpClient(MockEngine { respond("legacy session missing", HttpStatusCode.BadRequest) }).use { client ->
            assertFalse(ModernMcpTransport(client).supports(config))
        }
        for (code in listOf(-32020, -32021, -32600, -32602, -32700)) {
            HttpClient(MockEngine { respond("""{"error":{"code":$code,"message":"Invalid request"}}""", HttpStatusCode.BadRequest) }).use { client ->
                assertTrue(runCatching { ModernMcpTransport(client).supports(config) }.exceptionOrNull() is McpProtocolConfigurationException)
            }
        }
        HttpClient(MockEngine { respond("""{"error":{"code":-32601,"message":"Method not found"}}""", HttpStatusCode.NotFound) }).use { client ->
            assertTrue(runCatching { ModernMcpTransport(client).supports(config) }.exceptionOrNull() is McpProtocolConfigurationException)
        }
    }

    @Test fun versionNegotiationUsesAdvertisedAlternativesAndRejectsMalformedErrors() = runBlocking {
        for ((data, accepted) in listOf(
            """{"supported":["2025-06-18"],"requested":"2026-07-28"}""" to true,
            """{"supported":[],"requested":"2026-07-28"}""" to false,
            """{"supported":["2026-07-28"],"requested":"2026-07-28"}""" to false,
            """{"supported":["2099-01-01"],"requested":"2026-07-28"}""" to false,
            """{"supported":["2025-06-18"],"requested":"different"}""" to false,
            """{"supported":["2025-06-18"]}""" to false
        )) {
            HttpClient(MockEngine { respond("""{"error":{"code":-32022,"message":"UnsupportedProtocolVersion","data":$data}}""", HttpStatusCode.BadRequest) }).use { client ->
                val result = runCatching { ModernMcpTransport(client).supports(config) }
                if (accepted) assertEquals(false, result.getOrThrow()) else assertTrue(result.exceptionOrNull() is McpProtocolConfigurationException)
            }
        }
    }

    @Test fun mismatchedDiscoveryErrorCannotSelectALegacyProtocol() = runBlocking {
        HttpClient(MockEngine { respond("""{"id":"different","error":{"code":-32000,"message":"Session missing"}}""", HttpStatusCode.BadRequest) }).use { client ->
            val failure = runCatching { ModernMcpTransport(client).supports(config) }.exceptionOrNull()
            assertTrue(failure is McpProtocolConfigurationException)
            assertTrue(failure?.message.orEmpty().contains("MCP_RESPONSE_ID_MISMATCH"))
        }
    }

    @Test fun changedGoogleCredentialReprobesDiscoveryAndIsForwarded() = runBlocking {
        val credentials = mutableListOf<String?>()
        HttpClient(
            MockEngine { request ->
                credentials += request.headers["X-Goog-Api-Key"]
                respond("legacy session missing", HttpStatusCode.BadRequest)
            }
        ).use { client ->
            val transport = ModernMcpTransport(client)
            assertFalse(transport.supports(McpConnectionConfig("google", config.endpointUrl, false, googleApiKey = "first")))
            assertFalse(transport.supports(McpConnectionConfig("google", config.endpointUrl, false, googleApiKey = "second")))
            assertEquals(listOf("first", "second"), credentials)
        }
    }

    @Test fun localDiscoveryTransportFailureNeverClaimsLegacySupport() = runBlocking {
        var requests = 0
        val localConfig = McpConnectionConfig("local-modern", "http://127.0.0.1:8101/mcp", true)
        HttpClient(
            MockEngine {
                requests++
                error("local discovery unavailable")
            }
        ).use { client ->
            val modern = ModernMcpTransport(client)
            assertTrue(runCatching { modern.supports(localConfig) }.isFailure)
            assertTrue(runCatching { modern.supports(localConfig) }.isFailure)
            assertEquals(1, requests)
            assertTrue(runCatching { modern.supports(localConfig, refresh = true) }.isFailure)
            assertEquals(2, requests)
        }

        HttpClient(MockEngine { error("remote discovery unavailable") }).use { client ->
            assertTrue(runCatching { ModernMcpTransport(client).supports(config) }.isFailure)
        }
    }

    @Test fun rejectedLegacyInitializationIsNotRepeatedUnchanged() = runBlocking {
        val methods = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                if (request.method.value == "GET") return@MockEngine respond("", HttpStatusCode.MethodNotAllowed)
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                methods += body.getValue("method").jsonPrimitive.content
                assertEquals(config.authorizationHeader, request.headers["Authorization"])
                respond("""{"jsonrpc":"2.0","id":null,"error":{"code":-32000,"message":"Malformed endpoint request"}}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
            }
        ) { install(SSE) }.use { client ->
            val manager = McpClientManager(client, modern = ModernMcpTransport(client))
            try {
                repeat(6) {
                    val failure = runCatching { manager.listTools(config) }.exceptionOrNull()
                    assertTrue(failure is McpProtocolConfigurationException)
                    assertTrue(failure?.message.orEmpty().contains("MCP_INITIALIZATION_SCHEMA_INVALID"))
                }
                assertEquals(listOf("server/discover", "initialize"), methods)
                assertEquals(1, manager.healthSnapshot(config.connectionUid).consecutiveFailures)
            } finally {
                manager.closeAll()
            }
        }
    }

    @Test fun malformedCataloguesNeverPublishReadinessOrRepeatDiscovery() = runBlocking {
        for (catalogue in listOf(
            """{}""",
            """{"tools":{}}""",
            """{"tools":[null]}""",
            """{"tools":[{"name":"read"}]}""",
            """{"tools":[{"name":"read","inputSchema":{"type":"object"}},{"name":"read","inputSchema":{"type":"object"}}]}""",
            """{"tools":[],"nextCursor":5}"""
        )) {
            val methods = mutableListOf<String>()
            HttpClient(
                MockEngine { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    val method = body.getValue("method").jsonPrimitive.content
                    methods += method
                    val result = if (method == "server/discover") obj("""{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""") else obj(catalogue)
                    respond(
                        buildJsonObject {
                            put("id", body.getValue("id"))
                            put("result", result)
                        }.toString(),
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            ).use { client ->
                val manager = McpClientManager(client, modern = ModernMcpTransport(client))
                try {
                    repeat(2) { assertTrue(runCatching { manager.listTools(config) }.exceptionOrNull() is McpProtocolConfigurationException) }
                    assertEquals(listOf("server/discover", "tools/list"), methods)
                    assertFalse(manager.catalogueAvailability(config).executionAvailable)
                    assertEquals(null, manager.healthSnapshot(config.connectionUid).availableToolCount)
                } finally {
                    manager.closeAll()
                }
            }
        }
    }

    @Test fun invalidToolArgumentsNeverSelectHandshakeFallbackOrRepeatTheCall() = runBlocking {
        val methods = mutableListOf<String>()
        HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                methods += method
                if (method == "tools/call") {
                    respond("""{"error":{"code":-32602,"message":"Invalid params"}}""", HttpStatusCode.BadRequest, headersOf("Content-Type", "application/json"))
                } else {
                    val result = if (method == "server/discover") obj("""{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""") else obj("""{"tools":[{"name":"write","inputSchema":{"type":"object"}}]}""")
                    respond(
                        buildJsonObject {
                            put("id", body.getValue("id"))
                            put("result", result)
                        }.toString(),
                        headers = headersOf("Content-Type", "application/json")
                    )
                }
            }
        ).use { client ->
            val manager = McpClientManager(client, modern = ModernMcpTransport(client))
            try {
                assertTrue(runCatching { manager.callTool(config, "write", JsonObject(emptyMap())) }.exceptionOrNull() is ModernMcpError)
                assertEquals(listOf("server/discover", "tools/list", "tools/call"), methods)
            } finally {
                manager.closeAll()
            }
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
