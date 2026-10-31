package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import dev.chungjungsoo.gptmobile.data.permissions.GitHubAuthorizationBoundary
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubToolCompletenessTest {
    private fun args(vararg values: Pair<String, String>) = buildJsonObject {
        put("owner", "o")
        put("repo", "r")
        values.forEach { (key, value) -> put(key, value) }
    }

    @Test fun issueHistoryUsesTimelineAndPreservesEveryRecordWithContinuation() = runTest {
        val client = GitHubInspection { path ->
            assertEquals("/repos/o/r/issues/7/timeline?per_page=30&page=1", path)
            Json.parseToJsonElement((1..30).joinToString(",", "[", "]") { "{\"id\":$it,\"event\":\"labeled\"}" })
        }
        val result = client.execute("issue_events", "/repos/o/r", args("issue_number" to "7"), 1)
        assertEquals(30, result["items"]!!.jsonArray.size)
        assertEquals("2", result["continuation"]!!.jsonObject["page"]!!.jsonPrimitive.content)
    }

    @Test fun missingBranchPolicyRemainsUnknown() = runTest {
        val client = GitHubInspection { path ->
            if (path == "/repos/o/r/branches/topic") Json.parseToJsonElement("""{"name":"topic","commit":{"sha":"abc"}}""") else error("403")
        }
        val result = client.execute("branch_context", "/repos/o/r", args("branch" to "topic"), 1)
        assertEquals("false", result["protection"]!!.jsonObject["available"].toString())
        assertEquals("unknown", result["rules"]!!.jsonObject["state"]!!.jsonPrimitive.content)
    }

    @Test fun issueWritesAreNeverClassifiedAsReplayableReads() {
        for (action in listOf("create", "update")) {
            assertFalse(GitHubAuthorizationBoundary.isReplayableRead("github_issue", args("action" to action)))
        }
        assertTrue(GitHubAuthorizationBoundary.isReplayableRead("github_issue", args("action" to "history")))
        assertFalse(GitHubAuthorizationBoundary.isReplayableRead("github_commit", args("action" to "delete_branch")))
    }

    @Test fun issueUpdateRejectsStaleRevisionWithoutMutation() = runTest {
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                if (request.method != HttpMethod.Get) writes++
                respond("""{"number":7,"id":123,"updated_at":"new"}""")
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http)
            assertTrue(runCatching { client.execute("update_issue", args("issue_number" to "7", "expected_updated_at" to "old", "title" to "Updated")) }.isFailure)
            assertEquals(0, writes)
        } finally {
            http.close()
        }
    }

    @Test fun issueUpdateReturnsFullIdentityAndBeforeAfterReceipt() = runTest {
        val http = HttpClient(
            MockEngine { request ->
                respond(if (request.method == HttpMethod.Get) """{"id":123,"number":7,"body":"old body","updated_at":"old"}""" else """{"id":123,"number":7,"node_id":"I_123","body":"new body","updated_at":"new"}""")
            }
        )
        try {
            val result = GitHubWorkspaceClient("token", http).execute("update_issue", args("issue_number" to "7", "expected_updated_at" to "old", "body" to "new body")).jsonObject
            assertEquals("old body", result["previous"]!!.jsonObject["body"]!!.jsonPrimitive.content)
            assertEquals("I_123", result["issue"]!!.jsonObject["node_id"]!!.jsonPrimitive.content)
            assertEquals("new", result["expected_updated_at"]!!.jsonPrimitive.content)
            assertEquals("issue_context", result["continuation"]!!.jsonObject["action"]!!.jsonPrimitive.content)
        } finally {
            http.close()
        }
    }

    @Test fun commitSearchCannotEscapeSelectedRepository() = runTest {
        var calls = 0
        val client = GitHubInspection {
            calls++
            error("must not dispatch")
        }
        assertTrue(runCatching { client.execute("search_commits", "/repos/o/r", args("query" to "fix repo:another/private"), 1) }.isFailure)
        assertEquals(0, calls)
    }

    @Test fun defaultBranchDeletionStopsBeforeMutation() = runTest {
        var calls = 0
        val http = HttpClient(
            MockEngine { request ->
                calls++
                assertEquals(HttpMethod.Get, request.method)
                assertEquals("/repos/o/r", request.url.encodedPath)
                respond("""{"default_branch":"main"}""")
            }
        )
        try {
            assertTrue(runCatching { GitHubWorkspaceClient("token", http).execute("delete_branch", args("branch" to "main", "expected_head_sha" to "a".repeat(40))) }.isFailure)
            assertEquals(1, calls)
        } finally {
            http.close()
        }
    }

    @Test fun branchDeletionRequiresCompleteDependenciesAndFreshHead() = runTest {
        val sha = "a".repeat(40)
        for (obstacle in listOf("none", "rules", "pulls", "runs", "unmerged", "moved")) {
            var deletes = 0
            val http = HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    val body = when {
                        request.method == HttpMethod.Delete -> {
                            deletes++
                            "{}"
                        }
                        path == "/repos/o/r" -> """{"default_branch":"main"}"""
                        path.endsWith("/branches/topic") && "/rules/" !in path -> """{"protected":false}"""
                        "/rules/" in path -> if (obstacle == "rules") """[{"type":"deletion"}]""" else "[]"
                        path.endsWith("/pulls") -> if (obstacle == "pulls") """[{"number":7}]""" else "[]"
                        path.endsWith("/actions/runs") -> if (obstacle == "runs") """{"total_count":1}""" else """{"total_count":0}"""
                        path.endsWith("/commits/main") -> """{"sha":"${"b".repeat(40)}"}"""
                        "/compare/" in path -> if (obstacle == "unmerged") """{"status":"diverged"}""" else """{"status":"behind"}"""
                        "/git/ref/" in path -> """{"object":{"sha":"${if (obstacle == "moved") "c".repeat(40) else sha}"}}"""
                        else -> error(path)
                    }
                    respond(body)
                }
            )
            try {
                val outcome = runCatching { GitHubWorkspaceClient("token", http).execute("delete_branch", args("branch" to "topic", "expected_head_sha" to sha)) }
                assertEquals(obstacle, obstacle == "none", outcome.isSuccess)
                assertEquals(obstacle, if (obstacle == "none") 1 else 0, deletes)
            } finally {
                http.close()
            }
        }
    }

    @Test fun definitionsAreInTheDirectSearchContract() {
        val schema = GitHubOperationTool.definition("github_search").inputSchema
        assertTrue(schema["properties"]!!.jsonObject["mode"].toString().contains("definitions"))
        assertTrue("github_issue" in GitHubOperationTool.taskTools("GitHub create an issue"))
    }

    @Test fun statusPaginationRevalidatesMutableChecksAtTheSameCommit() = runTest {
        val sha = "a".repeat(40)
        var statusCalls = 0
        val http = HttpClient(
            MockEngine { request ->
                val body = when {
                    request.url.encodedPath.endsWith("/check-runs") -> """{"total_count":0,"check_runs":[]}"""
                    request.url.encodedPath.endsWith("/status") -> {
                        statusCalls++
                        val state = if (statusCalls == 1) "pending" else "success"
                        """{"total_count":1,"state":"$state","statuses":[{"state":"$state","context":"build"}]}"""
                    }
                    else -> """{"sha":"$sha"}"""
                }
                respond(body)
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http, conditionalReads = true)
            val arguments = args("ref" to sha)
            val first = client.execute("get_commit_status_summary", arguments).jsonObject
            val second = client.execute("get_commit_status_summary", arguments).jsonObject
            assertEquals("pending", first["status_details"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals("success", second["status_details"]!!.jsonObject["state"]!!.jsonPrimitive.content)
            assertEquals(2, statusCalls)
        } finally {
            http.close()
        }
    }

    @Test fun commitDetailHonorsPinnedCommitOverMutableRef() = runTest {
        val sha = "a".repeat(40)
        val http = HttpClient(
            MockEngine { request ->
                assertEquals("/repos/o/r/commits/$sha", request.url.encodedPath)
                assertEquals("30", request.url.parameters["per_page"])
                respond("""{"sha":"$sha","files":[]}""")
            }
        )
        try {
            GitHubWorkspaceClient("token", http).execute("get_commit_detail", args("ref" to "main", "commit_sha" to sha))
        } finally {
            http.close()
        }
    }

    @Test fun annotatedTagInspectionResolvesTheObservedObjectInsteadOfAMutableName() = runTest {
        val client = GitHubInspection { path ->
            Json.parseToJsonElement(
                when (path) {
                    "/repos/o/r/git/ref/tags/v1" -> """{"object":{"type":"tag","sha":"annotation"}}"""
                    "/repos/o/r/git/tags/annotation" -> """{"message":"Release notes","object":{"type":"commit","sha":"pinned"}}"""
                    "/repos/o/r/commits/pinned" -> """{"sha":"pinned"}"""
                    "/repos/o/r/releases/tags/v1" -> """{"tag_name":"v1","body":"Release notes"}"""
                    else -> error("Unexpected mutable lookup: $path")
                }
            )
        }
        val result = client.execute("tag_context", "/repos/o/r", args("tag_name" to "v1"), 1)
        assertEquals("pinned", result["commit"]!!.jsonObject["sha"]!!.jsonPrimitive.content)
        assertEquals("Release notes", result["annotation"]!!.jsonObject["message"]!!.jsonPrimitive.content)
    }
}
