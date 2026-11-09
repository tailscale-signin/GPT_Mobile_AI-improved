package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.context.RequestAuthorizationGuard
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
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

class GatewayRepositoryOperationsTest {
    @Test fun requestDigestMatchesHostCanonicalJsonIncludingUnicodePrincipals() {
        val payload = payload(arguments())
        // Generated independently with Python json.dumps(..., sort_keys=True,
        // separators=(',', ':'), ensure_ascii=True) and hashlib.sha256.
        assertEquals("7bd03c47e6ea452d870a71aa95144fedd66c3a6661ae488b7abc971e93d5b28b", repositoryOperationDigest("owner", payload))
        assertEquals("aa19ab4c328d4f8cf8a98f18c03e35def993999a03caaf0e2b69de57a41af32c", repositoryOperationDigest("équipe😀", payload))
        assertEquals(repositoryOperationDigest("owner", payload), repositoryOperationDigest("owner", JsonObject(payload.toList().reversed().toMap())))
    }

    @Test fun absentExplicitGatewayAndUnqualifiedCapabilityNeverDispatchToInference() = runTest {
        Fixture().use { fixture ->
            fixture.features = AppFeatureSettings()
            assertTrue(runCatching { fixture.execute() }.isFailure)
            assertTrue(fixture.requests.isEmpty())
            fixture.configure()
            fixture.qualified = false
            assertTrue(runCatching { fixture.execute() }.isFailure)
            assertEquals(listOf(HttpMethod.Get), fixture.requests.map { it.method })
            assertTrue(fixture.requests.single().url.encodedPath.endsWith("/capabilities"))
        }
    }

    @Test fun exactRequestAndRecoveryIdentityAreProtectedBeforePost() = runTest {
        Fixture().use { fixture ->
            fixture.operation = { request ->
                assertEquals(HttpMethod.Post, request.method)
                assertEquals(fixture.payload, Json.parseToJsonElement((request.body as TextContent).text))
                assertEquals("gateway-installation", request.headers["X-Gateway-Installation-ID"])
                assertEquals("owner", request.headers["X-Gateway-Principal-ID"])
                assertEquals("Bearer private-token", request.headers["Authorization"])
                val original = requireNotNull(fixture.state.read(NAMESPACE, requestKey(fixture.payload)))
                val receipt = requireNotNull(fixture.state.read(NAMESPACE, receiptKey(fixture.operationId)))
                assertEquals(fixture.payload, original["request"])
                assertEquals(fixture.payload, receipt["request"])
                assertEquals(JsonPrimitive("DISPATCH_ATTEMPTED"), original["dispatch_state"])
                assertEquals(JsonPrimitive(fixture.operationId), original["operation_id"])
                HttpStatusCode.Accepted to fixture.receipt().toString()
            }
            assertEquals(fixture.operationId, fixture.execute()["operation_id"]?.jsonPrimitive?.content)
            assertEquals(1, fixture.posts)
        }
    }

