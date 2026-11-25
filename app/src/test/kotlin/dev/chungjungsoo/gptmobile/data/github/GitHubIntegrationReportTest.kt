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

    @Test fun terminalTimeWindowUsesLatestPageAndLeavesFixtureApprovalWithItsCriterion() {
        val records = mapOf("source" to source())
        val base = GitHubTestChecklist.checkpoint(task, records, tools, 128)
        val stopBudget = JsonObject((base.getValue("stage_budget") as JsonObject) + ("stop_reason" to JsonPrimitive("ACCEPTED_TASK_DEADLINE_REACHED")))
        val page = json("""{"next_call":{"tool":"github_ci","arguments":{"action":"runs","page":117,"per_page":100}}}""")
        val end = JsonObject(base + mapOf("stage_budget" to stopBudget, "pagination_resume" to page))
        val reason = GitHubIntegrationReport.stopReason(task, records, tools, 128, end)
        assertEquals(JsonPrimitive("AUTHORIZE_NEW_TIME_WINDOW"), reason["recovery_action"])
        val report = GitHubIntegrationReport.render(task, records, tools, 128, checkpoint = end)
        val next = report.lines().single { it.startsWith("**Next action**") }
        assertTrue(next.contains(GitHubExecutionLimits.RENEWAL_EXAMPLE))
        assertTrue(next.contains("page 117"))
        assertFalse(next.contains("fixture", ignoreCase = true))
        assertFalse(report.contains("INVENTORY_CONTINUATION_BLOCKED"))
    }

    @Test fun firstInventoryTotalEstimatesMinimumRemainingCallsUnderOriginalLimit() {
        val args = json("""{"action":"runs","per_page":100,"page":1}""")
        val result = json("""{"total_count":40000,"page":1,"per_page":100,"has_more":true,"workflow_runs":[{"id":14}]}""")
        val records = mapOf("page1" to receipt("page1", "github_ci", args, result))
        val row = GitHubTestChecklist.inventoryCoverage(records, 255).single().jsonObject
        assertEquals(JsonPrimitive(400L), row["minimum_pages"])
        assertEquals(JsonPrimitive(399L), row["minimum_remaining_page_calls"])
        assertEquals(JsonPrimitive(false), row["fits_remaining_call_budget"])
    }

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

    @Test fun preExecutionNoticeUsesTheRetainedDecisionBeforeAnyReadReceiptsExist() {
        for (mode in listOf(GitHubDecisionMode.ASK, GitHubDecisionMode.AUTO_APPROVE, null)) {
            val preflight = GitHubTestChecklist.fixtureApprovalPreflight(mode, "accepted-task", "owner/repo", "main", json("{}"))
            val before = preflight.toString()
            val checkpoint = GitHubTestChecklist.checkpoint(task, emptyMap(), tools, 200, preflight)
            val notice = checkpoint.getValue("preparation").jsonObject.text("pre_execution_notice")
            assertTrue(notice.contains("actual run/job receipts at the selected SHA"))
            assertEquals(mode == GitHubDecisionMode.ASK, notice.contains("existing exact approval interaction"))
            assertEquals(mode == null, notice.contains("blocked until"))
            assertEquals(before, preflight.toString())
            assertEquals(JsonPrimitive(false), preflight["mutation_dispatched"])
        }
        val readOnly = GitHubIntegrationPreparation.preflightSummary("Test GitHub integration read only", null)
        assertTrue(readOnly.contains("mutation coverage stays out of scope"))
        assertFalse(readOnly.contains("need"))
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

    @Test fun inspectOnlyToolsReportTheirCanonicalFamilyOutcome() {
        val name = "github_context_inspect__selected"
        val available = listOf(tool(name, "status"))
        val records = mapOf("source" to JsonObject(source() + ("tool" to JsonPrimitive(name))))
        val request = "Test all authorized GitHub integration tools read only."
        val checkpoint = GitHubTestChecklist.checkpoint(request, records, available, 200)
        assertEquals(listOf("github_context:status"), checkpoint.array("capabilities").map { it.text("id") })

        val rendered = GitHubIntegrationReport.render(request, records, available, 200, checkpoint = checkpoint)
        assertTrue(rendered, rendered.contains("| PASSED | 1 |"))
        assertTrue(rendered.contains("1/1 known authorized GitHub operation families have live observations"))
        assertTrue(rendered.contains("1/1 known authorized GitHub tools have live observations"))
        assertTrue(rendered.contains("### Tool family outcomes\n\n| Outcome | Families | Meaning |"))
        assertEquals(name, records.getValue("source").text("tool"))
    }

    @Test fun fullAndInspectToolsShareAnOutcomeWhileDiscoveryRetainsExactNames() {
        val names = listOf("github_context_inspect__selected", "github_context__selected")
        val available = names.map { tool(it, "status") }
        val records = names.mapIndexed { index, name ->
            "source-$index" to JsonObject(source() + ("tool" to JsonPrimitive(name)) + ("receipt_id" to JsonPrimitive("source-$index")))
        }.toMap().toMutableMap()
        records["catalog"] = receipt(
            "catalog",
            GitHubAuthorizedCatalog.TOOL,
            json("{}"),
            buildJsonObject {
                put("inventory_version", "version-1")
                put("authorized_tool_count", available.size)
                put("inventory_offset", 0)
                put("returned", available.size)
                put("has_more", false)
                put(
                    "authorized_inventory",
                    JsonArray(
                        available.map { definition ->
                            buildJsonObject {
                                put("name", definition.name)
                                put("schema_fingerprint", GitHubEvidenceBook.digest(definition.inputSchema.toString()))
                            }
                        }
                    )
                )
            }
        )
        val originalRecords = records.toMap()
        val request = "Test all authorized GitHub integration tools read only."
        val checkpoint = GitHubTestChecklist.checkpoint(request, records, available, 200)
        val catalog = checkpoint.getValue("authorized_catalog").jsonObject
        assertEquals(JsonPrimitive(true), catalog["denominator_final"])
        assertEquals(JsonArray(names.map(::JsonPrimitive)), catalog["observed_names"])
        assertEquals(JsonPrimitive(2), catalog["observed_github_tool_count"])

        val rendered = GitHubIntegrationReport.render(request, records, available, 200, checkpoint = checkpoint)
        assertTrue(rendered, rendered.contains("1/1 known authorized GitHub operation families have live observations"))
        assertTrue(rendered.contains("2/2 known authorized GitHub tools have live observations"))
        assertTrue(rendered.contains("2 authorized tool names observed, including 2 GitHub tools"))
        assertTrue(rendered.contains("| PASSED | 1 |"))
        assertFalse(rendered.contains("| PASSED | 2 |"))
        assertEquals(originalRecords, records)
    }

    @Test fun onlyExactAuthorizedVisibleLiveEligibleNamesExerciseAReportFamily() {
        val available = listOf(
            tool("github_context__selected", "status"),
            tool("github_context_inspect__selected", "status").copy(modelVisible = false)
        )
        val baseline = source()
        val variants = listOf(
            "foreign" to JsonObject(baseline + ("tool" to JsonPrimitive("github_context__other"))),
            "hidden" to JsonObject(baseline + ("tool" to JsonPrimitive("github_context_inspect__selected"))),
            "ineligible" to JsonObject(baseline + ("tool" to JsonPrimitive("github_context__selected")) + ("verification_eligible" to JsonPrimitive(false))),
            "untrusted" to JsonObject(baseline + ("tool" to JsonPrimitive("github_context__selected")) + ("trusted" to JsonPrimitive(false))),
            "cached" to JsonObject(baseline + ("tool" to JsonPrimitive("github_context__selected")) + ("evidence_origin" to JsonPrimitive("LOCAL")))
        )
        for ((reason, observation) in variants) {
            val records = mapOf(reason to observation)
            val rendered = GitHubIntegrationReport.render(task, records, available, 200)
            assertTrue("$reason must not exercise an exact authorized tool: $rendered", rendered.contains("0/1 known authorized GitHub tools have live observations"))
            assertTrue(reason, rendered.contains("0/1 known authorized GitHub operation families have live observations"))
            assertEquals(observation, records.getValue(reason))
        }
        val selected = JsonObject(baseline + ("tool" to JsonPrimitive("github_context__selected")))
        val rendered = GitHubIntegrationReport.render(task, mapOf("source" to selected), available, 200)
        assertTrue(rendered.contains("1/1 known authorized GitHub operation families have live observations"))
        assertTrue(rendered.contains("1/1 known authorized GitHub tools have live observations"))
    }

    private fun JsonObject.array(key: String) = (get(key) as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
    private fun JsonObject.text(key: String) = (get(key) as? JsonPrimitive)?.contentOrNull.orEmpty()

    @Test fun providerFailureReplacesTheIntegrationActionAndPreservesReportEvidence() {
        val records = mapOf("source" to source())
        val approval = GitHubTestChecklist.fixtureApprovalPreflight(GitHubDecisionMode.ASK, "accepted-task", "owner/repo", "main", json("{}"))
        val checkpoint = GitHubTestChecklist.checkpoint(task, records, tools, 128, approval)
        val rendered = GitHubIntegrationReport.render(task, records, tools, 128, checkpoint = checkpoint)
        assertEquals(1, rendered.lines().count { it.startsWith("**Next action**") })
        val failed = buildJsonObject {
            put(
                "original_failure",
                buildJsonObject {
                    put("code", "TLS_FAILURE")
                    put("remote_acceptance", "UNKNOWN")
                    put("dispatched", true)
                    put("invocation_id", "integration-attempt-42")
                    put("resume_allowed", false)
                }
            )
            put("recovery_action", "CONTINUE_FROM_THE_FIRST_UNVERIFIED_CRITERION")
            put("task_outcome", "PARTIAL")
        }
        val result = GitHubEffectiveRecovery.publicationText(rendered, failed, records)
        val next = result.lines().single { it.startsWith("**Next action**") }
        assertTrue(next.contains("Review the failed provider request"))
        assertTrue(next.contains("integration-attempt-42"))
        assertTrue(next.contains("1 retained successful read receipts"))
        assertFalse(next.contains("fixture", ignoreCase = true))
        assertTrue(result.contains("### Verification details"))
        assertTrue(result.contains("### Repository inventory and stop reason"))
        assertTrue(result.contains("FIXTURE_APPROVAL_MODE_REQUIRED"))
        assertTrue(result.endsWith("The failed request remains paused."))
    }
}
