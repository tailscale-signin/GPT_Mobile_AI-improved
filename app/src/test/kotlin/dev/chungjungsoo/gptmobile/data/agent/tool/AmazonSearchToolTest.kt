package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProviderException
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonSearchToolTest {
    @Test fun modelRequestedUSCannotOverrideCanadaAndUnpricedRowsAreNotSourced() = runBlocking {
        var domain: String? = null
        val tool = AmazonSearchTool({ params ->
            domain = params["amazon_domain"]
            response
        }, PluginExecutionSettings(amazonMarketplace = "amazon.ca"))
        val result = tool.execute("canada", Json.parseToJsonElement("""{"query":"audio","marketplace":"amazon.com"}""") as JsonObject)
        assertEquals("amazon.ca", domain)
        assertEquals(1, products(result).size)
        assertFalse(result.content.toString().contains("Speakers"))
    }

    private val response = Json.parseToJsonElement("""{"organic_results":[{"asin":"B000000001","title":"Headphones","price":"$49.99","extracted_price":49.99},{"asin":"B000000002","title":"Speakers"}]}""") as JsonObject

    @Test
    fun `search uses correct API parameters and respects configured marketplace and limits`() = runBlocking {
        var request = emptyMap<String, String>()
        val tool = AmazonSearchTool(fetch = {
            request = it
            response
        }, settings = PluginExecutionSettings(searchResults = 1, amazonMarketplace = "amazon.ca"))
        val result = tool.execute(
            "search",
            buildJsonObject {
                put("query", "headphones")
                put("maxResults", 10)
                put("sort", "price_asc")
                put("page", 2)
                put("fresh", true)
            }
        )
        assertFalse(result.isError)
        assertEquals(mapOf("engine" to "amazon", "amazon_domain" to "amazon.ca", "k" to "headphones", "s" to "price-asc-rank", "page" to "2", "no_cache" to "true"), request)
        assertEquals(1, products(result).size)
        assertEquals(AmazonProducts.SCHEMA, AmazonProducts.text((result.content as ToolResultContent.Json).value as JsonObject, "schema"))
    }

    @Test
    fun `freshness preference cannot be disabled by a model argument`() = runBlocking {
        var request = emptyMap<String, String>()
        val tool = AmazonSearchTool({
            request = it
            response
        }, PluginExecutionSettings(amazonFreshPrices = true))
        tool.execute(
            "search",
            buildJsonObject {
                put("query", "headphones")
                put("fresh", false)
            }
        )
        assertEquals("true", request["no_cache"])
    }

    @Test
    fun `invalid inputs never contact the provider`() = runBlocking {
        var requests = 0
        val tool = AmazonSearchTool({
            requests++
            response
        })
        for (raw in listOf("""{"query":""}""", """{"query":"test","marketplace":"amazon.ca.evil.example"}""", """{"query":"test","page":0}""", """{"query":"test","maxResults":11}""", """{"query":"test","fresh":"true"}""", """{"query":"test","sort":"unsupported"}""", """{"query":"test","minPrice":50,"maxPrice":10}""", """{"query":"test","api_key":"injected"}""")) {
            assertTrue(raw, tool.execute("invalid", Json.parseToJsonElement(raw) as JsonObject).isError)
        }
        assertEquals(0, requests)
    }

    @Test
    fun `price bounds filter only known amounts and disclose page scope`() = runBlocking {
        val result = AmazonSearchTool({ response }).execute(
            "prices",
            buildJsonObject {
                put("query", "audio")
                put("minPrice", 10)
                put("maxPrice", 50)
            }
        )
        assertEquals(1, products(result).size)
        assertTrue(result.content.toString().contains("retrieved page"))
    }

    @Test
    fun `provider errors do not claim successful empty searches or reveal raw bodies`() = runBlocking {
        val nullableError = JsonObject(response + ("error" to kotlinx.serialization.json.JsonNull))
        assertFalse(AmazonSearchTool({ nullableError }).execute("success", buildJsonObject { put("query", "audio") }).isError)
        for (payload in listOf("""{"error":"api_key=super-secret"}""", "{}", """{"organic_results":[{"title":"No ASIN"}]}""")) {
            val tool = AmazonSearchTool({ Json.parseToJsonElement(payload) as JsonObject })
            val result = tool.execute("failure", buildJsonObject { put("query", "audio") })
            assertTrue(result.isError)
            assertFalse(result.content.toString().contains("super-secret"))
        }
        assertFalse(AmazonSearchTool({ buildJsonObject { put("organic_results", JsonArray(emptyList())) } }).execute("empty", buildJsonObject { put("query", "audio") }).isError)
    }

    @Test
    fun `details uses one request per ASIN and reports partial failures`() = runBlocking {
        val calls = mutableListOf<Map<String, String>>()
        val tool = AmazonSearchTool(fetch = { params ->
            calls += params
            if (params["asin"] == "B000000002") throw AmazonProviderException("Provider limit reached.")
            buildJsonObject {
                put(
                    "product_results",
                    buildJsonObject {
                        put("asin", params.getValue("asin"))
                        put("title", "Headphones")
                    }
                )
            }
        }, settings = PluginExecutionSettings(amazonMarketplace = "amazon.com"), productDetails = true)
        val result = tool.execute("details", Json.parseToJsonElement("""{"asins":["B000000001","B000000002"],"marketplace":"amazon.com"}""") as JsonObject)
        assertFalse(result.isError)
        assertEquals(2, calls.size)
        assertTrue(calls.all { it["engine"] == "amazon_product" && it["amazon_domain"] == "amazon.com" })
        assertEquals(1, products(result).size)
        assertTrue(result.content.toString().contains("partialErrors"))
    }

    @Test
    fun `details rejects duplicate malformed and excessive ASIN requests before spending credits`() = runBlocking {
        var calls = 0
        val tool = AmazonSearchTool(fetch = {
            calls++
            response
        }, productDetails = true)
        for (raw in listOf("""{"asins":[]}""", """{"asins":["BAD"]}""", """{"asins":["B000000001","B000000001"]}""", """{"asins":["B000000001","B000000002","B000000003","B000000004","B000000005","B000000006"]}""")) {
            assertTrue(tool.execute("invalid", Json.parseToJsonElement(raw) as JsonObject).isError)
        }
        assertEquals(0, calls)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation propagates to the run`() = runBlocking<Unit> {
        AmazonSearchTool({ throw CancellationException("cancel") }).execute("cancel", buildJsonObject { put("query", "audio") })
        Unit
    }

    private fun products(result: AgentToolResult) = ((result.content as ToolResultContent.Json).value as JsonObject)["products"] as JsonArray
}
