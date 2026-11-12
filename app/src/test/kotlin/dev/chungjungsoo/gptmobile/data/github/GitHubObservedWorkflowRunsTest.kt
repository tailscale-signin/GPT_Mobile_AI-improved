package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubObservedWorkflowRunsTest {
    private val sha = "a".repeat(40)
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject
    private fun list(run: String) = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"runs"},"result":{"workflow_runs":[$run]}}""")
    private fun status(run: String) = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"run","run_id":7},"result":$run}""")
    private fun run(attempt: Int, state: String, conclusion: String = "failure", head: String = sha) = """{"id":7,"run_attempt":$attempt,"head_sha":"$head","status":"$state","conclusion":"$conclusion"}"""

    @Test fun pendingRerunReplacesHistoricalFailure() {
        val current = GitHubObservedWorkflowRuns.current(listOf(list(run(1, "completed")), status(run(2, "in_progress", ""))), JsonPrimitive(sha)).single()
        assertEquals(JsonPrimitive(2), current["run_attempt"])
        assertEquals(JsonPrimitive("in_progress"), current["status"])
    }

    @Test fun legacyExcerptSuppliesOnlyItsObservedExactRevisionRunSelectors() {
        val original = list(run(1, "completed"))
        val legacy = JsonObject(original + ("result" to json("""{"observationTruncated":true,"excerpt":${original["result"]}}""")))
        assertEquals(JsonPrimitive(7), GitHubObservedWorkflowRuns.current(listOf(legacy), JsonPrimitive(sha)).single()["id"])
        assertTrue(GitHubObservedWorkflowRuns.current(listOf(legacy), JsonPrimitive("b".repeat(40))).isEmpty())
    }

    @Test fun olderListPageCannotRollBackANewerAttempt() {
        val current = GitHubObservedWorkflowRuns.current(listOf(status(run(2, "in_progress", "")), list(run(1, "completed"))), JsonPrimitive(sha)).single()
        assertEquals(JsonPrimitive(2), current["run_attempt"])
    }

    @Test fun sameAttemptUsesLastObservedState() {
        val current = GitHubObservedWorkflowRuns.current(listOf(list(run(1, "in_progress", "")), status(run(1, "completed", "success"))), JsonPrimitive(sha)).single()
        assertEquals(JsonPrimitive("success"), current["conclusion"])
    }

    @Test fun mismatchedNewObservationCannotResurrectOldRevision() {
        assertTrue(GitHubObservedWorkflowRuns.current(listOf(list(run(1, "completed")), status(run(2, "completed", head = "b".repeat(40)))), JsonPrimitive(sha)).isEmpty())
    }

    @Test fun failedUntrustedAndExcludedResponsesCannotProvideAWorkflow() {
        for (changes in listOf(mapOf("trusted" to JsonPrimitive(false)), mapOf("success" to JsonPrimitive(false)), mapOf("verification_eligible" to JsonPrimitive(false)))) {
            assertTrue(GitHubObservedWorkflowRuns.current(listOf(JsonObject(list(run(1, "completed")) + changes)), JsonPrimitive(sha)).isEmpty())
        }
        assertTrue(GitHubObservedWorkflowRuns.current(listOf(list(run(1, "completed"))), null).isEmpty())
    }

    @Test fun jobAndCheckIdsAreNotWorkflowRunIds() {
        val job = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"job"},"result":${run(1, "completed")}}""")
        assertTrue(GitHubObservedWorkflowRuns.current(listOf(job), JsonPrimitive(sha)).isEmpty())
    }

    @Test fun jobStatusDoesNotBecomeAWorkflowRun() {
        val job = json("""{"trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"status","job_id":7},"result":${run(1, "completed")}}""")
        assertTrue(GitHubObservedWorkflowRuns.current(listOf(job), JsonPrimitive(sha)).isEmpty())
    }

    @Test fun missingAttemptInNewReadbackDoesNotResurrectAnOldFailure() {
        val pending = json(run(1, "in_progress", "")) - "run_attempt"
        val current = GitHubObservedWorkflowRuns.current(listOf(list(run(1, "completed")), status(JsonObject(pending).toString())), JsonPrimitive(sha)).single()
        assertEquals(JsonPrimitive("in_progress"), current["status"])
    }
}
