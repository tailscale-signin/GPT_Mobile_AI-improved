package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.mockk.coEvery
import io.mockk.mockk
import java.io.EOFException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayCopilotOperationsTest {
    @Test fun delegationSendsNumericActorAndOnlyReviewedFieldsWithGatewayCredential() = runTest {
        val directory = Files.createTempDirectory("gateway-copilot-wire").toFile()
        try {
            Fixture(GitHubRepositoryStateStore(directory)).use { fixture ->
                fixture.operation = { request ->
                    val body = Json.parseToJsonElement((request.body as TextContent).text).jsonObject
                    assertEquals(JsonPrimitive(7L), body["expected_actor_id"])
                    assertFalse(body.getValue("expected_actor_id").jsonPrimitive.isString)
                    assertEquals(JsonPrimitive(false), body["create_pull_request"])
                    assertEquals(JsonPrimitive("GATEWAY"), body["execution_owner"])
                    assertEquals("Bearer gateway-secret", request.headers["Authorization"])
                    assertEquals("gateway-installation", request.headers["X-Gateway-Installation-ID"])
                    assertEquals("principal", request.headers["X-Gateway-Principal-ID"])
                    assertFalse(body.toString().contains("phone-github-secret"))
                    assertFalse(body.containsKey("github_token"))
                    val saved = requireNotNull(fixture.state.read(NAMESPACE, requestKey(REQUEST_ID)))
                    assertEquals(body, saved["request"])
                    HttpStatusCode.Accepted to fixture.receipt().toString()
                }
                fixture.execute(args = JsonObject(arguments() + ("github_token" to JsonPrimitive("phone-github-secret"))))
                assertEquals(1, fixture.posts)
                assertFalse(
                    directory.walkTopDown().filter { it.isFile }.any { file ->
                        val text = file.readBytes().decodeToString()
                        text.contains("gateway-secret") || text.contains("phone-github-secret")
                    }
                )
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun lostPostAndProcessRestartRecoverByRequestIdThenRetainExactJobWithoutRepost() = runTest {
        val directory = Files.createTempDirectory("gateway-copilot-recovery").toFile()
        try {
            Fixture(GitHubRepositoryStateStore(directory)).use { fixture ->
                fixture.operation = { throw EOFException("response lost") }
                val failure = runCatching { fixture.execute() }.exceptionOrNull() as GitHubExecutionBlocked
                val next = requireNotNull(failure.checkpoint["next_action"] as? JsonObject)
                assertEquals(setOf("action", "request_id"), next.keys)
                assertEquals(JsonPrimitive(REQUEST_ID), next["request_id"])
                assertEquals(JsonPrimitive("copilot_task_status"), next["action"])
                fixture.state = GitHubRepositoryStateStore(directory)
                fixture.operation = { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    HttpStatusCode.OK to fixture.response(request).toString()
                }
                assertEquals(JsonPrimitive(JOB_ID), fixture.execute("copilot_task_status", statusArguments())["gateway_job_id"])
                assertEquals(REQUEST_ID, fixture.taskReads.single().url.parameters["request_id"])
                fixture.state = GitHubRepositoryStateStore(directory)
                fixture.execute("copilot_task_status", statusArguments())
                assertTrue(fixture.taskReads.last().url.encodedPath.endsWith("/$JOB_ID"))
                fixture.execute()
                assertEquals(1, fixture.posts)
                assertEquals(JsonPrimitive("OBSERVED"), fixture.state.read(NAMESPACE, requestKey(REQUEST_ID))?.get("dispatch_state"))
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun missingReadbackAndReplacementRequestIdNeverReplayUncertainPost() = runTest {
        Fixture().use { fixture ->
            fixture.operation = { throw EOFException("response lost") }
            assertTrue(runCatching { fixture.execute() }.exceptionOrNull() is GitHubExecutionBlocked)
            fixture.operation = {
                HttpStatusCode.OK to buildJsonObject {
                    put("schema", SCHEMA)
                    put("tasks", JsonArray(emptyList()))
                }.toString()
            }
            assertTrue(runCatching { fixture.execute() }.exceptionOrNull() is GitHubExecutionBlocked)
            assertTrue(runCatching { fixture.execute(args = JsonObject(arguments() + ("request_id" to JsonPrimitive("replacement-request")))) }.exceptionOrNull() is GitHubExecutionBlocked)
            assertEquals(1, fixture.posts)
        }
    }

    @Test fun changedGatewayPrincipalAccountEndpointOrControllerCannotResumeSavedRequest() = runTest {
        for (change in listOf<(Fixture) -> Unit>(
            { it.gatewayId = "another-gateway" },
            { it.principal = "another-principal" },
            { it.actorId = 99 },
            { it.profile = it.profile.copy(apiUrl = "http://192.168.1.99:8090/v1") },
            { it.features = AppFeatureSettings() }
        )) {
            Fixture().use { fixture ->
                fixture.execute()
                change(fixture)
                assertTrue(runCatching { fixture.execute("copilot_task_status", statusArguments()) }.isFailure)
                assertEquals(1, fixture.posts)
                assertTrue(fixture.taskReads.isEmpty())
            }
        }
    }

    @Test fun unrelatedCapabilityOrNonNumericActorNeverCreatesTask() = runTest {
        for ((field, value) in mapOf(
            "schema" to JsonPrimitive("other.schema"),
            "supported" to JsonPrimitive(false),
            "execution_owner" to JsonPrimitive("CLIENT"),
            "orchestration_owner" to JsonPrimitive("CLIENT"),
            "actor_id" to JsonPrimitive("7")
        )) {
            Fixture().use { fixture ->
                fixture.capabilityChange = { JsonObject(it + (field to value)) }
                assertTrue(runCatching { fixture.execute() }.isFailure)
                assertEquals(0, fixture.posts)
            }
        }
    }

    @Test fun wrongReceiptIdentityOrOptionsStayUnconfirmedUntilReadOnlyReconciliation() = runTest {
        for ((field, value) in mapOf(
            "gateway_id" to JsonPrimitive("another-gateway"),
            "principal_id" to JsonPrimitive("another-principal"),
            "repository" to JsonPrimitive("other/repository"),
            "request_id" to JsonPrimitive("another-request"),
            "github_actor_id" to JsonPrimitive(99),
            "expected_base_sha" to JsonPrimitive("b".repeat(40)),
            "create_pull_request" to JsonPrimitive(true)
        )) {
            Fixture().use { fixture ->
                fixture.operation = { HttpStatusCode.Accepted to JsonObject(fixture.receipt() + (field to value)).toString() }
                assertTrue(runCatching { fixture.execute() }.exceptionOrNull() is GitHubExecutionBlocked)
                assertEquals(null, fixture.state.read(NAMESPACE, requestKey(REQUEST_ID))?.get("receipt"))
                fixture.operation = { request -> HttpStatusCode.OK to fixture.response(request).toString() }
                fixture.execute("copilot_task_status", statusArguments())
                assertEquals(1, fixture.posts)
                assertEquals(1, fixture.taskReads.size)
            }
        }
    }

    @Test fun statusRejectsAnotherJobEvenWhenRequestAndAccountMatch() = runTest {
        Fixture().use { fixture ->
            fixture.execute()
            fixture.operation = { HttpStatusCode.OK to JsonObject(fixture.receipt() + ("gateway_job_id" to JsonPrimitive("cp_" + "b".repeat(32)))).toString() }
            val failure = runCatching { fixture.execute("copilot_task_status", statusArguments()) }.exceptionOrNull() as GitHubExecutionBlocked
            assertEquals(JsonPrimitive(JOB_ID), failure.checkpoint["gateway_job_id"])
            val next = requireNotNull(failure.checkpoint["next_action"] as? JsonObject)
            assertEquals(JsonPrimitive(REQUEST_ID), next["request_id"])
            assertFalse(next.containsKey("gateway_job_id"))
            assertEquals(1, fixture.posts)
        }
    }

    @Test fun hostOffAndRevokedConsentStillAllowExistingStatusAndList() = runTest {
        Fixture().use { fixture ->
            fixture.execute()
            fixture.hostEnabled = false
            fixture.actorId = null
            fixture.allowCreate = false
            fixture.execute("copilot_task_status", statusArguments())
            val listed = fixture.execute("copilot_tasks", target())
            assertEquals(JsonPrimitive("GITHUB_CLOUD"), listed["execution_owner"])
            assertEquals(JsonPrimitive("GATEWAY"), listed["orchestration_owner"])
            assertEquals(JsonPrimitive("gateway-installation"), listed["gateway_id"])
            assertTrue(runCatching { fixture.execute() }.isFailure)
            assertEquals(1, fixture.posts)
        }
    }

    @Test fun consentOrRouteRevokedDuringCapabilityLookupPreventsPostAndCanResumeSafely() = runTest {
        for (revoke in listOf<(Fixture) -> Unit>(
            { it.allowCreate = false },
            { it.profile = it.profile.copy(token = "changed-gateway-token") }
        )) {
            Fixture().use { fixture ->
                val original = fixture.profile
                fixture.onCapabilities = { revoke(fixture) }
                assertTrue(runCatching { fixture.execute() }.isFailure)
                assertEquals(0, fixture.posts)
                fixture.onCapabilities = {}
                fixture.allowCreate = true
                fixture.profile = original
                fixture.execute()
                assertEquals(1, fixture.posts)
            }
        }
    }

    @Test fun listPageIsExplicitBoundedAndKeepsHostCompleteness() = runTest {
        Fixture().use { fixture ->
            val args = JsonObject(target() + mapOf("page" to JsonPrimitive(2), "per_page" to JsonPrimitive(7)))
            val result = fixture.execute("copilot_tasks", args)
            assertEquals("2", fixture.taskReads.single().url.parameters["page"])
            assertEquals("7", fixture.taskReads.single().url.parameters["per_page"])
            assertEquals(JsonPrimitive(2), result["page"])
            assertEquals(JsonPrimitive(7), result["per_page"])
            assertEquals(JsonPrimitive(false), result["has_more"])
            assertEquals(JsonPrimitive(true), result["complete"])
            for ((field, value) in listOf("page" to JsonPrimitive(0), "page" to JsonPrimitive(1001), "per_page" to JsonPrimitive(101), "per_page" to JsonPrimitive("7"))) {
                assertTrue(runCatching { fixture.execute("copilot_tasks", JsonObject(args + (field to value))) }.isFailure)
            }
            assertEquals(1, fixture.taskReads.size)
            assertEquals(0, fixture.posts)
        }
    }

    private class Fixture(var state: GitHubRepositoryStateStore = GitHubRepositoryStateStore()) : AutoCloseable {
        val settings = mockk<SettingRepository>()
        var profile = PlatformV2(uid = "gateway", name = "Selected gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.9:8090/v1", token = "gateway-secret", model = "unused")
        var features = AppFeatureSettings(githubProfileOverrides = mapOf("inference" to GitHubChatProfileOverride(engineeringGatewayProfileUid = "gateway", copilotExecutionOwner = GitHubCopilotExecutionOwner.GATEWAY)))
        var gatewayId = "gateway-installation"
        var principal = "principal"
        var actorId: Long? = 7
        var hostEnabled = true
        var allowCreate = true
        var onCapabilities: () -> Unit = {}
        var capabilityChange: (JsonObject) -> JsonObject = { it }
        val requests = mutableListOf<HttpRequestData>()
        val posts get() = requests.count { it.method == HttpMethod.Post }
        val taskReads get() = requests.filter { it.method == HttpMethod.Get && "/copilot/tasks" in it.url.encodedPath }
        var operation: suspend (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.OK to response(it).toString() }
        private val http = HttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.encodedPath.endsWith("/copilot/capabilities")) {
                    onCapabilities()
                    respond(
                        capabilityChange(
                            buildJsonObject {
                                put("schema", SCHEMA)
                                put("supported", true)
                                put("enabled", hostEnabled)
                                put("configured", hostEnabled)
                                put("gateway_id", gatewayId)
                                put("principal_id", principal)
                                put("execution_owner", "GITHUB_CLOUD")
                                put("orchestration_owner", "GATEWAY")
                                actorId?.let { put("actor_id", it) }
                            }
                        ).toString()
                    )
                } else {
                    val (status, body) = operation(request)
                    respond(body, status)
                }
            }
        ) {
            install(HttpTimeout)
            followRedirects = false
        }
        private val client = GatewayEngineeringClient(http)

        init {
            coEvery { settings.getFeatureSettings() } coAnswers { features }
            coEvery { settings.fetchPlatformV2s() } coAnswers { listOf(profile) }
        }

        suspend fun execute(action: String = "copilot_create_task", args: JsonObject = arguments()) =
            GatewayCopilotOperations(settings, client, state).execute("inference", "gateway", "chat:8", action, args) { allowCreate }

        fun response(request: HttpRequestData): JsonObject = if (request.method == HttpMethod.Get && request.url.encodedPath.endsWith("/copilot/tasks")) {
            buildJsonObject {
                put("schema", SCHEMA)
                put("tasks", JsonArray(listOf(receipt())))
                put("page", request.url.parameters["page"]?.toInt() ?: 1)
                put("per_page", request.url.parameters["per_page"]?.toInt() ?: 30)
                put("has_more", false)
                put("complete", true)
            }
        } else {
            receipt()
        }

        fun receipt() = buildJsonObject {
            put("schema", SCHEMA)
            put("gateway_job_id", JOB_ID)
            put("request_id", REQUEST_ID)
            put("repository", "owner/project")
            put("gateway_id", "gateway-installation")
            put("principal_id", "principal")
            put("github_actor_id", 7)
            put("execution_owner", "GITHUB_CLOUD")
            put("orchestration_owner", "GATEWAY")
            put("state", "QUEUED")
            put("base_ref", "main")
            put("expected_base_sha", "a".repeat(40))
            put("create_pull_request", false)
        }

        override fun close() = http.close()
    }

    private companion object {
        const val SCHEMA = "github.copilot-tasks.v1"
        const val NAMESPACE = "chat:8:gateway-copilot-tasks"
        const val REQUEST_ID = "original-request"
        val JOB_ID = "cp_" + "a".repeat(32)
        fun requestKey(id: String) = "execution:" + GitHubRepositoryStateStore.digest("request:$id")
        fun target() = buildJsonObject {
            put("owner", "owner")
            put("repo", "project")
        }
        fun statusArguments() = JsonObject(target() + ("request_id" to JsonPrimitive(REQUEST_ID)))
        fun arguments() = JsonObject(statusArguments() + mapOf("prompt" to JsonPrimitive("Implement the approved change."), "base_ref" to JsonPrimitive("main"), "expected_base_sha" to JsonPrimitive("a".repeat(40))))
    }
}
