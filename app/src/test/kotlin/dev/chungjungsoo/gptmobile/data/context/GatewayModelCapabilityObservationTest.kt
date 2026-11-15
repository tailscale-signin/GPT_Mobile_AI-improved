package dev.chungjungsoo.gptmobile.data.context

import dev.chungjungsoo.gptmobile.data.network.ProviderRequestConfig
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayModelCapabilityObservationTest {
    private val config = ProviderRequestConfig("http://127.0.0.1:29981/v1", null)

    private fun envelope(fields: String) = """{"contractVersion":1,"instanceId":"${"a".repeat(32)}","selectedModel":{
        "schema":"gptmobile.gateway.selected-model.v1","schemaVersion":1,"requestedModel":"selected",
        "limitsVerified":false,"reasoningControlState":"UNVERIFIED",$fields}}"""

    private fun qualified(probe: String? = null): String = envelope(
        """"reportedModel":"selected","contextTokens":8192,"outputLimitSource":"context_capacity",
        "capabilityRevision":"${"b".repeat(64)}","observedAtMillis":1700000000000,"observationAgeMs":8,
        "evidenceOrigin":"GATEWAY_OBSERVED_PROVIDER_METADATA"${probe?.let { ",\"reasoningProbe\":$it" }.orEmpty()}"""
    ).replace("\"limitsVerified\":false", "\"limitsVerified\":true")
        .replace("\"reasoningControlState\":\"UNVERIFIED\"", "\"reasoningControlState\":\"UNSUPPORTED\"")

    @Test fun cachedProbeAgeAdvancesIndependentlyFromFreshCapacityMetadataWithoutGrantingControl() {
        val observation = parseGatewayModelObservation(qualified("""{"outcome":"IDENTICAL_PROMPTS","attempts":2,"observationAgeMs":8000}"""), config, "selected", 100)
        assertNotNull(observation.capacity)
        assertNull(observation.reasoningControl)
        assertEquals("UNSUPPORTED", observation.reasoningControlState)
        assertEquals(8L, observation.observationAgeMillis)
        assertEquals(GatewayReasoningProbeObservation(GatewayReasoningProbeOutcome.IDENTICAL_PROMPTS, 2, 8000), observation.reasoningProbe)
        val cached = observation.fresh(5000).fresh(7000)
        assertEquals(6908L, cached.observationAgeMillis)
        assertEquals(14900L, cached.reasoningProbe?.observationAgeMillis)
        assertEquals(8000L, observation.reasoningProbe?.observationAgeMillis)
        assertEquals(JsonPrimitive(6908), cached.reasoningFailureFields()["reasoning_metadata_age_ms"])
        assertEquals(JsonPrimitive(14900), cached.reasoningFailureFields()["reasoning_probe_age_ms"])
        assertNull(cached.reasoningFailureFields()["reasoning_capability_age_ms"])
        val stale = cached.fresh(20_100)
        assertNull(stale.capacity)
        assertNull(stale.reasoningControl)
        assertEquals("UNVERIFIED", stale.reasoningControlState)
        assertEquals(28_000L, stale.reasoningProbe?.observationAgeMillis)
        assertEquals(Long.MAX_VALUE, observation.fresh(99).reasoningProbe?.observationAgeMillis)
        assertNull(observation.fresh(99).reasoningFailureFields()["reasoning_probe_age_ms"])
    }

    @Test fun absentAndMalformedOptionalProbeEvidenceCannotInvalidateCapacityOrProveReasoning() {
        val valid = """{"outcome":"IDENTICAL_PROMPTS","attempts":2,"observationAgeMs":8000}"""
        for (probe in listOf(
            null, "null", "[]", "{}", valid.replace("IDENTICAL_PROMPTS", "private template"),
            valid.replace("\"attempts\":2", "\"attempts\":true"), valid.replace("\"attempts\":2", "\"attempts\":\"2\""),
            valid.replace("\"attempts\":2", "\"attempts\":-1"), valid.replace("\"attempts\":2", "\"attempts\":5"),
            valid.replace("8000", "-1"), valid.replace("8000", "86400001"), valid.replace("8000", "8.5"), valid.replace("8000", "\"8000\"")
        )) {
            val observed = parseGatewayModelObservation(qualified(probe), config, "selected", 100)
            assertNotNull("probe=$probe", observed.capacity)
            assertNull("probe=$probe", observed.reasoningProbe)
            assertNull(observed.reasoningControl)
            assertEquals("UNSUPPORTED", observed.reasoningControlState)
        }
        for (outcome in GatewayReasoningProbeOutcome.entries) {
            val observed = parseGatewayModelObservation(qualified(valid.replace("IDENTICAL_PROMPTS", outcome.name)), config, "selected", 100)
            assertEquals(outcome, observed.reasoningProbe?.outcome)
            assertNull(observed.reasoningControl)
            assertEquals("UNSUPPORTED", observed.reasoningControlState)
        }
        val mismatch = parseGatewayModelObservation(qualified(valid), config, "another-model", 100)
        assertNull(mismatch.capacity)
        assertNull(mismatch.reasoningProbe)
        val revision = parseGatewayModelObservation(qualified(valid).replace("b".repeat(64), "private revision"), config, "selected", 100)
        assertNull(revision.capacity)
        assertNull(revision.reasoningProbe)
    }

    @Test fun unloadedPropertiesFailureRetainsActionableFactsWithoutQualifyingReasoning() {
        val observation = parseGatewayModelObservation(
            envelope(
                """"reasonCode":"SELECTED_MODEL_NOT_LOADED","failureCode":"EXECUTION_MODEL_NOT_LOADED",
                "dependency":"backend_properties","upstreamStatus":400,"upstreamCode":400,
                "upstreamErrorType":"invalid_request_error","upstreamMessageCode":"MODEL_NOT_LOADED",
                "upstreamErrorBodyState":"PARSED","upstreamMessage":"private upstream text",
                "modelAdvertised":true,"modelLoaded":false,"modelState":"UNLOADED""""
            ),
            config,
            "selected",
            100
        )
        assertNull(observation.capacity)
        assertNull(observation.reasoningControl)
        assertEquals("UNVERIFIED", observation.reasoningControlState)
        assertEquals("400", observation.upstreamCode)
        assertEquals("UNLOADED", observation.modelState)
        assertEquals(true, observation.modelAdvertised)
        assertEquals(false, observation.modelLoaded)
        assertTrue(requireNotNull(observation.blockingSummary()).contains("prepare-model --model"))
        assertEquals(JsonPrimitive(400), observation.failureFields()["gateway_upstream_status"])
        assertEquals(JsonPrimitive("MODEL_NOT_LOADED"), observation.failureFields()["gateway_upstream_message_code"])
        assertFalse(observation.toString().contains("private"))
    }

    @Test fun genericProperties400RemainsAConfigurationFailureWithoutInferringLoadState() {
        val observation = parseGatewayModelObservation(
            envelope(""""reasonCode":"BACKEND_HTTP_ERROR","dependency":"backend_properties","upstreamStatus":400,"modelAdvertised":true"""),
            config,
            "selected",
            100
        )
        assertNull(observation.modelLoaded)
        assertEquals("UNKNOWN", observation.modelState)
        assertTrue(requireNotNull(observation.blockingSummary()).contains("HTTP 400"))
        assertFalse(requireNotNull(observation.blockingSummary()).contains("prepare-model"))
    }

    @Test fun unknownUpstreamValuesAndRawMessagesCannotEnterFailureFields() {
        val observation = parseGatewayModelObservation(
            envelope(""""reasonCode":"private","dependency":"private","upstreamStatus":999,"upstreamCode":"private","upstreamErrorType":"private","upstreamMessageCode":"private","upstreamErrorBodyState":"private","modelState":"private"""),
            config,
            "selected",
            100
        )
        assertNull(observation.reasonCode)
        assertNull(observation.upstreamStatus)
        assertNull(observation.upstreamCode)
        assertNull(observation.blockingSummary())
        assertFalse(observation.failureFields().toString().contains("private"))
    }

    @Test fun actionablePropertiesFailuresTakePriorityOverGenericHttpGuidance() {
        for ((reason, status, expected) in listOf(
            Triple("BACKEND_METADATA_DENIED", 403, "authorization"),
            Triple("SELECTED_MODEL_NOT_AVAILABLE", 404, "exact selected model is absent"),
            Triple("BACKEND_ROUTE_UNSUPPORTED", 404, "required model metadata route")
        )) {
            val observation = parseGatewayModelObservation(
                envelope(""""reasonCode":"$reason","dependency":"backend_properties","upstreamStatus":$status"""),
                config,
                "selected",
                100
            )
            assertTrue(requireNotNull(observation.blockingSummary()).contains(expected))
            assertFalse(requireNotNull(observation.blockingSummary()).contains("validation returned"))
            assertNull(observation.capacity)
            assertNull(observation.reasoningControl)
        }
    }

    @Test fun missingModelSelectorIsAConfigurationBlockWithoutInferringLoadState() {
        val observation = parseGatewayModelObservation(
            envelope(""""reasonCode":"BACKEND_CONFIGURATION_INVALID","dependency":"backend_properties","upstreamStatus":400,"upstreamMessageCode":"MODEL_SELECTOR_REQUIRED""""),
            config,
            "selected",
            100
        )
        assertNull(observation.modelLoaded)
        assertTrue(requireNotNull(observation.blockingSummary()).contains("model query parameter"))
        assertEquals(JsonPrimitive("MODEL_SELECTOR_REQUIRED"), observation.failureFields()["gateway_upstream_message_code"])
        assertNull(observation.capacity)
    }
}
