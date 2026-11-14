package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.Headers
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCooldownHttpTest {
    @Test fun aCompletedReadCanEstablishACooldownThatBlocksTheNextReadBeforeHttp() = runTest {
        var requests = 0
        val reset = System.currentTimeMillis() / 1000 + 600
        val http = HttpClient(
            MockEngine {
                requests++
                respond(
                    """{"login":"fixture"}""",
                    headers = headersOf(
                        "X-RateLimit-Resource" to listOf("core"),
                        "X-RateLimit-Remaining" to listOf("0"),
                        "X-RateLimit-Reset" to listOf(reset.toString())
                    )
                )
            }
        )
        try {
            val client = GitHubWorkspaceClient("fixture-token", http, persistentState = false)
            client.execute("get_account", buildJsonObject {})
            assertEquals(1, requests)
            val failure = runCatching { client.execute("get_account", buildJsonObject {}) }.exceptionOrNull()
            assertTrue(failure is GitHubCooldownActive)
            val cooldown = failure as GitHubCooldownActive
            assertEquals(GitHubCooldownActive.Basis.RESOURCE_RESET, cooldown.basis)
            assertEquals(reset * 1000, cooldown.retryNotBeforeMillis)
            assertTrue(cooldown.delayMillis > 0)
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test fun primaryHttpRejectionRetainsTheExactResponseQuotaAndObservationTime() = runTest {
        val reset = System.currentTimeMillis() / 1000 + 600
        val limits = GitHubRateLimitManager()
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond(
                    """{"message":"private quota detail"}""",
                    HttpStatusCode.Forbidden,
                    headersOf(
                        "X-RateLimit-Resource" to listOf("core"),
                        "X-RateLimit-Remaining" to listOf("0"),
                        "X-RateLimit-Reset" to listOf(reset.toString()),
                        "X-OAuth-Scopes" to listOf("private scope")
                    )
                )
            }
        )
        try {
            val client = GitHubWorkspaceClient("fixture-token", http, rateLimits = limits, persistentState = false)
            val failure = requireNotNull(runCatching { client.execute("get_account", buildJsonObject {}) }.exceptionOrNull())
            assertTrue(failure is GitHubRequestRejected)
            val rejection = failure as GitHubRequestRejected
            val evidence = requireNotNull(rejection.evidence)
            val observed = limits.snapshot().observedAtMillis
            assertEquals(403, rejection.status)
            assertEquals(JsonPrimitive("GITHUB_PRIMARY_RATE_LIMITED"), evidence["code"])
            assertEquals(JsonPrimitive(403), evidence["http_status"])
            assertEquals(JsonPrimitive("CORE"), evidence["github_cooldown_resource"])
            assertEquals(JsonPrimitive("RESOURCE_RESET"), evidence["github_cooldown_basis"])
            assertEquals(JsonPrimitive(observed), evidence["github_cooldown_observed_at_ms"])
            assertEquals(JsonPrimitive(reset * 1000), evidence["github_cooldown_deadline_ms"])
            assertEquals(JsonPrimitive(reset * 1000 - observed), evidence["github_cooldown_delay_ms"])
            assertEquals(JsonPrimitive(0L), evidence["github_cooldown_remaining_requests"])
            assertEquals(1, requests)
            assertFalse(evidence.toString().contains("private"))
            assertNoAggregateFacts(evidence)
            val retained = evidence.toString()
            limits.record(headersOf("X-RateLimit-Resource" to listOf("search"), "X-RateLimit-Remaining" to listOf("100")), nowMillis = observed + 1)
            assertEquals(retained, rejection.evidence.toString())
        } finally {
            http.close()
        }
    }

    @Test fun secondaryHttpRejectionRetainsRetryAfterWithoutAddingAReadRetry() = runTest {
        val limits = GitHubRateLimitManager()
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond(
                    """{"message":"private secondary limit"}""",
                    HttpStatusCode.TooManyRequests,
                    headersOf("Retry-After" to listOf("120"), "X-RateLimit-Resource" to listOf("search"), "X-RateLimit-Remaining" to listOf("40"))
                )
            }
        )
        try {
            val failure = requireNotNull(runCatching { GitHubWorkspaceClient("fixture-token", http, rateLimits = limits, persistentState = false).execute("get_account", buildJsonObject {}) }.exceptionOrNull())
            assertTrue(failure is GitHubRequestRejected)
            val evidence = requireNotNull((failure as GitHubRequestRejected).evidence)
            val observed = limits.snapshot().observedAtMillis
            assertEquals(429, failure.status)
            assertEquals(JsonPrimitive("GITHUB_SECONDARY_RATE_LIMITED"), evidence["code"])
            assertEquals(JsonPrimitive(429), evidence["http_status"])
            assertEquals(JsonPrimitive("SEARCH"), evidence["github_cooldown_resource"])
            assertEquals(JsonPrimitive("RESOURCE_RETRY_AFTER"), evidence["github_cooldown_basis"])
            assertEquals(JsonPrimitive(120_000L), evidence["github_cooldown_delay_ms"])
            assertEquals(JsonPrimitive(observed + 120_000L), evidence["github_cooldown_deadline_ms"])
            assertEquals(JsonPrimitive(observed), evidence["github_cooldown_observed_at_ms"])
            assertEquals(JsonPrimitive(120_000L), evidence["retry_after_ms"])
            assertEquals(1, requests)
            assertNoAggregateFacts(evidence)
        } finally {
            http.close()
        }
    }

    @Test fun rateLimitedHttpWithoutDeadlineDoesNotReportTheManagersDefaultCooldown() = runTest {
        val limits = GitHubRateLimitManager()
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond("{}", HttpStatusCode.TooManyRequests)
            }
        )
        try {
            val failure = requireNotNull(runCatching { GitHubWorkspaceClient("fixture-token", http, rateLimits = limits, persistentState = false).execute("get_account", buildJsonObject {}) }.exceptionOrNull())
            assertTrue(failure is GitHubRequestRejected)
            val evidence = requireNotNull((failure as GitHubRequestRejected).evidence)
            assertEquals(JsonPrimitive("GITHUB_SECONDARY_RATE_LIMITED"), evidence["code"])
            assertEquals(JsonPrimitive(429), evidence["http_status"])
            assertEquals(JsonPrimitive(limits.snapshot().observedAtMillis), evidence["github_cooldown_observed_at_ms"])
            assertTrue("The existing manager still applies its local 429 backoff", limits.retryDelayMillis() > 0)
            for (key in listOf("github_cooldown_resource", "github_cooldown_remaining_requests", "github_cooldown_basis", "github_cooldown_delay_ms", "github_cooldown_deadline_ms", "retry_after_ms")) {
                assertFalse("$key was not observed in this response", key in evidence)
            }
            assertEquals(1, requests)
            assertNoAggregateFacts(evidence)
        } finally {
            http.close()
        }
    }

    @Test fun primaryHttpWithoutAResetDeadlineDoesNotInventAWait() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond("{}", HttpStatusCode.Forbidden, headersOf("X-RateLimit-Remaining", "0"))
            }
        )
        try {
            val failure = requireNotNull(runCatching { GitHubWorkspaceClient("fixture-token", http, persistentState = false).execute("get_account", buildJsonObject {}) }.exceptionOrNull())
            assertTrue(failure is GitHubRequestRejected)
            val evidence = requireNotNull((failure as GitHubRequestRejected).evidence)
            assertEquals(JsonPrimitive("GITHUB_PRIMARY_RATE_LIMITED"), evidence["code"])
            assertEquals(JsonPrimitive(0L), evidence["github_cooldown_remaining_requests"])
            assertTrue(evidence.getValue("github_cooldown_observed_at_ms").jsonPrimitive.long > 0)
            for (key in listOf("github_cooldown_basis", "github_cooldown_delay_ms", "github_cooldown_deadline_ms", "retry_after_ms")) assertFalse(key in evidence)
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test fun aRecoveryResponseReplacesAnOrphaned304sRateEvidence() = runTest {
        var requests = 0
        val limits = GitHubRateLimitManager()
        val reset = System.currentTimeMillis() / 1000 + 600
        val http = HttpClient(
            MockEngine {
                requests++
                if (requests == 1) {
                    respond("", HttpStatusCode.NotModified, headersOf("X-RateLimit-Resource" to listOf("graphql"), "X-RateLimit-Remaining" to listOf("5000")))
                } else {
                    respond("{}", HttpStatusCode.Forbidden, headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(reset.toString())))
                }
            }
        )
        try {
            val failure = requireNotNull(runCatching { GitHubWorkspaceClient("fixture-token", http, rateLimits = limits, persistentState = false).execute("get_account", buildJsonObject {}) }.exceptionOrNull())
            assertTrue(failure is GitHubRequestRejected)
            val evidence = requireNotNull((failure as GitHubRequestRejected).evidence)
            val observed = limits.snapshot().observedAtMillis
            assertEquals(JsonPrimitive(403), evidence["http_status"])
            assertEquals(JsonPrimitive("CORE"), evidence["github_cooldown_resource"])
            assertEquals(JsonPrimitive(0L), evidence["github_cooldown_remaining_requests"])
            assertEquals(JsonPrimitive(observed), evidence["github_cooldown_observed_at_ms"])
            assertEquals(JsonPrimitive(reset * 1000 - observed), evidence["github_cooldown_delay_ms"])
            assertEquals(2, requests)
            assertNoAggregateFacts(evidence)
        } finally {
            http.close()
        }
    }

    @Test fun ordinaryNotFoundEvidenceKeepsItsExistingShape() = runTest {
        val http = HttpClient(MockEngine { respond("{}", HttpStatusCode.NotFound, Headers.Empty) })
        try {
            val failure = requireNotNull(runCatching { GitHubWorkspaceClient("fixture-token", http, persistentState = false).execute("get_account", buildJsonObject {}) }.exceptionOrNull())
            assertTrue(failure is GitHubRequestRejected)
            val evidence = requireNotNull((failure as GitHubRequestRejected).evidence)
            assertEquals(JsonPrimitive("github.failure-evidence.v1"), evidence["schema"])
            assertEquals(JsonPrimitive("GITHUB_NOT_FOUND_OR_INACCESSIBLE"), evidence["error_code"])
            assertEquals(JsonPrimitive(404), evidence["http_status"])
            assertEquals(JsonPrimitive("/user"), evidence["endpoint"])
            assertFalse(evidence.keys.any { it.startsWith("github_cooldown_") })
            assertFalse("code" in evidence)
        } finally {
            http.close()
        }
    }

    @Test fun ordinaryForbiddenResponseDoesNotAcquireRateEvidenceFromMessageText() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                respond("""{"message":"GITHUB_PRIMARY_RATE_LIMITED"}""", HttpStatusCode.Forbidden)
            }
        )
        try {
            val failure = requireNotNull(runCatching { GitHubWorkspaceClient("fixture-token", http, persistentState = false).execute("get_account", buildJsonObject {}) }.exceptionOrNull())
            assertTrue(failure is GitHubRequestRejected)
            assertTrue(failure.message.orEmpty().startsWith("GITHUB_FORBIDDEN_UNKNOWN"))
            assertNull((failure as GitHubRequestRejected).evidence)
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test fun restMutationCooldownKeepsItsTypedCauseAndRejectedJournalWithoutPosting() = runTest {
        val store = GitHubRepositoryStateStore()
        val limits = GitHubRateLimitManager()
        val reset = System.currentTimeMillis() / 1000 + 600
        limits.record(headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(reset.toString())))
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                error("No HTTP request is admitted during this cooldown")
            }
        )
        try {
            val client = mutationClient(http, store, limits)
            val failure = requireNotNull(
                runCatching {
                    client.execute(
                        "create_issue",
                        buildJsonObject {
                            put("owner", "o")
                            put("repo", "r")
                            put("title", "fixture")
                        }
                    )
                }.exceptionOrNull()
            )
            assertTrue(failure is GitHubRequestRejected)
            val rejection = failure as GitHubRequestRejected
            assertEquals(0, rejection.status)
            assertNull(rejection.evidence)
            assertTrue(rejection.cause is GitHubCooldownActive)
            val cooldown = rejection.cause as GitHubCooldownActive
            assertEquals(GitHubCooldownActive.Resource.CORE, cooldown.resource)
            assertEquals(reset * 1000, cooldown.retryNotBeforeMillis)
            assertEquals(0, requests)
            assertRejectedJournal(store, "http.POST")
        } finally {
            http.close()
        }
    }

    @Test fun graphQlMutationCooldownKeepsItsTypedCauseAndRejectedJournalWithoutPosting() = runTest {
        val store = GitHubRepositoryStateStore()
        val limits = GitHubRateLimitManager()
        val reset = System.currentTimeMillis() / 1000 + 600
        limits.record(headersOf("X-RateLimit-Resource" to listOf("graphql"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf(reset.toString())))
        var requests = 0
        var posts = 0
        val sha = "a".repeat(40)
        val http = HttpClient(
            MockEngine { request ->
                requests++
                if (request.method == HttpMethod.Post) posts++
                assertEquals("/repos/o/r/pulls/7", request.url.encodedPath)
                respond("""{"number":7,"node_id":"PR_fixture","state":"open","draft":true,"head":{"sha":"$sha"}}""")
            }
        )
        try {
            val failure = requireNotNull(
                runCatching {
                    mutationClient(http, store, limits).execute(
                        "mark_ready_for_review",
                        buildJsonObject {
                            put("owner", "o")
                            put("repo", "r")
                            put("pull_number", 7)
                            put("expected_head_sha", sha)
                        }
                    )
                }.exceptionOrNull()
            )
            assertTrue(failure is GitHubRequestRejected)
            val rejection = failure as GitHubRequestRejected
            assertEquals(0, rejection.status)
            assertNull(rejection.evidence)
            assertTrue(rejection.cause is GitHubCooldownActive)
            val cooldown = rejection.cause as GitHubCooldownActive
            assertEquals(GitHubCooldownActive.Resource.GRAPHQL, cooldown.resource)
            assertEquals(reset * 1000, cooldown.retryNotBeforeMillis)
            assertEquals(1, requests)
            assertEquals(0, posts)
            assertRejectedJournal(store, "graphql.mutation")
        } finally {
            http.close()
        }
    }

    private fun mutationClient(http: HttpClient, store: GitHubRepositoryStateStore, limits: GitHubRateLimitManager) = GitHubWorkspaceClient(
        "fixture-token",
        http,
        rateLimits = limits,
        stateStore = store,
        durableIdentity = "fixture-account",
        connectionUid = "fixture-connection",
        conversationId = "fixture-conversation",
        taskId = "fixture-task"
    )

    private suspend fun assertRejectedJournal(store: GitHubRepositoryStateStore, action: String) {
        val context = GitHubExecutionJournal(store, "fixture-account:fixture-connection:fixture-conversation:fixture-task").context()
        val rows = (context["executions"] as JsonArray).filterIsInstance<JsonObject>()
        assertEquals(1, rows.size)
        assertEquals(JsonPrimitive(action), rows.single()["action"])
        assertEquals(JsonPrimitive("REJECTED"), rows.single()["status"])
    }

    private fun assertNoAggregateFacts(evidence: JsonObject) {
        for (key in listOf("dispatched", "dispatch_state", "attempt_dispatch_state", "remote_acceptance", "request_replay_allowed", "partial_output_preserved")) {
            assertFalse(key in evidence)
        }
    }
}
