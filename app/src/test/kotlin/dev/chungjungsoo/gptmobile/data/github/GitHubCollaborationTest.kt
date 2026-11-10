package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationArguments
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpMethod
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCollaborationTest {
    private val sha = "a".repeat(40)
    private fun args(action: String = "ready", extra: JsonObject = JsonObject(emptyMap())) = buildJsonObject {
        put("owner", "o")
        put("repo", "r")
        put("action", action)
        put("pull_number", 7)
        put("expected_head_sha", sha)
        extra.forEach { (key, value) -> put(key, value) }
    }
    private fun parent(draft: Boolean = false, head: String = sha) = Json.parseToJsonElement("""{"number":7,"node_id":"PR_7","state":"open","draft":$draft,"head":{"sha":"$head"}}""").jsonObject

    @Test fun everyCollaborationActionIsModelVisibleAndClassifiedAsAWrite() {
        for ((tool, actions, prefix) in listOf(Triple("github_pr", GitHubCollaboration.prActions, "pr_"), Triple("github_issue", GitHubCollaboration.issueActions, "issue_"))) {
            for (action in actions) {
                val input = buildJsonObject {
                    put("action", action)
                    put("pull_number", 7)
                    put("issue_number", 7)
                    put("expected_head_sha", sha)
                    put("expected_updated_at", "2026-11-10T00:00:00Z")
                    put("thread_id", "THREAD_7")
                    put("review_id", 4)
                    put("comment_id", 5)
                    put("body", "Reviewed change")
                    put("expected_comment_updated_at", "2026-11-10T00:00:00Z")
                    put("label", "reviewed")
                    put("reaction", "heart")
                    put("reaction_id", 8)
                    listOf("reviewers", "team_reviewers", "labels", "assignees").forEach { put(it, JsonArray(listOf(JsonPrimitive("reviewed")))) }
                }.let { JsonObject(it - if (tool == "github_pr") setOf("issue_number", "expected_updated_at") else setOf("pull_number", "expected_head_sha")) }
                assertEquals("$tool/$action", emptyList<String>(), ToolArgumentValidator.errors(input, GitHubOperationTool.definition(tool).inputSchema))
                assertEquals(emptyList<String>(), GitHubOperationArguments.errors(tool, input))
                assertEquals(JsonPrimitive(prefix + action), GitHubOperationTool.nativeArguments(tool, input)?.get("action"))
                assertTrue(prefix + action in GitHubWorkspaceClient.writeActions)
                assertFalse(prefix + action in GitHubWorkspaceClient.readActions)
            }
        }
    }

    @Test fun draftReadyAndBackUseGraphqlAndVerifyTheExactHead() = runTest {
        var draft = true
        val mutations = mutableListOf<String>()
        val http = HttpClient(
            MockEngine { request ->
                if (request.url.encodedPath == "/graphql") {
                    val body = (request.body as TextContent).text
                    mutations += body
                    draft = "convertPullRequestToDraft" in body
                    respond("""{"data":{"result":{"pullRequest":{"isDraft":$draft}}}}""")
                } else {
                    assertEquals("/repos/o/r/pulls/7", request.url.encodedPath)
                    respond(parent(draft).toString())
                }
            }
        )
        try {
            val client = GitHubWorkspaceClient("token", http, persistentState = false)
            for ((action, expected) in listOf("ready" to false, "draft" to true)) {
                val native = requireNotNull(GitHubOperationTool.nativeArguments("github_pr", args(action)))
                val result = client.execute(native.getValue("action").toString().trim('"'), native).jsonObject
                assertEquals(JsonPrimitive(expected), result["draft"])
                assertEquals(JsonPrimitive(true), result["remote_state_verified"])
                assertEquals(JsonPrimitive(sha), result["head_sha"])
            }
            assertEquals(2, mutations.size)
            assertTrue(mutations.first().contains("markPullRequestReadyForReview"))
            assertTrue(mutations.last().contains("convertPullRequestToDraft"))
        } finally {
            http.close()
        }
    }

    @Test fun nativeReviewerLabelAndCommentWritesHaveVerifiedReadbacks() = runTest {
        for (action in listOf("request_reviewers", "add_labels", "edit_comment")) {
            var changed = false
            var writes = 0
            val http = HttpClient(
                MockEngine { request ->
                    val path = request.url.encodedPath
                    if (request.method != HttpMethod.Get) {
                        writes++
                        changed = true
                    }
                    when {
                        path == "/repos/o/r/pulls/7" -> respond(parent().toString())
                        path.endsWith("/requested_reviewers") -> respond(if (changed) """{"users":[{"login":"reviewer"}],"teams":[]}""" else """{"users":[],"teams":[]}""")
                        path.endsWith("/labels") -> respond(if (changed) """[{"name":"reviewed"}]""" else "[]")
                        path.endsWith("/comments/5") -> respond("""{"id":5,"issue_url":"https://api.github.com/repos/o/r/issues/7","updated_at":"${if (changed) "new" else "old"}","body":"${if (changed) "Revised comment" else "Original comment"}"}""")
                        else -> error("Unexpected request: ${request.method} ${request.url}")
                    }
                }
            )
            try {
                val input = args(
                    action,
                    buildJsonObject {
                        when (action) {
                            "request_reviewers" -> put("reviewers", JsonArray(listOf(JsonPrimitive("reviewer"))))
                            "add_labels" -> put("labels", JsonArray(listOf(JsonPrimitive("reviewed"))))
                            else -> {
                                put("comment_id", 5)
                                put("expected_comment_updated_at", "old")
                                put("body", "Revised comment")
                            }
                        }
                    }
                )
                val result = GitHubWorkspaceClient("token", http, persistentState = false).execute("pr_$action", input).jsonObject
                assertEquals(action, JsonPrimitive(true), result["remote_state_verified"])
                assertEquals(1, writes)
            } finally {
                http.close()
            }
        }
    }

    @Test fun draftFieldCannotDisappearInsideAnOrdinaryUpdate() = runTest {
        var writes = 0
        val http = HttpClient(
            MockEngine { request ->
                if (request.method != HttpMethod.Get) writes++
                respond(parent().toString())
            }
        )
        try {
            val input = args(
                "update",
                buildJsonObject {
                    put("title", "Title")
                    put("draft", false)
                }
            )
            assertTrue(GitHubOperationArguments.errors("github_pr", input).any { "action=ready" in it })
            val failure = runCatching { GitHubWorkspaceClient("token", http, persistentState = false).execute("update_pull_request", input) }.exceptionOrNull()
            assertNotNull(failure)
            assertEquals(0, writes)
        } finally {
            http.close()
        }
    }

    @Test fun staleHeadBlocksBeforeReviewerNotifications() = runTest {
        var writes = 0
        val failure = runCatching {
            GitHubCollaboration.execute(
                "/repos/o/r",
                "pr_request_reviewers",
                args(extra = buildJsonObject { put("reviewers", JsonArray(listOf(JsonPrimitive("reviewer")))) }),
                "https://api.github.com",
                request = { _, method, _ ->
                    if (method != HttpMethod.Get) writes++
                    parent(head = "b".repeat(40))
                },
                graphQl = { _, _ -> error("No GraphQL") }
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, writes)
    }

    @Test fun foreignThreadCannotBeResolvedBySupplyingItsId() = runTest {
        var mutations = 0
        val failure = runCatching {
            GitHubCollaboration.execute(
                "/repos/o/r",
                "pr_resolve_thread",
                args(extra = buildJsonObject { put("thread_id", "THREAD_FOREIGN") }),
                "https://api.github.com",
                request = { _, _, _ -> parent() },
                graphQl = { query, _ ->
                    if (query.startsWith("mutation")) mutations++
                    Json.parseToJsonElement("""{"data":{"node":{"id":"THREAD_FOREIGN","isResolved":false,"pullRequest":{"id":"PR_OTHER","headRefOid":"$sha"}}}}""").jsonObject
                }
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, mutations)
    }

    @Test fun staleCommentCannotOverwriteAnotherEdit() = runTest {
        var writes = 0
        val failure = runCatching {
            GitHubCollaboration.execute(
                "/repos/o/r",
                "pr_edit_comment",
                args(
                    extra = buildJsonObject {
                        put("comment_id", 5)
                        put("body", "New text")
                        put("expected_comment_updated_at", "old")
                    }
                ),
                "https://api.github.com",
                request = { path, method, _ ->
                    if (method != HttpMethod.Get) writes++
                    if (path.endsWith("/comments/5")) Json.parseToJsonElement("""{"id":5,"issue_url":"https://api.github.com/repos/o/r/issues/7","updated_at":"new"}""") else parent()
                },
                graphQl = { _, _ -> error("No GraphQL") }
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(0, writes)
    }

    @Test fun reviewerWriteWithoutMatchingReadbackCannotClaimCompletion() = runTest {
        var writes = 0
        val failure = runCatching {
            GitHubCollaboration.execute(
                "/repos/o/r",
                "pr_request_reviewers",
                args(extra = buildJsonObject { put("reviewers", JsonArray(listOf(JsonPrimitive("reviewer")))) }),
                "https://api.github.com",
                request = { path, method, _ ->
                    if (method != HttpMethod.Get) writes++
                    if (path.endsWith("requested_reviewers")) Json.parseToJsonElement("""{"users":[],"teams":[]}""") else parent()
                },
                graphQl = { _, _ -> error("No GraphQL") }
            )
        }.exceptionOrNull()
        assertNotNull(failure)
        assertEquals(1, writes)
    }

    @Test fun autoMergeReceiptDoesNotClaimThePullRequestMerged() = runTest {
        var enabled = false
        val result = GitHubCollaboration.execute(
            "/repos/o/r",
            "pr_enable_auto_merge",
            args(),
            "https://api.github.com",
            request = { _, _, _ -> JsonObject(parent() + ("auto_merge" to if (enabled) buildJsonObject { put("merge_method", "squash") } else JsonNull)) },
            graphQl = { query, _ ->
                assertTrue(query.contains("enablePullRequestAutoMerge"))
                enabled = true
                buildJsonObject { put("accepted", true) }
            }
        )
        assertEquals(JsonPrimitive("AUTO_MERGE_ENABLED_NOT_MERGED"), result["completion"])
        assertNull(result["merged"])
    }
}
