package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIntegrationPreparationTest {
    private val task = "Test all authorized GitHub integration tools and verify cleanup."
    private val sha = "a".repeat(40)
    private val fixture = "codex-self-test/" + GitHubEvidenceBook.digest("tasksource").take(20)
    private fun json(text: String) = Json.parseToJsonElement(text).jsonObject
    private fun tool(name: String, vararg actions: String) = AgentToolDefinition(
        name,
        name,
        buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("action", buildJsonObject { put("enum", JsonArray(actions.map(::JsonPrimitive))) }) })
        }
    )
    private val tools = listOf(tool("github_context", "status", "resolve", "branch_dependencies"), tool("github_commit", "branch", "delete_branch"), tool("github_issue", "list", "create"))
    private fun receipt(id: String, name: String, args: String, result: String) = buildJsonObject {
        put("receipt_id", id)
        put("call_id", id)
        put("binding", "task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", true)
        put("dispatched", true)
        put("evidence_origin", "LIVE")
        put("tool", name)
        put("arguments", json(args))
        put("result", json(result))
    }
    private fun source() = receipt("source", "github_context", """{"action":"status"}""", """{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}""")
    private fun active() = linkedMapOf(
        "source" to source(),
        "creation" to receipt(
            "creation",
            "github_commit",
            """{"action":"branch","branch":"$fixture","from_branch":"main","expected_head_sha":"$sha"}""",
            """{"branch":"$fixture","created":true,"receipt_verified":true,"head_sha":"$sha"}"""
        )
    )

    @Test fun acceptedCeilingAndLiveCounterSurviveCheckpointResumeAndFinalReport() = runBlocking {
        val stored = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("budget-task", "owner/repo", "main", emptySet(), load = { stored[it] }, save = { id, value -> stored[id] = value })
        book.retainTaskContract(task, 7, tools, 256)
        book.retainProgress(task, tools, remainingToolCalls = 203)
        val key = "verification_current_" + GitHubEvidenceBook.digest("budget-task").take(24)
        val checkpoint = stored.getValue(key).getValue("test_checklist").jsonObject
        val budget = checkpoint.getValue("stage_budget").jsonObject
        assertEquals(JsonPrimitive(256), budget["effective_call_limit"])
        assertEquals(JsonPrimitive(53), budget["used_calls"])
        assertEquals(JsonPrimitive(203), budget["remaining_calls"])
        assertEquals(JsonPrimitive("AUTHORIZED_RUN_CONTRACT"), budget["limit_source"])
        assertEquals(JsonPrimitive("LIVE_EXECUTION_LEDGER"), budget["remaining_source"])
        assertEquals(budget, checkpoint.getValue("preparation").jsonObject["budget"])

        val serialized = stored.mapValues { json(it.value.toString()) }
        val resumed = GitHubEvidenceBook("budget-task", "owner/repo", "main", emptySet(), load = { serialized[it] })
        resumed.retainTaskContract(task, 7, tools, 512)
        val resumedBudget = resumed.stageBudget(emptyMap(), null, GitHubSelfTestFixtures.minimumCalls(tools))
        assertEquals(JsonPrimitive(256), resumedBudget["effective_call_limit"])
        assertEquals(JsonPrimitive(53), resumedBudget["used_calls"])
        assertEquals(JsonPrimitive(203), resumedBudget["remaining_calls"])
        assertEquals(JsonPrimitive("RETAINED_EXECUTION_CHECKPOINT"), resumedBudget["remaining_source"])
        val (text, report) = GitHubEvidencePolicy.testing(emptyMap(), 1000, 0, task, tools, stageBudget = resumedBudget)
        assertEquals(resumedBudget, report.getValue("test_checklist").jsonObject["stage_budget"])
        assertTrue(text.contains("Effective tool-call limit: 256 (AUTHORIZED_RUN_CONTRACT). Calls used: 53. Budget remaining: 203"))
        assertEquals(JsonPrimitive(false), report["completion_verified"])
    }

    @Test fun executableReserveAndTaskIdentityStayAttachedToTheirCounter() = runBlocking {
        var contract: JsonObject? = null
        val book = GitHubEvidenceBook("budget-task", "owner/repo", "main", emptySet(), save = { id, value -> if (id == "task_contract") contract = value })
        book.retainTaskContract(task, 7, tools, 256, executionCallLimit = 254)
        val budget = GitHubChecklistBudget.checkpoint(emptyMap(), 201, acceptedContract = contract)
        assertEquals(JsonPrimitive(256), budget["configured_call_limit"])
        assertEquals(JsonPrimitive(254), budget["effective_call_limit"])
        assertEquals(JsonPrimitive(53), budget["used_calls"])
        val other = JsonObject(requireNotNull(contract) + ("source_message_id" to JsonPrimitive(8)))
        val unrelated = GitHubChecklistBudget.checkpoint(emptyMap(), acceptedContract = other, retainedBudget = budget)
        assertNull(unrelated["remaining_calls"])
        assertNull(unrelated["used_calls"])
    }

    @Test fun exhaustedRetainedCounterAlsoControlsTheResumedStopReason() = runBlocking {
        val stored = linkedMapOf<String, JsonObject>()
        val book = GitHubEvidenceBook("exhausted-task", "owner/repo", "main", emptySet(), load = { stored[it] }, save = { id, value -> stored[id] = value })
        book.retainTaskContract(task, 7, tools, 256)
        book.retainProgress(task, tools, remainingToolCalls = 0)
        val serialized = stored.mapValues { json(it.value.toString()) }
        val resumed = GitHubEvidenceBook("exhausted-task", "owner/repo", "main", emptySet(), load = { serialized[it] })
        val budget = resumed.stageBudget(emptyMap(), null, GitHubSelfTestFixtures.minimumCalls(tools))
        val (text, report) = GitHubEvidencePolicy.testing(emptyMap(), 1000, 0, task, tools, stageBudget = budget)
        assertEquals(JsonPrimitive(0), budget["remaining_calls"])
        assertTrue(text.contains("**TOOL_BUDGET_EXHAUSTED**"))
        assertTrue(text.contains("Calls used: 256. Budget remaining: 0"))
        val preparation = report.getValue("test_checklist").jsonObject.getValue("preparation").jsonObject
        assertEquals(JsonPrimitive(false), preparation.getValue("budget").jsonObject["acquisition_allowed"])
        assertEquals(JsonPrimitive(0), preparation["ready_read_count"])
        assertEquals(JsonPrimitive(false), report["completion_verified"])
        val (partialText, partialReport) = GitHubEvidencePolicy.partial(emptyMap(), 1000, 0, task, "GITHUB_EXTRACTION_TIMEOUT", tools, stageBudget = budget)
        assertTrue(partialText.contains("**TOOL_BUDGET_EXHAUSTED**"))
        assertEquals(budget, partialReport.getValue("test_checklist").jsonObject["stage_budget"])
        assertEquals(JsonPrimitive(0), partialReport.getValue("test_checklist").jsonObject.getValue("preparation").jsonObject["ready_read_count"])
        assertEquals(JsonPrimitive(false), partialReport["completion_verified"])
    }

    @Test fun manualApprovalAndUnsupportedResourceLifecyclesExposeExactPrerequisitesWithoutMutations() {
        val preflight = GitHubTestChecklist.fixtureApprovalPreflight(GitHubDecisionMode.ASK, "task", "owner/repo", "main", JsonObject(emptyMap()))
        val checkpoint = GitHubTestChecklist.checkpoint(task, mapOf("source" to source()), tools, 64, preflight)
        val preparation = checkpoint.getValue("preparation").jsonObject
        assertEquals(JsonPrimitive(false), preparation["automatic_permission_changes"])
        assertEquals(JsonPrimitive(false), preparation["owned_fixture_creation_admissible"])
        assertNull(preparation["next_owned_fixture_call"])
        val blockers = preparation.getValue("blockers").jsonArray.map { it.jsonObject }
        val approval = blockers.single { it["code"] == JsonPrimitive("FIXTURE_APPROVAL_MODE_REQUIRED") }
        assertTrue(approval.getValue("capability_ids").jsonArray.contains(JsonPrimitive("github_commit:branch")))
        assertTrue(approval.getValue("required_inputs").toString().contains("Chat decisions > Fixture-only auto-approval"))
        assertTrue(approval.getValue("required_inputs").toString().contains("retained task approval preflight"))
        val owned = blockers.single { it["code"] == JsonPrimitive("OWNED_FIXTURE_REQUIRED") }
        assertTrue(owned.getValue("required_inputs").jsonArray.any { it.toString().contains("cleanup") })
        assertTrue(owned.getValue("supported_preparation_ids").jsonArray.isEmpty())
    }

    @Test fun existingFixtureReservesCleanupBeforeOptionalReadsAndSurvivesCheckpointSerialization() {
        val receipts = active()
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        assertFalse(GitHubChecklistBudget.acquisitionAllowed(receipts, 6))
        assertTrue(GitHubChecklistBudget.acquisitionAllowed(receipts, 7))
        val next = requireNotNull(GitHubTestChecklist.nextCall(task, tools, receipts, 6))
        assertEquals("github_context", next.name)
        assertEquals(JsonPrimitive("resolve"), next.arguments["action"])
        val stored = GitHubTestChecklist.checkpoint(task, receipts, tools, 6).toString()
        val restored = json(stored)
        assertEquals(JsonPrimitive(4), restored.getValue("stage_budget").jsonObject["cleanup_reserve"])
        assertEquals(JsonPrimitive(false), restored.getValue("stage_budget").jsonObject["acquisition_allowed"])
        assertEquals(JsonPrimitive("CLEANUP_RESERVE_ACTIVE"), restored.getValue("preparation").jsonObject["status"])
        assertEquals(JsonPrimitive("github_context"), restored.getValue("preparation").jsonObject.getValue("next_owned_fixture_call").jsonObject["tool"])
        assertEquals(2, receipts.size)
    }

    @Test fun cachedLiveVerificationCannotSpendAnActiveFixturesCleanupReserve() {
        val branches = AgentToolDefinition(
            "github_context",
            "Read branches",
            json("""{"type":"object","properties":{"action":{"enum":["branches"]},"verify_live":{"type":"boolean"}},"required":["action"],"additionalProperties":false}""")
        )
        val receipts = active()
        receipts["cached"] = JsonObject(
            receipt("cached", "github_context", """{"action":"branches"}""", """{"has_more":false}""") + mapOf(
                "dispatched" to JsonPrimitive(false),
                "evidence_origin" to JsonPrimitive("CACHED")
            )
        )
        val retained = receipts.toMap()
        val available = listOf(branches)
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        assertNull(GitHubSelfTestFixtures.next(task, available, receipts, allowCreate = false, cleanupOnly = true))
        assertNull(GitHubTestChecklist.nextCall(task, available, receipts, remainingToolCalls = 4))

        val verification = requireNotNull(GitHubTestChecklist.nextCall(task, available, receipts, remainingToolCalls = 5))
        assertEquals("github_context", verification.name)
        assertEquals(json("""{"action":"branches","verify_live":true}"""), verification.arguments)
        assertEquals(retained, receipts)
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, available, remainingToolCalls = 4)
        assertEquals(JsonPrimitive(4), checkpoint.getValue("stage_budget").jsonObject["cleanup_reserve"])
    }

    @Test fun advertisedPaginationCannotSpendTheOwnedFixtureCleanupReserve() {
        val available = tools + tool("github_ci", "logs")
        val receipts = active()
        val next = json("""{"tool":"github_ci","arguments":{"action":"logs","job_id":7,"start_line":41,"end_line":80}}""")
        receipts["logs"] = receipt("logs", "github_ci", """{"action":"logs","job_id":7,"start_line":1,"end_line":40}""", """{"continuation_call":$next}""")
        val retained = receipts.toMap()
        val request = "$task Complete pending pagination."
        val reserved = GitHubTestChecklist.paginationResume(request, receipts, available, remainingToolCalls = 6)
        assertEquals(JsonPrimitive(4), reserved["cleanup_reserve"])
        assertEquals(JsonPrimitive(false), reserved["acquisition_allowed"])
        assertFalse(reserved.containsKey("next_call"))
        assertTrue(reserved.getValue("next_action").toString().contains("owned-fixture cleanup"))
        assertEquals(JsonPrimitive("resolve"), GitHubTestChecklist.nextCall(request, available, receipts, remainingToolCalls = 6)?.arguments?.get("action"))

        val resumed = GitHubTestChecklist.paginationResume(request, receipts, available, remainingToolCalls = 7)
        assertEquals(JsonPrimitive(true), resumed["acquisition_allowed"])
        assertEquals(next, resumed["next_call"])
        assertEquals(retained, receipts)
    }

    @Test fun cleanupReserveIsReleasedOnlyAfterExactOwnedAbsenceIsVerified() {
        val receipts = active()
        val deletion = receipt("cleanup", "github_commit", """{"action":"delete_branch","branch":"$fixture","expected_head_sha":"$sha"}""", """{"deleted":true,"absence_verified":true,"branch":"$fixture","observed_head_sha":"$sha"}""")
        receipts["cleanup"] = JsonObject(deletion + ("result" to JsonObject(deletion.getValue("result").jsonObject + ("absence_verified" to JsonPrimitive(false)))))
        assertEquals(4, GitHubChecklistBudget.cleanupReserve(receipts))
        receipts["cleanup"] = deletion
        assertEquals(0, GitHubChecklistBudget.cleanupReserve(receipts))
        assertTrue(GitHubChecklistBudget.acquisitionAllowed(receipts, 6))
    }
}
