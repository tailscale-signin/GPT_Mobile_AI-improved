package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnection
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionAuthType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonJanNaftaToolTest {
    @Test
    fun `search cards keep facts and validated affiliate links and apply profile limits`() = runBlocking {
        var sent = JsonObject(emptyMap())
        val response = "Top 1 results for \"headphones\" on Amazon CA:\n\n1. Headphones\n   1,299.99 CAD  ★4.8 (1,234)  ✓Prime\n   ASIN: B08N5WRWNW\n   Buy (affiliate): https://www.amazon.ca/dp/B08N5WRWNW?tag=host-20&linkCode=ll1"
        val tool = AmazonJanNaftaTool(fake(response) { sent = it }, "search_products", PluginExecutionSettings(searchResults = 3, amazonMarketplace = "amazon.ca"))
        val result = tool.execute(
            "search",
            buildJsonObject {
                put("query", "headphones")
                put("limit", 40)
            }
        )
        assertEquals("CA", AmazonProducts.text(sent, "marketplace"))
        assertEquals("3", AmazonProducts.text(sent, "limit"))
        val data = (result.content as ToolResultContent.Json).value as JsonObject
        assertEquals(AmazonProducts.SCHEMA, AmazonProducts.text(data, "schema"))
        val card = (data["products"] as JsonArray).single() as JsonObject
        assertEquals("1299.99", AmazonProducts.text(card, "priceAmount"))
        assertEquals("CAD", AmazonProducts.text(card, "currency"))
        assertEquals("1234", AmazonProducts.text(card, "reviewCount"))
        assertEquals("https://www.amazon.ca/dp/B08N5WRWNW", AmazonProducts.text(card, "url"))
        assertEquals("https://www.amazon.ca/dp/B08N5WRWNW?tag=host-20&linkCode=ll1", AmazonProducts.text(card, "affiliateUrl"))
        assertTrue(ResolvedAgentTool(tool, "host", "Amazon", "search_products", tool.definition.name).isAmazonProductTool())
    }

    @Test
    fun `unknown product prices and untagged links stay unknown and untagged`() = runBlocking {
        val response = "Keyboard\n— CAD  No ratings\nAvailability: In stock\nASIN: B08N5WRWNW · Marketplace: CA\nBuy (affiliate): https://www.amazon.ca/dp/B08N5WRWNW"
        val result = AmazonJanNaftaTool(fake(response), "get_product", PluginExecutionSettings()).execute(
            "detail",
            buildJsonObject {
                put("asin", "B08N5WRWNW")
                put("marketplace", "CA")
            }
        )
        val data = (result.content as ToolResultContent.Json).value as JsonObject
        val card = (data["products"] as JsonArray).single() as JsonObject
        assertFalse("priceAmount" in card)
        assertFalse("affiliateUrl" in card)
        assertEquals("In stock", AmazonProducts.text(card, "availability"))
        assertEquals(response, (data["sourceText"] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `deal output becomes cards but history comparisons watches and failures remain intact`() = runBlocking {
        val deal = "1 deals on Amazon CA:\n\n1. Keyboard\n   40.00 CAD (−20%) was 50.00 CAD\n   Buy (affiliate): https://www.amazon.ca/dp/B08N5WRWNW"
        val result = AmazonJanNaftaTool(fake(deal), "get_deals", PluginExecutionSettings(amazonMarketplace = "amazon.ca")).execute("deal", buildJsonObject {})
        assertTrue(result.content is ToolResultContent.Json)
        for (name in AmazonJanNaftaTool.TOOL_NAMES - setOf("get_product", "search_products", "get_deals")) {
            val raw = AgentToolResult("raw", ToolResultContent.Text("Price history and watch state on the host"), false)
            assertSame(raw, AmazonJanNaftaTool(fake(raw), name, PluginExecutionSettings()).execute("pass", buildJsonObject {}))
        }
        val failure = AgentToolResult("blocked", ToolResultContent.Text("Amazon request blocked"), true)
        assertSame(failure, AmazonJanNaftaTool(fake(failure), "search_products", PluginExecutionSettings()).execute("fail", buildJsonObject {}))
    }

    @Test
    fun `affiliate navigation rejects hostile hosts mismatched products and unsafe query fields`() {
        val root = "https://www.amazon.ca/dp/B08N5WRWNW"
        assertNull(AmazonProducts.affiliateUrl("$root?tag=one&tag=two", "amazon.ca", "B08N5WRWNW"))
        assertNull(AmazonProducts.affiliateUrl("$root?tag=one&redirect=https://evil.example", "amazon.ca", "B08N5WRWNW"))
        assertNull(AmazonProducts.affiliateUrl("$root?tag=one", "amazon.com", "B08N5WRWNW"))
        assertNull(AmazonProducts.affiliateUrl("$root?tag=one", "amazon.ca", "B000000001"))
        assertNull(AmazonProducts.affiliateUrl("https://www.amazon.ca.evil.example/dp/B08N5WRWNW?tag=one", "amazon.ca", "B08N5WRWNW"))
        assertNull(AmazonProducts.affiliateUrl("https://www.amazon.ca/gp/aws/cart/add.html?tag=one", "amazon.ca", "B08N5WRWNW"))
        assertEquals("$root?tag=owner-20&linkCode=ll1", AmazonProducts.affiliateUrl("$root?tag=owner-20", "amazon.ca", "B08N5WRWNW"))
    }

    @Test
    fun `adapter recognizes the complete upstream tool set only in the Amazon service`() {
        val connection = ToolConnection(connectionUid = "host", name = "Jan Nafta MCP", alias = "amazon_jannafta", type = ToolConnectionType.MCP, endpointUrl = "https://amazon.example/mcp", authType = ToolConnectionAuthType.NONE, secretRef = null, oauthClientId = null)
        assertTrue(AmazonJanNaftaTool.recognizes(connection, AmazonJanNaftaTool.TOOL_NAMES))
        assertFalse(AmazonJanNaftaTool.recognizes(connection, setOf("search_products")))
        assertFalse(AmazonJanNaftaTool.recognizes(connection.copy(name = "Unrelated", alias = "unrelated", endpointUrl = "https://unrelated.example/mcp"), AmazonJanNaftaTool.TOOL_NAMES))
    }

    private fun fake(text: String, capture: (JsonObject) -> Unit = {}) = fake(AgentToolResult("fixture", ToolResultContent.Text(text), false), capture)
    private fun fake(result: AgentToolResult, capture: (JsonObject) -> Unit = {}) = object : AgentTool {
        override val definition = AgentToolDefinition("search_products", "Product search", buildJsonObject { put("type", "object") })
        override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult {
            capture(arguments)
            return result
        }
    }
}
