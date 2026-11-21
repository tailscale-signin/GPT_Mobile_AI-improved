package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubTargetedInventoryTest {
    @Test fun everyIssueCursorRejectionKeepsItsTypedCauseAndNeverQueriesAnotherPage() = runTest {
        var calls = 0
        val inspection = GitHubInspection { error("Cursor rejection cannot fall back to a REST page") }
        val query: suspend (String, JsonObject) -> JsonObject = { _, _ ->
            calls++
            Json.parseToJsonElement("""{"data":{"repository":{"issues":{"totalCount":2,"pageInfo":{"hasNextPage":true,"endCursor":"private-relay-cursor"},"nodes":[{"id":"issue-1","number":1,"state":"OPEN"}]}}}}""").jsonObject
        }
        val first = inspection.execute("list_issues", "/repos/owner/repo", JsonObject(emptyMap()), 1, query)
        val next = first.getValue("continuation").jsonObject
        val states = GitHubInspection.InvalidIssueContinuation.State.entries
        for (state in states) {
            val arguments = when (state) {
                GitHubInspection.InvalidIssueContinuation.State.MISSING_AFTER -> JsonObject(next - "after")
                GitHubInspection.InvalidIssueContinuation.State.MALFORMED_AFTER -> JsonObject(next + ("after" to JsonPrimitive("private-malformed")))
                GitHubInspection.InvalidIssueContinuation.State.FILTER_MISMATCH -> JsonObject(next + ("state" to JsonPrimitive("closed")))
                else -> next
            }
            val page = when (state) {
                GitHubInspection.InvalidIssueContinuation.State.PAGE_MISMATCH -> 3
                GitHubInspection.InvalidIssueContinuation.State.INITIAL_PAGE_WITH_AFTER -> 1
                else -> 2
            }
            val graphQl = query.takeUnless { state == GitHubInspection.InvalidIssueContinuation.State.AUTHENTICATED_CONNECTION_REQUIRED }
            val failure = runCatching { inspection.execute("list_issues", "/repos/owner/repo", arguments, page, graphQl) }.exceptionOrNull()
            assertTrue(state.name, failure is GitHubInspection.InvalidIssueContinuation)
            val typed = failure as GitHubInspection.InvalidIssueContinuation
            assertEquals(state, typed.state)
            assertTrue("after" in typed.fields)
            assertFalse(typed.message.orEmpty().contains("private"))
        }
        assertEquals(1, calls)
    }

    @Test fun authenticatedIssuesUseTypedCursorWithFrozenFilters() = runTest {
        var calls = 0
        val inspection = GitHubInspection { error("Authenticated issue pagination should not use a mixed REST page") }
        val query: suspend (String, JsonObject) -> JsonObject = { document, variables ->
            assertTrue(document.contains("issues(first:"))
            assertEquals(JsonPrimitive(10), variables["first"])
            calls++
            if (calls == 2) assertEquals(JsonPrimitive("relay-cursor"), variables["after"])
            Json.parseToJsonElement("""{"data":{"repository":{"issues":{"totalCount":11,"pageInfo":{"hasNextPage":${calls == 1},"endCursor":"relay-cursor"},"nodes":[{"id":"issue-$calls","number":$calls,"state":"OPEN"}]}}}}""").jsonObject
        }
        val args = buildJsonObject { put("per_page", 10) }
        val first = inspection.execute("list_issues", "/repos/owner/repo", args, 1, query)
        val next = first.getValue("continuation").jsonObject
        assertTrue(runCatching { inspection.execute("list_issues", "/repos/owner/repo", JsonObject(next + ("per_page" to JsonPrimitive(30))), 2, query) }.isFailure)
        val last = inspection.execute("list_issues", "/repos/owner/repo", next, 2, query)
        assertEquals(JsonPrimitive(false), last["has_more"])
        assertEquals(JsonPrimitive("issue"), (last.getValue("items") as JsonArray).single().jsonObject["resource_type"])
        assertEquals(2, calls)
    }

    @Test fun fullIssuePageKeepsItsFilterOrderAndSizeWhileExcludingPullRequests() = runTest {
        val paths = mutableListOf<String>()
        val inspection = GitHubInspection { path ->
            paths += path
            if ("page=2" in path) {
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("id", 101)
                            put("number", 8)
                        }
                    )
                )
            } else {
                JsonArray(
                    (1..100).map { id ->
                        buildJsonObject {
                            put("id", id)
                            put("pull_request", JsonObject(emptyMap()))
                        }
                    }
                )
            }
        }
        val arguments = buildJsonObject {
            put("action", "list")
            put("state", "all")
            put("per_page", 100)
            put("sort", "created")
            put("direction", "desc")
        }
        assertTrue(ToolArgumentValidator.errors(arguments, GitHubOperationTool.definition("github_issue").inputSchema).isEmpty())
        val first = inspection.execute("list_issues", "/repos/owner/repo", arguments, 1)
        assertEquals(JsonPrimitive(0), first["returned"])
        assertEquals(JsonPrimitive(100), first["upstream_returned"])
        assertEquals(JsonPrimitive(true), first["has_more"])
        assertEquals(JsonPrimitive("MORE"), first["traversal_state"])
        val continuation = first.getValue("continuation").jsonObject
        assertEquals(JsonPrimitive(100), continuation["per_page"])
        assertEquals(JsonPrimitive("desc"), continuation["direction"])
        val second = inspection.execute("list_issues", "/repos/owner/repo", continuation, 2)
        assertEquals(JsonPrimitive(false), second["has_more"])
        assertEquals(JsonPrimitive(1), second["returned"])
        assertTrue(paths.all { it.contains("state=all&sort=created&direction=desc&per_page=100") })
        assertFalse((second["items"] as JsonArray).single().jsonObject.containsKey("pull_request"))
    }

    @Test fun boundedSmokeReadsRequestFullRecentIssueAndReleasePagesWithoutBroadeningOtherSelectors() {
        val issue = GitHubOperationTool.definition("github_issue")
        val nextIssue = requireNotNull(GitHubSelfTestReads.next(listOf(issue), emptyList()))
        assertEquals(JsonPrimitive(100), nextIssue.arguments["per_page"])
        assertEquals(JsonPrimitive("desc"), nextIssue.arguments["direction"])
        assertEquals(JsonPrimitive("all"), nextIssue.arguments["state"])
        val release = GitHubOperationTool.definition("github_release")
        val nextRelease = requireNotNull(GitHubSelfTestReads.next(listOf(release), emptyList()))
        assertEquals(JsonPrimitive(100), nextRelease.arguments["per_page"])
        assertTrue(ToolArgumentValidator.errors(nextRelease.arguments, release.inputSchema).isEmpty())
    }
}
