package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubIntegrationStopReasonTest {
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private val branches = AgentToolDefinition("github_context", "Branches", json("""{"properties":{"action":{"const":"branches"}}}"""))
    private val commits = AgentToolDefinition("github_commit", "Owned fixture", json("""{"properties":{"action":{"enum":["branch","delete_branch"]}}}"""))
    private val nextPage = json("""{"tool":"github_context","arguments":{"action":"branches","page":4}}""")

    /** Two extra remote pages spend only the optional inventory sample allowance. */
    private fun sampled(): Map<String, JsonObject> = (1..3).associate { page ->
        "page-$page" to buildJsonObject {
            put("receipt_id", "page-$page")
            put("binding", "accepted-task")
            put("repository", "owner/repo")
            put("trusted", true)
            put("success", true)
            put("dispatched", true)
            put("evidence_origin", "LIVE")
            put("tool", branches.name)
            put("arguments", json("""{"action":"branches","page":$page}"""))
            put("result", buildJsonObject { if (page == 3) put("continuation_call", nextPage) })
        }
    }

    @Test fun optionalInventoryCannotHideRequiredFixtureApprovalPrerequisites() {
        val task = "Test all tools and exercise permitted writes in an owned disposable fixture with verified cleanup."
        val tools = listOf(branches, commits)
        val receipts = sampled()
        val preflight = GitHubTestChecklist.fixtureApprovalPreflight(GitHubDecisionMode.ASK, "accepted-task", "owner/repo", "main", json("{}"))
        val checklist = GitHubTestChecklist.checkpoint(task, receipts, tools, 100, preflight)
        val rows = GitHubTestChecklist.rows(receipts, tools, task, preflight).filterIsInstance<JsonObject>()
        assertTrue(rows.any { it["required"] == JsonPrimitive(true) && it["blocker_code"] == JsonPrimitive("FIXTURE_APPROVAL_MODE_REQUIRED") })
        assertNull(GitHubTestChecklist.nextCall(task, tools, receipts, remainingToolCalls = 100))
        val stop = GitHubIntegrationReport.stopReason(task, receipts, tools, 100, checklist)
        assertEquals(JsonPrimitive("CAPABILITY_PREREQUISITES_MISSING"), stop["code"])
        assertEquals(JsonPrimitive("RESOLVE_CAPABILITY_PREREQUISITE"), stop["recovery_action"])
        assertFalse(stop.containsKey("inventory_stop_reason"))
        assertEquals(JsonPrimitive(1), stop["pending_continuations"])
        assertEquals(JsonPrimitive(100), stop["remaining_tool_calls"])
        assertEquals(JsonPrimitive(0), stop["pending_local_fragments"])
        assertEquals(JsonPrimitive(1), stop["pending_remote_continuations"])
        assertEquals(JsonPrimitive(false), stop["exhaustive_inventory_required"])
        assertEquals(JsonPrimitive(2), (stop["required_capability_blocker_counts"] as JsonObject)["FIXTURE_APPROVAL_MODE_REQUIRED"])
        assertTrue((stop["detail"] as JsonPrimitive).content.contains("explicit fixture approval policy"))
        assertTrue((stop["detail"] as JsonPrimitive).content.contains("Unused budget does not grant approval"))
        assertFalse(GitHubTestChecklist.apiComplete(task, receipts, tools))
        val report = GitHubIntegrationReport.render(task, receipts, tools, 100, checkpoint = checklist)
        assertTrue(report.contains("**CAPABILITY_PREREQUISITES_MISSING**"))
        assertFalse(report.contains("**BOUNDED_INVENTORY_SAMPLE_REACHED**"))
        assertTrue(report.contains("Retained continuation recipes: 1."))
        assertTrue(report.contains("0 local response fragments and 1 remote continuation recipes remain"))
    }

    @Test fun optionalPagesDoNotReplaceMissingIndependentBuildAcceptance() {
        val task = """
            Test all tools read only.
            Acceptance criteria:
            - Bind acceptance to the running app build and exact repository revision
        """.trimIndent()
        val receipts = sampled()
        val tools = listOf(branches)
        val checkpoint = GitHubTestChecklist.checkpoint(task, receipts, tools, 100)
        assertTrue((checkpoint["remaining_requirements"] as JsonArray).isNotEmpty())
        assertTrue(GitHubTestChecklist.rows(receipts, tools, task).filterIsInstance<JsonObject>().all { it["status"] == JsonPrimitive("PASSED") })
        assertNull(GitHubTestChecklist.nextCall(task, tools, receipts, remainingToolCalls = 100))
        val stop = GitHubIntegrationReport.stopReason(task, receipts, tools, 100)
        assertEquals(JsonPrimitive("ACCEPTANCE_EVIDENCE_MISSING"), stop["code"])
        assertFalse(stop.containsKey("inventory_stop_reason"))
        assertFalse(GitHubTestChecklist.apiComplete(task, receipts, tools))
        assertEquals(checkpoint, GitHubTestChecklist.checkpoint(task, receipts, tools, 100))
    }

    @Test fun successfulSampledAcceptanceKeepsOptionalRecipesWithoutRequiringFullInventory() {
        val task = "Test all tools read only"
        val receipts = sampled()
        assertTrue(GitHubTestChecklist.apiComplete(task, receipts, listOf(branches)))
        assertNull(GitHubTestChecklist.nextCall(task, listOf(branches), receipts, remainingToolCalls = 100))
        val stop = GitHubIntegrationReport.stopReason(task, receipts, listOf(branches), 100)
        assertEquals(JsonPrimitive("ACCEPTANCE_COMPLETE"), stop["code"])
        assertEquals(JsonPrimitive("NONE"), stop["recovery_action"])
        assertEquals(listOf(nextPage), GitHubTestChecklist.pendingPages(receipts))
    }

    @Test fun explicitFullInventoryAndBudgetStopsKeepTheirOwnAuthority() {
        val task = "Test all tools read only and all branches"
        val receipts = sampled()
        val tools = listOf(branches)
        assertEquals(nextPage["arguments"], GitHubTestChecklist.nextCall(task, tools, receipts, remainingToolCalls = 100)?.arguments)
        assertEquals(JsonPrimitive("INVENTORY_CONTINUATION_BLOCKED"), GitHubIntegrationReport.stopReason(task, receipts, tools, 100)["code"])
        assertFalse(GitHubIntegrationReport.stopReason(task, receipts, tools, 100).containsKey("inventory_stop_reason"))
        assertFalse(GitHubTestChecklist.apiComplete(task, receipts, tools))
        for ((remaining, reason) in listOf(0 to "TOOL_BUDGET_EXHAUSTED", 2 to "VERIFICATION_RESERVE_REACHED")) {
            assertNull(GitHubTestChecklist.nextCall(task, tools, receipts, remainingToolCalls = remaining))
            assertEquals(JsonPrimitive(reason), GitHubIntegrationReport.stopReason(task, receipts, tools, remaining)["code"])
        }
    }

    @Test fun requiredLocalFragmentAtBudgetExhaustionIsNotLabeledAsOptionalSampling() {
        val task = "Test all tools read only. Acceptance criteria:\n- Bind acceptance to the running app build and exact repository revision"
        val continuation = json("""{"tool":"github_context","arguments":{"view_cursor":"required-fragment:1"}}""")
        val source = JsonObject(sampled().getValue("page-1") + ("result" to json("""{"local_page_complete":false,"continuation_call":$continuation}""")))
        val receipts = mapOf("source" to source)
        val tools = listOf(branches)
        assertEquals(listOf(continuation), GitHubTestChecklist.pendingPages(receipts))
        assertNull(GitHubTestChecklist.nextCall(task, tools, receipts, remainingToolCalls = 0))
        val stop = GitHubIntegrationReport.stopReason(task, receipts, tools, 0)
        assertEquals(JsonPrimitive("TOOL_BUDGET_EXHAUSTED"), stop["code"])
        assertEquals(JsonPrimitive(1), stop["pending_continuations"])
        assertEquals(JsonPrimitive(1), stop["pending_local_fragments"])
        assertEquals(JsonPrimitive(0), stop["pending_remote_continuations"])
        assertFalse(stop.containsKey("inventory_stop_reason"))
        val report = GitHubIntegrationReport.render(task, receipts, tools, 0)
        assertTrue(report.contains("**TOOL_BUDGET_EXHAUSTED**"))
        assertTrue(report.contains("Retained continuation recipes: 1."))
        assertFalse(report.contains("optional continuation"))
        assertFalse(report.contains("BOUNDED_INVENTORY_SAMPLE_REACHED"))
    }
}
