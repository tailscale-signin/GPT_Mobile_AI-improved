package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolContractsTest {
    private val sha = "a".repeat(40)
    private fun args(raw: String) = Json.parseToJsonElement(raw).jsonObject
    private fun data(result: AgentToolResult) = when (val content = result.content) {
        is ToolResultContent.Text -> Json.parseToJsonElement(content.text).jsonObject
        is ToolResultContent.Json -> content.value.jsonObject
        else -> error("Expected JSON")
    }
    private fun call(action: String, extra: String = "") = args("""{"owner":"o","repo":"r","action":"$action"$extra}""")

    @Test fun invalidReadsAndActionSpecificArgumentsNeverDispatch() = runTest {
        val backend = Backend()
        val cases = listOf(
            "github_read" to "{}",
            "github_read" to """{"action":"ranges","ranges":[]}""",
            "github_read" to """{"ranges":[{"path":"x.kt","start_line":20,"end_line":1}]}""",
            "github_ci" to """{"action":"artifacts"}""",
            "github_ci" to """{"action":"jobs"}""",
            "github_ci" to """{"action":"logs","job_id":0}""",
            "github_ci" to """{"action":"dispatch","workflow_id":"build.yml"}""",
            "github_commit" to """{"action":"commit","stage_id":"S1"}""",
            "github_stage_patch" to """{"target_file":"README.md"}"""
        )
        for ((operation, raw) in cases) {
            val result = GitHubOperationTool(operation, backend, "admission").execute("invalid", args(raw))
            assertEquals("$operation $raw", "INVALID_ARGUMENTS", result.errorCode)
            assertFalse(result.dispatched)
        }
        assertTrue(backend.calls.isEmpty())
    }

    @Test fun aliasesKeepReadAndWriteClassification() = runTest {
        val cases = listOf(
            Triple("github_ci", """{"action":"status","run_id":7}""", "get_workflow_run"),
            Triple("github_ci", """{"action":"status","job_id":8}""", "get_workflow_job"),
            Triple("github_ci", """{"action":"status","branch":"work"}""", "get_commit_status_summary"),
            Triple("github_ci", """{"action":"rerun_failed_jobs","run_id":7}""", "rerun_failed_jobs"),
            Triple("github_ci", """{"action":"rerun_all","run_id":7}""", "rerun_workflow"),
            Triple("github_commit", """{"action":"commit_staged_patch","stage_id":"S1","branch":"work","message":"Fix","expected_head_sha":"$sha"}""", "commit_staged_patch")
        )
        for ((operation, raw, native) in cases) {
            val backend = Backend()
            val input = args(raw)
            assertFalse(GitHubOperationTool(operation, backend, "aliases").execute("alias", input).isError)
            assertEquals(native, backend.calls.single()["action"]!!.jsonPrimitive.content)
            assertEquals(native.startsWith("get_"), GitHubAuthorizationBoundary.isReplayableRead(operation, input))
        }
    }

    @Test fun topLevelReviewsAndInlineReviewsUseTheirOwnEndpoints() = runTest {
        for ((extra, native) in listOf(
            "\"event\":\"APPROVE\"" to "submit_pull_request_review",
            "\"body\":\"Please explain the retry policy\"" to "submit_pull_request_review",
            "\"comments\":[{\"path\":\"A.kt\",\"line\":1,\"body\":\"Check this\"}]" to "create_pull_request_review"
        )) {
            val backend = Backend()
            val input = args("""{"action":"review","pull_number":1,"expected_head_sha":"$sha",$extra}""")
            assertFalse(GitHubOperationTool("github_pr", backend, "reviews").execute("review", input).isError)
            assertEquals(native, backend.calls.single()["action"]!!.jsonPrimitive.content)
        }
    }

    @Test fun acceptedCiMutationContinuationOnlyReadsTheRun() = runTest {
        val backend = Backend().apply { value = args("""{"status":"accepted","continuation":{"action":"get_workflow_run","run_id":7}}""") }
        val tool = GitHubOperationTool("github_ci", backend, "receipt")
        val first = tool.execute("retry", args("""{"action":"rerun_failed_jobs","run_id":7}"""))
        val next = data(first)["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
        assertEquals("run", next["action"]!!.jsonPrimitive.content)
        assertTrue(GitHubAuthorizationBoundary.isReplayableRead("github_ci", next))
        tool.execute("observe", next)
        assertEquals(listOf("rerun_failed_jobs", "get_workflow_run"), backend.calls.map { it["action"]!!.jsonPrimitive.content })
    }

    @Test fun runIdCannotDispatchAWorkflow() = runTest {
        val requests = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                requests += "${request.method.value} ${request.url.encodedPath}"
                when (request.url.encodedPath) {
                    "/repos/o/r/commits/work" -> respond("""{"sha":"$sha"}""")
                    "/repos/o/r/actions/workflows/41148584430" -> respond("""{"message":"Not Found"}""", HttpStatusCode.NotFound)
                    "/repos/o/r/actions/runs/41148584430" -> respond("""{"id":41148584430,"workflow_id":25,"path":".github/workflows/build.yml"}""")
                    else -> error("Unexpected request")
                }
            }
        )
        try {
            val result = GitHubTool("token", http).execute("bad-selector", call("dispatch_workflow", """, "workflow_id":"41148584430", "ref":"work", "expected_head_sha":"$sha""""))
            assertEquals("WORKFLOW_ID_IS_RUN_ID", result.errorCode)
            assertFalse(result.dispatched)
            assertEquals("25", data(result)["observation"]!!.jsonObject["workflow_id"]!!.jsonPrimitive.content)
            assertFalse(requests.any { it.startsWith("POST") })
        } finally {
            http.close()
        }
    }

    @Test fun rerunDoesNotCancelOrRetryAnActiveRun() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/repos/o/r/actions/runs/7", request.url.encodedPath)
                respond("""{"id":7,"status":"in_progress"}""")
            }
        )
        try {
            val result = GitHubTool("token", http).execute("retry", call("rerun_failed_jobs", """, "run_id":7"""))
            assertEquals("CI_RUN_ACTIVE", result.errorCode)
            assertFalse(result.dispatched)
        } finally {
            http.close()
        }
    }

    @Test fun cancelOnCompletedRunIsAnObservedNoOp() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                assertEquals(HttpMethod.Get, request.method)
                respond("""{"id":7,"status":"completed","conclusion":"success"}""")
            }
        )
        try {
            val result = GitHubTool("token", http).execute("cancel", call("cancel_workflow", """, "run_id":7"""))
            assertFalse(result.isError)
            assertFalse(result.dispatched)
            assertEquals("already_completed", data(result)["status"]!!.jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    @Test fun artifactsAreEmptySuccessfullyAndPaginationKeepsTheRun() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/repos/o/r/actions/runs/7") return@MockEngine respond("""{"id":7,"head_sha":"$sha"}""")
                assertEquals("/repos/o/r/actions/runs/7/artifacts", request.url.encodedPath)
                assertEquals("1", request.url.parameters["per_page"])
                if (request.url.parameters["page"] == "2") {
                    respond("""{"total_count":0,"artifacts":[]}""")
                } else {
                    respond("""{"total_count":2,"artifacts":[{"id":8,"name":"report"}]}""")
                }
            }
        )
        try {
            val tool = GitHubOperationTool("github_ci", GitHubTool("token", http), "artifacts")
            val first = data(tool.execute("page1", call("artifacts", """, "run_id":7, "per_page":1""")))
            val next = first["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
            assertEquals("artifacts", next["action"]!!.jsonPrimitive.content)
            assertEquals("7", next["run_id"]!!.jsonPrimitive.content)
            val second = tool.execute("page2", next)
            assertFalse(second.isError)
            val body = data(second)["data"]!!.jsonObject
            assertEquals(JsonPrimitive(true), body["empty"])
            assertEquals(JsonPrimitive(false), body["has_more"])
        } finally {
            http.close()
        }
    }

    @Test fun missingCollectionsAreNotReportedAsSuccessfulEmptyResults() = runTest {
        val http = HttpClient(MockEngine { request -> if (request.url.encodedPath == "/repos/o/r/actions/runs/7") respond("""{"id":7,"head_sha":"$sha"}""") else respond("{}") })
        try {
            val result = GitHubTool("token", http).execute("missing", call("list_workflow_artifacts", """, "run_id":7"""))
            assertTrue(result.isError)
            assertEquals("GITHUB_UNEXPECTED_RESPONSE", result.errorCode)
        } finally {
            http.close()
        }
    }

    @Test fun aFailedJobBeyondTheSearchBudgetCanBeResumed() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/repos/o/r/actions/runs/7" -> respond("""{"id":7,"head_sha":"$sha"}""")
                    "/repos/o/r/actions/runs/7/jobs" -> {
                        val failed = request.url.parameters["page"] == "11"
                        respond("""{"total_count":1001,"jobs":[{"id":8,"run_id":7,"head_sha":"$sha","status":"completed","conclusion":"${if (failed) "failure" else "success"}"}]}""")
                    }
                    "/repos/o/r/actions/jobs/8/logs" -> respond("error: build failed")
                    else -> error("Unexpected request")
                }
            }
        )
        try {
            val tool = GitHubOperationTool("github_ci", GitHubTool("token", http), "failure-search")
            val first = data(tool.execute("first", call("failure", """, "run_id":7""")))
            assertEquals(JsonPrimitive("job_search_incomplete"), first["data"]!!.jsonObject["status"])
            val next = first["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
            assertEquals(JsonPrimitive(11), next["page"])
            val found = tool.execute("next", next)
            assertFalse(found.isError)
            assertEquals(JsonPrimitive("available"), data(found)["data"]!!.jsonObject["status"])
        } finally {
            http.close()
        }
    }

    @Test fun issuesHaveDirectInspectionAndDoNotIncludePullRequests() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("/repos/o/r/issues", request.url.encodedPath)
                assertEquals("all", request.url.parameters["state"])
                val items = if (request.url.parameters["page"] == "1") {
                    (1..30).joinToString(",") { """{"number":$it,"pull_request":{}}""" }
                } else {
                    """{"number":31,"title":"Real issue","state":"open"}"""
                }
                respond("[$items]")
            }
        )
        try {
            val tool = GitHubOperationTool("github_issue", GitHubTool("token", http), "issue-list")
            val first = data(tool.execute("issues", call("list", """, "state":"all"""")))
            assertEquals(JsonPrimitive(0), first["data"]!!.jsonObject["returned"])
            assertEquals(JsonPrimitive(true), first["data"]!!.jsonObject["has_more"])
            val next = first["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
            assertEquals(JsonPrimitive("list"), next["action"])
            assertTrue(GitHubAuthorizationBoundary.isReplayableRead("github_issue", next))
            val second = tool.execute("next", next)
            assertFalse(second.isError)
            val items = data(second)["data"]!!.jsonObject["items"]!!.jsonArray
            assertEquals(JsonPrimitive(31), items.single().jsonObject["number"])
        } finally {
            http.close()
        }
    }

    @Test fun directLogsFollowRedirectsWithoutSendingCredentialsToStorage() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.host == "api.github.com") {
                    assertEquals("Bearer private-token", request.headers[HttpHeaders.Authorization])
                    respond("", HttpStatusCode.Found, headersOf(HttpHeaders.Location, "https://logs.blob.core.windows.net/log.txt"))
                } else {
                    assertNull(request.headers[HttpHeaders.Authorization])
                    respond("first line\nerror: private-token\nlast line")
                }
            }
        )
        try {
            val tool = GitHubOperationTool("github_ci", GitHubTool("private-token", http), "logs")
            val result = tool.execute("logs", call("logs", """, "job_id":8, "start_line":2, "end_line":2"""))
            assertFalse(result.isError)
            val body = data(result)["data"]!!.jsonObject
            assertEquals(JsonPrimitive(true), body["log_complete"])
            val text = body["excerpts"]!!.jsonArray.single().jsonObject["text"]!!.jsonPrimitive.content
            assertTrue(text.contains("[REDACTED]"))
            assertFalse(text.contains("private-token"))
        } finally {
            http.close()
        }
    }

    @Test fun topLevelReviewPostsTheObservedHeadAndStaleReviewsNeverPost() = runTest {
        var currentHead = sha
        var posts = 0
        val http = HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Get) {
                    respond("""{"state":"open","head":{"sha":"$currentHead"}}""")
                } else {
                    posts++
                    assertEquals("/repos/o/r/pulls/1/reviews", request.url.encodedPath)
                    val body = Json.parseToJsonElement((request.body as io.ktor.http.content.TextContent).text).jsonObject
                    assertEquals(JsonPrimitive(sha), body["commit_id"])
                    assertEquals(JsonPrimitive("APPROVE"), body["event"])
                    assertFalse(body.containsKey("comments"))
                    respond("""{"id":9,"state":"APPROVED","commit_id":"$sha"}""")
                }
            }
        )
        try {
            val tool = GitHubOperationTool("github_pr", GitHubTool("token", http), "reviews")
            val input = call("review", """, "pull_number":1,"expected_head_sha":"$sha","event":"APPROVE"""")
            assertFalse(tool.execute("approve", input).isError)
            currentHead = "b".repeat(40)
            val stale = tool.execute("stale", input)
            assertEquals("GITHUB_PR_HEAD_CHANGED", stale.errorCode)
            assertFalse(stale.dispatched)
            assertEquals(1, posts)
        } finally {
            http.close()
        }
    }

    @Test fun failureDigestReturnsAnExecutableLogExpansion() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                when (request.url.encodedPath) {
                    "/repos/o/r/actions/runs/7" -> respond("""{"id":7,"head_sha":"$sha"}""")
                    "/repos/o/r/actions/runs/7/jobs" -> respond("""{"total_count":1,"jobs":[{"id":8,"run_id":7,"head_sha":"$sha","status":"completed","conclusion":"failure"}]}""")
                    "/repos/o/r/actions/jobs/8" -> respond("""{"id":8,"run_id":7,"head_sha":"$sha","status":"completed"}""")
                    "/repos/o/r/actions/jobs/8/logs" -> respond((1..90).joinToString("\n") { "error line $it" })
                    else -> error("Unexpected request")
                }
            }
        )
        try {
            val tool = GitHubOperationTool("github_ci", GitHubTool("token", http), "failure-expansion")
            val failure = data(tool.execute("failure", call("failure", """, "run_id":7""")))
            val next = failure["continuation_call"]!!.jsonObject["arguments"]!!.jsonObject
            assertEquals(JsonPrimitive("logs"), next["action"])
            assertEquals(JsonPrimitive(sha), next["expected_head_sha"])
            val expanded = tool.execute("expand", next)
            assertFalse(expanded.isError)
            val lines = data(expanded)["data"]!!.jsonObject["excerpts"]!!.jsonArray
            assertEquals(90, lines.size)
        } finally {
            http.close()
        }
    }

    @Test fun ciLostAcknowledgementCannotPublishAgainAcrossToolSessions() = runTest {
        val store = dev.chungjungsoo.gptmobile.data.github.GitHubRepositoryStateStore()
        var posts = 0
        val http = HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    posts++
                    throw java.io.IOException("Acknowledgement lost after dispatch")
                }
                if (request.url.encodedPath == "/repos/o/r/commits/work") respond("""{"sha":"$sha"}""") else respond("""{"id":8,"state":"active"}""")
            }
        )
        try {
            val input = call("dispatch_workflow", """, "workflow_id":"build.yml", "ref":"work", "expected_head_sha":"$sha"""")
            val first = GitHubTool("token", http, stateStore = store).execute("first", input)
            assertTrue(first.isError)
            val second = GitHubTool("token", http, stateStore = store).execute("recover", input)
            assertTrue(second.isError)
            assertEquals("RECONCILE_REQUIRED", second.errorCode)
            assertFalse(second.dispatched)
            assertEquals(1, posts)
        } finally {
            http.close()
        }
    }

    @Test fun ciReceiptIsReusedAndANewObservedRunAttemptHasItsOwnIdentity() = runTest {
        var posts = 0
        var attempt = 1
        val http = HttpClient(
            MockEngine { request ->
                if (request.method == HttpMethod.Post) {
                    posts++
                    respond("", HttpStatusCode.Created)
                } else {
                    respond("""{"id":7,"run_attempt":$attempt,"status":"completed","conclusion":"failure"}""")
                }
            }
        )
        try {
            val tool = GitHubTool("token", http)
            val input = call("rerun_failed_jobs", """, "run_id":7""")
            assertFalse(tool.execute("first", input).isError)
            val replay = tool.execute("replay", input)
            assertFalse(replay.isError)
            assertTrue(replay.sharedResult)
            assertFalse(replay.dispatched)
            assertEquals(1, posts)
            attempt = 2
            assertFalse(tool.execute("requested-new-attempt", input).isError)
            assertEquals(2, posts)
        } finally {
            http.close()
        }
    }

    @Test fun releaseExecutionAndReceiptSelectorsReachTheNativeBackend() = runTest {
        val backend = Backend()
        val tool = GitHubOperationTool("github_release", backend, "release-receipts")
        assertFalse(tool.execute("publish", call("publish", """, "tag_name":"v1.2.3", "execution_id":"explicit-request", "ref":"work", "release_strategy":"workflow", "inputs":{"target":"preview"}""")).isError)
        val publish = backend.calls.single()
        assertEquals("publish_release", publish["action"]!!.jsonPrimitive.content)
        assertEquals("explicit-request", publish["execution_id"]!!.jsonPrimitive.content)
        assertEquals("work", publish["ref"]!!.jsonPrimitive.content)
        assertFalse(tool.execute("receipt-status", call("status", """, "tag_name":"v1.2.3", "workflow_run_id":501""")).isError)
        assertEquals(JsonPrimitive(501), backend.calls.last()["workflow_run_id"])
    }

    private class Backend : AgentTool {
        override val definition = AgentToolDefinition("github", "Test backend", JsonObject(emptyMap()))
        val calls = mutableListOf<JsonObject>()
        var value: JsonElement = JsonObject(emptyMap())
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            calls += arguments
            return AgentToolResult(callId, ToolResultContent.Json(value), false)
        }
    }
}
