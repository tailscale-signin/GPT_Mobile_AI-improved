package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint
import dev.chungjungsoo.gptmobile.data.agent.ToolResultContent
import dev.chungjungsoo.gptmobile.data.agent.tool.AmazonNativeTool
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFetchResult
import dev.chungjungsoo.gptmobile.data.amazon.AmazonFreeMarket
import dev.chungjungsoo.gptmobile.data.amazon.AmazonItemFailure
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductObservation
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProductRequest
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProvider
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadContext
import dev.chungjungsoo.gptmobile.data.amazon.AmazonReadError
import dev.chungjungsoo.gptmobile.data.amazon.AmazonSearchRequest
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import dev.chungjungsoo.gptmobile.data.model.PluginExecutionSettings
import java.time.Instant
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonProductResultsTest {
    @Test fun restoredUnpricedProductsCannotBecomeBubbles() {
        for (price in listOf("Price unavailable", "", "Unavailable (2 offers)")) {
            assertTrue(amazonProductResults(listOf(event(1, "Blue", price))).isEmpty())
            assertTrue(collectChatSources("", listOf(event(1, "Blue", price))).sources.isEmpty())
        }
    }

    @Test
    fun `product searches and detail lookups also populate the source picker`() {
        for (name in listOf("amazon_search", "amazon_get_products")) {
            val saved = event(1, "Blue", "$49.99").copy(toolName = name, modelToolName = "${name}__shopping")
            val sources = collectChatSources("", listOf(saved))
            assertEquals("https://www.amazon.ca/dp/B000000001", sources.sources.single().url)
            assertEquals("Headphones", sources.sources.single().title)
        }
    }

    @Test
    fun `checkpoint results restore cards and the latest facts win without merging variants`() {
        val older = event(1, "Blue", "$49.99")
        val newer = event(2, "Blue", "$39.99")
        val variant = event(3, "Red", "$44.99")
        val products = amazonProductResults(listOf(older, newer, variant))
        assertEquals(2, products.size)
        assertEquals("$39.99", AmazonProducts.text(products.single { AmazonProducts.text(it, "variant") == "Blue" }, "price"))
    }

    @Test
    fun `failed events and unsafe restored product links cannot produce cards`() {
        val valid = event(1, "Blue", "$49.99")
        assertTrue(amazonProductResults(listOf(valid.copy(isError = true))).isEmpty())
        assertTrue(amazonProductResults(listOf(valid.copy(status = ToolEventStatus.FAILED))).isEmpty())
        assertTrue(amazonProductResults(listOf(event(2, "Blue", "$49.99", "https://amazon.ca.evil.example/dp/B000000001"))).isEmpty())
    }

    @Test
    fun `limited native research still renders every retained product and price`() = runBlocking {
        val products = (1..10).map { index ->
            AmazonProductObservation("B${index.toString().padStart(9, '0')}", AmazonFreeMarket.CANADA, "Headphones $index", Instant.parse("2026-10-08T00:00:00Z"), "search_page", price = "$49.99", currency = "CAD")
        }
        val provider = object : AmazonProvider {
            override suspend fun search(request: AmazonSearchRequest, context: AmazonReadContext) = AmazonFetchResult(products)
            override suspend fun products(request: AmazonProductRequest, context: AmazonReadContext) = error("unused")
        }
        val result = AmazonNativeTool(provider, false, { PluginExecutionSettings(maxOutputCharacters = 1000) }, { true })
            .execute("limited", buildJsonObject { put("query", "headphones") })
        val retained = (result.retainedContent as ToolResultContent.Json).value.toString()
        val display = (result.content as ToolResultContent.Json).value.toString()
        val saved = event(1, "Blue", "$49.99").copy(
            modelToolName = AmazonNativeTool.SEARCH,
            result = ToolResultCheckpoint.encode(retained, ToolEventResultType.JSON, display)
        )
        assertTrue(display.length <= 1000)
        assertFalse(result.isError)
        val cards = amazonProductResults(listOf(saved))
        assertEquals(10, cards.size)
        assertTrue(cards.all { amazonProductPrice(it) == "$49.99 CAD" })
    }

    @Test
    fun `filtered discovery creates a price bubble without claiming a verified budget match`() {
        val item = AmazonProductObservation("B000000001", AmazonFreeMarket.CANADA, "Headphones", Instant.parse("2026-10-08T00:00:00Z"), "search_page", price = "$49.99")
        val payload = AmazonFetchResult(emptyList(), listOf(AmazonItemFailure(AmazonReadError.PRICE_UNAVAILABLE, "Unconfirmed currency")), unverifiedProducts = listOf(item)).toJson("discovery", AmazonFreeMarket.CANADA)
        val saved = event(1, "Blue", "$49.99").copy(result = payload.toString(), resultType = ToolEventResultType.JSON)
        val card = amazonProductResults(listOf(saved)).single()
        assertEquals("$49.99", amazonProductPrice(card))
        assertEquals("false", AmazonProducts.text(card, "priceFilterVerified"))
        assertNull(amazonResultNotice(listOf(saved)))
        assertEquals("https://www.amazon.ca/dp/B000000001", collectChatSources("", listOf(saved)).sources.single().url)
    }

    @Test
    fun `empty and blocked searches show different visible reasons`() {
        val empty = AmazonFetchResult(emptyList()).toJson("empty", AmazonFreeMarket.CANADA)
        val blocked = AmazonFetchResult(emptyList(), listOf(AmazonItemFailure(AmazonReadError.CHALLENGE_REQUIRED, "Blocked"))).toJson("blocked", AmazonFreeMarket.CANADA)
        val saved = event(1, "Blue", "$49.99").copy(result = empty.toString(), resultType = ToolEventResultType.JSON)
        assertTrue(amazonResultNotice(listOf(saved)).orEmpty().startsWith("No products matched"))
        val failed = saved.copy(result = blocked.toString(), status = ToolEventStatus.FAILED, isError = true)
        assertTrue(amazonResultNotice(listOf(failed)).orEmpty().startsWith("Amazon blocked"))
        assertTrue(amazonProductResults(listOf(failed)).isEmpty())
    }

    @Test
    fun `a numeric provider price remains visible when no display string is supplied`() {
        val product = buildJsonObject {
            put("priceAmount", "49.99")
            put("currency", "CAD")
        }
        assertEquals("49.99 CAD", amazonProductPrice(product))
        for (label in listOf("Price unavailable", "Unavailable (2 offers)", "N/A")) {
            val conflicting = JsonObject(product + ("price" to kotlinx.serialization.json.JsonPrimitive(label)))
            assertEquals("49.99 CAD", amazonProductPrice(conflicting))
        }
        assertEquals("Price unavailable", amazonProductPrice(JsonObject(emptyMap())))
    }

    private fun event(sequence: Int, variant: String, price: String, url: String = "https://www.amazon.ca/dp/B000000001"): ToolEvent {
        val payload = buildJsonObject {
            put("schema", AmazonProducts.SCHEMA)
            put(
                "products",
                JsonArray(
                    listOf(
                        buildJsonObject {
                            put("asin", "B000000001")
                            put("marketplace", "amazon.ca")
                            put("title", "Headphones")
                            put("url", url)
                            put("variant", variant)
                            put("price", price)
                            put("provider", "SerpApi")
                        }
                    )
                )
            )
        }.toString()
        return ToolEvent(
            eventId = "event-$sequence",
            runId = "run",
            sequence = sequence,
            callId = "call-$sequence",
            connectionUidSnapshot = "shopping",
            connectionNameSnapshot = "Amazon Search",
            toolName = "amazon_search",
            modelToolName = "amazon_search__shopping",
            arguments = "{}",
            result = ToolResultCheckpoint.encode(payload, ToolEventResultType.JSON, "Product details"),
            resultType = ToolEventResultType.CHECKPOINT,
            status = ToolEventStatus.COMPLETED,
            completedAt = sequence.toLong()
        )
    }
}
