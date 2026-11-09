package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubSessionCachesTest {
    @Test fun repositoryNegativesRemainCredentialScopedWhenTokensBindToTheSameAccount() {
        val restricted = "restricted-installation-token"
        val privileged = "full-installation-token"
        GitHubSessionCaches.bind(restricted, "repository-access-account")
        GitHubSessionCaches.bind(privileged, "repository-access-account")
        val denied = GitHubSessionCaches.refsForCredential(restricted)
        val allowed = GitHubSessionCaches.refsForCredential(privileged)
        denied.failed("/repos/owner/private", 404, "Not found")
        assertTrue(runCatching { denied.requireAvailable("/repos/owner/private/commits/main") }.isFailure)
        allowed.requireAvailable("/repos/owner/private/commits/main")
        GitHubSessionCaches.refsForCredential(restricted, "git.example.org").requireAvailable("/repos/owner/private/commits/main")
        GitHubSessionCaches.credentialReconnected(privileged)
        assertTrue("Refreshing a different credential must not erase the original observation", runCatching { denied.requireAvailable("/repos/owner/private") }.isFailure)
        GitHubSessionCaches.credentialReconnected(restricted)
        denied.requireAvailable("/repos/owner/private")
        assertSame(GitHubSessionCaches.stateForCredential(restricted), GitHubSessionCaches.stateForCredential(privileged))
    }

    @Test fun authenticationRejectionCannotPoisonAnotherCredentialForTheSameAccount() {
        val expired = "expired-account-token"
        val current = "current-account-token"
        GitHubSessionCaches.bind(expired, "shared-auth-account")
        GitHubSessionCaches.bind(current, "shared-auth-account")
        val rejected = GitHubSessionCaches.authenticationForCredential(expired)
        val accepted = GitHubSessionCaches.authenticationForCredential(current)
        rejected.set(true)
        assertFalse(accepted.get())
        GitHubSessionCaches.credentialReconnected(current)
        assertTrue("Reconnecting another token does not validate this token", rejected.get())
        GitHubSessionCaches.credentialReconnected(expired)
        assertFalse(rejected.get())
        assertSame(GitHubSessionCaches.rateLimitsForCredential(expired), GitHubSessionCaches.rateLimitsForCredential(current))
        assertSame(GitHubSessionCaches.stateForCredential(expired), GitHubSessionCaches.stateForCredential(current))
    }

    @Test fun sharedCredentialTextCannotShareAuthorizationOrReceiptsAcrossHosts() {
        val token = "host-isolation-fixture"
        val cloud = GitHubSessionCaches.authenticationForCredential(token)
        val enterprise = GitHubSessionCaches.authenticationForCredential(token, "git.example.org")
        GitHubSessionCaches.bind(token, "same-account-id", "github.com")
        GitHubSessionCaches.bind(token, "same-account-id", "git.example.org")
        cloud.set(true)
        assertFalse(enterprise.get())
        assertTrue(GitHubSessionCaches.authenticationForCredential(token).get())
        assertFalse(GitHubSessionCaches.stateForCredential(token) === GitHubSessionCaches.stateForCredential(token, "git.example.org"))
        GitHubSessionCaches.credentialReconnected(token, "git.example.org")
        assertTrue(cloud.get())
    }

    @Test fun reconnectResetsOnlyThatCredentialsAuthenticationAndRetainsReceiptStore() {
        val token = "reconnect-test-credential"
        val other = "reconnect-test-other"
        val rejected = GitHubSessionCaches.authenticationForCredential(token)
        val sibling = GitHubSessionCaches.authenticationForCredential(other)
        val receipts = GitHubSessionCaches.stateForCredential(token)
        val limits = GitHubSessionCaches.rateLimitsForCredential(token)
        rejected.set(true)
        sibling.set(true)
        GitHubSessionCaches.credentialReconnected(token)
        assertFalse(rejected.get())
        assertTrue(sibling.get())
        assertSame(receipts, GitHubSessionCaches.stateForCredential(token))
        assertSame(limits, GitHubSessionCaches.rateLimitsForCredential(token))
    }

    @Test fun ciLogAuthenticationFailureStopsAnotherLogRequestBeforeDispatch() = runTest {
        val rejected = java.util.concurrent.atomic.AtomicBoolean()
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond("Bad credentials", HttpStatusCode.Unauthorized)
            }
        )
        try {
            val reader = GitHubCiLogReader(http, "expired", GitHubRequestScheduler(), GitHubResponseCache(), authenticationRejected = rejected)
            assertTrue(runCatching { reader.read("/repos/a/b/actions/jobs/1/logs", false) }.isFailure)
            assertTrue(rejected.get())
            assertTrue(runCatching { reader.read("/repos/a/b/actions/jobs/2/logs", false) }.exceptionOrNull()!!.message.orEmpty().contains("Reconnect"))
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test(timeout = 15_000)
    fun anOldInFlightFailureCannotPoisonTheRotatedCredentialsAdmissionGate() = runBlocking {
        for (native in listOf(false, true)) {
            for (status in listOf(HttpStatusCode.Unauthorized, HttpStatusCode.NotFound)) {
                val originalToken = "rotation-old-$native-${status.value}"
                val nextToken = "rotation-current-$native-${status.value}"
                val credential = java.util.concurrent.atomic.AtomicReference(originalToken)
                val started = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var originalRequests = 0
                val http = HttpClient(
                    MockEngine { request ->
                        if (request.headers[io.ktor.http.HttpHeaders.Authorization] == "Bearer $originalToken") {
                            originalRequests++
                            started.complete(Unit)
                            release.await()
                            respond("""{"message":"Unavailable for original token"}""", status)
                        } else {
                            assertEquals("Bearer $nextToken", request.headers[io.ktor.http.HttpHeaders.Authorization])
                            if (request.url.encodedPath.endsWith("/languages")) {
                                respond("{}")
                            } else {
                                respond("""{"id":1,"full_name":"owner/repository","default_branch":"main"}""")
                            }
                        }
                    }
                )
                try {
                    val args = buildJsonObject {
                        put("owner", "owner")
                        put("repo", "repository")
                    }
                    val client = GitHubWorkspaceClient(originalToken, http, credentialProvider = { credential.get() }, sharedCredentialAdmission = true)
                    val tool = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubTool(originalToken, http, credentialProvider = { credential.get() }, persistentState = false)
                    suspend fun rejected(): Boolean = if (native) {
                        tool.execute("rotation", kotlinx.serialization.json.JsonObject(args + ("action" to kotlinx.serialization.json.JsonPrimitive("repository_details")))).isError
                    } else {
                        runCatching { client.execute("get_repository", args) }.isFailure
                    }
                    val old = async { rejected() }
                    withTimeout(5000) { started.await() }
                    credential.set(nextToken)
                    assertFalse("The rotated credential can read while the original request is in flight", rejected())
                    release.complete(Unit)
                    assertTrue(old.await())
                    assertFalse("A delayed $status must update only its original credential's gate", rejected())
                    assertEquals(1, originalRequests)
                } finally {
                    release.complete(Unit)
                    http.close()
                }
            }
        }
    }

    @Test(timeout = 15_000)
    fun queuedDependentReadRechecksRepositoryAvailabilityBeforeDispatch() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                started.complete(Unit)
                release.await()
                respond("""{"message":"Not found"}""", HttpStatusCode.NotFound)
            }
        )
        try {
            val scheduler = GitHubRequestScheduler(concurrency = 1)
            val client = GitHubWorkspaceClient("queued-repository", http, scheduler = scheduler)
            val args = buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
                put("ref", "main")
            }
            val first = async { runCatching { client.execute("get_repository", args) } }
            started.await()
            val dependent = async { runCatching { client.execute("get_commit_detail", args) } }
            withTimeout(10_000) {
                while (scheduler.metrics().getValue("queued_requests").jsonPrimitive.intOrNull != 1) yield()
            }
            release.complete(Unit)
            assertTrue(first.await().isFailure)
            assertEquals("GITHUB_REPOSITORY_UNAVAILABLE", (dependent.await().exceptionOrNull() as GitHubReadValidation).code)
            assertEquals(1, requests)
        } finally {
            release.complete(Unit)
            http.close()
        }
    }

    @Test(timeout = 15_000)
    fun queuedRequestRechecksAuthenticationAfterAnEarlierRequestRejectsCredentials() = runBlocking {
        val rejected = java.util.concurrent.atomic.AtomicBoolean()
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                started.complete(Unit)
                release.await()
                respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
            }
        )
        try {
            val scheduler = GitHubRequestScheduler(concurrency = 1)
            val client = GitHubWorkspaceClient("queued-rejected", http, scheduler = scheduler, authenticationRejected = rejected)
            val args = buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
            }
            val first = async { runCatching { client.execute("get_repository", args) } }
            started.await()
            val second = async { runCatching { client.execute("get_account", buildJsonObject {}) } }
            // HTTP responses run on real IO; do not advance a virtual queue deadline
            // while the first response is still travelling back to the caller.
            withTimeout(10_000) {
                while (scheduler.metrics().getValue("queued_requests").jsonPrimitive.intOrNull != 1) yield()
            }
            assertEquals(1, scheduler.metrics().getValue("queued_requests").jsonPrimitive.intOrNull)
            release.complete(Unit)
            assertTrue(first.await().isFailure)
            assertTrue(second.await().exceptionOrNull()!!.message.orEmpty().contains("Reconnect"))
            assertEquals(1, requests)
        } finally {
            release.complete(Unit)
            http.close()
        }
    }
}
