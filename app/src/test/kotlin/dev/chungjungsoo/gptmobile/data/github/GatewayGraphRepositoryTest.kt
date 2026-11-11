package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.mockk
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayGraphRepositoryTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "cloud-ai")
    private val ai = PlatformV2(uid = "cloud-ai", name = "Selected cloud AI", compatibleType = ClientType.OPENAI, apiUrl = "https://provider.example/v1", model = "selected-model")
    private val host = PlatformV2(uid = "host", name = "Selected PC", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8090/${UUID.randomUUID()}/v1", model = "unused")
    private val settings = mockk<SettingRepository>()
    private val connections = mockk<ToolConnectionRepository>()
    private var profiles = listOf(ai, host)
    private var features = AppFeatureSettings(githubProfileOverrides = mapOf(ai.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = host.uid)))
    private val connection = ToolConnection("github", "GitHub", "github", "GITHUB", null, "NONE", null, null)
    private val headers = headersOf("Content-Type", "application/json")
    private val ready = """{"service":"engineering-gateway","protocol_version":1,"principal_id":"account","gateway_id":"installation","code_graph":{"schema_version":1,"enabled":true},"readiness":{"schema_version":1,"ready":false,"graph_ready":true,"checks":{"journal":"ready","host_github_connection":"missing","graph_host_github_connection":"configured","worker":"unavailable"}}}"""
    private val notIndexed = """{"status":"NOT_INDEXED","repository":"owner/repo","commit_sha":"${context.commitSha}"}"""
    private fun status(cursor: String) = """{"has_more":true,"index_cursor":"$cursor","graph":{"repository":"owner/repo","branch":"main","commit_sha":"${context.commitSha}"}}"""

    private fun repository(http: HttpClient, state: GitHubRepositoryStateStore = GitHubRepositoryStateStore()): GatewayGraphRepository {
        coEvery { settings.fetchPlatformV2s() } coAnswers { profiles }
        coEvery { settings.getFeatureSettings() } coAnswers { features }
        coEvery { connections.getConnection("github") } returns connection
        return GatewayGraphRepository(settings, connections, GatewayEngineeringClient(http), state)
    }

    @Test fun cloudAiUsesOnlyItsExplicitHostAndReadOnlyGraphReadiness() = runTest {
        var graphRequests = 0
        HttpClient(
            MockEngine { request ->
                assertTrue(request.url.toString().startsWith(host.apiUrl))
                if (request.url.encodedPath.endsWith("capabilities")) {
                    respond(ready, HttpStatusCode.OK, headers)
                } else {
                    graphRequests++
                    assertEquals("installation", request.headers["X-Gateway-Installation-ID"])
                    assertEquals("account", request.headers["X-Gateway-Principal-ID"])
                    val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                    assertEquals(JsonPrimitive(context.commitSha), body["commit_sha"])
                    respond(notIndexed, HttpStatusCode.OK, headers)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val result = repository(http).executeForMap(context, action("status"), host.uid)
            assertEquals(host.uid, result.gatewayProfileUid)
            assertEquals(host.name, result.gatewayName)
            assertEquals(1, graphRequests)
        }
    }

    @Test fun missingExplicitHostNeverGuessesTheInferenceAddress() = runTest {
        features = AppFeatureSettings()
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond(ready, HttpStatusCode.OK, headers)
            }
        ) { install(HttpTimeout) }.use { http ->
            val failure = runCatching { repository(http).executeForMap(context, action("index")) }.exceptionOrNull() as GatewayGraphOperationException
            assertEquals("SELECT_CONFIGURED_ENGINEERING_SURFACE", failure.repairAction)
            assertFalse(failure.indexOutcomeUnknown)
            assertEquals(0, requests)
        }
    }

    @Test fun savedGraphAllowsEquivalentRepositoryCasingButRejectsDifferentIdentities() = runTest {
        val selected = context.copy(owner = "Owner", repo = "Repo", commitSha = "A".repeat(40))
        var response = status("saved-cursor")
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("capabilities")) {
                    respond(ready, HttpStatusCode.OK, headers)
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                    assertEquals(JsonPrimitive("Owner/Repo"), body["repository"])
                    assertEquals(JsonPrimitive(context.commitSha), body["commit_sha"])
                    respond(response, HttpStatusCode.OK, headers)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val repo = repository(http)
            assertEquals(JsonPrimitive("saved-cursor"), repo.executeForMap(selected, action("status"), host.uid).result["index_cursor"])
            response = status("saved-cursor").replace("owner/repo", "owner/other")
            assertTrue(runCatching { repo.executeForMap(selected, action("status"), host.uid) }.exceptionOrNull() is GatewayGraphOperationException)
            response = status("saved-cursor").replace("\"main\"", "\"Main\"")
            assertTrue(runCatching { repo.executeForMap(selected, action("status"), host.uid) }.exceptionOrNull() is GatewayGraphOperationException)
        }
    }

    @Test fun configurationChangeDuringReadinessCannotDispatchToTheOldHost() = runTest {
        val admitted = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var posts = 0
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("capabilities")) {
                    admitted.complete(Unit)
                    resume.await()
                    respond(ready, HttpStatusCode.OK, headers)
                } else {
                    posts++
                    respond(notIndexed, HttpStatusCode.OK, headers)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val repo = repository(http)
            val pending = async { runCatching { repo.executeForMap(context, action("index"), host.uid) } }
            admitted.await()
            profiles = listOf(ai, host.copy(apiUrl = "http://127.0.0.1:8091/v1"))
            resume.complete(Unit)
            assertTrue(pending.await().isFailure)
            assertEquals(0, posts)
        }
    }

    @Test fun explicitRecheckReadsFreshReadinessAfterTheHostIsRepaired() = runTest {
        var checks = 0
        var posts = 0
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("capabilities")) {
                    checks++
                    respond(if (checks == 1) ready.replace("\"enabled\":true", "\"enabled\":false").replace("\"graph_ready\":true", "\"graph_ready\":false").replace("\"graph_host_github_connection\":\"configured\"", "\"graph_host_github_connection\":\"missing\"") else ready, HttpStatusCode.OK, headers)
                } else {
                    posts++
                    respond(notIndexed, HttpStatusCode.OK, headers)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val repo = repository(http)
            val first = runCatching { repo.executeForMap(context, action("status"), host.uid) }.exceptionOrNull() as GatewayGraphOperationException
            assertEquals("CONFIGURE_SELECTED_GATEWAY_HOST_GITHUB", first.repairAction)
            repo.executeForMap(context, action("status"), host.uid)
            assertEquals(2, checks)
            assertEquals(1, posts)
        }
    }

    @Test fun lostIndexResponseIsNotReplayedAndStatusReconcilesTheRetainedCursor() = runTest {
        var saved = notIndexed
        var writes = 0
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("capabilities")) {
                    respond(ready, HttpStatusCode.OK, headers)
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                    if (body["action"]?.jsonPrimitive?.content == "index") {
                        writes++
                        saved = status("next-page")
                        throw IOException("lost response")
                    }
                    respond(saved, HttpStatusCode.OK, headers)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val state = GitHubRepositoryStateStore()
            val repo = repository(http, state)
            val first = runCatching { repo.executeForMap(context, action("index"), host.uid) }.exceptionOrNull() as GatewayGraphOperationException
            assertTrue(first.indexOutcomeUnknown)
            val restored = repository(http, state)
            assertTrue(runCatching { restored.executeForMap(context, action("index"), host.uid) }.isFailure)
            assertEquals(1, writes)
            val observed = restored.executeForMap(context, action("status"), host.uid)
            assertFalse(observed.indexOutcomeUnknown)
            assertEquals(JsonPrimitive("next-page"), observed.result["index_cursor"])
        }
    }

    @Test fun wrongRevisionResponseCannotClearTheOriginalIndexCheckpoint() = runTest {
        var saved = notIndexed
        var writes = 0
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("capabilities")) {
                    respond(ready, HttpStatusCode.OK, headers)
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                    if (body["action"]?.jsonPrimitive?.content == "index") {
                        writes++
                        saved = status("next-page").replace(context.commitSha, "b".repeat(40))
                        throw IOException("lost response")
                    }
                    respond(saved, HttpStatusCode.OK, headers)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val repo = repository(http)
            runCatching { repo.executeForMap(context, action("index"), host.uid) }
            val failure = runCatching { repo.executeForMap(context, action("status"), host.uid) }.exceptionOrNull() as GatewayGraphOperationException
            assertTrue(failure.indexOutcomeUnknown)
            assertTrue(runCatching { repo.executeForMap(context, action("index"), host.uid) }.isFailure)
            assertEquals(1, writes)
        }
    }

    @Test fun cancelledIndexKeepsItsFenceAcrossAiProfileChanges() = runTest {
        val started = CompletableDeferred<Unit>()
        var writes = 0
        HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath.endsWith("capabilities")) {
                    respond(ready, HttpStatusCode.OK, headers)
                } else {
                    val body = Json.parseToJsonElement((request.body as TextContent).text) as JsonObject
                    if (body["action"]?.jsonPrimitive?.content == "index") {
                        writes++
                        started.complete(Unit)
                        awaitCancellation()
                    }
                    respond(notIndexed, HttpStatusCode.OK, headers)
                }
            }
        ) { install(HttpTimeout) }.use { http ->
            val repo = repository(http)
            val pending = async { repo.executeForMap(context, action("index"), host.uid) }
            started.await()
            pending.cancelAndJoin()
            val replacement = ai.copy(uid = "another-ai")
            profiles = listOf(ai, replacement, host)
            features = features.copy(githubProfileOverrides = features.githubProfileOverrides + (replacement.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = host.uid)))
            val selected = context.copy(mainProfileUid = replacement.uid)
            assertTrue(repo.executeForMap(selected, action("status"), host.uid).indexOutcomeUnknown)
            val failure = runCatching { repo.executeForMap(selected, action("index"), host.uid) }.exceptionOrNull() as GatewayGraphOperationException
            assertTrue(failure.indexOutcomeUnknown)
            assertEquals(1, writes)
        }
    }

    private fun action(value: String) = buildJsonObject { put("action", value) }
}
