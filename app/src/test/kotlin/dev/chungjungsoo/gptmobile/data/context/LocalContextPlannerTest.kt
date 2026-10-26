package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalContextPlannerTest {
    @Test fun greetingWithRealPromptBridgeFitsSmallContextAndLargeCatalog() {
        val tools = (1..15).map { AgentToolDefinition("tool$it", "Optional capability ".repeat(35), buildJsonObject {}) }
        val system = dev.chungjungsoo.gptmobile.data.agent.liveToolSystemPrompt(null, emptyList(), compact = true, deferCapabilities = true)
        val plan = LocalContextPlanner.plan(
            emptyList(),
            "hi",
            system,
            tools,
            2048,
            4096,
            renderToolPrompt = { dev.chungjungsoo.gptmobile.data.agent.provider.LocalPromptToolBridge.instruction(it) },
            renderCapabilityPrompt = { dev.chungjungsoo.gptmobile.data.agent.localToolCapabilityPrompt(it.map { tool -> tool.name }) }
        )
        assertTrue(plan.outputReserveTokens > 0)
        assertTrue(plan.omittedTools > 0)
        assertTrue(plan.toolSelectionReasons.values.all { it == "not_needed_for_request" })
        assertTrue(plan.estimatedPromptTokens + plan.outputReserveTokens + plan.toolResultBytes / 2 + 256 < 2048)
    }

    @Test fun relatedFollowUpsRetainSearchAndItsTaskUnderContextPressure() {
        val search = AgentToolDefinition("web_search", "Search", buildJsonObject {})
        val others = (1..20).map { AgentToolDefinition("a$it", "Unrelated ".repeat(80), buildJsonObject {}) }
        val goal = turn("search hulk hogan")
        val history = listOf(goal, turn("check again"))
        listOf("check again", "test toola", "Please try again!", "tell me", "what did you find").forEach { prompt ->
            val plan = LocalContextPlanner.plan(history, prompt, "", others + search, 1024, null)
            assertTrue(search in plan.tools)
            assertTrue(goal in plan.priorTurns)
            assertEquals("required_follow_up", plan.toolSelectionReasons[search.name])
            assertTrue(plan.toolSelectionReasons.values.contains("not_needed_for_request"))
        }
    }

    @Test fun followUpDoesNotResurrectDisabledToolsOrCrossANewTopic() {
        val date = AgentToolDefinition("current_date", "Date", buildJsonObject {})
        val search = AgentToolDefinition("web_search", "Search", buildJsonObject {})
        val history = listOf(turn("search hulk hogan"), turn("Write a poem"))
        val plan = LocalContextPlanner.plan(history, "check again", "", listOf(search, date), 1024, null)
        assertEquals("not_needed_for_request", plan.toolSelectionReasons[search.name])
        val disabled = LocalContextPlanner.plan(listOf(turn("search hulk hogan")), "check again", "", listOf(date), 1024, null)
        assertTrue(disabled.tools.isEmpty())
        assertEquals("required_current_request", LocalContextPlanner.plan(history, "tell me the time", "", listOf(date, search), 1024, null).toolSelectionReasons[date.name])
    }

    @Test fun explicitSearchKeepsItsReaderAvailableForTitleOnlyResults() {
        val search = AgentToolDefinition("web_search", "Search", buildJsonObject {})
        val reader = AgentToolDefinition("read_url", "Read a public page", buildJsonObject {})
        val plan = LocalContextPlanner.plan(emptyList(), "Search the web for launch news", "", listOf(search, reader), 1024, null)
        assertTrue(plan.tools.containsAll(listOf(search, reader)))
        assertEquals("required_current_request", plan.toolSelectionReasons[reader.name])
    }

    @Test fun finalCapabilityInventoryIsChargedEvenWithNoTools() {
        val plan = LocalContextPlanner.plan(emptyList(), "Hi", "", emptyList(), 1024, null, renderCapabilityPrompt = { "none" })
        assertEquals(2L, plan.toolDefinitionTokens)
        assertEquals(3L, plan.estimatedPromptTokens)
    }

    @Test fun optionalSchemaSelectionIsBoundedByRequestRelevance() {
        val tools = (1..30).map { AgentToolDefinition("tool$it", "Short", buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(emptyList(), "Compare the available actions", "", tools, 32_768, 1024)
        assertEquals(3, plan.tools.size)
        assertEquals(27, plan.omittedTools)
    }

    @Test
    fun `large tool catalog fits a 1024 context with response and result headroom`() {
        val tools = (1..40).map { AgentToolDefinition("tool$it", "description ".repeat(20), buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(emptyList(), "Hi", "Be helpful", tools, 1024, null)
        assertTrue(plan.tools.isEmpty())
        assertTrue(plan.omittedTools > 0)
        assertTrue(plan.estimatedPromptTokens < 200)
        assertEquals(0, plan.toolResultBytes)
    }

    @Test
    fun `tool definitions are not double charged against the context budget`() {
        val tools = (1..8).map { AgentToolDefinition("tool$it", "short", buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(emptyList(), "Use a tool", "", tools, 1024, null)
        assertTrue(plan.tools.isNotEmpty())
    }

    @Test
    fun `single oversized anchor is evicted instead of bypassing compaction`() {
        val huge = turn("x".repeat(40000))
        val plan = LocalContextPlanner.plan(listOf(huge), "Current question", "System", emptyList(), 1024, null)
        assertTrue(plan.priorTurns.isEmpty())
        assertEquals(1, plan.omittedTurns)
    }

    @Test
    fun `oversized anchor cannot exclude recent turns that fit`() {
        val recent = turn("Keep this recent question")
        val plan = LocalContextPlanner.plan(listOf(turn("x".repeat(40000)), recent), "Hi", "", emptyList(), 1024, null)
        assertEquals(listOf(recent), plan.priorTurns)
    }

    @Test
    fun `current prompt is rejected intact when fixed input cannot fit`() {
        assertThrows(IllegalArgumentException::class.java) {
            LocalContextPlanner.plan(emptyList(), "x".repeat(40000), "System", emptyList(), 8192, null)
        }
    }

    @Test
    fun `unicode estimates evict history that an English character ratio would retain`() {
        val prior = turn("界".repeat(1000))
        val plan = LocalContextPlanner.plan(listOf(prior), "Hello", "", emptyList(), 1024, null)
        assertTrue(plan.priorTurns.isEmpty())
    }

    @Test
    fun explicitOutputReserveIsNeverSilentlyReducedToAQuarterOfContext() {
        val plan = LocalContextPlanner.plan(emptyList(), "Full task", "", emptyList(), 8192, 4096)
        assertEquals(4096, plan.outputReserveTokens)
        assertEquals(4096, LocalContextPlanner.plan(emptyList(), "Full task", "", emptyList(), 8192, 16384).outputReserveTokens)
    }

    @Test
    fun `output preference never becomes the engine context size`() {
        val plan = LocalContextPlanner.plan(listOf(turn("x".repeat(2000))), "Hi", "", emptyList(), 8192, 100)
        assertFalse(plan.priorTurns.isEmpty())
    }

    @Test
    fun requiredSearchCannotDisappearUnderContextPressure() {
        val required = AgentToolDefinition("web_search", "Search", buildJsonObject {})
        val others = (1..20).map { AgentToolDefinition("a$it", "Unrelated ".repeat(80), buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(emptyList(), "Research the latest weather", "", others + required, 1024, null)
        assertTrue(plan.tools.any { it.name == "web_search" })
    }

    @Test
    fun unrelatedSchemasCannotEvictDocumentHistory() {
        val anchor = turn("Retained document observations " + "important ".repeat(12))
        val tools = (1..30).map { AgentToolDefinition("unrelated$it", "Management ".repeat(20), buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(listOf(anchor), "Summarize the document", "", tools, 1024, null)
        assertEquals(listOf(anchor), plan.priorTurns)
        assertTrue(plan.omittedTools > 0)
    }

    @Test
    fun largerContextAllowsMoreThanTwoKilobytesOfEvidence() {
        val tool = AgentToolDefinition("web_search", "Search", buildJsonObject {})
        assertTrue(LocalContextPlanner.plan(emptyList(), "Search", "", listOf(tool), 8192, null).toolResultBytes > 2048)
    }

    @Test
    fun optionalSchemasCannotStarveDocumentHistory() {
        val anchor = turn("Attachment: first.pdf\nOriginal document")
        val recent = turn("Attachment: recent.pdf\nRecent document")
        val tools = (1..40).map { AgentToolDefinition("tool$it", "Optional description ".repeat(20), buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(listOf(anchor, recent), "Summarize", "", tools, 1024, null)
        assertEquals(listOf(anchor, recent), plan.priorTurns)
        assertTrue(plan.omittedTools > 0)
        assertTrue(plan.estimatedPromptTokens < 384)
    }

    @Test
    fun requiredSchemasTakePrecedenceOverHistory() {
        val search = AgentToolDefinition("web_search", "Search ".repeat(20), buildJsonObject {})
        val plan = LocalContextPlanner.plan(listOf(turn("x".repeat(600))), "Search", "", listOf(search), 1024, null)
        assertEquals(listOf(search), plan.tools)
        assertTrue(plan.priorTurns.isEmpty())
    }

    @Test
    fun excessiveOutputPreferenceCannotRemoveEveryOptionalTool() {
        val tools = (1..9).map { AgentToolDefinition("tool$it", "Description ".repeat(30), buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(emptyList(), "What can you do?", "Instructions ".repeat(270), tools, 8192, 8192)
        assertTrue(plan.tools.isNotEmpty())
        assertTrue(plan.outputReserveTokens > 0)
    }

    @Test
    fun optionalToolReservationBorrowsOutputSpaceWithoutEvictingDocumentHistory() {
        val history = turn("Document contents ".repeat(8))
        val tool = AgentToolDefinition("lookup", "Optional description ".repeat(20), buildJsonObject {})
        val plan = LocalContextPlanner.plan(listOf(history), "Summarize", "Instruction ".repeat(30), listOf(tool), 1024, 256)
        assertEquals(listOf(history), plan.priorTurns)
        assertTrue(plan.tools.isEmpty())
        assertTrue(plan.outputReserveTokens in 1..256)
        assertTrue(plan.estimatedPromptTokens + plan.outputReserveTokens + plan.toolResultBytes / 2 + 128 < 1024)
    }

    @Test
    fun promptToolCatalogIsChargedOnceAndContainsOnlyAdmittedSchemas() {
        val tools = (1..9).map { AgentToolDefinition("tool$it", "Description ".repeat(100), buildJsonObject {}) }
        val render: (List<AgentToolDefinition>) -> String = { selected ->
            selected.joinToString("\n") { it.name + ":" + it.description + it.inputSchema }
        }
        val system = "Instructions ".repeat(270)
        val user = "What can you do?"
        val plan = LocalContextPlanner.plan(emptyList(), user, system, tools, 8192, 8192, renderToolPrompt = render)
        val actualToolTokens = (render(plan.tools).toByteArray().size.toLong() + 1) / 2
        assertTrue(plan.tools.isNotEmpty())
        assertEquals(actualToolTokens, plan.toolDefinitionTokens)
        assertEquals((system.toByteArray().size.toLong() + 1) / 2 + (user.length + 1) / 2 + actualToolTokens, plan.estimatedPromptTokens)
        assertTrue(plan.estimatedPromptTokens + plan.outputReserveTokens + plan.toolResultBytes / 2 + 256 <= 8192)
    }

    @Test
    fun largeOutputPreferencePreservesUsableCatalogInsteadOfOneTool() {
        val tools = (1..9).map { AgentToolDefinition("tool$it", "Description ".repeat(30), buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(emptyList(), "What can you do?", "Instructions ".repeat(270), tools, 8192, 8192)
        assertEquals(3, plan.tools.size)
        assertTrue(plan.outputReserveTokens >= 512)
        assertTrue(plan.estimatedPromptTokens + plan.outputReserveTokens + plan.toolResultBytes / 2 + 256 < 8192)
    }

    @Test
    fun researchPreservesSearchAndReaderUnderCatalogPressure() {
        val search = AgentToolDefinition("web_search", "Search", buildJsonObject {})
        val reader = AgentToolDefinition("read_url", "Read", buildJsonObject {})
        val others = (1..20).map { AgentToolDefinition("tool$it", "Unrelated ".repeat(80), buildJsonObject {}) }
        val plan = LocalContextPlanner.plan(emptyList(), "Research with sources", "", others + search + reader, 1024, null)
        assertTrue(plan.tools.containsAll(listOf(search, reader)))
    }

    @Test
    fun fileReadingRemainsAvailableWithLargeOutputPreference() {
        val reader = AgentToolDefinition("read_file_slice", "Read file", buildJsonObject {})
        assertEquals(setOf(reader.name), LocalContextPlanner.requiredToolNames("Read the file", listOf(reader)))
    }

    private fun turn(text: String) = ConversationTurn(MessageV2(content = text, platformType = null), null, false)
}
