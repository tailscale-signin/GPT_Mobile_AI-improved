package dev.chungjungsoo.gptmobile.data.github

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubBlockedIntegrationReportTest {
    private val task = "Test all GitHub tools"
    private val sha = "a".repeat(40)
    private fun json(value: String) = Json.parseToJsonElement(value).jsonObject
    private fun tool(name: String, vararg actions: String) = AgentToolDefinition(
        name,
        name,
        json("""{"type":"object","properties":{"action":{"enum":${JsonArray(actions.map(::JsonPrimitive))}}}}""")
    )

    private fun receipt(id: String, name: String, arguments: String, result: String, live: Boolean = true) = buildJsonObject {
        put("receipt_id", id)
        put("call_id", id)
        put("binding", "accepted-task")
        put("repository", "owner/repo")
        put("trusted", true)
        put("success", true)
        put("dispatched", live)
        put("evidence_origin", if (live) "LIVE" else "LOCAL")
        put("tool", name)
        put("arguments", json(arguments))
        put("result", json(result))
    }

    private fun source() = receipt("source", "github_context", """{"action":"status"}""", """{"branch":"main","commit_sha":"$sha","repository":{"default_branch":"main"}}""")

    @Test fun missingExecutableSchemaControlsHeadlineAndNextActionDespiteReadyPrerequisite() {
        val tools = listOf(tool("github_context", "status"), tool("github_ci", "workflows"))
        val records = mapOf("source" to source())
        val candidate = ProviderEvent.ToolCall("workflow-inventory", "github_ci", json("""{"action":"workflows"}"""))
        val plan = GitHubChecklistSchedule.select(tools, emptyList(), records, listOf(candidate), null, null, true)
        val (text, verdict) = GitHubEvidencePolicy.testing(records, 1000, 0, task, tools, 237, executionPlan = plan.snapshot)

        assertEquals(JsonPrimitive("BLOCKED"), verdict["task_outcome"])
        assertEquals(JsonPrimitive(false), verdict["completion_verified"])
        assertTrue(text.contains("Task outcome: blocked; partial coverage retained"))
        assertFalse(text.contains("partial acceptance"))
        val next = text.lines().single { it.startsWith("**Next action**") }
        assertTrue(next.contains("github_ci / workflows"))
        assertTrue(next.contains("DISCOVERY_TOOL_NOT_ADVERTISED"))
        assertFalse(next.contains("all required inputs are available"))
        assertFalse(next.contains("Ready under"))
        assertEquals(JsonPrimitive(237), verdict.getValue("test_checklist").jsonObject.getValue("stage_budget").jsonObject["remaining_calls"])
        assertEquals(1, records.size)
    }

    @Test fun schemaSelectionAndDirectoryPagesNeverBecomeRepositoryPagination() {
        val directoryCall = """{"tool":"discover_available_tools","arguments":{"inventory_cursor":12,"inventory_version":"catalog-v1"}}"""
        val records = linkedMapOf(
            "selection" to receipt("selection", "discover_available_tools", """{"names":["github_pr"],"action":"list"}""", """{"available_next_round":["github_pr"],"unknown_names":[]}""", live = false),
            "tags" to receipt("tags", "discover_available_tools", """{"names":["github_release"],"action":"tags"}""", """{"available_next_round":["github_release"]}""", live = false),
            "directory" to receipt("directory", "discover_available_tools", "{}", """{"inventory_version":"catalog-v1","authorized_tool_count":20,"inventory_offset":0,"returned":12,"has_more":true,"continuation_call":$directoryCall}""", live = false),
            "pulls" to receipt("pulls", "github_pr_inspect__selected", """{"action":"list","page":1,"per_page":30}""", """{"pull_requests":[{"number":2651}],"page":1,"per_page":30,"has_more":false,"total_count":1}""")
        )
        val inventory = GitHubTestChecklist.inventoryCoverage(records).single().jsonObject
        assertEquals(JsonPrimitive("github_pr_inspect__selected"), inventory["tool"])
        assertEquals(JsonPrimitive(1), inventory["retrieved"])
        assertTrue(GitHubTestChecklist.pendingPages(records).isEmpty())
        assertEquals(JsonPrimitive(0), GitHubTestChecklist.paginationResume(task, records, emptyList(), 237)["remote_samples_spent"])
        assertEquals(JsonPrimitive(3), GitHubAuthorizedCatalog.snapshot(records, emptyList())["discovery_calls_observed"])
        assertEquals(4, records.size)
    }

    @Test fun compactPublicationKeepsTheEntireDurableMatrixAndExactBudget() {
        val tools = GitHubOperationTool.names.sorted().map { GitHubOperationTool.definition(it) }
        val records = mapOf("source" to source())
        val expected = GitHubTestChecklist.checkpoint(task, records, tools, 237)
        val (text, verdict) = GitHubEvidencePolicy.testing(records, 1000, 0, task, tools, 237)
        val restored = json(verdict.toString()).getValue("test_checklist").jsonObject
        val capabilities = restored.getValue("capabilities") as JsonArray
        assertTrue(capabilities.size > 100)
        assertEquals(expected["capabilities"], capabilities)
        assertEquals(expected["preparation"], restored["preparation"])
        assertEquals(expected["stage_budget"], restored["stage_budget"])
        assertEquals(expected["resume_plan"], restored["resume_plan"])
        assertTrue(text.contains("The complete ${capabilities.size}-capability matrix"))
        assertTrue(text.contains("Budget remaining: 237"))
        assertTrue(text.contains("Fixture approval and verified cleanup remain required"))
        assertFalse(text.contains("## Verified observations"))
        val detailed = GitHubIntegrationReport.render(task, records, tools, 237, checkpoint = expected)
        assertTrue("Compact report has ${text.length} characters", text.length < 6500)
        assertTrue("Compact ${text.length}, detailed ${detailed.length}", text.length < detailed.length / 2)
        assertEquals(JsonPrimitive(false), verdict["completion_verified"])
    }

    @Test fun explicitDetailedRequestRetainsTheCompletePrerequisitePresentation() {
        val tools = GitHubOperationTool.names.sorted().map { GitHubOperationTool.definition(it) }
        val records = mapOf("source" to source())
        val detailed = GitHubTaskCoverage.render(records, "$task and provide a detailed report", tools, 237)
        assertTrue(detailed.contains("Every affected capability and current outcome"))
        assertTrue(detailed.contains("github_pr:merge (BLOCKED)"))
        assertTrue(detailed.contains("FIXTURE_APPROVAL_STATE_UNKNOWN"))
        assertFalse(detailed.contains("partial acceptance"))
        assertEquals(1, records.size)
    }
}
