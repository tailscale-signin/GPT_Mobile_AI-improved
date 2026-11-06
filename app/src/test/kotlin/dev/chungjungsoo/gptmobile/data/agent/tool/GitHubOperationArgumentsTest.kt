package dev.chungjungsoo.gptmobile.data.agent.tool

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubOperationArgumentsTest {
    private fun args(value: String) = Json.parseToJsonElement(value) as JsonObject

    @Test fun placeholderCommitIsRejectedForLookupAndReadsButRealHeadsRemainUsable() {
        for (operation in listOf("github_context", "github_read")) {
            val extra = if (operation == "github_read") ",\"ranges\":[{\"path\":\"README.md\",\"start_line\":1,\"end_line\":30}]" else ""
            val bad = args("{\"commit_sha\":\"${"0".repeat(40)}\"$extra}")
            assertTrue(GitHubOperationArguments.errors(operation, bad).any { "placeholder" in it })
            val good = args("{\"commit_sha\":\"${"a".repeat(40)}\"$extra}")
            assertTrue(GitHubOperationArguments.errors(operation, good).isEmpty())
        }
    }

    @Test fun missingIssueNumberOffersDiscoveryAndOversizedReadsOfferChunks() {
        assertTrue(GitHubOperationArguments.errors("github_issue", args("""{"action":"headers"}""")).any { "action=list" in it })
        assertTrue(GitHubOperationArguments.errors("github_read", args("""{"ranges":[{"path":"README.md","start_line":1,"end_line":4000}]}""")).any { "2001..4000" in it })
        assertTrue(GitHubOperationArguments.errors("github_read", args("""{"ranges":[{"path":"README.md","start_line":1,"end_line":2000},{"path":"README.md","start_line":2001,"end_line":4000}]}""")).isEmpty())
    }

    @Test fun malformedWorkflowSelectorIsRejectedBeforeDispatch() {
        for (selector in listOf("0", "-1", "999999999999999999999999", "../ci.yml", "")) {
            assertTrue(GitHubOperationArguments.errors("github_ci", args("""{"action":"definitions","workflow_id":"$selector"}""")).any { "workflow_id" in it })
        }
        assertTrue(GitHubOperationArguments.errors("github_ci", args("""{"action":"definitions","workflow_id":".github/workflows/ci.yml"}""")).isEmpty())
    }
}
