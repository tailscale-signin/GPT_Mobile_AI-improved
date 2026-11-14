package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun laterReadSourceStatusCannotReplaceTheWorkspaceJobAuthority() {
        fun readSource(receipt: JsonObject) = JsonObject(
            receipt + mapOf("repository" to JsonPrimitive("other/repo"), "repository_role" to JsonPrimitive("READ_SOURCE"))
        )
        val own = listOf(status(), runs(10), jobs(10, job(1, 10)))
        val foreign = listOf(status(), runs(20), jobs(20, job(2, 20))).map(::readSource)
        val selection = GitHubSelfTestWorkflowJobs.select(own + foreign, JsonPrimitive(sha))
        assertEquals(JsonPrimitive(1), selection.job?.get("id"))
        assertEquals(listOf(JsonPrimitive(10)), selection.runs.map { it["id"] })
        assertNull(GitHubSelfTestWorkflowJobs.select(foreign, JsonPrimitive(sha)).job)
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

    @Test fun thirtySkippedRunsNeedOnlyOneJobsProbeBeforeDiscoveringAnotherPage() {
        val skipped = receipt("runs", """{"workflow_runs":[${(1..30).joinToString(",") { run(it).replace("success", "skipped") }}]}""")
        assertEquals(JsonPrimitive(1), select(skipped).nextRun?.get("id"))
        assertNull(select(skipped, jobs(1, job(1, 1, "skipped", steps = """, "steps":[]"""))).nextRun)
        assertEquals(JsonPrimitive(31), select(skipped, jobs(1), runs(31)).nextRun?.get("id"))
    }

    @Test fun cancelledNeutralAndUnknownRunsRemainInspectableAfterASkippedJobProbe() {
        for (conclusion in listOf("\"cancelled\"", "\"neutral\"", "null")) {
            val candidate = run(20).replace("\"success\"", conclusion)
            val observed = receipt("runs", """{"workflow_runs":[${run(10).replace("success", "skipped")},$candidate]}""")
            val skipped = jobs(10, job(1, 10, "skipped", steps = """, "steps":[]"""))
            val pending = select(observed, skipped)
            assertEquals("Run conclusion $conclusion must not hide its jobs", JsonPrimitive(20), pending.nextRun?.get("id"))
            assertNull(pending.job)
            val executed = job(2, 20, steps = """, "steps":[{"status":"completed","conclusion":"success"}]""")
            val inspected = select(observed, skipped, jobs(20, executed))
            assertEquals(JsonPrimitive(2), inspected.job?.get("id"))
            assertEquals(JsonPrimitive(20), inspected.job?.get("run_id"))
            assertNull(inspected.nextRun)
        }
    }

    @Test fun jobDependencyPagesAreRevisionBoundDurableAndIndependentlyLimited() {
        val tool = AgentToolDefinition("github_ci", "CI", json("""{"properties":{"action":{"enum":["runs","jobs","job","logs"]}}}"""))
        fun page(number: Int, head: String = sha, extra: String = "") = json("""{"tool":"github_ci","arguments":{"action":"runs","commit_sha":"$head","page":$number$extra}}""")
        val scopedRuns = JsonObject(runs(1) + ("arguments" to json("""{"action":"runs","commit_sha":"$sha"}""")))
        val exhausted = listOf(status(), scopedRuns, jobs(1))
        val issuer = exhausted[1]
        assertTrue(GitHubSelfTestWorkflowJobs.dependencyPage(page(2), listOf(tool), exhausted, issuer))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2, "b".repeat(40)), listOf(tool), exhausted, issuer))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2, extra = """, "owner":"other","repo":"repo""""), listOf(tool), exhausted, issuer))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2), emptyList(), exhausted, issuer))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2), listOf(tool), listOf(status(), runs(1, 2), jobs(1)), issuer))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2), listOf(tool), exhausted, JsonObject(issuer + ("binding" to JsonPrimitive("other-task")))))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2), listOf(tool), exhausted, JsonObject(issuer + ("repository" to JsonPrimitive("other/repo")))))
        for (foreign in listOf(
            JsonObject(issuer + ("tool" to JsonPrimitive("github_ci__other"))),
            JsonObject(issuer + ("arguments" to json("""{"action":"workflows","commit_sha":"$sha"}"""))),
            JsonObject(issuer + ("arguments" to json("""{"action":"runs","commit_sha":"${"b".repeat(40)}"}"""))),
            JsonObject(issuer + ("arguments" to json("""{"action":"runs"}""")))
        )) {
            assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2), listOf(tool), listOf(status(), foreign, jobs(1)), foreign))
        }
        val failed = JsonObject(jobs(1) + ("success" to JsonPrimitive(false)))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(2), listOf(tool), listOf(status(), runs(1), failed), issuer))
        val pages = (2..9).map { number -> receipt("runs", "{}", """, "commit_sha":"$sha", "page":$number""") }
        assertTrue(GitHubSelfTestWorkflowJobs.dependencyPage(page(9), listOf(tool), exhausted + pages.dropLast(1), issuer))
        assertFalse(GitHubSelfTestWorkflowJobs.dependencyPage(page(10), listOf(tool), exhausted + pages, issuer))
    }
}
