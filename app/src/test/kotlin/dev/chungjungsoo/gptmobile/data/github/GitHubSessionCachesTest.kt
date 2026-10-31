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
            val second = async { runCatching { client.execute("get_repository", args) } }
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
