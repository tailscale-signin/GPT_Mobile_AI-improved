package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.ProgressiveToolCatalog
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalCompactRequestTest {
    private val search = AgentToolDefinition("web_search", "Search the web", buildJsonObject {})
    private val reader = AgentToolDefinition("read_url", "Read a page", buildJsonObject {})
    private val discovery = ProgressiveToolCatalog(listOf(search, reader), "search").definition
    private val catalog = listOf(search, reader, discovery) + (1..15).map { AgentToolDefinition("github_tool_$it", "Repository operations ".repeat(80), buildJsonObject {}) }

    @Test fun freshGreetingHasNoSchemasOrToolInstructions() {
        val system = LocalRequestPrompt.build(null, "", false)
        val plan = LocalContextPlanner.plan(emptyList(), "hi", system, catalog, 4096, 512)
        assertTrue(plan.tools.isEmpty())
        assertEquals(0L, plan.toolDefinitionTokens)
        assertEquals(0, plan.toolResultBytes)
        assertTrue(plan.estimatedPromptTokens < 200)
        assertFalse(system.contains("tool", true))
        assertFalse(system.contains("date", true))
    }

    @Test fun greetingPrefixDoesNotRemoveExplicitSearchAndReader() {
        val plan = LocalContextPlanner.plan(emptyList(), "Hi, search the web and verify sources about this model", "", catalog, 4096, 512)
        assertTrue(plan.tools.containsAll(listOf(search, reader, discovery)))
        assertFalse(plan.tools.any { it.name.startsWith("github") })
    }

    @Test fun ordinaryWritingOmitsUnrelatedCapabilitiesButKeepsUserInstructions() {
        assertTrue(LocalContextPlanner.plan(emptyList(), "Write a short poem about autumn", "", catalog, 4096, 512).tools.isEmpty())
        val prompt = LocalRequestPrompt.build("Reply in French", "Keep names unchanged", false)
        assertTrue(prompt.contains("Reply in French"))
        assertTrue(prompt.contains("Keep names unchanged"))
        assertEquals(prompt, LocalRequestPrompt.build("Reply in French", "Keep names unchanged", false))
    }

    @Test fun exactRequestedToolIsNotConfusedWithLongerAlias() {
        val tools = listOf("tool1", "tool10").map { AgentToolDefinition(it, "Action", buildJsonObject {}) }
        assertEquals(setOf("tool10"), LocalContextPlanner.requiredToolNames("Hi, use tool10", tools))
    }

    @Test fun explicitCustomInstructionKeepsItsRequiredCapabilityForGreeting() {
        val plan = LocalContextPlanner.plan(emptyList(), "hi", "Always use web_search", catalog, 4096, 512, explicitlyRequiredTools = setOf("web_search"))
        assertTrue(plan.tools.contains(search))
        assertFalse(plan.tools.any { it.name.startsWith("github") })
    }

    @Test fun explicitGenericToolRequestUsesSmallAuthorizedCatalog() {
        val task = "Hi, help me plan a project; use available tools when needed."
        assertEquals(listOf(discovery), LocalContextPlanner.relevantTools(task, catalog))
        assertEquals(listOf(search), LocalContextPlanner.relevantTools(task, listOf(search)))
    }
}
