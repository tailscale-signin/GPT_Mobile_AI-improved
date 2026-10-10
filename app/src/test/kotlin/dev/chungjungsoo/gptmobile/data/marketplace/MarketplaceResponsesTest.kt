package dev.chungjungsoo.gptmobile.data.marketplace

import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MarketplaceResponsesTest {
    @Test
    fun emptyErrorFieldsAreNotProviderFailures() {
        listOf("null", "false", "\"\"", "[]", "{}", "[null, false]").forEach {
            assertFalse(it, hasMeaningfulProviderError(Json.parseToJsonElement(it)))
        }
        listOf("\"denied\"", "{\"code\":401}", "[\"error\"]", "true").forEach {
            assertTrue(it, hasMeaningfulProviderError(Json.parseToJsonElement(it)))
        }
    }

    @Test
    fun retryAfterSupportsSecondsDatesAndBounds() {
        val now = Instant.parse("2026-10-10T12:00:00Z").toEpochMilli()
        assertEquals(5000, retryAfterMillis("5", now))
        assertEquals(300_000, retryAfterMillis("300", now))
        assertEquals(120_000, retryAfterMillis("Sat, 10 Oct 2026 12:02:00 GMT", now))
        assertEquals(60_000, retryAfterMillis("invalid", now))
        assertEquals(86_400_000, retryAfterMillis(Long.MAX_VALUE.toString(), now))
        assertEquals(1000, retryAfterMillis("-1", now))
    }

    @Test
    fun largeValidResultRetainsIdentifiersAndSignalsOmissions() {
        val original = buildJsonObject {
            put("description", "large".repeat(50_000))
            put("id", "stable-place-123")
            put("url", "https://example.org/place/123")
            put("name", "Example place")
        }
        val result = boundMarketplaceEvidence(original, 5, 1000)
        val data = result.data as JsonObject
        assertTrue(result.truncated)
        assertEquals(JsonPrimitive("stable-place-123"), data["id"])
        assertEquals(JsonPrimitive("https://example.org/place/123"), data["url"])
        assertEquals(JsonNull, data["description"])
        assertTrue(result.data.toString().length <= 1000)
        assertEquals(result.data, Json.parseToJsonElement(result.data.toString()))
    }

    @Test
    fun truncationIsExplicitForLargeArraysAndEscapedStrings() {
        val result = boundMarketplaceEvidence(Json.parseToJsonElement("[1,2,3,4]"), 2)
        assertEquals("[1,2]", result.data.toString())
        assertTrue(result.truncated)
        val exact = boundMarketplaceEvidence(Json.parseToJsonElement("{\"name\":\"small\",\"id\":1}"), 10)
        assertFalse(exact.truncated)
        val escaped = boundMarketplaceEvidence(buildJsonObject { put("name", "\n".repeat(999)) }, 10, 100)
        assertTrue(escaped.truncated)
        assertTrue(escaped.data.toString().length <= 100)
    }

    @Test
    fun recordLimitsDoNotCorruptCoordinatePairs() {
        val input = Json.parseToJsonElement("""{"features":[{"id":1,"geometry":{"coordinates":[-79.38,43.65]}}]}""")
        val bounded = boundMarketplaceEvidence(input, 1)
        assertEquals(input, bounded.data)
        assertFalse(bounded.truncated)
    }

    @Test
    fun failureMessagesExplainRecoveryWithoutEchoingSecrets() {
        assertTrue(marketplaceFailureMessage(IllegalStateException("Daily request allowance reached.")).contains("Daily request"))
        assertTrue(marketplaceFailureMessage(MarketplaceRegistryRepairRequired()).contains("Repair"))
        assertTrue(marketplaceFailureMessage(IllegalArgumentException("Package integrity check failed.")).contains("verification"))
        assertFalse(marketplaceFailureMessage(java.io.IOException("https://example.org?token=private-key")).contains("private-key"))
    }
}
