package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.PrivateMetadataGate
import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayPairedRequestSource
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewaySignedRequest
import dev.chungjungsoo.gptmobile.data.privacy.ConversationPrivacyContext
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.privacy.UnverifiedServiceTransport
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.io.EOFException
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class GatewayGraphPrivacyDispatchTest {
    private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "cloud-ai")
    private val ai = PlatformV2(uid = "cloud-ai", name = "Selected AI", compatibleType = ClientType.OPENAI, apiUrl = "https://provider.example/v1", model = "selected-model")
    private val host = PlatformV2(uid = "host", name = "Selected Gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://127.0.0.1:8090/${UUID.randomUUID()}/v1", model = "unused")
    private val privateConversation = ConversationPrivacyContext(77, true)
    private val ready = """{"service":"engineering-gateway","protocol_version":1,"principal_id":"account","gateway_id":"installation","code_graph":{"schema_version":1,"enabled":true},"readiness":{"schema_version":1,"graph_ready":true,"checks":{"journal":"ready","graph_host_github_connection":"configured"}}}"""
    private val complete = """{"has_more":false,"graph":{"repository":"owner/repo","branch":"main","commit_sha":"${context.commitSha}"}}"""

    @Before fun initializePolicy() {
        val app = mockk<Context>()
        val preferences = mockk<SharedPreferences>()
        every { app.applicationContext } returns app
        every { app.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) } returns preferences
        every { preferences.getBoolean("privacy_mode_enabled", false) } returns false
        PrivacyModeStore.initialize(app)
    }

    @Test fun aRealLocalGateDenialBeforeAnyExchangeIsNotDispatched() = runTest {
        Fixture().use { fixture ->
            val tool = RepositoryGraphTool("repository_graph") { fixture.client.graph(host.engineeringConfig(), it) }
            val result = withContext(privateConversation) { tool.execute("denied", action("status")) }
            assertPrivacyReceipt(result, dispatched = false)
            assertEquals(emptyList<String>(), fixture.sentPaths)
            assertEquals(JsonPrimitive("REVIEW_PRIVACY_MODE"), result.executionReceipt?.get("repair_action"))
        }
    }

    @Test fun aCapabilityResponseRemainsDispatchedWhenTheGraphPostIsLocallyDenied() = runTest {
        Fixture().use { fixture ->
            val tool = RepositoryGraphTool("repository_graph") {
                fixture.client.capabilities(host.engineeringConfig())
                withContext(privateConversation) { fixture.client.graph(host.engineeringConfig(), it) }
            }
            val result = tool.execute("capability-then-denied", action("status"))
            assertPrivacyReceipt(result, dispatched = true)
            assertEquals(1, fixture.sentPaths.size)
            assertTrue(fixture.sentPaths.single().endsWith("capabilities"))
        }
    }

    @Test fun aLostStatusExchangeRemainsDispatchedWhenItsBoundedRetryIsLocallyDenied() = runTest {
        var preparations = 0
        Fixture(
            graph = { throw EOFException("Fixture status response was interrupted") },
            paired = { http ->
                pairedSource { _, path, _ ->
                    if (path.endsWith("/graph") && ++preparations == 2) deny(http)
                }
            }
        ).use { fixture ->
            fixture.warmCapabilities()
            val result = fixture.tool().execute("status-retry", action("status"))
            assertPrivacyReceipt(result, dispatched = true)
            assertEquals(2, preparations)
            assertEquals(1, fixture.graphRequests)
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
        }
    }

    @Test fun aLocallyDeniedFreshIndexClearsOnlyItsUnsentCheckpoint() = runTest {
        Fixture().use { fixture ->
            fixture.warmCapabilities()
            val result = withContext(privateConversation) { fixture.tool().execute("unsent-index", action("index")) }
            assertPrivacyReceipt(result, dispatched = false)
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("index_outcome_unknown"))
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("current_index_outcome_unknown"))
            assertFalse(fixture.checkpoint.unresolved())
            assertEquals(0, fixture.graphRequests)
            assertFalse(fixture.tool().execute("next-index", action("index")).isError)
            assertEquals(1, fixture.graphRequests)
        }
    }

    @Test fun pairedPreparationCanSendMetadataWithoutSendingOrFencingTheIndexPost() = runTest {
        var blocked = true
        Fixture(
            paired = { http ->
                pairedSource { _, path, _ ->
                    if (path.endsWith("/graph") && blocked) {
                        http.get("${host.apiUrl}/gateway/live")
                        deny(http)
                    }
                }
            }
        ).use { fixture ->
            val result = fixture.tool().execute("paired-index", action("index"))
            assertPrivacyReceipt(result, dispatched = true)
            assertEquals(2, fixture.sentPaths.size)
            assertEquals(0, fixture.graphRequests)
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("index_outcome_unknown"))
            assertEquals(JsonPrimitive(false), result.executionReceipt?.get("current_index_outcome_unknown"))
            assertFalse(fixture.checkpoint.unresolved())
            blocked = false
            assertFalse(fixture.tool().execute("after-prepare-denial", action("index")).isError)
            assertEquals(1, fixture.graphRequests)
        }
    }

    @Test fun aPrivacyDeniedStatusKeepsAnEarlierUnknownIndexIdentity() = runTest {
        Fixture(graph = { throw EOFException("Fixture index response was lost") }).use { fixture ->
            fixture.warmCapabilities()
            val first = fixture.tool().execute("prior-index", action("index"))
            val originalHash = requireNotNull(first.executionReceipt?.get("graph_index_attempt_hash"))
            assertEquals(JsonPrimitive(true), first.executionReceipt?.get("current_index_outcome_unknown"))
            val denied = withContext(privateConversation) { fixture.tool().execute("denied-status", action("status")) }
            assertPrivacyReceipt(denied, dispatched = false)
            assertEquals(originalHash, denied.executionReceipt?.get("graph_index_attempt_hash"))
            assertEquals(JsonPrimitive(true), denied.executionReceipt?.get("requires_reconciliation"))
            assertEquals(JsonPrimitive(true), denied.executionReceipt?.get("index_outcome_unknown"))
            assertEquals(JsonPrimitive(false), denied.executionReceipt?.get("current_index_outcome_unknown"))
            assertEquals(JsonPrimitive("CHECK_SAVED_INDEX"), denied.executionReceipt?.get("repair_action"))
            val refused = fixture.tool().execute("still-pending", action("index"))
            assertEquals("GRAPH_INDEX_OUTCOME_UNKNOWN", refused.errorCode)
            assertEquals(originalHash, refused.executionReceipt?.get("graph_index_attempt_hash"))
            assertEquals(1, fixture.graphRequests)
        }
    }

    @Test fun unsentAttemptCleanupCannotClearAConcurrentReplacementCheckpoint() = runTest {
        val state = GitHubRepositoryStateStore()
        val retained = GatewayGraphIndexCheckpoint(state, scope())
        var originalHash: String? = null
        var replacementHash: String? = null
        Fixture(
            state = state,
            paired = { http ->
                pairedSource { _, path, _ ->
                    if (path.endsWith("/graph")) {
                        val original = requireNotNull(retained.pendingRequestId())
                        originalHash = retained.attemptHash(original)
                        retained.confirm(original, executed = false)
                        replacementHash = retained.attemptHash(retained.begin("replacement"))
                        deny(http)
                    }
                }
            }
        ).use { fixture ->
            fixture.warmCapabilities()
            val denied = fixture.tool().execute("replaced-unsent-index", action("index"))
            assertPrivacyReceipt(denied, dispatched = false)
            assertNotEquals(originalHash, replacementHash)
            assertEquals(replacementHash, retained.observation().attemptHash)
            assertEquals(JsonPrimitive(replacementHash), denied.executionReceipt?.get("graph_index_attempt_hash"))
            assertEquals(JsonPrimitive(true), denied.executionReceipt?.get("index_outcome_unknown"))
            assertEquals(JsonPrimitive(false), denied.executionReceipt?.get("current_index_outcome_unknown"))
            assertEquals(JsonPrimitive("CHECK_SAVED_INDEX"), denied.executionReceipt?.get("repair_action"))
            assertEquals(0, fixture.graphRequests)
        }
    }

    @Test fun cancellationAfterTheIndexEntersTransportDoesNotClearItsUnknownCheckpoint() = runTest {
        val entered = CompletableDeferred<Unit>()
        Fixture(graph = {
            entered.complete(Unit)
            awaitCancellation()
        }).use { fixture ->
            fixture.warmCapabilities()
            val running = async { fixture.tool().execute("cancelled-index", action("index")) }
            entered.await()
            val originalHash = requireNotNull(fixture.checkpoint.observation().attemptHash)
            running.cancelAndJoin()
            assertTrue(running.isCancelled)
            assertEquals(originalHash, fixture.checkpoint.observation().attemptHash)
            val refused = fixture.tool().execute("after-cancellation", action("index"))
            assertEquals("GRAPH_INDEX_OUTCOME_UNKNOWN", refused.errorCode)
            assertEquals(JsonPrimitive(originalHash), refused.executionReceipt?.get("graph_index_attempt_hash"))
            assertEquals(1, fixture.graphRequests)
        }
    }

    private fun assertPrivacyReceipt(result: AgentToolResult, dispatched: Boolean) {
        assertTrue(result.isError)
        assertEquals(UnverifiedServiceTransport.CODE, result.errorCode)
        assertEquals(dispatched, result.dispatched)
        assertEquals(JsonPrimitive(dispatched), result.executionReceipt?.get("dispatched"))
        assertEquals(JsonPrimitive(if (dispatched) "FAILED" else "PRECHECK_REJECTED"), result.executionReceipt?.get("phase"))
        assertEquals(JsonPrimitive(if (dispatched) "tool_execution" else "tool_precheck"), result.executionReceipt?.get("failed_stage"))
        assertEquals(JsonPrimitive(if (dispatched) "tool_execution" else "tool_precheck"), result.executionReceipt?.get("authority_failed_stage"))
    }

    private suspend fun deny(http: HttpClient) {
        withContext(privateConversation) { http.get("${host.apiUrl}/gateway/engineering/denied") }
        error("The real privacy gate must reject before the engine")
    }

    private fun pairedSource(prepare: suspend (ProviderRequestConfig, String, String) -> Unit): GatewayPairedRequestSource = object : GatewayPairedRequestSource {
        override suspend fun prepare(config: ProviderRequestConfig, method: String, path: String, body: String): GatewaySignedRequest? {
            prepare(config, path, body)
            return null
        }
    }

    private fun action(value: String) = buildJsonObject {
        put("action", value)
        put("map_source", "gateway")
        if (value == "index") put("options", buildJsonObject { put("index_cursor", "saved-batch") })
    }

    private fun scope() = GitHubRepositoryStateStore.digest(
        buildJsonObject {
            put("kind", "gateway-graph-index-v1")
            put("gateway_id", "installation")
            put("principal", "account")
            put("repository", "owner/repo")
            put("branch", "main")
            put("commit_sha", context.commitSha)
        }.toString()
    )

    private inner class Fixture(
        state: GitHubRepositoryStateStore = GitHubRepositoryStateStore(),
        graph: suspend (JsonObject) -> String = { complete },
        paired: (HttpClient) -> GatewayPairedRequestSource? = { null }
    ) : AutoCloseable {
        val sentPaths = mutableListOf<String>()
        var graphRequests = 0
        val checkpoint = GatewayGraphIndexCheckpoint(state, scope())
        private val http = HttpClient(
            MockEngine { request ->
                sentPaths += request.url.encodedPath
                val payload = when {
                    request.url.encodedPath.endsWith("/capabilities") -> ready
                    request.url.encodedPath.endsWith("/gateway/live") -> "{}"
                    request.url.encodedPath.endsWith("/graph") -> {
                        graphRequests++
                        graph(Json.parseToJsonElement((request.body as TextContent).text).jsonObject)
                    }
                    else -> error("A privacy-denied request reached the HTTP engine")
                }
                respond(payload, HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ) {
            install(HttpTimeout)
            install(PrivateMetadataGate)
        }
        val client = GatewayEngineeringClient(http, pairedRequests = paired(http))
        private val settings = mockk<SettingRepository>()
        private val connections = mockk<ToolConnectionRepository>()
        private val repository: GatewayGraphRepository

        init {
            coEvery { settings.fetchPlatformV2s() } returns listOf(ai, host)
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings(githubProfileOverrides = mapOf(ai.uid to GitHubChatProfileOverride(engineeringGatewayProfileUid = host.uid)))
            coEvery { connections.getConnection(context.connectionUid) } returns ToolConnection("github", "GitHub", "github", "GITHUB", null, "NONE", null, null)
            repository = GatewayGraphRepository(settings, connections, client, state)
        }

        suspend fun warmCapabilities() {
            client.capabilities(host.engineeringConfig())
            sentPaths.clear()
        }

        fun tool() = RepositoryGraphTool("repository_graph") { repository.executeForMap(context, it, refreshReadiness = false).result }

        override fun close() = http.close()
    }
}
