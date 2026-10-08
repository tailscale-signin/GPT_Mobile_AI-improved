package dev.chungjungsoo.gptmobile.data.agent.tool

import dev.chungjungsoo.gptmobile.data.agent.AgentTool
import dev.chungjungsoo.gptmobile.data.agent.AgentToolDefinition
import dev.chungjungsoo.gptmobile.data.agent.AgentToolResult
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import kotlinx.coroutines.CompletableDeferred
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

class AmazonCombinedToolTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"query":{"type":"string"},"minPrice":{"type":"string"}}}""") as JsonObject
    private val arguments = buildJsonObject {
        put("query", "audio")
        put("minPrice", 10)
    }
    private fun child(name: String, allowed: suspend () -> Boolean = { true }, execute: suspend (JsonObject) -> AgentToolResult): ResolvedAgentTool {
        val tool = object : AgentTool {
            override val definition = AgentToolDefinition(name, "Amazon test provider", schema)
            override suspend fun execute(callId: String, arguments: JsonObject): AgentToolResult = execute(arguments).copy(callId = callId)
        }
        return ResolvedAgentTool(tool, null, name, AmazonSearchTool.SEARCH, name, canReuseResult = allowed)
    }
    private fun listing(provider: String, description: String? = null) = AgentToolResult(
        "result",
        ToolResultContent.Json(
            buildJsonObject {
                put("schema", AmazonProducts.SCHEMA)
                put(
                    "products",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("asin", "B000000001")
                                put("marketplace", "amazon.com")
                                put("title", "Headphones")
                                put("provider", provider)
                                description?.let { put("description", it) }
                            }
                        )
                    )
                )
            }
        ),
        isError = false
    )

    @Test fun providersStartTogetherMergeProductsAndReuseOnlyWhileAuthorized() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var requests = 0
        var allowed = true
        val first = child("free", { allowed }) { args ->
            requests++
            firstStarted.complete(Unit)
            secondStarted.await()
            assertEquals("\"10\"", args["minPrice"].toString())
            if (allowed) listing("free_native") else AgentToolResult("denied", ToolResultContent.Text("disabled"), true)
        }
        val second = child("paid") {
            requests++
            secondStarted.complete(Unit)
            firstStarted.await()
            listing("serpapi", "Full product description")
        }
        val aggregate = aggregateAmazonTools(listOf(first, second)).single().tool
        val result = aggregate.execute("one", arguments)
        assertFalse(result.isError)
        val payload = (result.content as ToolResultContent.Json).value as JsonObject
        val products = payload["products"] as JsonArray
        assertEquals(1, products.size)
        assertEquals("Full product description", AmazonProducts.text(products.single() as JsonObject, "description"))
        assertTrue(aggregate.execute("two", arguments).sharedResult)
        assertEquals(2, requests)
        allowed = false
        val revoked = aggregate.execute("three", arguments)
        assertFalse(revoked.isError)
        assertFalse(revoked.sharedResult)
        assertEquals(4, requests)
        assertEquals("serpapi", AmazonProducts.text((((revoked.content as ToolResultContent.Json).value as JsonObject)["products"] as JsonArray).single() as JsonObject, "provider"))
    }

    @Test fun retainedNativeProductsAndUnverifiedDiscoverySurviveProviderOutputLimits() = runBlocking {
        val full = (listing("free_native").content as ToolResultContent.Json).value as JsonObject
        val discovery = buildJsonObject {
            put("asin", "B000000002")
            put("marketplace", "amazon.com")
            put("title", "Unconfirmed headphones")
            put("price", "$29.99")
            put("priceFilterVerified", false)
        }
        val retained = JsonObject(full + ("unverifiedProducts" to JsonArray(listOf(discovery))))
        val limited = JsonObject(full + ("products" to JsonArray(emptyList())))
        val native = child("free") { AgentToolResult("limited", ToolResultContent.Json(limited), false, retainedContent = ToolResultContent.Json(retained)) }
        val other = child("other") { AgentToolResult("blocked", ToolResultContent.Text("blocked"), true) }
        val result = AmazonCombinedTool(listOf(native, other), false).execute("combined", arguments)
        val data = (result.content as ToolResultContent.Json).value as JsonObject
        assertFalse(result.isError)
        assertEquals(1, (data["products"] as JsonArray).size)
        assertEquals(discovery, (data["unverifiedProducts"] as JsonArray).single())
        assertEquals("partial", AmazonProducts.text(data, "status"))
    }

    @Test fun confirmedProviderListingSupersedesTheSameUnverifiedDiscovery() = runBlocking {
        val full = (listing("free_native").content as ToolResultContent.Json).value as JsonObject
        val discovery = child("free") {
            AgentToolResult("discovery", ToolResultContent.Json(JsonObject(full + ("products" to JsonArray(emptyList())) + ("unverifiedProducts" to full.getValue("products")))), false)
        }
        val known = child("paid") { listing("serpapi") }
        val result = AmazonCombinedTool(listOf(discovery, known), false).execute("merged", arguments)
        val data = (result.content as ToolResultContent.Json).value as JsonObject
        assertEquals(1, (data["products"] as JsonArray).size)
        assertFalse(data.containsKey("unverifiedProducts"))
    }

    @Test fun oneProviderFailurePreservesVerifiedListingsAndSingleProviderRemainsUsable() = runBlocking {
        val good = child("paid") { listing("serpapi") }
        val bad = child("free") { AgentToolResult("failed", ToolResultContent.Text("quota"), true) }
        val result = AmazonCombinedTool(listOf(good, bad), false).execute("partial", arguments)
        assertFalse(result.isError)
        assertEquals("partial", AmazonProducts.text((result.content as ToolResultContent.Json).value as JsonObject, "status"))
        assertEquals(listOf(good), aggregateAmazonTools(listOf(good)))
    }
}
