package dev.chungjungsoo.gptmobile.data.github

import android.content.Context
import android.content.SharedPreferences
import dev.chungjungsoo.gptmobile.data.agent.AgentProviderSession
import dev.chungjungsoo.gptmobile.data.agent.AgentRunEvent
import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentRunner
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool
import dev.chungjungsoo.gptmobile.data.agent.tool.RepositoryGraphTool
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.network.PrivateExternalServiceGate
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Files
import java.util.Base64
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Both deadline owners execute the real native facade, admission, session and persistent store. */
class PhoneIndexDeadlineRecoveryTest {
    @Test fun runnerDeadlineReadsBackAndResumesPersistedPhoneProgress() = runBlocking {
        Fixture().use { fixture -> verifyInterruptedProgress(fixture, budgeted = false) }
    }

    @Test fun sharedBudgetDeadlineReadsBackAndResumesPersistedPhoneProgress() = runBlocking {
        Fixture().use { fixture -> verifyInterruptedProgress(fixture, budgeted = true) }
    }

    private suspend fun verifyInterruptedProgress(fixture: Fixture, budgeted: Boolean) {
        val limits = AgentRunLimits(githubTask = true, toolTimeoutMillis = 5000, maxToolCalls = 8)
        val budget = ToolExecutionBudget(limits)
        val native = fixture.tool()
        val tool = if (budgeted) budget.bind(native) else native
        val result = throughRunner(tool, call("interrupted"), limits)
        assertTrue(fixture.secondBlobEntered.isCompleted)
        assertTrue(fixture.secondBlobStopped.isCompleted)
        assertEquals("PHONE_INDEX_INTERRUPTED", result.errorCode)
        assertTrue(result.isError)
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
        assertEquals(JsonPrimitive(true), result.executionReceipt?.get("local_work_stopped"))
        assertEquals(JsonPrimitive(false), result.executionReceipt?.get("request_replay_allowed"))
        assertEquals(0, fixture.mutations.get())

        // Reopen the actual persistent cache. Status and the next batch re-run native admission.
        val reopened = fixture.tool()
        val continuation = if (budgeted) budget.bind(reopened) else reopened
        val recovery = body(result).getValue("recovery_call").jsonObject
        val status = continuation.execute("readback", recovery.getValue("arguments").jsonObject)
        assertFalse(status.isError)
        val statusBody = body(status)
        assertEquals(JsonPrimitive(1), statusBody.getValue("graph").jsonObject["processed_files"])
        val cursor = statusBody.getValue("index_cursor").jsonPrimitive.content
        val resumed = continuation.execute("resume", arguments("index", cursor))
        assertFalse(resumed.isError)
        assertEquals(JsonPrimitive(true), body(resumed).getValue("graph").jsonObject["coverage_complete"])
        assertEquals(1, fixture.firstBlobReads.get())
        assertEquals(2, fixture.secondBlobReads.get())
        assertEquals(0, fixture.mutations.get())
        assertTrue(fixture.repositoryReads.get() >= 3)
        assertTrue(status.executionReceipt?.get("confirmed_index_attempt_hash") == null)
        assertTrue(resumed.executionReceipt?.get("confirmed_index_attempt_hash") == null)
    }

    @Test fun parentCancellationPropagatesAndDoesNotFenceTheStoppedLocalBatch() = runBlocking {
        Fixture().use { fixture ->
            val limits = AgentRunLimits(toolTimeoutMillis = 60_000, maxToolCalls = 8)
            val budget = ToolExecutionBudget(limits)
            val tool = budget.bind(fixture.tool())
            val work = async { tool.execute("cancelled", arguments("index")) }
            withTimeout(5000) { fixture.secondBlobEntered.await() }
            work.cancelAndJoin()
            assertTrue(work.isCancelled)
            assertTrue(fixture.secondBlobStopped.isCompleted)
            val resumed = tool.execute("new-call", arguments("index"))
            assertFalse(resumed.isError)
            assertEquals(JsonPrimitive(true), body(resumed).getValue("graph").jsonObject["coverage_complete"])
            assertEquals(1, fixture.firstBlobReads.get())
            assertEquals(2, fixture.secondBlobReads.get())
        }
    }

    @Test fun gatewayUnknownStillFencesIdenticalIndexAndPhoneStatusCannotClearIt() = runBlocking {
        var gatewayDispatches = 0
        val graph = RepositoryGraphTool("repository_graph__selected") { payload ->
            if (payload["map_source"] == JsonPrimitive("gateway")) {
                gatewayDispatches++
                awaitCancellation()
            }
            buildJsonObject {
                put("map_source", "phone")
                put("status", "complete")
            }
        }
        val limits = AgentRunLimits(toolTimeoutMillis = 100, maxToolCalls = 8)
        val tool = ToolExecutionBudget(limits).bind(graph)
        val gateway = buildJsonObject {
            put("action", "index")
            put("map_source", "gateway")
        }
        assertEquals("TIMEOUT_OUTCOME_UNKNOWN", tool.execute("gateway-first", gateway).errorCode)
        assertFalse(tool.execute("phone-status", arguments("status")).isError)
        assertEquals("RECONCILIATION_REQUIRED", tool.execute("gateway-repeat", gateway).errorCode)
        assertEquals(1, gatewayDispatches)
        val raw = throughRunner(graph, ProviderEvent.ToolCall("gateway-runner", graph.definition.name, gateway), limits)
        assertEquals("TIMEOUT_OUTCOME_UNKNOWN", raw.errorCode)
        assertEquals(2, gatewayDispatches)
    }

