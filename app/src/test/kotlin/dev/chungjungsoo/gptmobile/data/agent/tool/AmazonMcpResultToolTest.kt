package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonMcpResultToolTest {
    @Test
    fun `empty MCP searches keep the retail schema and provider errors remain failures`() = runBlocking {
        val empty = AgentToolResult("mcp", ToolResultContent.Json(buildJsonObject { put("result", "{\"organic_results\":[]}") }), false)
        val tool = AmazonMcpResultTool.wrap(fake(empty), "https://mcp.serpapi.com/mcp", "search")
        assertTrue(tool.execute("empty", arguments).content.toString().contains(AmazonProducts.SCHEMA))
        val failure = empty.copy(content = ToolResultContent.Json(buildJsonObject { put("result", "{\"error\":\"api_key=super-secret\"}") }))
        val result = AmazonMcpResultTool.wrap(fake(failure), "https://mcp.serpapi.com/mcp", "search").execute("failure", arguments)
        assertTrue(result.isError)
        assertFalse(result.content.toString().contains("super-secret"))
    }

    private val payload = Json.parseToJsonElement("""{"organic_results":[{"asin":"B000000001","title":"Headphones","price":"$49.99","extracted_price":49.99}]}""")
    private val arguments = buildJsonObject {
        put(
            "params",
            buildJsonObject {
                put("engine", "amazon")
                put("amazon_domain", "amazon.ca")
                put("k", "headphones")
            }
        )
    }

    @Test
    fun `hosted SerpApi Amazon results become product data and Google results stay unchanged`() = runBlocking {
        val result = AgentToolResult("mcp", ToolResultContent.Json(buildJsonObject { put("result", payload.toString()) }), false)
        val tool = AmazonMcpResultTool.wrap(fake(result), "https://mcp.serpapi.com/mcp", "search")
        val amazon = tool.execute("amazon", arguments)
        assertEquals(AmazonProducts.SCHEMA, AmazonProducts.text((amazon.content as ToolResultContent.Json).value as JsonObject, "schema"))
        val google = buildJsonObject {
            put(
                "params",
                buildJsonObject {
                    put("engine", "google")
                    put("q", "headphones")
                }
            )
        }
        assertSame(result, tool.execute("google", google))
    }

    @Test
    fun `unrelated endpoints and MCP errors are not reclassified`() = runBlocking {
        val failure = AgentToolResult("mcp", ToolResultContent.Json(payload), true)
        val delegate = fake(failure)
        assertSame(delegate, AmazonMcpResultTool.wrap(delegate, "https://mcp.serpapi.com.evil.example/mcp", "search"))
        assertSame(failure, AmazonMcpResultTool.wrap(delegate, "https://mcp.serpapi.com/mcp", "search").execute("mcp", arguments))
    }

    @Test
    fun `Bright Data product operations become cards without entering web search aggregation`() = runBlocking {
        val result = AgentToolResult("mcp", ToolResultContent.Json(payload), false)
        val delegate = fake(result, "web_data_amazon_product_search")
        val tool = AmazonMcpResultTool.wrap(delegate, "https://mcp.brightdata.com/mcp?groups=ecommerce", "web_data_amazon_product_search")
        val normalized = tool.execute(
            "mcp",
            buildJsonObject {
                put("domain", "https://www.amazon.ca")
                put("keyword", "headphones")
            }
        )
        assertFalse(normalized.isError)
        assertTrue(normalized.content.toString().contains(AmazonProducts.SCHEMA))
        assertEquals(null, WebSearchEngineAdapter.forTool("web_data_amazon_product_search", delegate.definition))
    }

    private fun fake(result: AgentToolResult, name: String = "search") = object : AgentTool {
        override val definition = AgentToolDefinition(name, "Retail product search", buildJsonObject { put("type", "object") })
        override suspend fun execute(callId: String, arguments: JsonObject) = result
    }
}
