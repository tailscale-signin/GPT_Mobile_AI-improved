package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.network.LocalServiceHealth
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.sse.SSE
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import java.io.IOException
import kotlinx.coroutines.CancellationException
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
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class McpDiscoveryOAuthRefreshTest {
    @Before fun resetHealth() = LocalServiceHealth.networkChanged()

    @After fun clearHealth() = LocalServiceHealth.networkChanged()

    private val initial = McpConnectionConfig("oauth", "https://oauth.example/mcp", false, "Bearer expired")
    private fun replacement(endpoint: String = initial.endpointUrl, cleartext: Boolean = false) = McpConnectionConfig("oauth", endpoint, cleartext, "Bearer renewed")

    @Test fun modern401RefreshesOnceAndRepeatsOnlyDiscoveryWithTheReplacementCredential() = runBlocking {
        val requests = mutableListOf<Pair<String, String?>>()
        val client = HttpClient(
            MockEngine { request ->
                val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                val method = body.getValue("method").jsonPrimitive.content
                val authorization = request.headers["Authorization"]
                requests += method to authorization
                if (authorization != "Bearer renewed") {
                    respond("Untrusted rejected credential response", HttpStatusCode.Unauthorized)
                } else {
                    val result = if (method == "server/discover") """{"supportedVersions":["2026-07-28"],"capabilities":{"tools":{}}}""" else """{"tools":[]}"""
                    respond(reply(body, result), headers = headersOf("Content-Type", "application/json"))
                }
            }
        )
        val manager = McpClientManager(client, modern = ModernMcpTransport(client))
        var refreshes = 0
        try {
            val tools = withMcpDiscoveryOAuthRefresh(initial, oauth = true, refresh = { rejected ->
                assertEquals(initial.authorizationHeader, rejected)
                refreshes++
                replacement()
            }) { config, refreshed ->
                manager.listTools(config, forceRefresh = refreshed, awaitCompletion = true)
            }
            assertTrue(tools.isEmpty())
            assertEquals(1, refreshes)
            assertEquals(
                listOf("server/discover" to "Bearer expired", "server/discover" to "Bearer renewed", "tools/list" to "Bearer renewed"),
                requests
            )
            assertTrue(manager.healthSnapshot(initial.connectionUid).toolsReady)
        } finally {
            manager.closeAll()
            client.close()
        }
    }

    @Test fun sdkStreamableHttp401AlsoRefreshesOnlyOnceBeforeSuccessfulCatalogueRead() = runBlocking {
        McpClientManagerTest.McpFixtureServer(acceptedAuthorization = "Bearer renewed", postOnly = true).use { server ->
            val client = HttpClient(CIO) {
                expectSuccess = false
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
                install(SSE)
            }
            val manager = McpClientManager(client)
            var refreshes = 0
            try {
                val config = McpConnectionConfig("oauth", server.url, true, "Bearer expired")
                val tools = withMcpDiscoveryOAuthRefresh(config, oauth = true, refresh = { rejected ->
                    assertEquals("Bearer expired", rejected)
                    refreshes++
                    replacement(server.url, true)
                }) { current, refreshed ->
                    manager.listTools(current, forceRefresh = refreshed, awaitCompletion = true)
                }
                assertEquals(listOf("echo"), tools.map { it.name })
                assertEquals(1, refreshes)
                assertEquals(1, server.authorizationHeaders.count { it == "Bearer expired" })
                assertEquals(1, server.methods.count { it == "initialize" })
                assertEquals(1, server.methods.count { it == "tools/list" })
                assertTrue(server.toolParams.isEmpty())
            } finally {
                manager.closeAll()
                client.close()
            }
        }
    }

    @Test fun second401IsTerminalEvenWhenTheInitialDiscoveryWasAnExplicitRefresh() = runBlocking {
        var calls = 0
        var refreshes = 0
        val failure = ModernMcpError(401, null, "Credential rejected")
        val observed = runCatching {
            withMcpDiscoveryOAuthRefresh(initial, oauth = true, refresh = {
                refreshes++
                replacement()
            }) { _, refreshed ->
                calls++
                assertEquals(calls == 2, refreshed)
                throw failure
            }
        }.exceptionOrNull()
        assertSame(failure, observed)
        assertEquals(2, calls)
        assertEquals(1, refreshes)
    }

    @Test fun unavailableRefreshPreservesTheObserved401AndDoesNotDispatchAgainOrExposeItsCause() = runBlocking {
        for (refreshFailure in listOf(
            McpLocalValidationException(McpLocalValidationRule.CREDENTIAL_REQUIRED),
            IOException("private-token https://private.example/refresh?secret=private-value")
        )) {
            var calls = 0
            var refreshes = 0
            val failure = runCatching {
                withMcpDiscoveryOAuthRefresh(initial, oauth = true, refresh = {
                    refreshes++
                    throw refreshFailure
                }) { _, _ ->
                    calls++
                    throw ModernMcpError(401, null, "Credential rejected")
                }
            }.exceptionOrNull()
            assertTrue(failure is McpDiscoveryOAuthRefreshException)
            assertEquals(1, calls)
            assertEquals(1, refreshes)
            val presentation = mcpFailurePresentation(requireNotNull(failure), McpConnectionHealth())
            assertEquals("MCP_AUTHENTICATION_REQUIRED", presentation.errorCode)
            assertEquals("oauth_refresh", presentation.phase)
            assertEquals(401, presentation.httpStatus)
            assertEquals("CONFIRMED", presentation.dispatchEvidence)
            assertEquals(false, presentation.retryable)
            assertFalse(presentation.message.contains("private"))
            assertEquals(null, failure.cause)
        }
    }

    @Test fun permissionDenialsLocalGatesAndUncertainTransportFailuresNeverRefresh() = runBlocking {
        for ((oauth, rejected) in listOf(
            true to ModernMcpError(403, null, "Forbidden"),
            false to ModernMcpError(401, null, "Unauthorized"),
            true to McpAuthenticationRequiredException(),
            true to IOException("HTTP 401 text is not a status receipt"),
            true to McpIncompleteResponseException("tools/call", 11)
        )) {
            var calls = 0
            val observed = runCatching {
                withMcpDiscoveryOAuthRefresh(initial, oauth, refresh = { error("Must not refresh") }) { _, _ ->
                    calls++
                    throw rejected
                }
            }.exceptionOrNull()
            assertSame(rejected, observed)
            assertEquals(1, calls)
        }
    }

    @Test fun wrapped401IsRecognizedButWrappedCancellationAlwaysPropagates() = runBlocking {
        var calls = 0
        val result = withMcpDiscoveryOAuthRefresh(initial, oauth = true, refresh = { replacement() }) { _, _ ->
            if (++calls == 1) throw IOException("SDK wrapper", ModernMcpError(401, null, "Unauthorized"))
            "catalogue"
        }
        assertEquals("catalogue", result)
        assertEquals(2, calls)
        val cancellation = CancellationException("Cancelled by owner")
        val failure = runCatching {
            withMcpDiscoveryOAuthRefresh(initial, oauth = true, refresh = { error("Must not refresh") }) { _, _ ->
                throw IOException("SDK wrapper", cancellation)
            }
        }.exceptionOrNull()
        assertSame(cancellation, failure)
    }

    private fun reply(request: JsonObject, result: String) = buildJsonObject {
        put("jsonrpc", "2.0")
        put("id", request.getValue("id"))
        put("result", Json.parseToJsonElement(result))
    }.toString()
}
