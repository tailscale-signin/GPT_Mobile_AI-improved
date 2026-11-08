package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.agent.tool.GitHubOperationTool
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDiscoveryEvidenceTest {
    private fun payload(result: AgentToolResult) = (result.content as ToolResultContent.Json).value.jsonObject

    @Test fun githubDiscoveryExcludesUnrelatedStatusAndResearchToolsButHonorsAnExactAuthorizedName() = runTest {
        val github = GitHubOperationTool.definition("github_context", "github_context__selected")
        val health = AgentToolDefinition("health_status", "Read health status records", buildJsonObject {})
        val web = AgentToolDefinition("web_search", "Research sources including GitHub repositories and status", buildJsonObject {}, capabilities = setOf(AgentToolCapability.WEB_SEARCH))
        val catalog = ProgressiveToolCatalog(listOf(github, health, web), "Check repo status")
        val found = payload(catalog.execute("github", buildJsonObject { put("query", "GitHub repository status") }))
        val names = found.getValue("available_next_round").jsonArray.map { it.jsonObject.getValue("name").jsonPrimitive.content }
        assertTrue(github.name in names)
        assertFalse(health.name in names)
        assertFalse(web.name in names)
        val explicit = payload(catalog.execute("explicit", buildJsonObject { put("names", JsonArray(listOf(JsonPrimitive(health.name)))) }))
        assertTrue(explicit.getValue("available_next_round").jsonArray.any { it.jsonObject["name"] == JsonPrimitive(health.name) })
    }

    @Test fun inventoryListingSchemaAdmissionExecutionAndAcceptanceHaveIndependentReceiptCounts() = runTest {
        val definitions = (1..91).map { AgentToolDefinition("tool_$it", "Tool $it", buildJsonObject {}) }
        val catalog = ProgressiveToolCatalog(definitions, "tool_1")
        val first = payload(catalog.execute("list", buildJsonObject {}))
        assertEquals(JsonPrimitive(91), first["authorized_tool_count"])
        assertEquals(JsonPrimitive(12), first["returned"])
        assertEquals(JsonPrimitive(79), first["inventory_remaining_after_page"])
        assertEquals(JsonPrimitive(79), first["inventory_entries_not_yet_listed"])
        assertEquals(JsonPrimitive(0), first.getValue("coverage").jsonObject["schemas_ever_advertised"])
        catalog.recordSelection(definitions, definitions.take(2))
        val success = AgentToolResult("actual-call", ToolResultContent.Text("Observed"), false)
        catalog.recordObservation("tool_1", success)
        catalog.recordObservation("TOOL_1", success)
        catalog.recordObservation("tool_1", success.copy(callId = "replayed", sharedResult = true))
        catalog.recordObservation("tool_2", success.copy(callId = "preflight", dispatched = false, isError = true))
        catalog.recordObservation("tool_2", success.copy(callId = "unknown", isError = true, errorCode = "TIMEOUT_OUTCOME_UNKNOWN"))
        val repeated = payload(catalog.execute("same-page", buildJsonObject {}))
        val coverage = repeated.getValue("coverage").jsonObject
        assertEquals(JsonPrimitive(12), coverage["inventory_entries_listed"])
        assertEquals(JsonPrimitive(2), coverage["schemas_ever_advertised"])
        assertEquals(JsonPrimitive(2), coverage["distinct_tools_dispatched"])
        assertEquals(JsonPrimitive(2L), coverage["dispatched_calls"])
        assertEquals(JsonPrimitive(1L), coverage["successful_calls"])
        assertEquals(JsonPrimitive(0L), coverage["failed_calls"])
        assertEquals(JsonPrimitive(1L), coverage["unknown_outcome_calls"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), coverage["acceptance_status"])
        val continuation = first.getValue("continuation_call").jsonObject.getValue("arguments").jsonObject
        val second = payload(catalog.execute("second-page", continuation))
        assertEquals(JsonPrimitive(24), second.getValue("coverage").jsonObject["inventory_entries_listed"])
        assertEquals(JsonPrimitive(67), second["inventory_entries_not_yet_listed"])
    }

    @Test fun aCurrentGreetingOrChatTestDoesNotAdvertiseRecoveryOrRepositorySchemas() {
        val definitions = listOf(
            GitHubOperationTool.definition("github_context"),
            AgentToolDefinition("read_recovery_context", "Continue previous GitHub work", buildJsonObject {})
        )
        for (request in listOf("hello", "test", "just a test message", "test all providers")) {
            assertTrue(request, ProgressiveToolCatalog(definitions, request).selected(definitions).isEmpty())
        }
    }

    @Test fun theDiscoveryReceiptRecordsWhatFitsTheActualNextProviderRequest() = runTest {
        val definitions = (1..4).map { AgentToolDefinition("tool_$it", "Tool $it", buildJsonObject {}) }
        val catalog = ProgressiveToolCatalog(definitions, "tool")
        val call = ProviderEvent.ToolCall("list", ProgressiveToolCatalog.NAME, buildJsonObject {})
        val result = catalog.execute(call.callId, call.arguments)
        val reconciled = payload(reconcileDiscoveryResults(listOf(call), listOf(result), definitions.take(1)).single())
        assertEquals(JsonPrimitive(1), reconciled.getValue("coverage").jsonObject["schemas_admitted_next_round"])
        assertEquals(1, reconciled.getValue("available_next_round").jsonArray.size)
        assertEquals(3, reconciled.getValue("context_budget_excluded").jsonArray.size)
    }
}
