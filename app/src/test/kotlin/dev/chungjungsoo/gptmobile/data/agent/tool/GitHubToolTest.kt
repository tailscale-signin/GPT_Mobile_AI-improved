package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope
import dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool
import dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolTest {
    @Test fun copilotCannotStartCloudWorkWithoutBothUserToggles() = runTest {
        var networkCalls = 0
        HttpClient(
            MockEngine {
                networkCalls++
                error("Copilot must not contact GitHub while disabled")
            }
        ).use { http ->
            val context = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext(
                "conn",
                "owner",
                "repo",
                "work",
                "a".repeat(40),
                "profile"
            )
            val tool = GitHubTool(
                "token",
                http,
                repositoryScope = context,
                conversationId = "42",
                featureSettings = dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
            ).operations().single { it.definition.name == "github_pr" }
            val denied = tool.execute(
                "cloud-repair-unauthorized",
                buildJsonObject {
                    put("action", "agent_merge_enable")
                    put("pull_number", 1534)
                    put("expected_head_sha", "a".repeat(40))
                }
            )
            assertTrue(denied.isError)
            assertEquals("GITHUB_COPILOT_DISABLED", denied.errorCode)
            assertFalse(denied.dispatched)
            assertEquals(0, networkCalls)
        }
    }

    @Test fun typedRateResponseEvidenceSurvivesTheNativeOperationAndMeasuredReceipt() = runTest {
        var requests = 0
        HttpClient(
            MockEngine {
                requests++
                respond(
                    """{"message":"private rate response"}""",
                    HttpStatusCode.TooManyRequests,
                    headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("30"), "X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("7"))
                )
            }
        ).use { http ->
            val tool = GitHubTool("private-token", http, repositoryContext = GitHubRepositoryContext("private-owner", "private-repo", "working")).operations().single { it.definition.name == "github_context" }
            val result = tool.execute("private-call", buildJsonObject { put("action", "repository") })
            assertEquals("GITHUB_SECONDARY_RATE_LIMITED", result.errorCode)
            assertTrue(result.dispatched)
            assertEquals(1, requests)
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals(JsonPrimitive("READ_FAILED"), receipt["phase"])
            assertEquals(JsonPrimitive(429), receipt["http_status"])
            assertEquals(JsonPrimitive(1), receipt["read_requests"])
            assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
            assertEquals(JsonPrimitive("CORE"), receipt["github_cooldown_resource"])
            assertEquals(JsonPrimitive("RESOURCE_RETRY_AFTER"), receipt["github_cooldown_basis"])
            assertEquals(JsonPrimitive(30_000), receipt["github_cooldown_delay_ms"])
            assertEquals(JsonPrimitive(30_000), receipt["retry_after_ms"])
            assertEquals(JsonPrimitive(7), receipt["github_cooldown_remaining_requests"])
            assertTrue(receipt.containsKey("github_cooldown_observed_at_ms"))
            assertTrue(receipt.containsKey("github_cooldown_deadline_ms"))
            assertEquals(null, receipt["recovery_action"])
            val event = dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent("private-event", "private-run", 0, result.callId, null, null, "github_context", tool.definition.name, """{"action":"repository"}""", null, null, "FAILED")
            val fields = receipt + dev.chungjungsoo.gptmobile.data.diagnostics.ToolReportDiagnostics.fields(event, result) + mapOf("run_id" to event.runId, "call_id" to event.callId, "tool" to event.modelToolName)
            val capsule = dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportIncidentCapsule.fields(dev.chungjungsoo.gptmobile.data.diagnostics.ReportFeature.TOOLS, "TOOL_RESULT", requireNotNull(result.errorCode), fields)
            val context = dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.fromFields("TOOL_RESULT", capsule, 1_800_000_000_000)
            assertEquals(JsonPrimitive(30_000), context.diagnostics?.values?.get("github_cooldown_delay_ms"))
            assertEquals(JsonPrimitive("GITHUB_SECONDARY_RATE_LIMITED"), context.diagnostics?.values?.get("tool_error_code"))
            assertEquals(429, context.httpStatus)
            val approved = dev.chungjungsoo.gptmobile.data.diagnostics.IssueReport("44444444-4444-4444-8444-444444444444", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture", feature = "tools", errorCode = result.errorCode, context = context).approvedPayload()
            assertFalse(approved.contains("private"))
        }
    }

    @Test fun typedCooldownAndItsUnsentWrapperKeepLocalWaitEvidenceWithoutNetworkRetry() = runTest {
        val cooldown = dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive(
            dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive.Resource.CORE,
            dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive.Basis.RESOURCE_RESET,
            30_000,
            1_800_000_030_000,
            1_800_000_000_000,
            0
        )
        val wrapped = dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected(0, cooldown.message.orEmpty()).also { it.initCause(cooldown) }
        val failures = listOf(
            cooldown to true,
            wrapped to true,
            IllegalStateException(cooldown.message) to false,
            dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected(0, cooldown.message.orEmpty()) to false,
            dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected(403, cooldown.message.orEmpty()).also { it.initCause(cooldown) } to false
        )
        for ((failure, typed) in failures) {
            var requests = 0
            var credentials = 0
            HttpClient(
                MockEngine {
                    requests++
                    error("No HTTP request may execute")
                }
            ).use { http ->
                val tool = GitHubTool(
                    "token",
                    http,
                    repositoryContext = GitHubRepositoryContext("owner", "repo", "working"),
                    credentialProvider = { if (++credentials == 2) throw failure else "token" }
                ).operations().single { it.definition.name == "github_context" }
                val result = tool.execute("call", buildJsonObject { put("action", "repository") })
                assertTrue(result.isError)
                assertFalse(result.dispatched)
                assertEquals(0, requests)
                assertEquals(2, credentials)
                val receipt = requireNotNull(result.executionReceipt)
                assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), receipt["phase"])
                assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
                assertFalse(dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.retryable(result))
                assertEquals(null, receipt["github_transport_wait_ms"])
                assertEquals(null, receipt["recovery_action"])
                if (typed) {
                    assertEquals("GITHUB_COOLDOWN_ACTIVE", result.errorCode)
                    cooldown.failureFields().forEach { (key, value) -> assertEquals(key, value, receipt[key]) }
                    assertEquals(JsonPrimitive(30_000), receipt["retry_after_ms"])
                    assertEquals(JsonPrimitive(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.exceptionFingerprint(failure)), receipt["exception_fingerprint"])
                } else {
                    assertFalse(result.errorCode == "GITHUB_COOLDOWN_ACTIVE")
                    assertEquals(null, receipt["github_cooldown_resource"])
                }
            }
        }
    }

    @Test fun nativeWriteRetainsTypedCooldownCauseFromItsActualAdmissionWrapper() = runTest {
        var requests = 0
        HttpClient(
            MockEngine { request ->
                requests++
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/repos/owner/repo/issues/1", request.url.encodedPath)
                respond("""{"number":1,"title":"retained"}""", headers = headersOf(HttpHeaders.ContentType to listOf("application/json"), "Retry-After" to listOf("30")))
            }
        ).use { http ->
            val tool = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("owner", "repo", "working"))
            assertFalse(
                tool.execute(
                    "read",
                    buildJsonObject {
                        put("action", "get_issue")
                        put("issue_number", 1)
                    }
                ).isError
            )
            val result = tool.execute(
                "write",
                buildJsonObject {
                    put("action", "create_issue_comment")
                    put("pull_number", 1)
                    put("body", "private comment")
                }
            )
            assertEquals("GITHUB_COOLDOWN_ACTIVE", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals(1, requests)
            val receipt = requireNotNull(result.executionReceipt)
            assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), receipt["phase"])
            assertEquals(JsonPrimitive(0), receipt["read_requests"])
            assertEquals(JsonPrimitive(0), receipt["mutation_requests"])
            assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
            assertTrue((receipt["cause_types"] as JsonArray).contains(JsonPrimitive(dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive::class.java.name)))
            assertTrue((receipt["cause_types"] as JsonArray).contains(JsonPrimitive(dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected::class.java.name)))
            assertFalse(dev.chungjungsoo.gptmobile.data.agent.ToolFailureMetadata.retryable(result))
        }
    }

    @Test fun contextPrecheckFailuresRetainActualCaughtEvidenceAcrossAllReportedReadActions() = runTest {
        for (action in listOf("repository", "commit", "changes", "branch", "branch_dependencies")) {
            var requests = 0
            var credentials = 0
            val observed = IllegalStateException("private credential provider detail", UnsupportedOperationException("private cause"))
            val http = HttpClient(
                MockEngine {
                    requests++
                    error("No HTTP request may execute")
                }
            )
            try {
                val tool = GitHubTool(
                    "private-token",
                    http,
                    repositoryContext = GitHubRepositoryContext("private-owner", "private-repo", "working"),
                    credentialProvider = { if (++credentials == 2) throw observed else "private-token" }
                ).operations().single { it.definition.name == "github_context" }
                val result = tool.execute(
                    "call-$action",
                    buildJsonObject {
                        put("action", action)
                        if (action == "commit") put("ref", "a".repeat(40))
                        if (action == "changes") put("base_sha", "a".repeat(40))
                        if (action in setOf("branch", "branch_dependencies")) put("branch", "working")
                    }
                )
                assertTrue(action, result.isError)
                assertEquals(action, "GITHUB_OPERATION_FAILED", result.errorCode)
                assertFalse(action, result.dispatched)
                assertEquals(action, 2, credentials)
                assertEquals(action, 0, requests)
                val receipt = requireNotNull(result.executionReceipt)
                assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), receipt["phase"])
                for (key in listOf("read_requests", "mutation_requests", "accepted_mutations", "unknown_mutations")) assertEquals(key, JsonPrimitive(0), receipt[key])
                assertEquals(JsonPrimitive(false), receipt["requires_reconciliation"])
                assertEquals(JsonArray(listOf(JsonPrimitive("java.lang.IllegalStateException"), JsonPrimitive("java.lang.UnsupportedOperationException"))), receipt["cause_types"])
                assertEquals(JsonPrimitive(dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportContext.exceptionFingerprint(observed)), receipt["exception_fingerprint"])
                assertFalse(requireNotNull(receipt["cause_types"]).toString().contains("private"))
                assertFalse(requireNotNull(receipt["exception_fingerprint"]).toString().contains("private"))
            } finally {
                http.close()
            }
        }
    }

    @Test fun typedCredentialPrecheckAndCancellationDoNotBecomeGenericCaughtFailures() = runTest {
        for (cancel in listOf(false, true)) {
            var credentials = 0
            var requests = 0
            val http = HttpClient(
                MockEngine {
                    requests++
                    error("No request may execute")
                }
            )
            try {
                val tool = GitHubTool(
                    "private-token",
                    http,
                    repositoryContext = GitHubRepositoryContext("owner", "repo", "working"),
                    credentialProvider = {
                        if (++credentials == 2) {
                            if (cancel) throw kotlinx.coroutines.CancellationException("cancelled")
                            throw dev.chungjungsoo.gptmobile.data.github.GitHubReadValidation("GITHUB_REF_UNAVAILABLE", buildJsonObject { put("reason", "unavailable") })
                        }
                        "private-token"
                    }
                ).operations().single { it.definition.name == "github_context" }
                val invocation = runCatching { tool.execute("call", buildJsonObject { put("action", "repository") }) }
                if (cancel) {
                    assertTrue(invocation.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                } else {
                    val result = invocation.getOrThrow()
                    assertEquals("GITHUB_REF_UNAVAILABLE", result.errorCode)
                    assertFalse(result.dispatched)
                    assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), result.executionReceipt?.get("phase"))
                    assertEquals(null, result.executionReceipt?.get("cause_types"))
                }
                assertEquals(0, requests)
            } finally {
                http.close()
            }
        }
    }

    @Test fun savedPagesRevalidateTheLiveAccountAndSurviveVerifiedCredentialRotation() = runTest {
        var requests = 0
        var credentialChecks = 0
        var account = "account-a"
        var credential = "token-before-rotation"
        val state = dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore()
        val http = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("/repos/owner/repo/actions/workflows", request.url.encodedPath)
                respond("""{"total_count":1,"workflows":[{"id":7,"name":"Private workflow","path":".github/workflows/test.yml","state":"active"}]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            fun operation(principal: String = "account-a", repository: String = "repo") = GitHubTool(
                apiToken = credential, httpClient = http,
                repositoryContext = GitHubRepositoryContext("owner", repository, "work"),
                connectionUid = "saved-view-auth", conversationId = "saved-view-auth-chat", taskId = "task", stateStore = state,
                durableIdentity = dev.chungjungsoo.gptmobile.data.github.GitHubAuthorizationIdentity.scope(principal, "saved-view-auth"),
                credentialProvider = {
                    credentialChecks++
                    check(account == principal) { "GitHub account changed" }
                    credential
                }
            ).operations().single { it.operation == "github_ci" }
            val original = operation()
            val first = original.execute("workflows", buildJsonObject { put("action", "workflows") })
            assertFalse(first.isError)
            val cursor = (first.content as ToolResultContent.Json).value.jsonObject.getValue("recovery_call").jsonObject.getValue("arguments").jsonObject
            val beforeRecovery = credentialChecks
            credential = "token-after-rotation"
            assertFalse(operation().execute("after-verified-rotation", cursor).isError)
            assertTrue(credentialChecks > beforeRecovery)
            assertEquals("SAVED_VIEW_UNAVAILABLE", operation(repository = "another-repo").execute("other-repository", cursor).errorCode)
            account = "account-b"
            assertEquals("SAVED_VIEW_UNAVAILABLE", operation(principal = "account-b").execute("other-account-same-connection", cursor).errorCode)
            val revoked = original.execute("stale-instance", cursor)
            assertEquals("SAVED_VIEW_AUTHORIZATION_CHANGED", revoked.errorCode)
            assertFalse(revoked.dispatched)
            assertFalse(revoked.content.toString().contains("Private workflow"))
            assertEquals(1, requests)
        } finally {
            http.close()
        }
    }

    @Test fun expiredEvidenceHandleIsTypedAndDoesNotDispatchAnyHttpRequest() = runTest {
        val http = HttpClient(MockEngine { error("No request may execute for an unknown evidence handle") })
        try {
            val result = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("o", "r", "working")).execute(
                "expired",
                buildJsonObject {
                    put("action", "read_ranges")
                    put("evidence_id", "expired-or-invented-handle")
                }
            )
            assertTrue(result.isError)
            assertEquals("EVIDENCE_UNAVAILABLE", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals("PREFLIGHT_REJECTED", result.executionReceipt?.get("phase")?.jsonPrimitive?.content)
        } finally {
            http.close()
        }
    }

    @Test fun admissionCapacityBeforeAnyRequestIsReportedAsUnsent() = runTest {
        val http = HttpClient(MockEngine { error("No network request should execute") })
        try {
            val tool = GitHubTool("token", http, watchChecks = { throw dev.chungjungsoo.gptmobile.data.github.GitHubRequestScheduler.Capacity(sharedRead = false) })
            val result = tool.execute(
                "capacity",
                buildJsonObject {
                    put("action", "watch_checks")
                    put("owner", "o")
                    put("repo", "r")
                    put("run_id", 42)
                }
            )
            assertTrue(result.isError)
            assertEquals("GITHUB_WAITING_CAPACITY", result.errorCode)
            assertFalse(result.dispatched)
            assertTrue((result.content as ToolResultContent.Text).text.contains("earlier mutation receipts"))
        } finally {
            http.close()
        }
    }

    @Test fun selectedWorkspaceBranchCannotBeDeletedBeforeTaskHydration() = runTest {
        var requests = 0
        val http = HttpClient(
            MockEngine {
                requests++
                error("must not dispatch")
            }
        )
        try {
            val tool = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("o", "r", "working"))
            val result = tool.execute(
                "delete",
                buildJsonObject {
                    put("action", "delete_branch")
                    put("branch", "working")
                    put("expected_head_sha", "a".repeat(40))
                }
            )
            assertTrue(result.isError)
            assertFalse(result.dispatched)
            assertEquals("PREFLIGHT_REJECTED", result.executionReceipt?.get("phase")?.jsonPrimitive?.content)
            assertEquals(0, requests)
        } finally {
            http.close()
        }
    }

    @Test fun workflowRunPagesPreserveFiltersAndExposeTheUpstreamSearchCeiling() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("/repos/o/r/actions/workflows/codeql.yml/runs", request.url.encodedPath)
                assertEquals("failure", request.url.parameters["status"])
                assertEquals("100", request.url.parameters["per_page"])
                assertEquals(">=2026-01-01", request.url.parameters["created"])
                respond("""{"total_count":1200,"workflow_runs":[{"id":7,"workflow_id":9,"run_attempt":2,"head_sha":"abc","artifacts_url":"https://api.github.com/artifacts"}]}""")
            }
        )
        try {
            val tool = GitHubTool("token", http)
            fun arguments(page: Int) = buildJsonObject {
                put("action", "list_workflow_runs")
                put("owner", "o")
                put("repo", "r")
                put("workflow_id", ".github/workflows/codeql.yml")
                put("status", "failure")
                put("created", ">=2026-01-01")
                put("page", page)
            }
            val first = Json.parseToJsonElement((tool.execute("first", arguments(1)).content as ToolResultContent.Text).text).jsonObject
            assertEquals("2", first["continuation"]!!.jsonObject["page"]!!.jsonPrimitive.content)
            assertEquals("failure", first["continuation"]!!.jsonObject["status"]!!.jsonPrimitive.content)
            assertEquals("2", first["workflow_runs"]!!.jsonArray.single().jsonObject["run_attempt"]!!.jsonPrimitive.content)
            val last = Json.parseToJsonElement((tool.execute("last", arguments(10)).content as ToolResultContent.Text).text).jsonObject
            assertEquals("false", last["has_more"].toString())
            assertEquals("true", last["history_truncated"].toString())
            assertEquals("false", last["history_complete"].toString())
        } finally {
            http.close()
        }
    }

    @Test fun unfilteredWorkflowHistoryCanContinueBeyondOneThousandRuns() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("11", request.url.parameters["page"])
                respond("""{"total_count":20793,"workflow_runs":[]}""")
            }
        )
        try {
            val result = GitHubTool("token", http).execute(
                "page",
                buildJsonObject {
                    put("action", "list_workflow_runs")
                    put("owner", "o")
                    put("repo", "r")
                    put("page", 11)
                }
            )
            val data = Json.parseToJsonElement((result.content as ToolResultContent.Text).text).jsonObject
            assertEquals("12", data["continuation"]!!.jsonObject["page"]!!.jsonPrimitive.content)
            assertEquals("false", data["history_truncated"].toString())
        } finally {
            http.close()
        }
    }

    @Test fun workflowJobsRetainStepFailuresAndDirectJobLookupIsReadOnly() = runTest {
        val job = """{"id":3,"run_id":7,"steps":[{"name":"Compile","conclusion":"failure","number":2}],"runner_name":"runner"}"""
        val http = HttpClient(
            MockEngine { request ->
                respond(
                    when (request.url.encodedPath) {
                        "/repos/o/r/actions/runs/7" -> """{"id":7,"head_sha":"${"a".repeat(40)}"}"""
                        "/repos/o/r/actions/runs/7/jobs" -> """{"total_count":1,"jobs":[$job]}"""
                        "/repos/o/r/actions/jobs/3" -> job
                        else -> error("Unexpected job endpoint")
                    }
                )
            }
        )
        try {
            val tool = GitHubTool("token", http)
            val common = buildJsonObject {
                put("owner", "o")
                put("repo", "r")
                put("run_id", 7)
                put("job_id", 3)
            }
            val jobs = tool.execute("jobs", kotlinx.serialization.json.JsonObject(common + ("action" to kotlinx.serialization.json.JsonPrimitive("list_workflow_jobs"))))
            val result = Json.parseToJsonElement((jobs.content as ToolResultContent.Text).text).jsonObject
            assertEquals("failure", result["jobs"]!!.jsonArray.single().jsonObject["steps"]!!.jsonArray.single().jsonObject["conclusion"]!!.jsonPrimitive.content)
            val direct = tool.execute("job", kotlinx.serialization.json.JsonObject(common + ("action" to kotlinx.serialization.json.JsonPrimitive("get_workflow_job"))))
            assertFalse(direct.isError)
            assertTrue(
                dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary.isReplayableRead(
                    "github_ci",
                    buildJsonObject {
                        put("action", "jobs")
                        put("job_id", 3)
                    }
                )
            )
            assertEquals(
                "get_workflow_job",
                GitHubOperationTool.nativeArguments(
                    "github_ci",
                    buildJsonObject {
                        put("action", "jobs")
                        put("job_id", 3)
                    }
                )!!["action"]!!.jsonPrimitive.content
            )
        } finally {
            http.close()
        }
    }

    @Test fun workflowPathsNormalizeAndMissingSelectionOnlyDiscovers() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                requests += "${request.method.value} ${request.url.encodedPath}"
                when (request.url.encodedPath) {
                    "/repos/owner/repo/commits/work" -> respond("""{"sha":"${"a".repeat(40)}"}""")
                    "/repos/owner/repo/actions/workflows" -> respond("""{"total_count":31,"workflows":[{"id":1,"path":".github/workflows/build.yml"}]}""")
                    "/repos/owner/repo/actions/workflows/build.yml" -> respond("""{"id":1,"state":"active","path":".github/workflows/build.yml"}""")
                    "/repos/owner/repo" -> respond("""{"default_branch":"main"}""")
                    "/repos/owner/repo/contents/.github/workflows/build.yml" -> respond("""{"encoding":"base64","size":27,"content":"${java.util.Base64.getEncoder().encodeToString("on: [workflow_dispatch]".toByteArray())}"}""")
                    "/repos/owner/repo/actions/workflows/build.yml/dispatches" -> respond("", HttpStatusCode.NoContent)
                    else -> error("Unexpected workflow path")
                }
            }
        )
        try {
            val tool = GitHubTool("token", http)
            val common = buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
                put("ref", "work")
                put("action", "dispatch_workflow")
                put("expected_head_sha", "a".repeat(40))
            }
            val missing = tool.execute("missing", common)
            assertEquals("WORKFLOW_SELECTION_REQUIRED", missing.errorCode)
            assertFalse(requests.any { it.startsWith("POST") })
            val selected = tool.execute("selected", kotlinx.serialization.json.JsonObject(common + ("workflow_id" to kotlinx.serialization.json.JsonPrimitive(".github/workflows/build.yml"))))
            assertFalse(selected.isError)
            assertEquals(1, requests.count { it.startsWith("POST") })
            for (path in listOf("../build.yml", ".github/workflows/../build.yml", "/build.yml", ".")) {
                assertTrue(runCatching { dev.chungjungsoo.gptmobile.data.github.normalizeGitHubWorkflowSelector(path) }.isFailure)
            }
        } finally {
            http.close()
        }
    }

    @Test fun existingBranchIsReconciledWithoutUpdatingOrSelectingIt() = runTest {
        val base = "a".repeat(40)
        val existing = "b".repeat(40)
        val requests = mutableListOf<String>()
        val client = HttpClient(
            MockEngine { request ->
                requests += "${request.method.value} ${request.url.encodedPath}"
                when (request.url.encodedPath) {
                    "/repos/owner/repo/git/ref/heads/main" -> respond("""{"object":{"sha":"$base"}}""", HttpStatusCode.OK)
                    "/repos/owner/repo/git/refs" -> respond("""{"message":"Reference already exists"}""", HttpStatusCode.UnprocessableEntity)
                    "/repos/owner/repo/git/ref/heads/feature%2Fexisting" -> respond("""{"ref":"refs/heads/feature/existing","object":{"sha":"$existing"}}""", HttpStatusCode.OK)
                    else -> error("Unexpected request ${request.url}")
                }
            }
        )
        try {
            val result = GitHubTool("token", client).execute(
                "branch",
                buildJsonObject {
                    put("action", "create_branch")
                    put("owner", "owner")
                    put("repo", "repo")
                    put("branch", "feature/existing")
                    put("base", "main")
                    put("expected_head_sha", base)
                }
            )
            assertFalse(result.isError)
            val body = (result.content as ToolResultContent.Json).value.jsonObject
            assertEquals("true", body["already_exists"].toString())
            assertEquals("false", body["created"].toString())
            assertEquals(existing, body["head_sha"]!!.jsonPrimitive.content)
            assertEquals("false", body["matches_requested_base"].toString())
            assertEquals(0, requests.count { it.startsWith("POST") })
            assertFalse(requests.any { it.startsWith("PATCH") })
        } finally {
            client.close()
        }
    }

    @Test fun commitReviewUsesTheExplicitTargetWithoutRebindingTheSelectedTaskBranch() = runTest {
        val sha = "a".repeat(40)
        val tree = "b".repeat(40)
        val files = JsonArray(
            listOf(
                buildJsonObject {
                    put("path", "review.txt")
                    put("content", "reviewed content")
                }
            )
        )
        var requests = 0
        var mutations = 0
        val client = HttpClient(
            MockEngine { request ->
                requests++
                if (request.method != HttpMethod.Get) mutations++
                when (request.url.encodedPath) {
                    "/repos/owner/repo/commits/working" -> respond("""{"sha":"$sha","commit":{"tree":{"sha":"$tree"}}}""", HttpStatusCode.OK)
                    "/repos/owner/repo/git/trees/$tree" -> respond("""{"tree":[],"truncated":false}""", HttpStatusCode.OK)
                    else -> error("Unexpected request: ${request.url}")
                }
            }
        )
        val proposedBranches = mutableListOf<String>()
        try {
            val tool = GitHubTool("token", client, repositoryContext = GitHubRepositoryContext("owner", "repo", "main"), engineeringPrepare = { _, arguments ->
                assertEquals(files, arguments["files"])
                assertEquals(JsonPrimitive(sha), arguments["expected_head_sha"])
                proposedBranches += arguments.getValue("branch").jsonPrimitive.content
                buildJsonObject { put("status", "AWAITING_APPROVAL") }
            })
            val selected = tool.execute(
                "select",
                buildJsonObject {
                    put("action", "resolve_context")
                    put("ref", "working")
                }
            )
            assertFalse(selected.isError)
            val selectionReads = requests
            val proposed = tool.execute(
                "propose",
                buildJsonObject {
                    put("action", "commit_files")
                    put("branch", "working")
                    put("expected_head_sha", sha)
                    put("message", "Review the selected branch patch")
                    put("files", files)
                }
            )
            assertFalse(proposed.toString(), proposed.isError)
            assertFalse(proposed.dispatched)
            assertEquals(JsonPrimitive("AWAITING_APPROVAL"), ToolResultEnvelope.element(proposed.content).jsonObject["status"])
            assertEquals(listOf("working"), proposedBranches)
            val explicit = tool.execute(
                "explicit-branch",
                buildJsonObject {
                    put("action", "commit_files")
                    put("branch", "unselected")
                    put("expected_head_sha", sha)
                    put("message", "Review the explicit branch patch")
                    put("files", files)
                }
            )
            assertFalse(explicit.toString(), explicit.isError)
            assertFalse(explicit.dispatched)
            assertEquals(JsonPrimitive("AWAITING_APPROVAL"), ToolResultEnvelope.element(explicit.content).jsonObject["status"])
            assertEquals(listOf("working", "unselected"), proposedBranches)
            assertEquals("working", tool.planningContext().repository?.ref)
            for (missing in listOf("branch", "expected_head_sha", "message")) {
                val invalid = tool.execute(
                    "missing-$missing",
                    buildJsonObject {
                        put("action", "commit_files")
                        if (missing != "branch") put("branch", "unselected")
                        if (missing != "expected_head_sha") put("expected_head_sha", sha)
                        if (missing != "message") put("message", "Review the explicit branch patch")
                        put("files", files)
                    }
                )
                assertTrue(missing, invalid.isError)
                assertFalse(missing, invalid.dispatched)
                assertEquals(missing, "INVALID_ARGUMENTS", invalid.errorCode)
            }
            assertEquals(listOf("working", "unselected"), proposedBranches)
            assertEquals(selectionReads, requests)
            assertEquals(0, mutations)
        } finally {
            client.close()
        }
    }

    @Test fun unavailableCredentialVerificationReturnsAnExplicitErrorWithoutDispatchOrCatalogOmission() = runTest {
        val http = HttpClient(MockEngine { error("An unverified account must not reach GitHub") })
        try {
            val tool = GitHubTool("configured-token", http, credentialAdmissionFailed = true)
            assertTrue(tool.hasCredential)
            assertTrue(tool.operations().any { it.operation == "github_write" })
            val result = tool.execute("authorization", buildJsonObject { put("action", "get_account") })
            assertTrue(result.isError)
            assertEquals(false, result.dispatched)
            assertEquals("GITHUB_AUTHORIZATION_UNVERIFIED", result.errorCode)
        } finally {
            http.close()
        }
    }

    @Test
    fun `programmer workspace rejects other repositories and unselected indexing before dispatch`() = runTest {
        val client = HttpClient(MockEngine { error("Out of scope operations must not reach GitHub") })
        try {
            val scope = dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext("connection", "owner", "repo", "feature", "a".repeat(40), "profile")
            val tool = GitHubTool("user-token", client, repositoryScope = scope)
            for ((action, repository) in listOf("read_code" to "other", "list_repositories" to "repo", "search_code" to "repo", "build_index" to "repo")) {
                val result = tool.execute(
                    "scoped-$action",
                    buildJsonObject {
                        put("action", action)
                        put("owner", "owner")
                        put("repo", repository)
                        put("path", "README.md")
                        put("query", "fun main")
                    }
                )
                assertTrue(result.isError)
                assertEquals("REPOSITORY_SCOPE_MISMATCH", result.errorCode)
                assertEquals(false, result.dispatched)
            }
        } finally {
            client.close()
        }
    }

    @Test
    fun `invalid workflow arguments return actionable validation errors without network access`() = runTest {
        val client = HttpClient(MockEngine { error("Invalid arguments must not reach the network") })
        try {
            val result = GitHubTool("user-token", client).execute(
                "invalid-workflow",
                buildJsonObject {
                    put("action", "get_workflow_run")
                    put("owner", "owner")
                    put("repo", "repo")
                    put("run_id", -1)
                }
            )
            assertTrue(result.isError)
            val message = (result.content as ToolResultContent.Text).text
            assertTrue(message.contains("GITHUB_INVALID_ARGUMENT"))
            assertTrue(message.contains("action=get_workflow_run"))
            assertTrue(message.contains("positive run_id"))
            assertFalse(message.contains("Failed requirement"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `workflow dispatch carries user ref and typed inputs and accepts no-content response`() = runTest {
        val client = HttpClient(
            MockEngine { request ->
                if (request.method == io.ktor.http.HttpMethod.Get) {
                    if (request.url.encodedPath == "/repos/owner/repo/commits/main") return@MockEngine respond("""{"sha":"${"a".repeat(40)}"}""")
                    if (request.url.encodedPath == "/repos/owner/repo") return@MockEngine respond("""{"default_branch":"main"}""")
                    if ("/contents/" in request.url.encodedPath) {
                        val source = "on:\n  workflow_dispatch:\n    inputs:\n      debug:\n        type: boolean\n"
                        return@MockEngine respond("""{"encoding":"base64","size":${source.length},"content":"${java.util.Base64.getEncoder().encodeToString(source.toByteArray())}"}""")
                    }
                    assertEquals("/repos/owner/repo/actions/workflows/build.yml", request.url.encodedPath)
                    return@MockEngine respond("""{"id":1,"state":"active","path":".github/workflows/build.yml"}""")
                }
                assertEquals("/repos/owner/repo/actions/workflows/build.yml/dispatches", request.url.encodedPath)
                assertEquals(io.ktor.http.HttpMethod.Post, request.method)
                assertEquals("Bearer user-token", request.headers[HttpHeaders.Authorization])
                val body = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                assertEquals("main", body["ref"]?.toString()?.trim('"'))
                assertEquals("true", body["inputs"]?.jsonObject?.get("debug")?.toString())
                respond("", HttpStatusCode.NoContent)
            }
        )
        try {
            val result = GitHubTool("user-token", client).execute(
                "dispatch",
                buildJsonObject {
                    put("action", "dispatch_workflow")
                    put("owner", "owner")
                    put("repo", "repo")
                    put("workflow_id", "build.yml")
                    put("ref", "main")
                    put("expected_head_sha", "a".repeat(40))
                    put("inputs", buildJsonObject { put("debug", true) })
                }
            )
            assertFalse(result.isError)
            assertTrue((result.content as ToolResultContent.Text).text.contains("accepted"))
        } finally {
            client.close()
        }
    }

    @Test
    fun `403 write with push permission is not misreported as read only token and never suggests shell fallback`() = runTest {
        var requestIndex = 0
        val client = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/repos/owner/repo/git/ref/heads/fix%2Ftest") {
                    respond("{\"message\":\"Not Found\"}", HttpStatusCode.NotFound)
                } else {
                    when (requestIndex++) {
                        0 -> {
                            assertEquals("/repos/owner/repo/git/ref/heads/main", request.url.encodedPath)
                            respond("""{"object":{"sha":"abc123"}}""", HttpStatusCode.OK)
                        }
                        1 -> {
                            assertEquals("/repos/owner/repo/git/refs", request.url.encodedPath)
                            respond(
                                """{"message":"Resource not accessible by personal access token"}""",
                                HttpStatusCode.Forbidden,
                                headersOf("X-RateLimit-Remaining", "4999")
                            )
                        }
                        2 -> {
                            assertEquals("/repos/owner/repo", request.url.encodedPath)
                            respond(
                                """{"full_name":"owner/repo","default_branch":"main","visibility":"public","permissions":{"push":true,"maintain":false,"admin":false}}""",
                                HttpStatusCode.OK
                            )
                        }
                        else -> error("Unexpected request: ${request.method.value} ${request.url}")
                    }
                }
            }
        )
        try {
            val result = GitHubTool("user-token", client).execute(
                "branch",
                buildJsonObject {
                    put("action", "create_branch")
                    put("owner", "owner")
                    put("repo", "repo")
                    put("branch", "fix/test")
                    put("base", "main")
                }
            )

            assertTrue(result.isError)
            val text = (result.content as ToolResultContent.Text).text
            assertTrue(text.contains("GITHUB_WRITE_BLOCKED"))
            assertTrue(text.contains("write_capability=allowed"))
            assertTrue(text.contains("can_push=true"))
            assertTrue(text.contains("actor can write to the repository"))
            assertTrue(text.contains("required_permission=Contents: write"))
            assertTrue(text.contains("do not fall back to shell", ignoreCase = true))
            assertFalse(text.contains("token is configured for Read-Only", ignoreCase = true))
            assertEquals(3, requestIndex)
        } finally {
            client.close()
        }
    }

    @Test
    fun `workflow writes cannot run without credentials and run IDs must be positive`() = runTest {
        val client = HttpClient(MockEngine { error("Invalid requests must not reach GitHub") })
        try {
            val tool = GitHubTool(httpClient = client)
            listOf("cancel_workflow", "rerun_workflow", "rerun_failed_jobs").forEach { action ->
                val result = tool.execute(
                    "write",
                    buildJsonObject {
                        put("action", action)
                        put("owner", "owner")
                        put("repo", "repo")
                        put("run_id", 10)
                    }
                )
                assertTrue(result.isError)
            }
            val result = tool.execute(
                "read",
                buildJsonObject {
                    put("action", "get_workflow_run")
                    put("owner", "owner")
                    put("repo", "repo")
                    put("run_id", -1)
                }
            )
            assertTrue(result.isError)
        } finally {
            client.close()
        }
    }

    @Test
    fun `job log previews stop at the output budget`() = runTest {
        val client = HttpClient(MockEngine { request -> if (request.url.encodedPath.endsWith("/logs")) respond("L".repeat(100_000), HttpStatusCode.OK) else respond("""{"id":20,"status":"completed"}""") })
        try {
            val result = GitHubTool(httpClient = client).execute(
                "logs",
                buildJsonObject {
                    put("action", "get_job_logs")
                    put("owner", "owner")
                    put("repo", "repo")
                    put("job_id", 20)
                }
            )
            assertFalse(result.isError)
            val text = (result.content as ToolResultContent.Text).text
            assertTrue(text.length < 33_000)
            val data = Json.parseToJsonElement(text).jsonObject
            assertEquals("true", data["has_more"].toString())
            assertEquals("24576", data["continuation"]!!.jsonObject["start_offset"]!!.jsonPrimitive.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `definition exposes correct name and required action property`() {
        val tool = GitHubTool()
        assertEquals(BuiltInAgentTool.GITHUB, tool.definition.name)
        assertTrue(tool.definition.description.contains("GitHub", ignoreCase = true))
        val properties = tool.definition.inputSchema["properties"]?.jsonObject
        assertTrue(properties?.containsKey("action") == true)
        assertTrue(properties?.containsKey("query") == true)
        assertTrue(properties?.containsKey("owner") == true)
        assertTrue(properties?.containsKey("repo") == true)
        assertTrue(properties?.containsKey("path") == true)
        assertTrue(properties?.containsKey("issue_number") == true)
        assertTrue(properties?.containsKey("tag_name") == true)
        assertTrue(properties?.containsKey("release_strategy") == true)
        assertTrue(properties?.containsKey("release_id") == true)
        val actions = properties?.get("action")?.jsonObject?.get("enum")?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
        assertTrue("release_status" in actions)
        assertTrue("publish_release" in actions)
        assertTrue("create_release" in actions)
        assertTrue("create_tag" in actions)
        assertTrue("update_release" in actions)
        assertTrue(tool.definition.description.contains("do not tell the user to use local gh", ignoreCase = true))
    }

    @Test
    fun `missing action returns error result`() = runTest {
        val tool = GitHubTool()
        val result = tool.execute("call-1", buildJsonObject {})
        assertTrue(result.isError)
        assertEquals("call-1", result.callId)
        val text = (result.content as ToolResultContent.Text).text
        assertTrue(text.contains("Missing required parameter: 'action'"))
    }

    @Test
    fun `unknown action returns error result`() = runTest {
        val tool = GitHubTool()
        val result = tool.execute("call-1", buildJsonObject { put("action", "unknown_action") })
        assertTrue(result.isError)
        assertEquals("call-1", result.callId)
        val text = (result.content as ToolResultContent.Text).text
        assertTrue(text.contains("Unknown GitHub action"))
    }

    @Test
    fun `search_repositories parses and formats search response`() = runTest {
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.encodedPath.contains("/search/repositories"))
            respond(
                content = """
                    {
                        "total_count": 1,
                        "items": [
                            {
                                "full_name": "tailscale-signin/GPT_Mobile_AI-improved",
                                "description": "AI Chat Assistant for Android",
                                "stargazers_count": 42,
                                "language": "Kotlin",
                                "html_url": "https://github.com/tailscale-signin/GPT_Mobile_AI-improved"
                            }
                        ]
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine)
        val tool = GitHubTool(httpClient = client)

        val result = tool.execute(
            "call-search",
            buildJsonObject {
                put("action", "search_repositories")
                put("query", "GPT_Mobile")
            }
        )

        assertFalse(result.isError)
        assertEquals("call-search", result.callId)
        val text = (result.content as ToolResultContent.Text).text
        val json = Json.parseToJsonElement(text).jsonObject
        assertTrue(json.containsKey("items"))
        assertTrue(text.contains("tailscale-signin/GPT_Mobile_AI-improved"))
    }

    @Test fun repositorySearchRejectsMissingBlankOrNonStringQueryBeforeDispatch() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                error("Invalid repository searches must not dispatch")
            }
        )
        try {
            val tool = GitHubTool("token", client, persistentState = false)
            for ((index, raw) in listOf(null, "\"\"", "\"   \"", "null", "false", "42", "{}", "[]").withIndex()) {
                val input = buildJsonObject {
                    put("action", "search_repositories")
                    raw?.let { put("query", Json.parseToJsonElement(it)) }
                }
                val result = tool.execute("invalid-search-$index", input)
                assertTrue(result.isError)
                assertEquals("INVALID_ARGUMENTS", result.errorCode)
                assertFalse(result.dispatched)
                assertEquals(JsonPrimitive("PREFLIGHT_REJECTED"), result.executionReceipt?.get("phase"))
                assertTrue(result.content.toString().contains("'query'"))
                assertEquals(JsonPrimitive(false), result.executionReceipt?.get("requires_reconciliation"))
                assertEquals(0, requests)
            }
        } finally {
            client.close()
        }
    }

    @Test fun repositoryDiscoveryRejectsMissingQueryAndAcceptsOneCorrectedSearch() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("/search/repositories", request.url.encodedPath)
                assertEquals("language:Kotlin", request.url.parameters["q"])
                respond("""{"total_count":0,"items":[]}""", headers = headersOf(HttpHeaders.ContentType, "application/json"))
            }
        )
        try {
            val tool = GitHubTool(
                "token",
                client,
                repositoryContext = GitHubRepositoryContext("selected", "repository", "main"),
                persistentState = false,
                taskRequest = "Find a Kotlin repository"
            ).operations().single { it.definition.name == "github_context" }
            for ((index, query) in listOf(null, "", "   ").withIndex()) {
                val result = tool.execute(
                    "missing-query-$index",
                    buildJsonObject {
                        put("action", "repositories")
                        query?.let { put("query", it) }
                    }
                )
                assertTrue(result.isError)
                assertEquals("INVALID_ARGUMENTS", result.errorCode)
                assertFalse(result.dispatched)
                assertEquals(JsonPrimitive("tool_precheck"), result.executionReceipt?.get("failed_stage"))
                assertEquals(JsonArray(listOf(JsonPrimitive("query"))), result.executionReceipt?.get(if (query == null) "missing_fields" else "invalid_fields"))
                assertEquals(JsonPrimitive(if (query == null) 1 else 0), result.executionReceipt?.get("missing_field_count"))
                assertEquals(JsonPrimitive(if (query == null) 0 else 1), result.executionReceipt?.get("invalid_field_count"))
                assertEquals(JsonArray(listOf(JsonPrimitive(if (query == null) "MISSING_REQUIRED" else "EMPTY_STRING"))), result.executionReceipt?.get("invalid_field_classes"))
                assertTrue(result.content.toString().contains("query is required"))
                assertEquals(0, requests)
            }
            val corrected = tool.execute(
                "corrected-search",
                buildJsonObject {
                    put("action", "repositories")
                    put("query", "language:Kotlin")
                }
            )
            assertFalse(corrected.toString(), corrected.isError)
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun `selected repository fills whichever owner or repo field is missing`() = runTest {
        var requestIndex = 0
        val client = HttpClient(
            MockEngine { request ->
                when (requestIndex++) {
                    0 -> assertEquals("/repos/selected-owner/repo/contents/README.md", request.url.encodedPath)
                    else -> assertEquals("/repos/selected-owner/repo/contents/README.md", request.url.encodedPath)
                }
                respond(
                    """{"name":"README.md","path":"README.md","encoding":"base64","content":"b2s="}""",
                    HttpStatusCode.OK,
                    headersOf(HttpHeaders.ContentType, "application/json")
                )
            }
        )
        try {
            val tool = GitHubTool(
                httpClient = client,
                repositoryContext = GitHubRepositoryContext("selected-owner", "repo", "main")
            )
            val missingOwner = tool.execute(
                "missing-owner",
                buildJsonObject {
                    put("action", "get_file_contents")
                    put("repo", "repo")
                    put("path", "README.md")
                }
            )
            val missingRepo = tool.execute(
                "missing-repo",
                buildJsonObject {
                    put("action", "get_file_contents")
                    put("owner", "selected-owner")
                    put("path", "README.md")
                }
            )
            assertFalse(missingOwner.isError)
            assertFalse(missingRepo.isError)
            assertEquals(2, requestIndex)
        } finally {
            client.close()
        }
    }

    @Test
    fun `get_file_contents decodes base64 encoded content`() = runTest {
        val rawContent = "Hello from GitHub agent tool test!"
        val base64Encoded = java.util.Base64.getEncoder().encodeToString(rawContent.toByteArray())
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.encodedPath.contains("/repos/owner/repo/contents/README.md"))
            respond(
                content = """
                    {
                        "name": "README.md",
                        "path": "README.md",
                        "encoding": "base64",
                        "content": "$base64Encoded"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine)
        val tool = GitHubTool(httpClient = client)

        val result = tool.execute(
            "call-file",
            buildJsonObject {
                put("action", "get_file_contents")
                put("owner", "owner")
                put("repo", "repo")
                put("path", "README.md")
            }
        )

        assertFalse(result.isError)
        assertEquals("call-file", result.callId)
        val text = (result.content as ToolResultContent.Text).text
        assertEquals("Hello from GitHub agent tool test!", text)
    }

    @Test
    fun `get_issue formats issue summary`() = runTest {
        val mockEngine = MockEngine { request ->
            assertTrue(request.url.encodedPath.contains("/repos/owner/repo/issues/101"))
            respond(
                content = """
                    {
                        "number": 101,
                        "title": "Bug in tool execution",
                        "state": "open",
                        "user": { "login": "octocat" },
                        "created_at": "2026-09-14T12:00:00Z",
                        "body": "Detailed description of the issue.",
                        "html_url": "https://github.com/owner/repo/issues/101"
                    }
                """.trimIndent(),
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json")
            )
        }
        val client = HttpClient(mockEngine)
        val tool = GitHubTool(httpClient = client)

        val result = tool.execute(
            "call-issue",
            buildJsonObject {
                put("action", "get_issue")
                put("owner", "owner")
                put("repo", "repo")
                put("issue_number", 101)
            }
        )

        assertFalse(result.isError)
        assertEquals("call-issue", result.callId)
        val text = (result.content as ToolResultContent.Text).text
        val json = Json.parseToJsonElement(text).jsonObject
        assertEquals("Bug in tool execution", json["title"]?.toString()?.replace("\"", ""))
        assertEquals("open", json["state"]?.toString()?.replace("\"", ""))
        assertEquals("octocat", json["user"]?.toString()?.replace("\"", ""))
    }

    @Test
    fun `issue search supplies discriminator and rate limited search stops before a second request`() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine { request ->
                requests++
                assertEquals("repo:owner/repo timeout is:issue", request.url.parameters["q"])
                respond(
                    """{"message":"API rate limit exceeded"}""",
                    HttpStatusCode.Forbidden,
                    headersOf(
                        "X-RateLimit-Resource" to listOf("search"),
                        "X-RateLimit-Remaining" to listOf("0"),
                        "X-RateLimit-Reset" to listOf((System.currentTimeMillis() / 1000 + 60).toString())
                    )
                )
            }
        )
        try {
            val tool = GitHubTool("token", client)
            val args = buildJsonObject {
                put("action", "search_issues")
                put("owner", "owner")
                put("repo", "repo")
                put("query", "repo:owner/repo timeout")
            }
            assertTrue(tool.execute("first", args).isError)
            val second = tool.execute("second", args)
            assertTrue(second.isError)
            assertTrue((second.content as ToolResultContent.Text).text.contains("cooling down"))
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun `401 from a workspace operation also blocks legacy read endpoints`() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                respond("""{"message":"Bad credentials"}""", HttpStatusCode.Unauthorized)
            }
        )
        try {
            val tool = GitHubTool("expired", client)
            val repository = buildJsonObject {
                put("action", "get_repository")
                put("owner", "owner")
                put("repo", "repo")
            }
            val rejected = tool.execute("repository", repository)
            assertTrue(rejected.isError)
            assertEquals("AUTH_REQUIRED", rejected.errorCode)
            val issue = buildJsonObject {
                put("action", "get_issue")
                put("owner", "owner")
                put("repo", "repo")
                put("issue_number", 1)
            }
            val result = tool.execute("issue", issue)
            assertTrue(result.isError)
            assertTrue((result.content as ToolResultContent.Text).text.contains("Reconnect"))
            assertEquals(1, requests)
        } finally {
            client.close()
        }
    }

    @Test
    fun `repository rule denial is distinguished from missing Actions permission`() = runTest {
        var requests = 0
        val client = HttpClient(
            MockEngine {
                requests++
                if (requests == 1) {
                    respond("""{"object":{"sha":"abc123"}}""", HttpStatusCode.OK)
                } else {
                    respond("""{"message":"GH013: Repository rule violations found"}""", HttpStatusCode.Forbidden)
                }
            }
        )
        try {
            val result = GitHubTool("rule-test", client).execute(
                "branch",
                buildJsonObject {
                    put("action", "create_branch")
                    put("owner", "owner")
                    put("repo", "repo")
                    put("branch", "fix/test")
                    put("base", "main")
                }
            )
            assertTrue(result.isError)
            assertEquals("GITHUB_BRANCH_RULES_BLOCKED", result.errorCode)
            assertEquals(2, requests)
        } finally {
            client.close()
        }
    }

    @Test fun workflowRunHttpFailureRetainsSpecificCodeAndRequestedResource() = runTest {
        HttpClient(MockEngine { respond("""{"message":"Resource not accessible by integration"}""", HttpStatusCode.Forbidden) }).use { http ->
            val result = GitHubTool("token", http).execute(
                "runs",
                buildJsonObject {
                    put("action", "list_workflow_runs")
                    put("owner", "o")
                    put("repo", "r")
                    put("head_sha", "a".repeat(40))
                    put("workflow_id", "ci.yml")
                }
            )
            assertTrue(result.isError)
            assertEquals("GITHUB_ENDPOINT_PERMISSION_REQUIRED", result.errorCode)
            assertEquals("ci.yml", result.executionReceipt?.get("resource_context")?.jsonObject?.get("workflow_id")?.jsonPrimitive?.content)
            assertEquals("a".repeat(40), result.executionReceipt?.get("resource_context")?.jsonObject?.get("head_sha")?.jsonPrimitive?.content)
        }
    }

    @Test fun malformedCiResponseIsNotMisreportedAsInvalidArguments() = runTest {
        for (payload in listOf("[]", "{broken")) {
            HttpClient(MockEngine { respond(payload) }).use { http ->
                val result = GitHubTool("token", http).execute(
                    "run",
                    buildJsonObject {
                        put("action", "get_workflow_run")
                        put("owner", "o")
                        put("repo", "r")
                        put("run_id", 7)
                    }
                )
                assertTrue(result.isError)
                assertEquals("GITHUB_UNEXPECTED_RESPONSE", result.errorCode)
                assertTrue(result.dispatched)
            }
        }
    }

    @Test fun disposableBranchReadbackNeverSelectsTheFixtureAsWorkspaceContext() = runTest {
        val sha = "a".repeat(40)
        val tree = "b".repeat(40)
        var created = false
        val paths = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                paths += request.url.encodedPath
                when (request.url.encodedPath) {
                    "/repos/o/r/git/ref/heads/main" -> respond("""{"ref":"refs/heads/main","object":{"sha":"$sha"}}""")
                    "/repos/o/r/git/ref/heads/fixture" -> if (created) respond("""{"ref":"refs/heads/fixture","object":{"sha":"$sha"}}""") else respond("{}", HttpStatusCode.NotFound)
                    "/repos/o/r/git/refs" -> {
                        created = true
                        respond("""{"ref":"refs/heads/fixture","object":{"sha":"$sha"}}""", HttpStatusCode.Created)
                    }
                    "/repos/o/r/commits/main" -> respond("""{"sha":"$sha","commit":{"tree":{"sha":"$tree"}},"files":[]}""")
                    else -> error("A fixture must not become the selected context: ${request.url}")
                }
            }
        )
        try {
            val tool = GitHubTool("token", http, repositoryContext = GitHubRepositoryContext("o", "r", "main"))
            val missing = tool.execute(
                "missing-fixture",
                buildJsonObject {
                    put("action", "get_branch_head")
                    put("branch", "fixture")
                }
            )
            assertTrue(missing.isError)
            assertEquals("GITHUB_NOT_FOUND_OR_INACCESSIBLE", missing.errorCode)
            val result = tool.execute(
                "fixture",
                buildJsonObject {
                    put("action", "create_branch")
                    put("owner", "o")
                    put("repo", "r")
                    put("branch", "fixture")
                    put("base", "main")
                    put("expected_head_sha", sha)
                    put("select_created_branch", false)
                }
            )
            assertFalse(result.isError)
            val body = dev.chungjungsoo.gptmobile.data.agent.ToolResultEnvelope.element(result.content).jsonObject
            assertEquals("true", body["selection_preserved"].toString())
            assertEquals("true", body["remote_state_verified"].toString())
            assertEquals("false", body["context_selected"].toString())
            assertFalse(body.containsKey("context"))
            assertFalse(tool.execute("read", buildJsonObject { put("action", "get_commit_detail") }).isError)
            assertFalse(paths.any { it == "/repos/o/r/commits/fixture" })
            assertEquals(1, paths.count { it == "/repos/o/r/commits/main" })
        } finally {
            http.close()
        }
    }

    @Test fun workflowRunPageCompactsNestedProfilesBeforePagination() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("/repos/o/r/actions/runs", request.url.encodedPath)
                respond("""{"total_count":1,"workflow_runs":[{"id":73,"name":"CI","status":"completed","conclusion":"success","head_sha":"abc","actor":{"login":"reviewer","id":7,"email":"private-email","organizations_url":"private-profile-url"},"triggering_actor":{"login":"reviewer","id":7,"private":"private-details"},"head_commit":{"id":"abc","message":"private-commit-message","author":{"email":"private-author"}},"artifacts_url":"https://api.github.com/artifacts"}]}""")
            }
        )
        try {
            val result = GitHubTool("token", http).execute(
                "compact-run",
                buildJsonObject {
                    put("action", "list_workflow_runs")
                    put("owner", "o")
                    put("repo", "r")
                    put("per_page", 30)
                }
            )
            val output = (result.content as ToolResultContent.Text).text
            val row = Json.parseToJsonElement(output).jsonObject.getValue("workflow_runs").jsonArray.single().jsonObject
            assertEquals("reviewer", row.getValue("actor").jsonObject.getValue("login").jsonPrimitive.content)
            assertEquals("7", row.getValue("actor").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("abc", row.getValue("head_commit").jsonObject.getValue("id").jsonPrimitive.content)
            assertEquals("https://api.github.com/artifacts", row.getValue("artifacts_url").jsonPrimitive.content)
            assertFalse(output.contains("private-"))
        } finally {
            http.close()
        }
    }
}