    @Test fun lostResponseThenProcessRestartUsesTheOriginalReadOnlyIdentity() = runTest {
        val directory = Files.createTempDirectory("gateway-repository-recovery").toFile()
        try {
            Fixture(GitHubRepositoryStateStore(directory)).use { fixture ->
                fixture.operation = { throw EOFException("response lost") }
                val failure = runCatching { fixture.execute() }.exceptionOrNull() as GitHubExecutionBlocked
                assertEquals(JsonPrimitive(fixture.operationId), failure.checkpoint["operation_id"])
                assertEquals(JsonPrimitive("repository_lifecycle_status"), failure.checkpoint["next_action"]?.jsonObject?.get("action"))
                fixture.state = GitHubRepositoryStateStore(directory)
                fixture.operation = { request ->
                    assertEquals(HttpMethod.Get, request.method)
                    assertTrue(request.url.encodedPath.endsWith("/" + fixture.operationId))
                    HttpStatusCode.OK to fixture.receipt().toString()
                }
                assertEquals(fixture.operationId, fixture.execute()["operation_id"]?.jsonPrimitive?.content)
                assertEquals(1, fixture.posts)
                assertEquals(1, fixture.statusReads)
                assertFalse(directory.walkTopDown().filter { it.isFile }.any { it.readBytes().decodeToString().contains("private-token") })
            }
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun lostPostThenStatus404NeverPermitsAnotherPost() = runTest {
        Fixture().use { fixture ->
            fixture.operation = { request ->
                if (request.method == HttpMethod.Post) throw EOFException("response lost")
                HttpStatusCode.NotFound to "{}"
            }
            repeat(3) { assertTrue(runCatching { fixture.execute() }.isFailure) }
            assertEquals(1, fixture.posts)
            assertEquals(2, fixture.statusReads)
        }
    }

    @Test fun serverFailureDoesNotResetTheOriginalDispatchClaim() = runTest {
        Fixture().use { fixture ->
            fixture.operation = { request ->
                if (request.method == HttpMethod.Post) HttpStatusCode.ServiceUnavailable to "{}" else HttpStatusCode.OK to fixture.receipt().toString()
            }
            assertTrue(runCatching { fixture.execute() }.isFailure)
            fixture.execute()
            assertEquals(1, fixture.posts)
            assertEquals(1, fixture.statusReads)
        }
    }

    @Test fun provenPreAdmissionRejectionAllowsOnlyAnExplicitLaterRetry() = runTest {
        Fixture().use { fixture ->
            fixture.operation = { HttpStatusCode.Forbidden to "{}" }
            assertTrue(runCatching { fixture.execute() }.exceptionOrNull() is GatewayEngineeringRequestRejected)
            assertEquals(1, fixture.posts)
            val rejected = requireNotNull(fixture.state.read(NAMESPACE, requestKey(fixture.payload)))
            assertEquals(JsonPrimitive("REJECTED"), rejected["dispatch_state"])
            fixture.operation = { HttpStatusCode.Accepted to fixture.receipt().toString() }
            fixture.execute()
            assertEquals(2, fixture.posts)
            assertEquals(0, fixture.statusReads)
        }
    }

    @Test fun cancellationDuringPostPreservesItsReadOnlyRecoveryClaim() = runTest {
        Fixture().use { fixture ->
            val dispatched = CompletableDeferred<Unit>()
            fixture.operation = {
                dispatched.complete(Unit)
                awaitCancellation()
            }
            val job = launch { fixture.execute() }
            dispatched.await()
            job.cancelAndJoin()
            fixture.operation = { HttpStatusCode.OK to fixture.receipt().toString() }
            fixture.execute(status(fixture.operationId))
            fixture.execute()
            assertEquals(1, fixture.posts)
            assertEquals(2, fixture.statusReads)
        }
    }

    @Test fun concurrentCallsShareOneAtomicPostClaim() = runTest {
        Fixture().use { fixture ->
            val dispatched = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            fixture.operation = { request ->
                if (request.method == HttpMethod.Post) {
                    dispatched.complete(Unit)
                    release.await()
                }
                HttpStatusCode.OK to fixture.receipt().toString()
            }
            val first = async { fixture.execute() }
            dispatched.await()
            val second = async { fixture.execute() }
            assertEquals(fixture.operationId, second.await()["operation_id"]?.jsonPrimitive?.content)
            release.complete(Unit)
            first.await()
            assertEquals(1, fixture.posts)
            assertEquals(1, fixture.statusReads)
        }
    }

    @Test fun authorizationRevocationBeforeClaimCanRecoverWithoutAnEarlierPost() = runTest {
        Fixture().use { fixture ->
            var checks = 0
            val guard = RequestAuthorizationGuard("original") { if (++checks == 2) error("authorization changed") }
            assertTrue(runCatching { withContext(guard) { fixture.execute() } }.isFailure)
            assertEquals(0, fixture.posts)
            fixture.execute()
            assertEquals(1, fixture.posts)
        }
    }

    @Test fun protectedCapacityFailurePreventsPost() = runTest {
        Fixture(GitHubRepositoryStateStore(protectedCapacityBytes = GitHubRepositoryStateStore.MAX_PROTECTED_RECORD_BYTES.toLong())).use { fixture ->
            assertTrue(runCatching { fixture.execute() }.isFailure)
            assertEquals(0, fixture.posts)
        }
    }

    @Test fun changedGatewayPrincipalOrEndpointCannotAdoptAnUncertainRequest() = runTest {
        for (change in listOf<(Fixture) -> Unit>(
            {
                it.gatewayId = "another-installation"
                it.profile = it.profile.copy(token = "rotate-capability-cache")
            },
            {
                it.principal = "another-account"
                it.profile = it.profile.copy(token = "rotate-capability-cache")
            },
            { it.profile = it.profile.copy(apiUrl = "http://192.168.1.99:8090/v1") }
        )) {
            Fixture().use { fixture ->
                fixture.operation = { throw EOFException("response lost") }
                assertTrue(runCatching { fixture.execute() }.isFailure)
                change(fixture)
                assertTrue(runCatching { fixture.execute() }.isFailure)
                assertTrue(runCatching { fixture.execute(status(fixture.operationId)) }.isFailure)
                assertEquals(1, fixture.posts)
                assertEquals(0, fixture.statusReads)
            }
        }
    }

    @Test fun routeChangeDuringPreparationRejectsBeforePostAndOriginalRouteCanResume() = runTest {
        Fixture().use { fixture ->
            val original = fixture.profile
            fixture.onCapabilities = { fixture.profile = original.copy(token = "changed-during-preparation") }
            assertTrue(runCatching { fixture.execute() }.isFailure)
            assertEquals(0, fixture.posts)
            fixture.onCapabilities = {}
            fixture.profile = original
            fixture.execute()
            assertEquals(1, fixture.posts)
        }
    }

    @Test fun receiptDigestBindsSourceRefActorDestinationTypeVisibilityAndCopyMode() = runTest {
        for ((field, changed) in mapOf(
            "source_ref" to JsonPrimitive("other-branch"),
            "expected_actor_id" to JsonPrimitive(999),
            "owner_type" to JsonPrimitive("user"),
            "destination_repo" to JsonPrimitive("other-copy"),
            "visibility" to JsonPrimitive("public"),
            "copy_mode" to JsonPrimitive("snapshot")
        )) {
            Fixture().use { fixture ->
                val unrelated = JsonObject(fixture.payload + (field to changed))
                fixture.operation = { HttpStatusCode.OK to fixture.receipt(unrelated).toString() }
                assertTrue("The changed $field must be rejected", runCatching { fixture.execute() }.isFailure)
                assertTrue(runCatching { fixture.execute(status(fixture.operationId)) }.isFailure)
                assertEquals(1, fixture.posts)
                assertEquals(1, fixture.statusReads)
            }
        }
    }

    @Test fun unrelatedOrMissingReceiptIdentityNeverBecomesAStoredRecoveryTarget() = runTest {
        for (receiptChange in listOf<(JsonObject) -> JsonObject>(
            { JsonObject(it - "request_digest") },
            { JsonObject(it + ("operation_id" to JsonPrimitive("repoop_" + "f".repeat(32)))) },
            { JsonObject(it + ("source_repository" to JsonPrimitive("elsewhere/repo"))) }
        )) {
            Fixture().use { fixture ->
                fixture.operation = { HttpStatusCode.OK to receiptChange(fixture.receipt()).toString() }
                assertTrue(runCatching { fixture.execute() }.isFailure)
                fixture.operation = { HttpStatusCode.OK to fixture.receipt().toString() }
                fixture.execute()
                assertEquals(1, fixture.posts)
                assertEquals(1, fixture.statusReads)
                assertEquals(null, fixture.state.read(NAMESPACE, receiptKey("repoop_" + "f".repeat(32))))
            }
        }
    }

    @Test fun statusNeedsAnOriginalProtectedRequestEvenWhenTheIdHasValidSyntax() = runTest {
        Fixture().use { fixture ->
            assertTrue(runCatching { fixture.execute(status(fixture.operationId)) }.isFailure)
            assertEquals(0, fixture.statusReads)
            assertEquals(0, fixture.posts)
        }
    }

    private class Fixture(var state: GitHubRepositoryStateStore = GitHubRepositoryStateStore()) : AutoCloseable {
        val settings = mockk<SettingRepository>()
        var profile = PlatformV2(uid = "gateway", name = "Explicit gateway", compatibleType = ClientType.LLAMA, apiUrl = "http://192.168.1.9:8090/v1", token = "private-token", model = "never-used")
        var features = AppFeatureSettings()
        var principal = "owner"
        var gatewayId = "gateway-installation"
        var qualified = true
        var onCapabilities: () -> Unit = {}
        val requests = mutableListOf<HttpRequestData>()
        val payload = payload(arguments())
        val operationId get() = "repoop_" + repositoryOperationDigest("owner", payload).take(32)
        var operation: suspend (HttpRequestData) -> Pair<HttpStatusCode, String> = { HttpStatusCode.Accepted to receipt().toString() }
        val posts get() = requests.count { it.method == HttpMethod.Post }
        val statusReads get() = requests.count { it.url.encodedPath.contains("/repository-operations/") }
        private val http = HttpClient(
            MockEngine { request ->
                requests += request
                if (request.url.encodedPath.endsWith("/capabilities")) {
                    onCapabilities()
                    respond("""{"service":"engineering-gateway","protocol_version":1,"enabled":true,"gateway_id":"$gatewayId","principal_id":"$principal","repository_operations":{"schema":"github.repository-lifecycle.v1","enabled":$qualified,"operations":["clone_repository","import_repository"],"copy_modes":["history"]}}""")
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
            configure()
            coEvery { settings.getFeatureSettings() } coAnswers { features }
            coEvery { settings.fetchPlatformV2s() } coAnswers { listOf(profile) }
        }

        fun configure() {
            features = AppFeatureSettings(githubProfileOverrides = mapOf("inference" to GitHubChatProfileOverride(engineeringGatewayProfileUid = "gateway")))
        }
        suspend fun execute(args: JsonObject = arguments()) = GatewayRepositoryOperations(settings, client, state).execute("inference", "gateway", "chat:8", args)
        fun receipt(request: JsonObject = payload) = buildJsonObject {
            val digest = repositoryOperationDigest(principal, request)
            put("schema", "github.repository-lifecycle.v1")
            put("request_digest", digest)
            put("operation_id", "repoop_" + digest.take(32))
            put("operation", requireNotNull(request["operation"]))
            put("status", "PENDING")
            put("source_repository", "${request["source_owner"]?.jsonPrimitive?.content}/${request["source_repo"]?.jsonPrimitive?.content}")
            put("destination_repository", "${request["destination_owner"]?.jsonPrimitive?.content}/${request["destination_repo"]?.jsonPrimitive?.content}")
            put("visibility", requireNotNull(request["visibility"]))
            put("copy_mode", requireNotNull(request["copy_mode"]))
        }
        override fun close() = http.close()
    }

    companion object {
        private const val NAMESPACE = "chat:8:gateway-repository-operations"
        private fun requestKey(payload: JsonObject) = "execution:" + GitHubRepositoryStateStore.digest("request:" + GitHubExecutionJournal.canonical(payload))
        private fun receiptKey(id: String) = "execution:" + GitHubRepositoryStateStore.digest("receipt:$id")
        private fun payload(args: JsonObject) = JsonObject(args.filterKeys { it != "action" } + ("operation" to requireNotNull(args["action"])))
        private fun status(id: String) = buildJsonObject {
            put("action", "repository_lifecycle_status")
            put("operation_id", id)
        }
        private fun arguments() = buildJsonObject {
            put("action", "import_repository")
            put("source_owner", "source")
            put("source_repo", "repo")
            put("source_ref", "main")
            put("expected_actor_id", 7)
            put("destination_owner", "destination")
            put("destination_repo", "copy")
            put("owner_type", "organization")
            put("visibility", "private")
            put("copy_mode", "history")
        }
    }
}
