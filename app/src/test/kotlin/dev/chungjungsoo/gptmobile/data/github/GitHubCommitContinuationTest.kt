package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubCommitContinuationTest {
    private val sha = "a".repeat(40)
    private val published = "Commit $sha was approved and published. Continue from the saved task using this exact commit. Subscribe with github_watch_checks when CI is pending; do not repeat the commit."
    private fun wrapped(text: String) = "GitHub workspace: o/r\nBranch: test-branch\n\n$text"

    @Test fun wrappedControlsPreserveTheTaskWhileNewRequestsDoNot() {
        for (prompt in listOf("continue", "retry failed stage", published)) {
            assertTrue(GitHubTaskContinuity.reuse(wrapped(prompt), "test all GitHub tools"))
            assertTrue(TaskRecoveryAction.fromText(wrapped(prompt)) != null)
        }
        assertEquals(sha, GitHubTaskContinuity.publishedCommit(wrapped(published)))
        assertNull(GitHubTaskContinuity.publishedCommit(published + " Also delete the branch."))
        assertNull(GitHubTaskContinuity.publishedCommit(published.replace(sha, "8")))
        assertFalse(GitHubTaskContinuity.reuse(wrapped("start a new test"), "test all tools"))
        assertTrue(GitHubStatusRequest.matches(wrapped("status")))
        assertFalse(GitHubStatusRequest.matches(wrapped("status and fix the build")))
    }

    @Test fun diagnosticsDistinguishAssistantFailuresFromRepositoryFailures() {
        assertTrue(GitHubTaskContinuity.diagnostic("Why are you failing?"))
        assertTrue(GitHubTaskContinuity.diagnostic(wrapped("Explain why the reviewer failed")))
        assertFalse(GitHubTaskContinuity.diagnostic("Why is CI failing?"))
        assertFalse(GitHubTaskContinuity.diagnostic("Explain why the Android build failed"))
    }

    @Test fun commitResumeRefreshesExactShaAndOnlyWatchesMatchingRuns() {
        val tools = listOf("github_context", "github_ci", "github_watch_checks", "github_commit").map { AgentToolDefinition(it, "tool", JsonObject(emptyMap())) }
        val receipts = linkedMapOf<String, JsonObject>()
        val actions = mutableListOf<String>()
        repeat(5) { index ->
            val call = requireNotNull(GitHubCommitContinuation.next(sha, "chat:69", tools, receipts))
            assertFalse(call.name == "github_commit")
            actions += (call.arguments["action"] as? JsonPrimitive)?.content ?: "watch"
            if (index in 1..2 || index == 4) assertEquals(JsonPrimitive(sha), call.arguments["commit_sha"])
            if (index == 3) assertEquals(JsonPrimitive(sha), call.arguments["head_sha"])
            if (index == 4) assertEquals(JsonPrimitive(41651916151L), call.arguments["run_id"])
            receipts[call.callId] = buildJsonObject {
                put("call_id", call.callId)
                put("trusted", true)
                put("success", true)
                put("tool", call.name)
                put("arguments", call.arguments)
                put("result", if (index == 3) Json.parseToJsonElement("""{"workflow_runs":[{"id":8,"head_sha":"${"b".repeat(40)}","status":"in_progress"},{"id":41651916151,"head_sha":"$sha","status":"in_progress"}]}""").jsonObject else JsonObject(emptyMap()))
            }
        }
        assertEquals(listOf("status", "commit", "checks", "runs", "watch"), actions)
        assertNull(GitHubCommitContinuation.next(sha, "chat:69", tools, receipts))
        assertEquals(JsonPrimitive("status"), GitHubCommitContinuation.next(sha, "chat:71", tools, receipts)?.arguments?.get("action"))
    }

    @Test fun failedReadsAreRecordedOnceAndUnauthorizedReceiptsCannotSatisfyResume() {
        val tools = listOf(AgentToolDefinition("github_context", "Read", JsonObject(emptyMap())))
        val first = requireNotNull(GitHubCommitContinuation.next(sha, "same", tools, emptyMap()))
        val failed = buildJsonObject {
            put("call_id", first.callId)
            put("trusted", true)
            put("success", false)
        }
        assertEquals(JsonPrimitive("commit"), GitHubCommitContinuation.next(sha, "same", tools, mapOf("failed" to failed))?.arguments?.get("action"))
        val excluded = JsonObject(failed + ("verification_eligible" to JsonPrimitive(false)))
        assertEquals(first, GitHubCommitContinuation.next(sha, "same", tools, mapOf("excluded" to excluded)))
    }
}
