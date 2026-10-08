package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfiguredPluginToolAmazonTest {
    @Test
    fun outputLimitRetainsFullRetailFactsForCardsAndProviderAggregation() = runBlocking {
        val products = (1..10).map { index ->
            buildJsonObject {
                put("asin", "B${index.toString().padStart(9, '0')}")
                put("marketplace", "amazon.ca")
                put("title", "Headphones $index")
                put("url", "https://www.amazon.ca/dp/B${index.toString().padStart(9, '0')}")
                put("price", "$49.99")
                put("description", "Detail ".repeat(500))
            }
        }
        val full = buildJsonObject {
            put("schema", AmazonProducts.SCHEMA)
            put("products", JsonArray(products))
        }
        val delegate = object : AgentTool {
            override val definition = AgentToolDefinition("amazon_search", "Search Amazon", JsonObject(emptyMap()))
            override suspend fun execute(callId: String, arguments: JsonObject) = AgentToolResult(callId, ToolResultContent.Json(full), false)
        }
        val result = ConfiguredPluginTool(delegate, PluginExecutionSettings(maxOutputCharacters = 1000)).execute("limited", JsonObject(emptyMap()))
        assertTrue((result.content as ToolResultContent.Json).value.toString().length <= 1000)
        assertEquals(full, (result.retainedContent as ToolResultContent.Json).value)
    }
}
