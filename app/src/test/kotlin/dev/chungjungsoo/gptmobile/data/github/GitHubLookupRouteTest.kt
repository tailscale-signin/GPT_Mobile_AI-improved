package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubLookupRouteTest {
    @Test fun inventoryAndNumberedReadsPlanOnlyTheirMatchingOperation() {
        val requests = mapOf(
            "Please list all open issues in this repo." to GitHubLookupRoute.ISSUES,
            "Show me issue #42" to GitHubLookupRoute.ISSUE,
            "Show open PRs in the GitHub repo" to GitHubLookupRoute.PULL_REQUESTS,
            "Summarize the changes in PR #15" to GitHubLookupRoute.PULL_REQUEST,
            "List git branches" to GitHubLookupRoute.BRANCHES,
            "Show the last 5 commits" to GitHubLookupRoute.COMMITS,
            "List workflows" to GitHubLookupRoute.WORKFLOWS,
            "Could you list the latest workflow runs?" to GitHubLookupRoute.RUNS,
            "What is the latest release?" to GitHubLookupRoute.RELEASES,
            "Show all tags" to GitHubLookupRoute.TAGS
        )
        for ((request, route) in requests) {
            assertEquals(request, route, GitHubLookupRoute.from("GitHub workspace: owner/project\nBranch: work\n\n$request"))
            assertFalse(request, GitHubStatusRequest.matches(request))
            val step = GitHubOperationPlanner.plan(request).single().jsonObject
            assertEquals(request, JsonPrimitive(route.operation), step["tool"])
            assertEquals(request, JsonPrimitive(route.action), step["action"])
            assertEquals(request, JsonPrimitive(route.step), step["id"])
        }
    }

    @Test fun compoundAndQuotedRequestsKeepTheGeneralPlanner() {
        for (request in listOf(
            "Show the repository status and open PRs",
            "List open issues and fix them",
            "List open issues\nThen close them",
            "Explain why the latest workflow failed",
            "Translate 'List open issues' into French",
            "Review the changes before publishing a release",
            "Inspect the latest PR for regressions"
        )) {
            assertNull(request, GitHubLookupRoute.from(request))
        }
    }

    @Test fun substringsDoNotRequestPullRequestsOrCiOrPublicationTools() {
        val request = "Inspect the repository staging preview and decision logic"
        val steps = GitHubOperationPlanner.plan(request).map { it.jsonObject["id"] }
        assertFalse(JsonPrimitive("pr_context") in steps)
        assertFalse(JsonPrimitive("ci_failure_digest") in steps)
        assertFalse("github_release" in dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool.taskTools(request))
    }

    @Test fun redesignAndRebuildRequestsReachValidatedEngineeringWork() {
        for (request in listOf("Redesign GitHub mode", "Look at the repository and rebuild the chat", "Upgrade the repository UI")) {
            val steps = GitHubOperationPlanner.plan(request).map { it.jsonObject["id"] }
            assertTrue(request, JsonPrimitive("stage_patch") in steps)
            assertTrue(request, JsonPrimitive("validation") in steps)
            assertFalse(request, JsonPrimitive("publish_release") in steps)
            assertFalse(request, GitHubRequestCompletion.informational(request))
            assertEquals(request, GitHubTaskComplexity.ENGINEERING, GitHubEngineeringStrategy.predict(GitHubIntelligenceLevel.INTELLIGENT, request).complexity)
        }
    }
}
