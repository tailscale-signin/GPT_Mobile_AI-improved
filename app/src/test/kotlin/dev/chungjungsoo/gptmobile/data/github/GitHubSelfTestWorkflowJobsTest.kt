package dev.chungjungsoo.gptmobile.data.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GitHubSelfTestWorkflowJobsTest {
    private val sha = "a".repeat(40)
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun receipt(action: String, result: String, args: String = "", scope: String = "owner/repo") = json(
        """{"binding":"task","repository":"$scope","trusted":true,"success":true,"tool":"github_ci","arguments":{"action":"$action"$args},"result":$result}"""
    )
    private fun status() = json("""{"binding":"task","repository":"owner/repo","trusted":true,"success":true,"tool":"github_context","arguments":{"action":"status"},"result":{"commit_sha":"$sha"}}""")
    private fun run(id: Int, head: String = sha, attempt: Int = 1) = """{"id":$id,"head_sha":"$head","run_attempt":$attempt,"status":"completed","conclusion":"success"}"""
    private fun runs(vararg ids: Int) = receipt("runs", """{"workflow_runs":[${ids.joinToString(",") { run(it) }}]}""")
    private fun job(id: Int, run: Int, conclusion: String = "success", attempt: Int = 1, steps: String = "") = """{"id":$id,"run_id":$run,"run_attempt":$attempt,"head_sha":"$sha","status":"completed","conclusion":"$conclusion"$steps}"""
    private fun jobs(run: Int, rows: String = "", attempt: Int = 1) = receipt("jobs", """{"jobs":[$rows]}""", """, "run_id":$run, "run_attempt":$attempt, "expected_head_sha":"$sha"""")
    private fun select(vararg receipts: JsonObject) = GitHubSelfTestWorkflowJobs.select(listOf(status()) + receipts, JsonPrimitive(sha))

    @Test fun emptyFirstRunLeavesTheNextObservedRunReadyForAJobQuery() {
        val selection = select(runs(10, 20), jobs(10))
        assertNull(selection.job)
        assertEquals(JsonPrimitive(20), selection.nextRun?.get("id"))
        assertEquals(JsonPrimitive(1), selection.nextRun?.get("run_attempt"))
    }

    @Test fun skippedAndEmptyStepJobsDoNotStrandAnotherExecutedRun() {
        val first = jobs(10, listOf(job(1, 10, "skipped"), job(2, 10, steps = """, "steps":[]""")).joinToString(","))
        assertEquals(JsonPrimitive(20), select(runs(10, 20), first).nextRun?.get("id"))
        val completed = select(runs(10, 20), first, jobs(20, job(3, 20)))
        assertEquals(JsonPrimitive(3), completed.job?.get("id"))
        assertEquals(JsonPrimitive(20), completed.job?.get("run_id"))
        assertNull(completed.nextRun)
    }

    @Test fun alternateDiscoveryUsesEachObservedCandidateOnceWithoutInventingAnother() {
        val selection = select(runs(10, 20, 30, 40), jobs(10), jobs(20), jobs(30))
        assertEquals(JsonPrimitive(40), selection.nextRun?.get("id"))
        assertNull(selection.job)
        assertNull(select(runs(10, 20, 30, 40), jobs(10), jobs(20), jobs(30), jobs(40)).nextRun)
    }

    @Test fun aFailedJobQueryDoesNotAuthorizeTryingAnotherResource() {
        val failed = JsonObject(jobs(10) + ("success" to JsonPrimitive(false)))
        assertNull(select(runs(10, 20), failed).nextRun)
    }

    @Test fun oldAttemptsCannotSupplyCurrentJobOrBlockANewAttemptRead() {
        val current = receipt("runs", """{"workflow_runs":[${run(10, attempt = 2)}]}""")
        val selection = select(current, jobs(10, job(1, 10)))
        assertNull(selection.job)
        assertEquals(JsonPrimitive(2), selection.nextRun?.get("run_attempt"))
    }

    @Test fun matchingShaFromAnotherRepositoryOrBindingCannotSupplyAJob() {
        val own = runs(10)
        val ownJobs = jobs(10, job(1, 10))
        for (foreign in listOf(
            JsonObject(ownJobs + ("repository" to JsonPrimitive("other/repo"))),
            JsonObject(ownJobs + ("binding" to JsonPrimitive("other-task"))),
            JsonObject(ownJobs + ("trusted" to JsonPrimitive(false))),
            JsonObject(ownJobs + ("verification_eligible" to JsonPrimitive(false)))
        )) {
            assertNull(select(own, foreign).job)
            assertEquals(JsonPrimitive(10), select(own, foreign).nextRun?.get("id"))
        }
    }

    @Test fun unrelatedRevisionAndMismatchedJobParentNeverSupplyLogs() {
        val unrelated = receipt("runs", """{"workflow_runs":[${run(10, head = "b".repeat(40))}]}""")
        assertNull(select(unrelated, jobs(10, job(1, 10))).job)
        assertNull(select(runs(10, 20), jobs(10, job(1, 20))).job)
        assertNull(select(jobs(10, job(1, 10))).job)
        assertNull(GitHubSelfTestWorkflowJobs.select(listOf(runs(10), jobs(10, job(1, 10))), JsonPrimitive(sha)).job)
        assertNull(GitHubSelfTestWorkflowJobs.select(listOf(jobs(10, job(1, 10))), null).job)
    }

    @Test fun laterIncompleteJobObservationCannotResurrectAnEarlierCompletedJob() {
        val completed = jobs(10, job(1, 10))
        val pending = jobs(10, job(1, 10).replace("completed", "in_progress"))
        assertNull(select(runs(10), completed, pending).job)
    }

    @Test fun unavailableLogCanSelectAnotherKnownExecutedJobWithoutRepeatingTheFailedOne() {
        val available = jobs(10, "${job(1, 10)},${job(2, 10)}")
        val attempted = receipt("logs", "{}", """, "job_id":1""")
        assertEquals(JsonPrimitive(2), select(runs(10), available, attempted).job?.get("id"))
    }

    @Test fun unknownConclusionAndUnexecutedStepsCannotEstablishJobExecution() {
        val unknown = job(1, 10, "")
        val skippedSteps = job(2, 10, steps = """, "steps":[{"status":"completed","conclusion":"skipped"}]""")
        assertNull(select(runs(10), jobs(10, "$unknown,$skippedSteps")).job)
    }
}
