package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIntegrationReportTest {
    private val task = "Test all authorized GitHub integration tools and exercise permitted writes with verified cleanup."
    private val sha = "a".repeat(40)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun tool(name: String, vararg actions: String) = AgentToolDefinition(
        name,
        name,
        json("""{"properties":{"action":{"enum":${JsonArray(actions.map(::JsonPrimitive))}}}}""")
    )
    private val tools = listOf(
        tool("github_context", "status", "resolve", "branches", "commits", "checks", "branch_dependencies"),
        tool("github_commit", "branch", "commit", "delete_branch"),
        tool("github_ci", "workflows", "runs", "run", "jobs", "job", "logs", "failure"),
        tool("github_issue", "list", "get", "create", "comment"),
        tool("github_pr", "list", "get", "files", "create", "merge")
    )
    private fun receipt(id: String, tool: String, arguments: JsonObject, result: JsonObject, success: Boolean = true) = buildJsonObject {
        put("receipt_id", id)
        put("call_id", id)
        put("binding", "accepted-task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", success)
        put("dispatched", true)
        put("evidence_origin", "LIVE")
        put("tool", tool)
        put("arguments", arguments)
        put("result", result)
        if (!success) put("error_code", "GITHUB_NOT_FOUND_OR_HIDDEN")
    }
    private fun source() = receipt("source", "github_context", json("""{"action":"status"}"""), json("""{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}"""))

    @Test fun everyUnresolvedCapabilityAppearsInThePrerequisiteLedgerBeyondThePreview() {
        val receipts = mapOf("source" to source())
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, tools, 204)
        val unresolved = (checkpoint.getValue("capabilities") as JsonArray).filterIsInstance<JsonObject>().filter {
            it.text("status") !in setOf("PASSED", "RECOVERED", "OUT_OF_SCOPE", "NOT_REQUESTED")
        }
        assertTrue(unresolved.size > 12)
        val rendered = GitHubIntegrationReport.render(task, receipts, tools, 204)
        val ledger = rendered.substringAfter("### Before Continue: remaining prerequisites").substringBefore("### Issues and exact observations")
        assertTrue(ledger.contains("All ${unresolved.size} unresolved capability rows"))
        unresolved.forEach { row -> assertTrue("Missing prerequisite: ${row.text("id")}", ledger.contains("${row.text("id")} (${row.text("status")})")) }
        assertTrue(ledger.contains("FIXTURE_APPROVAL_STATE_UNKNOWN"))
        assertTrue(ledger.contains("Effective task-bound approval decision"))
        assertTrue(ledger.contains("OWNED_FIXTURE_REQUIRED"))
        assertTrue(ledger.contains("Authorized cleanup operation"))
        assertTrue(ledger.contains("github_ci:logs"))
        assertTrue(ledger.contains("github_ci:failure"))
        assertTrue(ledger.contains("Run at the selected repository revision"))
        assertTrue(ledger.contains("completed job for logs or a failed completed run for failure analysis"))
        assertTrue(rendered.contains("Showing 12 of ${unresolved.size} unresolved capability rows; all ${unresolved.size} are included"))
        assertEquals(checkpoint, GitHubTestChecklist.checkpoint(task, receipts, tools, 204))
        assertFalse(GitHubTestChecklist.apiComplete(task, receipts, tools))
    }

    @Test fun publishedReportAndStopReasonUseTheSameTaskBoundApprovalDecision() {
        val receipts = mapOf("source" to source())
        for ((mode, state, blocker) in listOf(
            Triple(GitHubDecisionMode.AUTO_APPROVE, "READY", "EXECUTION_NOT_EXERCISED"),
            Triple(GitHubDecisionMode.ASK, "CONDITIONAL", "FIXTURE_APPROVAL_MODE_REQUIRED"),
            Triple(null, "UNKNOWN", "FIXTURE_APPROVAL_STATE_UNKNOWN")
        )) {
            val preflight = GitHubTestChecklist.fixtureApprovalPreflight(mode, "accepted-task", "owner/repo", "main", json("{}"))
            val (rendered, verdict) = GitHubEvidencePolicy.testing(receipts, 1, 0, task, tools, 204, fixturePreflight = preflight)
            val checklist = verdict.getValue("test_checklist").jsonObject
            assertEquals(state, checklist.getValue("fixture_approval_preflight").jsonObject.text("state"))
            val branch = (checklist.getValue("capabilities") as JsonArray).map { it.jsonObject }.single { it.text("id") == "github_commit:branch" }
            assertEquals(blocker, branch.text("blocker_code"))
            val stop = GitHubIntegrationReport.stopReason(task, receipts, tools, 204, checklist)
            assertEquals(mode == GitHubDecisionMode.ASK, stop.text("detail").contains("explicit fixture approval policy"))
            assertEquals(mode == GitHubDecisionMode.ASK, rendered.contains("FIXTURE_APPROVAL_MODE_REQUIRED"))
            assertFalse(verdict["completion_verified"] == JsonPrimitive(true))
        }
    }

    @Test fun progressAndFinalReportShareTheRequiredDenominatorWhenLifecycleStatusIsConditional() {
        val request = "Test all authorized GitHub integration tools read only."
        val available = listOf(tool("github_context", "status"), tool("github_repository", "status"))
        val receipts = mapOf("source" to source())
        val checkpoint = GitHubTestChecklist.checkpoint(request, receipts, available, 200)
        val capabilities = (checkpoint.getValue("capabilities") as JsonArray).map { it.jsonObject }
        val lifecycle = capabilities.single { it.text("id") == "github_repository:status" }
        assertEquals(JsonPrimitive(false), lifecycle["required"])
        assertEquals(JsonPrimitive("CONDITIONAL"), lifecycle["status"])

        val progress = GitHubTestChecklist.progressSummary(checkpoint, null)
        assertTrue(progress.startsWith("Checks: 1/1 verified"))
        assertFalse(progress.contains("need fixture approval"))
        assertTrue(progress.contains("All requested criteria are verified"))
        val report = GitHubIntegrationReport.render(request, receipts, available, 200, checkpoint = checkpoint)
        assertTrue(report.contains("1/1 required API capabilities verified"))
        assertTrue(report.contains("0 required and 1 conditional outside the required scope"))
        assertEquals(JsonPrimitive(0), checkpoint.getValue("preparation").jsonObject["required_pending_capability_count"])
        assertEquals(JsonPrimitive(1), checkpoint.getValue("preparation").jsonObject["conditional_pending_capability_count"])
        assertEquals(JsonPrimitive("ACCEPTANCE_COMPLETE"), GitHubIntegrationReport.stopReason(request, receipts, available, 200, checkpoint)["code"])
    }

    @Test fun requiredFixtureApprovalRemainsInTheProgressDenominatorAndCannotLookComplete() {
        val available = listOf(tool("github_context", "status"), tool("github_commit", "branch", "delete_branch"), tool("github_repository", "status"))
        val receipts = mapOf("source" to source())
        val approval = GitHubTestChecklist.fixtureApprovalPreflight(GitHubDecisionMode.ASK, "accepted-task", "owner/repo", "main", json("{}"))
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, available, 200, approval)
        val progress = GitHubTestChecklist.progressSummary(checkpoint, null)
        assertTrue(progress.startsWith("Checks: 1/3 verified; 2 need fixture approval"))
        assertFalse(progress.contains("All requested criteria are verified"))
        val preparation = checkpoint.getValue("preparation").jsonObject
        assertEquals(JsonPrimitive(2), preparation["required_pending_capability_count"])
        assertEquals(JsonPrimitive(1), preparation["conditional_pending_capability_count"])
        val fixture = preparation.getValue("blockers").let { it as JsonArray }.map { it.jsonObject }.single { it.text("code") == "FIXTURE_APPROVAL_MODE_REQUIRED" }
        assertEquals(JsonArray(listOf(JsonPrimitive("github_commit:branch"), JsonPrimitive("github_commit:delete_branch"))), fixture["required_capability_ids"])
        assertEquals(JsonArray(emptyList()), fixture["conditional_capability_ids"])
        assertEquals(JsonPrimitive("CAPABILITY_PREREQUISITES_MISSING"), GitHubIntegrationReport.stopReason(task, receipts, available, 200, checkpoint)["code"])
        assertEquals(1, receipts.size)
    }

    @Test fun remainingBudgetAndMissingPrerequisitesStaySeparateForEachCheckpoint() {
        val receipts = mapOf("source" to source())
        for (remaining in listOf(204, 48)) {
            val rendered = GitHubIntegrationReport.render(task, receipts, tools, remaining)
            assertTrue(rendered.contains("$remaining tool calls remain under the accepted budget"))
            assertTrue(rendered.contains("Continuing blocked capabilities also requires the inputs and approvals below"))
            assertTrue(rendered.contains("Continue preserves the existing approval decision and accepted budget"))
            assertTrue(rendered.contains("**CAPABILITY_PREREQUISITES_MISSING**"))
            assertFalse(rendered.contains("**TOOL_BUDGET_EXHAUSTED**"))
            assertFalse(rendered.contains("The accepted tool-call budget is exhausted"))
        }
        val exhausted = GitHubIntegrationReport.render(task, receipts, tools, 0)
        assertTrue(exhausted.contains("The accepted tool-call budget is exhausted"))
        assertTrue(exhausted.contains("**TOOL_BUDGET_EXHAUSTED**"))
        val reserved = GitHubIntegrationReport.render(task, receipts, tools, 2)
        assertTrue(reserved.contains("2 tool calls remain, with acquisition stopped to preserve verification and cleanup"))
        assertTrue(reserved.contains("**VERIFICATION_RESERVE_REACHED**"))
    }

    @Test fun shortenedOperationHistoryAndReceiptListsDeclareEveryOmittedCount() {
        val records = linkedMapOf("source" to source())
        for (job in 1..14) {
            val id = "failed-job-$job"
            records[id] = receipt(id, "github_ci", json("""{"action":"logs","job_id":$job,"run_id":10}"""), json("{}"), success = false)
        }
        for (attempt in 1..4) {
            val id = "extra-attempt-$attempt"
            records[id] = receipt(id, "github_ci", json("""{"action":"logs","job_id":1,"run_id":10}"""), json("{}"), success = false)
        }
        val rendered = GitHubIntegrationReport.render(task, records, tools, 48)
        assertEquals(15, GitHubTaskCoverage.rows(records).size)
        assertTrue(rendered.contains("Showing 12 of 15 observed operation groups, with failures first; 3 additional groups remain"))
        assertTrue(rendered.contains("Showing the latest 8 of 18 unsuccessful receipts; 10 earlier receipts remain"))
        assertTrue(rendered.contains("failed-job-1, extra-attempt-1, extra-attempt-2 (+2 more retained)"))
        assertEquals(19, records.size)
        assertFalse(GitHubTestChecklist.apiComplete(task, records, tools))
    }

    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()
}
