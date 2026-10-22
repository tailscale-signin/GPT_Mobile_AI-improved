package dev.chungjungsoo.gptmobile.data.openrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OpenRouterPrivacyPreflightTest {
    private val rows = Json.parseToJsonElement("""[{"model_id":"vendor/model","tag":"provider/secure"},{"model_id":"vendor/other","tag":"other"}]""") as JsonArray

    @Test fun requiresMatchingModelAndPreservesProviderRestrictions() {
        assertEquals(true, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true)))
        assertEquals(false, OpenRouterPrivacyPreflight.compatible(rows, "vendor/missing", OpenRouterProviderRouting(zdr = true)))
        assertEquals(false, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true, ignore = listOf("provider"))))
        assertEquals(false, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true, only = listOf("other"))))
        assertEquals(true, OpenRouterPrivacyPreflight.compatible(rows, "vendor/model", OpenRouterProviderRouting(zdr = true, only = listOf("provider"))))
    }

    @Test fun incompleteMetadataIsUnknownAndCannotInventCompatibilityOrIncompatibility() {
        assertNull(OpenRouterPrivacyPreflight.compatible(Json.parseToJsonElement("""[{"model_id":"vendor/model"}]""") as JsonArray, "vendor/model", OpenRouterProviderRouting(zdr = true)))
    }
}
