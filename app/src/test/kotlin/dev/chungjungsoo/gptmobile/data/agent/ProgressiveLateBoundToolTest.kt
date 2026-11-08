package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.assist.AssistTurnPlanningTool
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveLateBoundToolTest {
    private val planning = AssistTurnPlanningTool("Plan the steps for this task").definition

    @Test fun authorizedLatePlannerSurvivesBothCatalogSelectionLayersWithoutDiscovery() = runTest {
        var acquisitions = 0
        val catalog = ProgressiveToolCatalog(emptyList(), "Plan the steps for this task", acquireCold = { _, _ ->
            acquisitions++
            emptyList()
        })
        val allowed = listOf(catalog.definition, planning)
        assertEquals(listOf(catalog.definition), catalog.selected(allowed))
        assertTrue(catalog.admitAuthorizedTool(planning))
        var providerTools = emptyList<AgentToolDefinition>()
        val session = object : AgentProviderSession {
            override fun streamRound(tools: List<AgentToolDefinition>, exchanges: List<AgentToolExchange>) = flowOf(ProviderEvent.Completed).also { providerTools = tools }
        }
        session.withToolCatalog(catalog).streamRound(catalog.selected(allowed), emptyList()).toList()
        assertEquals(planning, providerTools.single { it.name == planning.name })
        assertEquals(0, acquisitions)
        val unauthorized = planning.copy(name = "ungranted_helper")
        assertFalse(catalog.selected(allowed + unauthorized).any { it.name == unauthorized.name })
        assertEquals(listOf(catalog.definition), catalog.selected(listOf(catalog.definition)))
        assertTrue(catalog.selected(emptyList()).isEmpty())
    }

    @Test fun latePlannerUsesTheExistingWindowAndPreservesRequiredTools() {
        val decision = AgentToolDefinition("ask_user", "Ask the user", JsonObject(emptyMap()), capabilities = setOf(AgentToolCapability.USER_DECISION))
        val definitions = listOf(decision) + (1..30).map { AgentToolDefinition("tool_$it", "Tool $it", JsonObject(emptyMap())) }
        val catalog = ProgressiveToolCatalog(definitions, "Plan the steps for this task")
        assertTrue(catalog.admitAuthorizedTool(planning))
        val selected = catalog.selected(definitions + planning)
        assertTrue(selected.containsAll(listOf(decision, planning)))
        assertTrue(selected.size <= ProgressiveToolCatalog.INITIAL_TOOLS)
        assertTrue(selected.sumOf(ProgressiveToolCatalog::schemaTokens) + 256 <= ProgressiveToolCatalog.SCHEMA_TOKEN_ALLOWANCE)
    }

    @Test fun optionalPlannerCannotEvictAnExplicitPrimaryToolAtTheEndOfAFullWindow() = runTest {
        val required = AgentToolDefinition("required_tool", "Required operation", JsonObject(emptyMap()))
        val definitions = (1 until ProgressiveToolCatalog.INITIAL_TOOLS).map { AgentToolDefinition("optional_$it", "Optional operation", JsonObject(emptyMap())) } + required
        val catalog = ProgressiveToolCatalog(definitions, "Use required_tool for this task")
        catalog.execute("select", JsonObject(mapOf("names" to JsonArray(definitions.map { JsonPrimitive(it.name) }))))
        assertEquals(ProgressiveToolCatalog.INITIAL_TOOLS, catalog.selected(definitions).size)
        assertEquals(required, catalog.selected(definitions).last())
        assertTrue(catalog.admitAuthorizedTool(planning))
        val selected = catalog.selected(definitions + planning)
        assertTrue(selected.containsAll(listOf(required, planning)))
        assertEquals(required, selected.first())
        assertTrue(selected.size <= ProgressiveToolCatalog.INITIAL_TOOLS)
    }

    @Test fun hiddenConversationalOversizedAndConflictingDefinitionsCannotBypassAdmission() {
        val catalog = ProgressiveToolCatalog(emptyList(), "Plan the steps for this task")
        assertFalse(catalog.admitAuthorizedTool(planning.copy(modelVisible = false)))
        assertFalse(catalog.admitAuthorizedTool(catalog.definition))
        val oversized = planning.copy(description = "x".repeat(ProgressiveToolCatalog.MAX_SCHEMA_TOKEN_ALLOWANCE * 16))
        assertFalse(catalog.admitAuthorizedTool(oversized))
        assertEquals("SCHEMA_TOO_LARGE", catalog.selectionOmissions(listOf(oversized))[planning.name])
        assertTrue(catalog.selected(listOf(oversized)).isEmpty())
        assertFalse(catalog.admitAuthorizedTool(planning))
        val greeting = ProgressiveToolCatalog(emptyList(), "Hi")
        assertFalse(greeting.admitAuthorizedTool(planning))
        assertTrue(greeting.selected(listOf(planning)).isEmpty())
    }
}
