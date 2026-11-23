package dev.chungjungsoo.gptmobile.data.github

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCopilotTasksTest {
    private val sha = "a".repeat(40)
    private val request = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put("request_id", "review-change-42")
        put("prompt", "Implement the reviewed change and add its regression test.")
        put("base_ref", "main")
        put("expected_base_sha", sha)
        put("create_pull_request", true)
    }

    @Test fun emptyTaskDiscoveryIsAValidReadAndMalformedTaskListsNeverTriggerCreation() = runTest {
        val directory = Files.createTempDirectory("copilot-empty-list").toFile()
        var payload = """{"tasks":[]}"""
        var reads = 0
        val http = HttpClient(
            MockEngine { req ->
                assertEquals(HttpMethod.Get, req.method)
                assertEquals("/agents/repos/owner/repo/tasks", req.url.encodedPath)
                reads++
                respond(payload)
            }
        )
        try {
            val client = client(http, GitHubRepositoryStateStore(directory), "list-turn")
            val args = buildJsonObject {
                put("owner", "owner")
                put("repo", "repo")
                put("action", "copilot_tasks")
            }
            val empty = client.execute("copilot_tasks", args).jsonObject
            assertEquals(Json.parseToJsonElement("[]"), empty["tasks"])
            assertEquals(JsonPrimitive(false), empty["has_more"])
            val assessed = dev.chungjungsoo.gptmobile.data.agent.tool.GitHubPaginationBoundary().assessPage("github_pr", args, empty)
            assertEquals(null, assessed.errorCode)
            assertEquals(JsonPrimitive(true), assessed.data["complete"])
            for (malformed in listOf("{}", """{"tasks":{}}""", """{"tasks":[null]}""", """{"tasks":[{}]}""")) {
                payload = malformed
                assertTrue(malformed, runCatching { client.execute("copilot_tasks", args) }.isFailure)
            }
            assertEquals(5, reads)
        } finally {
            http.close()
            directory.deleteRecursively()
        }
    }

    @Test fun acceptedNativeTaskResumesAfterStoreAndClientRestartWithoutGatewayOrAnotherPost() = runTest {
        val directory = Files.createTempDirectory("copilot-receipt").toFile()
        val calls = mutableListOf<Pair<HttpMethod, String>>()
        var state = "queued"
        val http = HttpClient(
            MockEngine { req ->
                calls += req.method to req.url.encodedPath
                assertEquals("api.github.com", req.url.host)
                assertEquals("2026-03-10", req.headers["X-GitHub-Api-Version"])
                assertEquals("Bearer github_pat_test", req.headers[HttpHeaders.Authorization])
                when (req.url.encodedPath) {
                    "/repos/owner/repo/git/ref/heads/main" -> respond("""{"object":{"type":"commit","sha":"$sha"}}""")
                    "/agents/repos/owner/repo/tasks" -> {
                        assertEquals(HttpMethod.Post, req.method)
                        assertEquals(Json.parseToJsonElement("""{"prompt":"Implement the reviewed change and add its regression test.","base_ref":"main","create_pull_request":true}"""), Json.parseToJsonElement((req.body as TextContent).text))
                        respond("""{"id":"remote-task-123","state":"queued","html_url":"https://github.com/copilot/tasks/remote-task-123"}""", HttpStatusCode.Created)
                    }
                    "/agents/repos/owner/repo/tasks/remote-task-123" -> respond("""{"id":"remote-task-123","state":"$state","sessions":[{"id":"session-1","task_id":"remote-task-123","state":"$state","prompt":"private instructions","head_ref":"copilot/change"}],"artifacts":[{"provider":"github","type":"pull","data":{"id":9}}]}""")
                    else -> error("Unexpected route ${req.url}")
                }
            }
        )
        try {
            val accepted = client(http, GitHubRepositoryStateStore(directory), "first-turn").execute("copilot_create_task", request).jsonObject
            assertEquals(JsonPrimitive("remote-task-123"), accepted["task_id"])
            assertEquals(JsonPrimitive("GITHUB_CLOUD"), accepted["execution_owner"])
            assertEquals(JsonPrimitive("ANDROID_DIRECT"), accepted["orchestration_owner"])
            assertEquals(JsonPrimitive(false), accepted["gateway_required"])
            assertEquals(JsonPrimitive(true), accepted["remote_continues_without_app"])

            val resumed = client(http, GitHubRepositoryStateStore(directory), "new-turn-after-restart")
            val replayed = resumed.execute("copilot_create_task", JsonObject(request + ("request_id" to JsonPrimitive("changed-request-id")))).jsonObject
            assertEquals(JsonPrimitive("remote-task-123"), replayed["task_id"])
            state = "completed"
            val status = resumed.execute("copilot_task_status", target("request_id", "review-change-42")).jsonObject
            assertEquals(JsonPrimitive("REMOTE_COMPLETED"), status["status"])
            assertEquals(JsonPrimitive(false), status["acceptance_verified"])
            assertEquals(JsonPrimitive(false), status["ci_verified"])
            assertEquals(JsonPrimitive(false), status["merge_authorized"])
            assertFalse(status.toString().contains("private instructions"))
            assertEquals(1, calls.count { it.first == HttpMethod.Post })
            assertEquals(1, calls.count { it.second.endsWith("heads/main") })
            assertEquals(1, calls.count { it.second.endsWith("remote-task-123") })
        } finally {
            http.close()
            directory.deleteRecursively()
        }
    }

    @Test fun lostCreationResponseIsNeverRepeatedAfterRestartOrChangedRequestId() = runTest {
        val directory = Files.createTempDirectory("copilot-uncertain").toFile()
        var posts = 0
        val http = HttpClient(
            MockEngine { req ->
                if (req.method == HttpMethod.Post) {
                    posts++
                    throw IOException("Connection lost after GitHub accepted the task")
                }
                respond("""{"object":{"type":"commit","sha":"$sha"}}""")
            }
        )
        try {
            val failure = runCatching { client(http, GitHubRepositoryStateStore(directory), "first").execute("copilot_create_task", request) }.exceptionOrNull()
            assertTrue(failure is GitHubExecutionBlocked)
            val restarted = client(http, GitHubRepositoryStateStore(directory), "second")
            val replay = restarted.execute("copilot_create_task", JsonObject(request + ("request_id" to JsonPrimitive("new-id")))).jsonObject
            assertEquals(JsonPrimitive("RECONCILE_REQUIRED"), replay["status"])
            val status = restarted.execute("copilot_task_status", target("request_id", "review-change-42")).jsonObject
            assertEquals(JsonPrimitive("RECONCILE_REQUIRED"), status["status"])
            assertEquals(JsonPrimitive(false), status["dispatched"])
            assertEquals(1, posts)
        } finally {
            http.close()
            directory.deleteRecursively()
        }
    }

    @Test fun staleRevisionAndUnsupportedInstallationCredentialNeverCreateWork() = runTest {
        var posts = 0
        var reads = 0
        val http = HttpClient(
            MockEngine { req ->
                if (req.method == HttpMethod.Post) posts++ else reads++
                respond("""{"object":{"type":"commit","sha":"${"b".repeat(40)}"}}""")
            }
        )
        try {
            val stale = runCatching { client(http, GitHubRepositoryStateStore(), "stale").execute("copilot_create_task", request) }.exceptionOrNull()
            assertTrue(stale is IllegalArgumentException)
            assertEquals(0, posts)
            assertEquals(1, reads)
            val installation = GitHubWorkspaceClient("ghs_installation", http, connectionUid = "connection", conversationId = "42", taskId = "installation", stateStore = GitHubRepositoryStateStore())
            assertTrue(runCatching { installation.execute("copilot_create_task", request) }.isFailure)
            assertEquals(0, posts)
            assertEquals(1, reads)
        } finally {
            http.close()
        }
    }

    @Test fun waitingAndUnknownStatesRequireActionInsteadOfClaimingSuccessAndStatusIsFresh() = runTest {
        var state = "waiting_for_user"
        var reads = 0
        val http = HttpClient(
            MockEngine { req ->
                assertEquals(HttpMethod.Get, req.method)
                assertEquals("/agents/repos/owner/repo/tasks/task%2Fopaque", req.url.encodedPath)
                reads++
                respond("""{"id":"task/opaque","state":"$state"}""")
            }
        )
        try {
            val client = client(http, GitHubRepositoryStateStore(), "read")
            val waiting = client.execute("copilot_task_status", target("task_id", "task/opaque")).jsonObject
            assertEquals(JsonPrimitive("HUMAN_ACTION_REQUIRED"), waiting["status"])
            assertEquals(JsonPrimitive(true), waiting["user_input_required"])
            state = "new-server-state"
            val unknown = client.execute("copilot_task_status", target("task_id", "task/opaque")).jsonObject
            assertEquals(JsonPrimitive("RECONCILE_REQUIRED"), unknown["status"])
            assertEquals(JsonPrimitive("UNKNOWN_REMOTE_STATE"), unknown["reconciliation_reason"])
            assertEquals(JsonPrimitive(true), unknown["requires_reconciliation"])
            assertEquals(JsonPrimitive(false), unknown["acceptance_verified"])
            assertEquals(2, reads)
        } finally {
            http.close()
        }
    }

    private fun client(http: HttpClient, store: GitHubRepositoryStateStore, turn: String) = GitHubWorkspaceClient(
        "github_pat_test",
        http,
        connectionUid = "connection",
        conversationId = "42",
        taskId = turn,
        stateStore = store,
        durableIdentity = "account"
    )

    private fun target(key: String, value: String) = buildJsonObject {
        put("owner", "owner")
        put("repo", "repo")
        put(key, value)
    }
}
