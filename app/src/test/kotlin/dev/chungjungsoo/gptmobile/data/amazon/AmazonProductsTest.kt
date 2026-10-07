package dev.chungjungsoo.gptmobile.data.amazon

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmazonProductsTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-07T12:00:00Z"), ZoneOffset.UTC)

    @Test
    fun `prices currency variants and timestamps remain product facts`() {
        val payload = Json.parseToJsonElement("""{"search_metadata":{"created_at":"2026-10-07 11:30:00 UTC"},"organic_results":[{"asin":"B000000001","title":"Headphones","link":"https://www.amazon.ca/dp/B000000001?tag=tracking","price":"$49.99 – $59.99","extracted_price":49.99,"extracted_max_price":59.99,"currency":"CAD","rating":4.5,"reviews":300,"seller":"Store","condition":"New","variant":"Blue","stock":"In stock","prime":true,"sponsored":false}]}""")
        val product = AmazonProducts.normalize(payload, "amazon.ca", "SerpApi", clock).single()
        assertEquals("49.99", AmazonProducts.text(product, "priceAmount"))
        assertEquals("59.99", AmazonProducts.text(product, "priceMaxAmount"))
        assertEquals("CAD", AmazonProducts.text(product, "currency"))
        assertEquals("Blue", AmazonProducts.text(product, "variant"))
        assertEquals("2026-10-07T11:30:00Z", AmazonProducts.text(product, "observedAt"))
        assertEquals("2026-10-07T12:00:00Z", AmazonProducts.text(product, "retrievedAt"))
        assertEquals("https://www.amazon.ca/dp/B000000001", AmazonProducts.text(product, "url"))
        assertFalse(product.toString().contains("tracking"))
    }

    @Test
    fun `unknown prices currency and sponsorship are not invented`() {
        val product = AmazonProducts.normalize(JsonArray(listOf(item())), "amazon.ca", "SerpApi", clock).single()
        assertFalse(product.containsKey("price"))
        assertFalse(product.containsKey("priceAmount"))
        assertFalse(product.containsKey("currency"))
        assertFalse(product.containsKey("sponsored"))
        assertFalse(product.containsKey("observedAt"))
    }

    @Test
    fun `sponsored rows require explicit inclusion and variants are not merged`() {
        val payload = JsonArray(listOf(item(), item(variant = "Blue"), item(variant = "Red"), item(sponsored = true)))
        assertEquals(3, AmazonProducts.normalize(payload, "amazon.ca", "SerpApi", clock).size)
        assertEquals(4, AmazonProducts.normalize(payload, "amazon.ca", "SerpApi", clock, includeSponsored = true).size)
    }

    @Test
    fun `canonical links reject injected hosts credentials schemes and mismatched marketplaces`() {
        for (link in listOf("https://amazon.ca.evil.example/dp/B000000001", "http://www.amazon.ca/dp/B000000001", "https://user@amazon.ca/dp/B000000001", "https://amazon.ca:443/dp/B000000001", "https://amazon.com/dp/B000000001")) {
            assertTrue(AmazonProducts.normalize(JsonArray(listOf(item(url = link))), "amazon.ca", "SerpApi", clock).isEmpty())
        }
        assertNull(AmazonProducts.productUrl("evil.example", "B000000001"))
        assertNull(AmazonProducts.productUrl("amazon.ca", "bad-asin"))
        assertNull(AmazonProducts.imageUrl("https://media-amazon.com.evil.example/a.jpg"))
        assertNull(AmazonProducts.imageUrl("https://m.media-amazon.com/a.jpg?api_key=secret"))
    }

    @Test
    fun `SerpApi MCP serialized payload and Bright Data arrays normalize`() {
        val response = buildJsonObject { put("organic_results", JsonArray(listOf(item()))) }
        val mcp = buildJsonObject { put("structuredContent", buildJsonObject { put("result", response.toString()) }) }
        assertEquals(1, AmazonProducts.normalize(mcp, "amazon.ca", "SerpApi", clock).size)
        val brightData = Json.parseToJsonElement("""[{"asin":"B000000001","name":"Headphones","url":"https://amazon.ca/dp/B000000001","final_price":39.95,"currency":"CAD","reviews_count":12}]""")
        val product = AmazonProducts.normalize(brightData, "amazon.ca", "Bright Data", clock).single()
        assertEquals("39.95", AmazonProducts.text(product, "priceAmount"))
        assertEquals("12", AmazonProducts.text(product, "reviewCount"))
    }

    @Test
    fun `extreme numbers invalid ratings and invalid records are dropped safely`() {
        val payload = Json.parseToJsonElement("""[{"asin":"B000000001","title":"Headphones","extracted_price":1e-1000000000,"rating":9,"reviews":-1},{"asin":"INVALID","title":"Bad product"}]""")
        val product = AmazonProducts.normalize(payload, "amazon.ca", "SerpApi", clock).single()
        assertFalse(product.containsKey("priceAmount"))
        assertFalse(product.containsKey("rating"))
        assertFalse(product.containsKey("reviewCount"))
    }

    @Test
    fun `output limits preserve valid JSON and disclose omitted products`() {
        val products = (1..10).map { item(variant = "Variant $it") }
        val normalized = AmazonProducts.normalize(JsonArray(products), "amazon.ca", "SerpApi", clock)
        val result = buildJsonObject {
            put("schema", AmazonProducts.SCHEMA)
            put("products", JsonArray(normalized))
        }
        val bounded = AmazonProducts.limitResult(result, 1000)
        assertTrue(bounded.toString().length <= 1000)
        assertEquals(AmazonProducts.SCHEMA, AmazonProducts.text(bounded, "schema"))
        assertTrue(bounded.containsKey("outputLimited"))
        assertTrue((bounded["products"] as JsonArray).size < products.size)
    }

    private fun item(variant: String? = null, sponsored: Boolean? = null, url: String? = null) = buildJsonObject {
        put("asin", "B000000001")
        put("title", "Headphones")
        variant?.let { put("variant", it) }
        sponsored?.let {
            put("sponsored", it)
            put("seller", "Sponsor")
        }
        url?.let { put("link", it) }
    }
}
