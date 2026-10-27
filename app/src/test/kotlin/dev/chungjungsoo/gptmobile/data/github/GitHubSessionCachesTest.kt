package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
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
}
