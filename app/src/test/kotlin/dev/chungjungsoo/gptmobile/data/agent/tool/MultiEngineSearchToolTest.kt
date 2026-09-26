package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits
import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MultiEngineSearchToolTest {
    private fun engine(name: String, action: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                name,
                "Web search",
                buildJsonObject {
                    put("properties", buildJsonObject { put("query", buildJsonObject { put("type", "string") }) })
                    put("required", JsonArray(listOf(JsonPrimitive("query"))))
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject) = action(callId, arguments)
        }
        return ResolvedAgentTool(tool, name, name, "web_search", name)
    }

    @Test fun `every engine runs and duplicate sources merge despite partial failure`() = runBlocking {
        val queried = mutableSetOf<String>()
        fun search(name: String, url: String) = engine(name) { id, args ->
            queried += name
            assertEquals(JsonPrimitive("Compose"), args["query"])
            AgentToolResult(
                id,
                ToolResultContent.Json(
                    buildJsonObject {
                        put(
                            "results",
                            JsonArray(
                                listOf(
                                    buildJsonObject {
                                        put("title", name)
                                        put("url", url)
                                        put("snippet", "Found")
                                    }
                                )
                            )
                        )
                    }
                ),
                false
            )
        }
        val broken = engine("broken") { _, _ ->
            queried += "broken"
            error("unavailable")
        }
        val result = MultiEngineSearchTool(listOf(search("one", "https://example.org/doc?utm_source=one"), search("two", "https://example.org/doc#section"), broken))
            .execute("parent", buildJsonObject { put("query", "Compose") })
        assertEquals(setOf("one", "two", "broken"), queried)
        assertFalse(result.isError)
        val json = (result.content as ToolResultContent.Json).value.jsonObject
        assertEquals(1, (json["results"] as JsonArray).size)
        assertEquals(3, (json["engines"] as JsonArray).size)
        assertEquals("parent", result.callId)
    }

    @Test fun `child permissions and shared call budget cannot be bypassed by fanout`() = runBlocking {
        var dispatched = 0
        val budget = ToolExecutionBudget(AgentRunLimits(maxToolCalls = 2))
        val engines = (1..4).map { index ->
            val resolved = engine("engine$index") { id, _ ->
                dispatched++
                AgentToolResult(id, ToolResultContent.Text("source"), false)
            }
            resolved.copy(tool = budget.bind(resolved.tool, authorize = { _, _ -> index != 1 }))
        }
        val result = MultiEngineSearchTool(engines).execute("parent", buildJsonObject { put("query", "news") })
        assertEquals(1, dispatched)
        assertTrue(result.outputBudgetExhausted)
        assertFalse(result.isError)
    }

    @Test fun `private search and unsupported schemas are never broadcast targets`() {
        val web = engine("web") { _, _ -> error("not executed") }
        assertTrue(web.isWebSearchEngine())
        assertFalse(web.copy(realToolName = "slack_search").isWebSearchEngine())
        assertFalse(web.copy(realToolName = "github_search_code").isWebSearchEngine())
        assertTrue(web.copy(realToolName = "brave_web_search").isWebSearchEngine())
        assertEquals(listOf("web_search"), aggregateWebSearch(listOf(web)).map { it.modelToolName })
    }
}
