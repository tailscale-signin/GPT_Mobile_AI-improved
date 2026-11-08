package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.ToolArgumentValidator
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.test.runTest
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