    private suspend fun throughRunner(tool: AgentTool, call: ProviderEvent.ToolCall, limits: AgentRunLimits): AgentToolResult {
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flow {
                if (exchanges.isEmpty()) emit(call) else emit(ProviderEvent.TextDelta("Retained the observed result."))
                emit(ProviderEvent.Completed)
            }
        }
        val events = AgentRunner(limits).run(session, listOf(tool)).toList()
        return events.filterIsInstance<AgentRunEvent.ToolFinished>().single { it.call.callId == call.callId }.result
    }

    private fun call(id: String) = ProviderEvent.ToolCall(id, "repository_graph__selected", arguments("index"))
    private fun arguments(action: String, cursor: String? = null) = buildJsonObject {
        put("action", action)
        put("map_source", "phone")
        cursor?.let { put("options", buildJsonObject { put("index_cursor", it) }) }
    }

    private fun body(result: AgentToolResult): JsonObject = when (val content = result.content) {
        is ToolResultContent.Json -> content.value.jsonObject
        is ToolResultContent.Text -> kotlinx.serialization.json.Json.parseToJsonElement(content.text).jsonObject
        else -> error("Expected the typed native result")
    }

    private class Fixture : AutoCloseable {
        private val directory = Files.createTempDirectory("phone-index-deadline").toFile()
        private val context = GitHubConversationContext("github", "owner", "repo", "main", "a".repeat(40), "cloud-ai")
        private val settings = mockk<SettingRepository>()
        private val connections = mockk<ToolConnectionRepository>()
        private val authentication = mockk<GitHubPublicAuthCoordinator>()
        private val connection = ToolConnection("github", "GitHub", "selected", "GITHUB", null, "BEARER", "credential", null)
        val firstBlobReads = AtomicInteger()
        val secondBlobReads = AtomicInteger()
        val repositoryReads = AtomicInteger()
        val mutations = AtomicInteger()
        val secondBlobEntered = CompletableDeferred<Unit>()
        val secondBlobStopped = CompletableDeferred<Unit>()

        init {
            val android = mockk<Context>()
            val preferences = mockk<SharedPreferences>()
            every { android.applicationContext } returns android
            every { android.getSharedPreferences("device_privacy", Context.MODE_PRIVATE) } returns preferences
            every { preferences.getBoolean("privacy_mode_enabled", false) } returns false
            PrivacyModeStore.initialize(android)
            coEvery { settings.fetchPlatformV2s() } returns listOf(PlatformV2(uid = "cloud-ai", name = "Cloud", compatibleType = ClientType.OPENAI, apiUrl = "https://provider.example/v1", model = "fixture"))
            coEvery { settings.getFeatureSettings() } returns AppFeatureSettings()
            coEvery { connections.getConnection("github") } returns connection
            coEvery { authentication.accessToken(connection) } returns "test-only-token"
            coEvery { authentication.durableIdentity("github") } returns "verified-account"
        }

        private val http = HttpClient(
            MockEngine { request ->
                if (request.method.value != "GET") mutations.incrementAndGet()
                assertEquals("GET", request.method.value)
                assertEquals("api.github.com", request.url.host)
                val path = request.url.encodedPath
                val value = when {
                    path == "/repos/owner/repo" -> {
                        repositoryReads.incrementAndGet()
                        buildJsonObject { put("full_name", "owner/repo") }
                    }
                    "/commits/" in path -> buildJsonObject {
                        put("sha", context.commitSha)
                        put("commit", buildJsonObject { put("tree", buildJsonObject { put("sha", "b".repeat(40)) }) })
                    }
                    "/git/trees/" in path -> buildJsonObject {
                        put("truncated", false)
                        put(
                            "tree",
                            buildJsonArray {
                                for (index in 1..2) {
                                    add(
                                        buildJsonObject {
                                            put("path", "src/File$index.kt")
                                            put("type", "blob")
                                            put("mode", "100644")
                                            put("sha", "%040x".format(index))
                                            put("size", 40)
                                        }
                                    )
                                }
                            }
                        )
                    }
                    "/git/blobs/" in path -> {
                        val sha = path.substringAfterLast('/')
                        if (sha == "%040x".format(1)) firstBlobReads.incrementAndGet()
                        if (sha == "%040x".format(2) && secondBlobReads.incrementAndGet() == 1) {
                            secondBlobEntered.complete(Unit)
                            try {
                                awaitCancellation()
                            } finally {
                                secondBlobStopped.complete(Unit)
                            }
                        }
                        buildJsonObject {
                            put("sha", sha)
                            put("encoding", "base64")
                            put("content", Base64.getEncoder().encodeToString("package fixture\nfun useful() = 1\n".toByteArray()))
                        }
                    }
                    else -> error("Unexpected native request $path")
                }
                respond(value.toString(), HttpStatusCode.OK, headersOf("Content-Type", "application/json"))
            }
        ) { install(PrivateExternalServiceGate) }

        fun tool(): AgentTool {
            val repository = PhoneRepositoryGraphRepository(settings, connections, authentication, GitHubRepositoryStateStore(directory)) { it.createClient(http) }
            return requireNotNull(
                GitHubTool(
                    apiToken = "test-only-token",
                    httpClient = http,
                    modelToolName = "github__selected",
                    repositoryScope = context,
                    connectionUid = context.connectionUid,
                    durableIdentity = "verified-account",
                    graphQuery = { payload -> repository.executeForMap(context, JsonObject(payload - "map_source"), forModel = true).result }
                ).graphTool()
            )
        }

        override fun close() {
            http.close()
            directory.deleteRecursively()
        }
    }
}
