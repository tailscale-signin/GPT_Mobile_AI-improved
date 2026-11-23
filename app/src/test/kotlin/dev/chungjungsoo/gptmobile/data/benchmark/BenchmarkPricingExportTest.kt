package dev.chungjungsoo.gptmobile.data.benchmark

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class BenchmarkPricingExportTest {
    @Test fun legacyContractNotesAndPrivateUrlsAreReplacedWithStableSourceProvenance() {
        listOf("Acme contract account 123", "https://private.example/contracts/customer-123?account=secret").forEach { privateSource ->
            val raw = buildJsonObject {
                put(
                    "price",
                    buildJsonObject {
                        put("source", privateSource)
                        put("inputMicrosPerMillion", 123)
                    }
                )
            }
            val safe = redactBenchmarkPricingSnapshot(raw)
            assertFalse(safe.toString().contains(privateSource))
            assertFalse(safe.toString().contains("private.example"))
            assertEquals("LEGACY_ALLOWANCE_RATE", safe.getValue("sourceKind").jsonPrimitive.content)
            assertEquals(benchmarkHash(privateSource), safe.getValue("sourceDigest").jsonPrimitive.content)
            assertEquals("123", safe.getValue("price").jsonObject.getValue("inputMicrosPerMillion").jsonPrimitive.content)
            assertEquals(safe, redactBenchmarkPricingSnapshot(safe))
        }
    }

    @Test fun importedQuoteSourcesAreRemovedWithoutLosingTheirRatesOrReviewDate() {
        val source = "https://private.example/account/prices"
        val raw = buildJsonObject {
            put(
                "quote",
                buildJsonObject {
                    put("source", "ACCOUNT_OVERRIDE")
                    put("sourceUrl", source)
                    put("inputPerMillion", "0.00000125")
                    put("reviewedOn", "2026-11-22")
                }
            )
        }
        val safe = redactBenchmarkPricingSnapshot(raw)
        assertFalse(safe.toString().contains("private.example"))
        assertEquals("ACCOUNT_OVERRIDE", safe.getValue("sourceKind").jsonPrimitive.content)
        assertEquals(benchmarkHash(source), safe.getValue("sourceDigest").jsonPrimitive.content)
        val quote = safe.getValue("quote").jsonObject
        assertEquals("", quote.getValue("sourceUrl").jsonPrimitive.content)
        assertEquals("0.00000125", quote.getValue("inputPerMillion").jsonPrimitive.content)
        assertEquals("2026-11-22", quote.getValue("reviewedOn").jsonPrimitive.content)
        assertEquals(safe, redactBenchmarkPricingSnapshot(safe))
    }

    @Test fun missingEvidenceDoesNotAcquireAnInventedSourceAndPrivateOptInIsExplicit() {
        val empty = redactBenchmarkPricingSnapshot(JsonObject(emptyMap()))
        assertEquals(JsonNull, empty["sourceKind"])
        assertEquals(JsonNull, empty["sourceDigest"])
        val raw = buildJsonObject { put("price", buildJsonObject { put("source", "Private contract") }) }
        val private = redactBenchmarkPricingSnapshot(raw, includePrivateProfileDetails = true)
        assertEquals(JsonPrimitive("Private contract"), private.getValue("price").jsonObject["source"])
    }

    @Test fun aRetainedLegacyPriceCannotReplaceTheCurrentQuotesSourceDigest() {
        val currentDigest = benchmarkHash("Current quote source")
        val raw = buildJsonObject {
            put(
                "quote",
                buildJsonObject {
                    put("source", "ACCOUNT_OVERRIDE")
                    put("sourceUrl", "")
                }
            )
            put("price", buildJsonObject { put("source", "Older private contract") })
            put("sourceDigest", currentDigest)
        }
        val safe = redactBenchmarkPricingSnapshot(raw)
        assertEquals(currentDigest, safe.getValue("sourceDigest").jsonPrimitive.content)
        assertEquals("ACCOUNT_OVERRIDE", safe.getValue("sourceKind").jsonPrimitive.content)
        assertFalse(safe.toString().contains("Older private contract"))
    }
}
