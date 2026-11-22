package dev.chungjungsoo.gptmobile.data.audit

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.github.GitHubDispatchAccounting
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestScheduler
import dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceClient
import dev.chungjungsoo.gptmobile.data.network.PrivateExternalServiceGate
import dev.chungjungsoo.gptmobile.data.network.isLocalPrivacyPolicyRejection
import dev.chungjungsoo.gptmobile.data.privacy.PrivacyModeStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GitHubPrivacyAuditTransportTest {
    private val repository = GitHubRepositoryContext("audit", "fixture", "main")

    @Before fun initialize() {
        PrivacyModeStore.initialize(ApplicationProvider.getApplicationContext<Application>())
        privacy(false)
    }

    @After fun clearPrivacy() = privacy(false)

    private fun privacy(enabled: Boolean) {
        val preferences = ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("device_privacy", Context.MODE_PRIVATE)
        assertTrue(preferences.edit().putBoolean("privacy_mode_enabled", enabled).putLong("privacy_revision", preferences.getLong("privacy_revision", 0) + 1).commit())
    }

    @Test fun allAuditReadsUseTheRealPrivacyGateAndCanImmediatelyRetryAfterPolicyChanges() = runTest {
        var requests = 0
        val scheduler = GitHubRequestScheduler()
        val http = HttpClient(
            MockEngine {
                requests++
                respond("""{"id":7}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { install(PrivateExternalServiceGate) }
        try {
            val client = GitHubWorkspaceClient("fixture-token", http, persistentState = false, scheduler = scheduler)
            privacy(true)
            for (check in AppAuditGitHubCatalog.live) {
                val observation = appAuditGitHubRead(check, repository, "7", client) {}
                assertEquals(check.id, AppAuditOutcome.BLOCKED, observation.outcome)
                assertEquals("PRIVACY_SERVICE_UNVERIFIED", observation.reasonCode)
                assertEquals("local_validation", observation.failurePhase)
                assertEquals("POLICY_BLOCKED", observation.failureCategory)
                assertEquals(false, observation.retryable)
                assertEquals(null, observation.httpStatus)
                assertEquals(AppAuditDispatchEvidence.NOT_DISPATCHED, observation.dispatchEvidence)
            }
            assertEquals(0, requests)
            assertEquals(JsonPrimitive(0), scheduler.metrics()["physical_requests"])
            assertEquals(JsonPrimitive("CLOSED"), scheduler.metrics().getValue("circuit").jsonObject["state"])
            privacy(false)
            val recovered = appAuditGitHubRead(AppAuditGitHubCatalog.live.first(), repository, "7", client) {}
            assertEquals(AppAuditOutcome.PASS, recovered.outcome)
            assertEquals(1, requests)
            assertEquals(JsonPrimitive(1), scheduler.metrics()["physical_requests"])
        } finally {
            http.close()
        }
    }

    @Test fun laterPolicyDenialRetainsTheEarlierHttpResponse() = runTest {
        var requests = 0
        val scheduler = GitHubRequestScheduler()
        val http = HttpClient(
            MockEngine {
                requests++
                privacy(true)
                respond("", HttpStatusCode.NotModified)
            }
        ) { install(PrivateExternalServiceGate) }
        try {
            val client = GitHubWorkspaceClient("fixture-token", http, conditionalReads = false, persistentState = false, scheduler = scheduler)
            val observation = appAuditGitHubRead(AppAuditGitHubCatalog.live.first(), repository, "7", client) {}
            assertEquals(AppAuditOutcome.BLOCKED, observation.outcome)
            assertEquals("PRIVACY_SERVICE_UNVERIFIED", observation.reasonCode)
            assertEquals(AppAuditDispatchEvidence.CONFIRMED, observation.dispatchEvidence)
            assertEquals(AppAuditObservationSource.LIVE, observation.observationSource)
            assertEquals(304, observation.httpStatus)
            assertEquals(1, requests)
            assertEquals(JsonPrimitive(1), scheduler.metrics()["physical_requests"])
        } finally {
            http.close()
        }
    }

    @Test fun rejectedMutationReleasesOnlyItsUnsentClaimAndRunsOnceAfterPolicyChanges() = runTest {
        var requests = 0
        val scheduler = GitHubRequestScheduler()
        val http = HttpClient(
            MockEngine {
                requests++
                respond("""{"id":9,"number":1,"updated_at":"2026-01-01T00:00:00Z"}""", HttpStatusCode.Created, headersOf(HttpHeaders.ContentType, "application/json"))
            }
        ) { install(PrivateExternalServiceGate) }
        try {
            val client = GitHubWorkspaceClient("fixture-token", http, persistentState = false, scheduler = scheduler)
            val arguments = buildJsonObject {
                put("owner", repository.owner)
                put("repo", repository.repo)
                put("title", "Fixture")
            }
            val accounting = GitHubDispatchAccounting(record = {})
            privacy(true)
            val failure = runCatching { withContext(accounting) { client.execute("create_issue", arguments) } }.exceptionOrNull()
            assertTrue(failure?.isLocalPrivacyPolicyRejection() == true)
            val denied = accounting.finish(AgentToolResult("write", ToolResultContent.Text("Policy denied"), true, errorCode = "PRIVACY_SERVICE_UNVERIFIED", dispatched = false), true)
            assertFalse(denied.dispatched)
            assertEquals(JsonPrimitive(0), denied.executionReceipt?.get("mutation_requests"))
            assertEquals(JsonPrimitive(0), denied.executionReceipt?.get("unknown_mutations"))
            assertEquals(JsonPrimitive(1), denied.executionReceipt?.get("local_request_rejections"))
            assertEquals(0, requests)
            privacy(false)
            client.execute("create_issue", arguments)
            client.execute("create_issue", arguments)
            assertEquals(1, requests)
            assertEquals(JsonPrimitive(1), scheduler.metrics()["physical_requests"])
        } finally {
            http.close()
        }
    }
}
