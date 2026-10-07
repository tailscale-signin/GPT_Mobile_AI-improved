package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ToolResultCheckpoint
import dev.chungjungsoo.gptmobile.data.amazon.AmazonProducts
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventResultType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEventStatus
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonProductResultsTest {
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
