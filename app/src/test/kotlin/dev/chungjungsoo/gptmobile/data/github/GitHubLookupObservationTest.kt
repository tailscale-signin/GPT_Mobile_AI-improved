package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubLookupObservationTest {
    private fun firstRead(task: String, operation: String) = requireNotNull(GitHubLookupRoute.request(task)).firstRead(
        listOf(GitHubOperationTool.definition(operation, "${operation}__mobile")),
        "owner/project",
        "feature/mobile"
    )

    @Test fun compactAndFullReadersUseTheActualIssueAndPullNumbers() {
        val issue = requireNotNull(firstRead("Show issue #17", "github_issue"))
        assertEquals("github_issue__mobile", issue.name)
        assertEquals(
            buildJsonObject {
                put("owner", "owner")
                put("repo", "project")
                put("action", "context")
                put("issue_number", 17)
            },
            issue.arguments
        )
        val pr = requireNotNull(firstRead("Summarize the changes in PR #120", "github_pr_inspect"))
        assertEquals("github_pr_inspect__mobile", pr.name)
        assertEquals(JsonPrimitive(120), pr.arguments["pull_number"])
        assertEquals(JsonPrimitive(true), pr.arguments["include_patches"])
        assertEquals(JsonPrimitive(false), pr.arguments["include_checks"])
        assertNull(firstRead("Show files in PR #42", "github_issue"))
        assertNull(GitHubLookupRoute.request("Show PR #99999999999999"))
    }

    @Test fun listsPreserveExplicitStateAndCountAndBindCommitsToTheSelectedRef() {
        for ((task, state) in listOf("List all open issues" to "open", "List closed issues" to "closed", "Show all issues" to "all")) {
            assertEquals(JsonPrimitive(state), requireNotNull(firstRead(task, "github_issue")).arguments["state"])
        }
        val recent = requireNotNull(firstRead("List the last 4 issues", "github_issue")).arguments
        assertEquals(JsonPrimitive("desc"), recent["direction"])
        assertEquals(JsonPrimitive("created"), recent["sort"])
        assertNull(requireNotNull(firstRead("Show PRs", "github_pr")).arguments["state"])
        assertEquals(JsonPrimitive("closed"), requireNotNull(firstRead("List closed PRs", "github_pr_inspect")).arguments["state"])
        val commits = requireNotNull(firstRead("Show the last 5 commits", "github_context_inspect")).arguments
        assertEquals(JsonPrimitive("feature/mobile"), commits["ref"])
        assertEquals(JsonPrimitive(5), commits["per_page"])
        assertEquals(JsonPrimitive(10), requireNotNull(firstRead("Show recent commits", "github_context")).arguments["per_page"])
        assertEquals(JsonPrimitive(100), requireNotNull(firstRead("Show the last 200 commits", "github_context")).arguments["per_page"])
        assertNull(GitHubLookupRoute.request("List the last 0 commits"))
        assertNull(GitHubLookupRoute.request("List closed issues labeled blocked"))
        assertNull(GitHubLookupRoute.request("Show failed workflow runs on another branch"))
        assertNull(GitHubLookupRoute.request("List the last 4 last 5 commits"))
    }

    @Test fun workflowReleaseAndTagInventoriesUseTheirOwnReadSchemas() {
        for ((task, action) in listOf("List workflows" to "workflows", "Show recent workflow runs" to "runs", "List releases" to "list", "Show all tags" to "tags")) {
            val family = if (action in setOf("workflows", "runs")) "github_ci" else "github_release"
            val call = requireNotNull(firstRead(task, family))
            assertEquals("${family}__mobile", call.name)
            assertEquals(JsonPrimitive(action), call.arguments["action"])
        }
    }

    @Test fun rejectsHiddenAmbiguousUnboundAndIncompatibleTools() {
        val request = requireNotNull(GitHubLookupRoute.request("List commits"))
        val first = GitHubOperationTool.definition("github_context_inspect", "github_context_inspect__one")
        val second = GitHubOperationTool.definition("github_context", "github_context__two")
        assertNull(request.firstRead(listOf(first, second), "o/r", "topic"))
        assertNull(request.firstRead(listOf(first.copy(modelVisible = false)), "o/r", "topic"))
        assertNull(request.firstRead(listOf(first), "o/r", null))
        assertNull(request.firstRead(listOf(first), "other", "topic"))
        val incompatible = first.copy(
            inputSchema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", false)
                put("properties", buildJsonObject {})
            }
        )
        assertNull(request.firstRead(listOf(incompatible), "o/r", "topic"))
    }
}
