package dev.chungjungsoo.gptmobile.data.openrouter

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PrivacyEndpointObservationTest {
    private val rows = Json.parseToJsonElement("""[{"model_id":"private/model","tag":"one"},{"model_id":"private/model","tag":"two"},{"model_id":"private/model","tag":"three"},{"model_id":"private/other","tag":"four"}]""") as JsonArray

    @Test fun disjointExclusionsExplainThePolicyBlockWithoutLeakingEndpointIdentities() {
        val policy = OpenRouterProviderRouting(zdr = true, only = listOf("one", "two"), ignore = listOf("two"), order = listOf("three"), allowFallbacks = false)
        val observation = requireNotNull(PrivacyEndpointObservation.evaluate(rows, "private/model", policy))

        assertEquals(PrivacyEndpointObservation(4, 3, 0, 1, 1, 1), observation)
        assertFalse(observation.compatible)
        val fields = observation.fields(120)
        assertEquals(JsonPrimitive(120), fields["privacy_metadata_age_ms"])
        assertEquals(JsonArray(listOf("PROVIDER_ALLOWLIST", "PROVIDER_DENYLIST", "FALLBACK_ORDER").map(::JsonPrimitive)), fields["privacy_blockers"])
        for (privateValue in listOf("private/model", "private/other", "one", "two", "three")) assertFalse(fields.toString().contains(privateValue))
        assertEquals(true, policy.zdr)
        assertEquals(false, policy.allowFallbacks)
    }

    @Test fun missingModelAndMalformedCatalogRemainDistinct() {
        val missing = requireNotNull(PrivacyEndpointObservation.evaluate(rows, "missing/model", OpenRouterProviderRouting(zdr = true)))
        assertEquals(JsonArray(listOf(JsonPrimitive("MODEL_NOT_IN_ZDR_CATALOG"))), missing.fields(0)["privacy_blockers"])
        val malformed = Json.parseToJsonElement("""[{"model_id":false,"tag":"one"}]""") as JsonArray
        assertNull(PrivacyEndpointObservation.evaluate(malformed, "missing/model", OpenRouterProviderRouting(zdr = true)))
        assertNull(PrivacyEndpointObservation.evaluate(rows, "openrouter/auto", OpenRouterProviderRouting(zdr = true)))
    }
}
