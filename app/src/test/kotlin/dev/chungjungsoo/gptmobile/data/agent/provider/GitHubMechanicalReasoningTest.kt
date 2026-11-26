package dev.chungjungsoo.gptmobile.data.agent.provider

import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolExchange
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ProviderEvent
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.context.ReasoningMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubMechanicalReasoningTest {
    private val constraints = RequestConstraints(maximizeReasoning = true, requestRole = "primary")
    private val tools = listOf(AgentToolDefinition("github_read", "Read", json("""{"type":"object"}""")))
    private fun json(value: String) = Json.parseToJsonElement(value) as JsonObject
    private fun page(id: String = "saved-result-consume-one", complete: Boolean = false, nextView: String = "view", failed: Boolean = false): AgentToolExchange {
        val call = ProviderEvent.ToolCall(id, "github_read", json("""{"view_cursor":"view:8388608:1536"}"""))
        val result = AgentToolResult(id, ToolResultContent.Json(json("""{"schema":"github.result.v1","local_page_complete":$complete,"continuation_call":{"tool":"github_read","arguments":{"view_cursor":"$nextView:8389000:8192"}}}""")), failed)
        return AgentToolExchange(listOf(call), listOf(result))
    }

    @Test fun onlyAnUnfinishedVerifiedLocalPageGetsTheMechanicalPreference() {
        assertTrue(GitHubReasoningPolicy.supportsOpenAiMechanicalEffort("gpt-5.2"))
        assertFalse(GitHubReasoningPolicy.supportsOpenAiMechanicalEffort("gpt-5.2-pro"))
        assertFalse(GitHubReasoningPolicy.supportsOpenAiMechanicalEffort("o3-deep-research"))
        assertFalse(GitHubReasoningPolicy.supportsOpenAiMechanicalEffort("unadvertised-model"))
        assertTrue(GitHubReasoningPolicy.mechanicalContinuation(constraints, tools, listOf(page())))
        assertFalse(GitHubReasoningPolicy.mechanicalContinuation(constraints, tools, listOf(page(complete = true))))
        assertTrue(GitHubReasoningPolicy.mechanicalContinuation(constraints, tools, listOf(page(id = "model-call-one"))))
        assertFalse(GitHubReasoningPolicy.mechanicalContinuation(constraints, tools, listOf(page(nextView = "other-view"))))
        assertFalse(GitHubReasoningPolicy.mechanicalContinuation(constraints, tools, listOf(page(failed = true))))
        assertFalse(GitHubReasoningPolicy.mechanicalContinuation(constraints, emptyList(), listOf(page())))
    }

    @Test fun explicitModesReviewAndMixedWritesPreserveTheSelectedEffort() {
        for (mode in listOf(ReasoningMode.ON, ReasoningMode.OFF)) {
            assertFalse(GitHubReasoningPolicy.mechanicalContinuation(constraints.copy(reasoningModeOverride = mode), tools, listOf(page())))
        }
        for (role in listOf("reviewer", "github-review-repair", "synthesis")) {
            assertFalse(GitHubReasoningPolicy.mechanicalContinuation(constraints.copy(requestRole = role), tools, listOf(page())))
        }
        val read = page()
        val write = ProviderEvent.ToolCall("write", "github_commit", json("""{"action":"commit"}"""))
        val mixed = AgentToolExchange(read.calls + write, read.results + AgentToolResult("write", ToolResultContent.Json(json("{}")), false))
        assertFalse(GitHubReasoningPolicy.mechanicalContinuation(constraints, tools, listOf(mixed)))
        assertTrue(constraints.maximizeReasoning)
    }
}
