package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayGraphTest {
    private val context = GitHubConversationContext("connection", "owner", "repo", "feature", "a".repeat(40), "local")

    @Test fun requestAlwaysCarriesSelectedImmutableRevision() {
        val payload = GatewayGraphRepository.boundRequest(
            context,
            buildJsonObject {
                put("action", "query")
                put("query", "auth")
            }
        )
        assertEquals(JsonPrimitive("a".repeat(40)), payload["commit_sha"])
        assertEquals(JsonPrimitive("owner/repo"), payload["repository"])
        assertTrue(runCatching { GatewayGraphRepository.boundRequest(context, buildJsonObject { put("commit_sha", "b".repeat(40)) }) }.isFailure)
    }

    @Test fun graphToolRejectsScopeOverrideBeforeCallingGateway() = runTest {
        var calls = 0
        val tool = RepositoryGraphTool("repository_graph") {
            calls++
            JsonObject(emptyMap())
        }
        val result = tool.execute(
            "call",
            buildJsonObject {
                put("action", "query")
                put("options", buildJsonObject { put("repository", "other/repo") })
            }
        )
        assertTrue(result.isError)
        assertFalse(result.dispatched)
        assertEquals(0, calls)
    }

    @Test fun graphToolRoutesOperationOptionsAndPreservesUncertainty() = runTest {
        val tool = RepositoryGraphTool("repository_graph") { arguments ->
            assertEquals(JsonPrimitive("path"), arguments["action"])
            assertEquals(JsonPrimitive("Auth"), arguments["source"])
            assertEquals(JsonPrimitive(16000), arguments["budget_bytes"])
            buildJsonObject {
                put("status", "NO_PATH_IN_INDEX")
                put("runtime_verified", false)
            }
        }
        val result = tool.execute(
            "call",
            buildJsonObject {
                put("action", "path")
                put(
                    "options",
                    buildJsonObject {
                        put("source", "Auth")
                        put("target", "Client")
                    }
                )
            }
        )
        assertFalse(result.isError)
        assertEquals(JsonPrimitive(false), ((result.content as ToolResultContent.Json).value as JsonObject)["runtime_verified"])
    }

    @Test fun graphClientUsesExistingGatewayEndpointAndPreservesCoverage() = runTest {
        val engine = MockEngine { request ->
            assertEquals("http://127.0.0.1:8090/v1/gateway/engineering/graph", request.url.toString())
            val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
            assertEquals(JsonPrimitive("a".repeat(40)), body["commit_sha"])
            respond("""{"status":"FOUND","stale":true,"graph":{"complete":false}}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
        }
        HttpClient(engine) { install(HttpTimeout) }.use { http ->
            val result = GatewayEngineeringClient(http).graph(ProviderRequestConfig("http://127.0.0.1:8090/v1", null), GatewayGraphRepository.boundRequest(context, buildJsonObject { put("action", "status") }))
            assertEquals(JsonPrimitive(true), result["stale"])
            assertEquals(JsonPrimitive(false), (result["graph"] as JsonObject)["complete"])
        }
    }

    @Test fun readinessDiagnosticsPreserveEveryExactPrerequisiteAndSelectedCommitWithoutRawGatewayText() = runTest {
        for ((checks, expected) in listOf(
            "\"journal\":\"disabled\"" to "JOURNAL_DISABLED",
            "\"journal\":\"write_failed\"" to "JOURNAL_UNAVAILABLE",
            "\"journal\":\"ready\",\"host_github_connection\":\"missing\"" to "HOST_GITHUB_CONNECTION_MISSING",
            "\"journal\":\"ready\",\"draining\":true" to "GATEWAY_DRAINING",
            "\"journal\":\"ready\"" to "GRAPH_SERVICE_UNAVAILABLE"
        )) {
            val http = HttpClient(
                MockEngine {
                    respond("""{"service":"engineering-gateway","protocol_version":1,"principal_id":"owner","code_graph":{"schema_version":1,"enabled":false},"readiness":{"schema_version":1,"graph_ready":false,"checks":{$checks}},"private_detail":"secret-token"}""", HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
                }
            ) { install(HttpTimeout) }
            http.use {
                val client = GatewayEngineeringClient(it)
                val tool = RepositoryGraphTool("repository_graph") {
                    try {
                        client.requireGraphReady(ProviderRequestConfig("http://127.0.0.1:8090/$expected", null))
                        error("Unexpected ready graph")
                    } catch (failure: GatewayGraphUnavailableException) {
                        throw failure.atRevision(context)
                    }
                }
                val result = tool.execute("status", buildJsonObject { put("action", "status") })
                val diagnostic = (result.content as ToolResultContent.Json).value as JsonObject
                assertTrue(result.isError)
                assertEquals(JsonPrimitive(expected), diagnostic["failure_category"])
                val revision = diagnostic["selected_revision"] as JsonObject
                assertEquals(JsonPrimitive("owner/repo"), revision["repository"])
                assertEquals(JsonPrimitive(context.commitSha), revision["commit_sha"])
                assertEquals(JsonPrimitive("NOT_QUALIFIED"), revision["qualification"])
                assertEquals(JsonPrimitive(false), diagnostic["graph_complete"])
                assertFalse(diagnostic.toString().contains("secret-token"))
            }
        }
    }

    @Test fun readinessChangingBetweenHandshakeAndGraphPostKeepsTypedPrerequisite() = runTest {
        HttpClient(
            MockEngine {
                respond("""{"error":{"code":"graph_connection_required","reason":"HOST_GITHUB_CONNECTION_REQUIRED"},"readiness":{"schema_version":1,"graph_ready":false,"checks":{"journal":"ready","host_github_connection":"missing"}}}""", HttpStatusCode.PreconditionFailed, headersOf("Content-Type", "application/json"))
            }
        ) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { GatewayEngineeringClient(http).graph(ProviderRequestConfig("http://127.0.0.1:8090/v1", null), GatewayGraphRepository.boundRequest(context, buildJsonObject { put("action", "status") })) }.exceptionOrNull()
            assertTrue(failure is GatewayGraphUnavailableException)
            assertEquals(listOf(GatewayGraphPrerequisite.HOST_GITHUB_CONNECTION_MISSING), (failure as GatewayGraphUnavailableException).prerequisites)
        }
    }
}
