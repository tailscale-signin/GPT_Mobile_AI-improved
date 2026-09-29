package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.model.ModelDelegationSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalResearchWorkflowTest {
    private val config = ModelDelegationSettings(enabled = true, maxPages = 2, crawlDepth = 1, maxSearchQueries = 1)
    private fun tool(name: String, action: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(name, "", Json.parseToJsonElement(if (name == "read_url") """{"properties":{"url":{"type":"string"},"includeLinks":{"type":"boolean"}},"required":["url"]}""" else """{"properties":{"query":{"type":"string"}},"required":["query"]}""").jsonObject)
            override suspend fun execute(callId: String, arguments: JsonObject) = action(callId, arguments)
        }
        return ResolvedAgentTool(tool, null, name, name, name)
    }
    private fun response(id: String, json: String) = AgentToolResult(id, ToolResultContent.Json(Json.parseToJsonElement(json)), false)
    private fun worker(prompt: String): String = when {
        prompt.startsWith("Plan public-web") -> """{"queries":["local model latency"],"urls":[]}"""
        prompt.startsWith("Choose up to") -> """{"ids":["S1","S999"]}"""
        else -> "Measured latency is 42 ms [S1]."
    }

    @Test fun `local worker searches reads and crawls within host and page limits before handing off`() = runTest {
        val calls = mutableListOf<String>()
        val search = tool("web_search") { id, args ->
            calls += "search"
            assertEquals("local model latency", args.getValue("query").jsonPrimitive.content)
            response(id, """{"results":[{"title":"Official benchmark","url":"https://example.org/start","snippet":"Measured latency"}]}""")
        }
        val reader = tool("read_url") { id, args ->
            val url = args.getValue("url").jsonPrimitive.content
            calls += url
            assertEquals("true", args.getValue("includeLinks").jsonPrimitive.content)
            response(id, """{"content":"Latency is exactly 42 ms.","links":["https://example.org/details","https://other.example/outside","https://example.org/third"]}""")
        }
        val result = LocalResearchWorkflow(config, listOf(search, reader), { prompt, _ -> worker(prompt) }).run("Research local model latency", "turn")
        assertEquals(listOf("search", "https://example.org/start", "https://example.org/details"), calls)
        assertEquals(2, result.pagesRead)
        assertEquals(1, result.searches)
        assertTrue(result.handoff.contains("42 ms"))
        assertFalse(result.handoff.contains("S999"))
        assertFalse(result.handoff.contains("other.example"))
        assertTrue(result.handoff.toByteArray().size <= config.handoffTokens * 3)
    }

    @Test fun `disabled search and invalid plans never send guessed task text to an engine`() = runTest {
        var calls = 0
        val search = tool("web_search") { _, _ ->
            calls++
            error("Must not run")
        }
        val result = LocalResearchWorkflow(config, listOf(search), { _, _ -> "not JSON" }).run("Private task content", "bad", automatic = true)
        assertEquals(0, calls)
        assertTrue(result.handoff.contains("no guessed query"))
        val denied = LocalResearchWorkflow(config, emptyList(), { prompt, _ -> worker(prompt) }).run("Research latency", "disabled")
        assertTrue(denied.handoff.contains("not enabled"))
    }

    @Test fun `automatic non web task bypasses planner and local generation`() = runTest {
        var generations = 0
        val result = LocalResearchWorkflow(config, emptyList(), { _, _ ->
            generations++
            "unexpected"
        }).run("Summarize the paragraph I provided.", "skip", automatic = true)

        assertEquals(0, generations)
        assertEquals(0, result.searches)
        assertEquals(0, result.pagesRead)
        assertEquals("", result.handoff)
        assertEquals(DelegationTaskKind.SUMMARIZATION, result.taskKind)
        assertEquals(LocalResearchOutcome.NO_RESEARCH_NEEDED, result.outcome)
    }

    @Test fun `automatic web research with no evidence returns explicit zero result`() = runTest {
        val search = tool("web_search") { id, _ -> response(id, """{"results":[]}""") }
        val result = LocalResearchWorkflow(config, listOf(search), { prompt, _ -> worker(prompt) })
            .run("Research the latest local model latency", "empty", automatic = true)

        assertEquals(1, result.searches)
        assertEquals(0, result.pagesRead)
        assertEquals("", result.handoff)
        assertEquals(DelegationTaskKind.WEB_RESEARCH, result.taskKind)
        assertEquals(LocalResearchOutcome.NO_RESEARCH_RESULTS, result.outcome)
    }

    @Test fun `missing search tool returns tool unavailable without fake handoff`() = runTest {
        val result = LocalResearchWorkflow(config, emptyList(), { prompt, _ -> worker(prompt) })
            .run("Search the web for the latest benchmark", "unavailable", automatic = true)

        assertEquals(0, result.searches)
        assertEquals(0, result.pagesRead)
        assertEquals("", result.handoff)
        assertEquals(LocalResearchOutcome.TOOL_UNAVAILABLE, result.outcome)
    }

    @Test fun `task classification chooses bounded adaptive output budgets`() {
        val generous = config.copy(maxOutputTokens = 4096)
        assertEquals(DelegationTaskKind.PAGE_EXTRACTION, classifyDelegationTask("Read https://example.com/report"))
        assertEquals(DelegationTaskKind.WEB_RESEARCH, classifyDelegationTask("Find the latest release notes online"))
        assertEquals(DelegationTaskKind.CODE_ANALYSIS, classifyDelegationTask("Review this Kotlin code for a bug"))
        assertEquals(DelegationTaskKind.TOOL_SELECTION, classifyDelegationTask("Which tool should handle this?"))
        assertEquals(128, delegationOutputBudget(DelegationTaskKind.TOOL_SELECTION, generous))
        assertEquals(256, delegationOutputBudget(DelegationTaskKind.SEARCH_RESULT_PROCESSING, generous))
        assertEquals(512, delegationOutputBudget(DelegationTaskKind.WEB_RESEARCH, generous))
        assertEquals(1024, delegationOutputBudget(DelegationTaskKind.CODE_ANALYSIS, generous))
        assertEquals(200, delegationOutputBudget(DelegationTaskKind.CODE_ANALYSIS, generous.copy(maxOutputTokens = 200)))
    }
    @Test fun `shared tool allowance prevents page fetching and parent cancellation propagates`() = runTest {
        var pageCalls = 0
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 1))
        val search = tool("web_search") { id, _ -> response(id, """{"results":[{"title":"One","url":"https://example.org/one","snippet":"Snippet"}]}""") }
        val boundedSearch = search.copy(tool = budget.bind(search.tool))
        val reader = tool("read_url") { id, _ ->
            pageCalls++
            response(id, "{}")
        }
        val result = LocalResearchWorkflow(config, listOf(boundedSearch, reader), { prompt, _ -> worker(prompt) }).run("Research latency", "budget")
        assertEquals(0, pageCalls)
        assertTrue(result.handoff.contains("tool budget"))
        val cancelled = runCatching { LocalResearchWorkflow(config, emptyList(), { _, _ -> throw CancellationException("stop") }).run("test", "cancel") }.exceptionOrNull()
        assertTrue(cancelled is CancellationException)
    }

    @Test fun `timeout retains fetched sources and reports incomplete research`() = runTest {
        val search = tool("web_search") { id, _ -> response(id, """{"results":[{"title":"One","url":"https://example.org/one","snippet":"A verified search snippet"}]}""") }
        val result = LocalResearchWorkflow(config.copy(timeoutSeconds = 5, maxLocalModelCalls = 1, maxPages = 0), listOf(search), { prompt, _ ->
            if (prompt.startsWith("Plan")) {
                worker(prompt)
            } else {
                // The workflow timeout intentionally includes serialized-worker headroom
                // (one worker timeout plus transport grace), so exceed that full window.
                delay(36_000)
                "late"
            }
        }).run("Research latency", "timeout")
        assertTrue(result.handoff.contains("timed out"))
        assertTrue(result.handoff.contains("https://example.org/one"))
        assertEquals(0, result.pagesRead)
    }
}
