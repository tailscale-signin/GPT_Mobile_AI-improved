package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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

class AmazonCombinedToolTest {
    @Test fun progressCheckpointsDoNotDiscardStructuredProductCards() = runBlocking {
        val paid = provider("amazon_search", "Amazon Search") { id, _ -> result(id, "SerpApi", "49.99") }
        val budget = dev.chungjungsoo.gptmobile.data.agent.ToolExecutionBudget(dev.chungjungsoo.gptmobile.data.agent.AgentRunLimits())
        val bounded = paid.copy(tool = budget.bind(paid.tool))
        repeat(dev.chungjungsoo.gptmobile.data.agent.ToolProgressTracker.INTERVAL - 1) {
            bounded.tool.execute("previous-$it", buildJsonObject { put("query", "previous product") })
        }
        val result = aggregateAmazonTools(listOf(bounded)).single().tool.execute("checkpoint", buildJsonObject { put("query", "headphones") })
        assertFalse(result.isError)
        val products = (result.content as ToolResultContent.Json).value.jsonObject["products"] as JsonArray
        assertEquals("49.99", AmazonProducts.text(products.single().jsonObject, "priceAmount"))
    }

    @Test fun bothProvidersStartTogetherMergeCardsAndReuseRepeatedLookups() = runBlocking {
        val paidStarted = CompletableDeferred<Unit>()
        val freeStarted = CompletableDeferred<Unit>()
        var executions = 0
        val paid = provider("amazon_search", "Amazon Search") { id, arguments ->
            executions++
            paidStarted.complete(Unit)
            withTimeout(1000) { freeStarted.await() }
            assertEquals(JsonPrimitive(20), arguments["minPrice"])
            result(id, "SerpApi", "49.99")
        }
        val free = provider(AmazonNativeTool.SEARCH, "Amazon Research Free") { id, arguments ->
            executions++
            freeStarted.complete(Unit)
            withTimeout(1000) { paidStarted.await() }
            assertEquals(JsonPrimitive("20"), arguments["minPrice"])
            assertEquals(JsonPrimitive("source"), arguments["sort"])
            result(id, "Amazon public pages", "39.99", "Detailed description")
        }
        val tools = aggregateAmazonTools(listOf(paid, free))
        assertEquals(listOf("amazon_search"), tools.map { it.modelToolName })
        val args = buildJsonObject {
            put("query", "headphones")
            put("marketplace", "amazon.ca")
            put("sort", "featured")
            put("minPrice", 20)
        }
        val first = tools.single().tool.execute("first", args)
        val products = (first.content as ToolResultContent.Json).value.jsonObject["products"] as JsonArray
        assertFalse(first.isError)
        assertEquals(1, products.size)
        assertEquals("49.99", AmazonProducts.text(products.single().jsonObject, "priceAmount"))
        assertEquals("Detailed description", AmazonProducts.text(products.single().jsonObject, "description"))
        assertEquals(2, (products.single().jsonObject["providers"] as JsonArray).size)
        val repeated = tools.single().tool.execute("second", args)
        assertTrue(repeated.sharedResult)
        assertEquals("second", repeated.callId)
        assertEquals(2, executions)
    }

    @Test fun successfulSiblingSurvivesPublicPageFailureAndForeignMarketsSkipUnsupportedProvider() = runBlocking {
        var freeCalls = 0
        val free = provider(AmazonNativeTool.SEARCH, "Amazon Research Free") { _, _ ->
            freeCalls++
            error("Blocked")
        }
        val paid = provider("amazon_search", "Amazon Search") { id, _ -> result(id, "SerpApi", "49.99") }
        val tool = aggregateAmazonTools(listOf(free, paid)).single().tool
        assertFalse(tool.execute("partial", buildJsonObject { put("query", "headphones") }).isError)
        val foreign = tool.execute(
            "foreign",
            buildJsonObject {
                put("query", "headphones")
                put("marketplace", "amazon.co.uk")
            }
        )
        assertFalse(foreign.isError)
        assertEquals(1, freeCalls)
        val statuses = (foreign.content as ToolResultContent.Json).value.jsonObject["providers"] as JsonArray
        assertEquals(2, statuses.size)
    }

    private fun provider(name: String, label: String, action: suspend (String, JsonObject) -> AgentToolResult): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(
                name,
                "Amazon product search",
                buildJsonObject {
                    put(
                        "properties",
                        buildJsonObject {
                            listOf("query", "marketplace", "sort", "minPrice").forEach { put(it, buildJsonObject { put("type", "string") }) }
                        }
                    )
                }
            )
            override suspend fun execute(callId: String, arguments: JsonObject) = action(callId, arguments)
        }
        return ResolvedAgentTool(tool, label, label, AmazonSearchTool.SEARCH, name)
    }

    private fun result(id: String, provider: String, price: String, description: String? = null) = AgentToolResult(
        id,
        ToolResultContent.Json(
            buildJsonObject {
                put("schema", AmazonProducts.SCHEMA)
                put(
                    "products",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("marketplace", "amazon.ca")
                                put("asin", "B000000001")
                                put("url", "https://www.amazon.ca/dp/B000000001")
                                put("title", "Headphones")
                                put("provider", provider)
                                put("price", "CAD $price")
                                put("priceAmount", price)
                                put("currency", "CAD")
                                description?.let { put("description", it) }
                            }
                        )
                    )
                )
            }
        ),
        false
    )
}
