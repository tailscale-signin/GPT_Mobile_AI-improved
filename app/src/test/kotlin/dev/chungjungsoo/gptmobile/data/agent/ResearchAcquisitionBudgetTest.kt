package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.model.DeepResearchSettings
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ResearchAcquisitionBudgetTest {
    private fun fixture() = object : AgentTool {
        override val definition = AgentToolDefinition("read_url", "Read-only fixture", JsonObject(emptyMap()))
        override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Text("Retained source body"), false)
    }

    @Test fun researchAcquisitionExceedsObservedAndCumulativeOutputCeilings() = runTest {
        val parent = AgentRunLimits(maxToolCalls = 2, maxObservedToolCalls = 256, maxToolOutputBytes = 4096)
        val budget = ToolExecutionBudget(researchAcquisitionLimits(parent, DeepResearchSettings().forEffort(100)))
        val reader = budget.bind(fixture())
        repeat(2501) { index ->
            val result = reader.execute("read-$index", JsonObject(emptyMap()))
            assertFalse(result.isError)
            assertFalse(result.toolCallBudgetExhausted)
            assertFalse(result.outputBudgetExhausted)
            assertNull(result.toolCallBudgetLimit)
        }
        assertTrue(budget.canExecute())
    }

    @Test fun ordinaryToolsKeepTheirConfiguredLimit() = runTest {
        val reader = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2)).bind(fixture())
        assertFalse(reader.execute("one", JsonObject(emptyMap())).isError)
        assertFalse(reader.execute("two", JsonObject(emptyMap())).isError)
        assertTrue(reader.execute("three", JsonObject(emptyMap())).isError)
    }
}
