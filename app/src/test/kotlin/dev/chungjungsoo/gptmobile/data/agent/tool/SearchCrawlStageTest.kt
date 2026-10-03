package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchCrawlStageTest {
    private fun crawler(calls: MutableList<String>): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                "read_url",
                "Read a page",
                buildJsonObject {
                    put("properties", buildJsonObject { put("url", buildJsonObject { put("type", "string") }) })
                    put("required", JsonArray(listOf(JsonPrimitive("url"))))
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
                calls += (arguments.getValue("url") as JsonPrimitive).content
                return AgentToolResult(callId, ToolResultContent.Text("Page contents"), false)
            }
        }
        return ResolvedAgentTool(tool, null, "Reader", "read_url", "read_url")
    }
    private fun source(url: String) = buildJsonObject { put("url", url) }

    @Test fun `crawling is app owned and never assigns research to a reviewer`() = runTest {
        val calls = mutableListOf<String>()
        val stage = SearchCrawlStage(listOf(crawler(calls)), 2)
        val result = stage.execute("search", listOf(source("https://example.org/a")))
        assertEquals(listOf("https://example.org/a"), calls)
        assertEquals(JsonPrimitive("search"), result["owner"])
        assertFalse(result.containsKey("review"))
    }

    @Test fun `disabled crawl stage prevents queued page calls`() = runTest {
        val calls = mutableListOf<String>()
        val stage = SearchCrawlStage(listOf(crawler(calls)), 5, stillEnabled = { false })
        stage.execute("search", listOf(source("https://example.org/a")))
        assertTrue(calls.isEmpty())
    }

    @Test fun `direct crawling deduplicates and bounds found pages`() = runTest {
        val calls = java.util.Collections.synchronizedList(mutableListOf<String>())
        val stage = SearchCrawlStage(listOf(crawler(calls)), 1)
        stage.execute("search", listOf(source("https://example.org/a"), source("https://example.org/a"), source("https://example.org/b")))
        assertEquals(listOf("https://example.org/a"), calls)
    }

    @Test fun `web search descriptions are discovered and crawler names are excluded`() {
        assertTrue(isNamedWebSearch("explore", "Use this tool for web search"))
        assertTrue(isNamedWebSearch("provider_web_search", "Query"))
        assertFalse(isNamedWebSearch("provider_crawler", "Crawl web search results"))
        assertFalse(isNamedWebSearch("read_url", "Read web search pages"))
    }
}
