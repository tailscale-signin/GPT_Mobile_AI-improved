package dev.chungjungsoo.gptmobile.data.network

import dev.chungjungsoo.gptmobile.data.dto.openai.response.ChatCompletionChunk
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationProvenance
import dev.chungjungsoo.gptmobile.data.dto.openai.response.GatewayGenerationTiming
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GatewayGenerationProvenanceTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val valid = GatewayGenerationProvenance(1, "BACKEND_RESPONSE", "selected", "selected", "MATCHED", 200, "a".repeat(64))

    @Test fun backendReceiptSurvivesCompletionDecodeWithoutClaimingUnmeasuredRuntime() {
        val chunk = json.decodeFromString<ChatCompletionChunk>(
            """{"gateway_provenance":{"schema_version":1,"origin":"BACKEND_RESPONSE","requested_model":"selected","reported_model":"selected","model_identity":"MATCHED","http_status":200,"backend_endpoint_digest":"${"a".repeat(64)}","accelerator":"fabricated-gpu","runtime_identity":"configured-model"}}"""
        )
        val evidence = requireNotNull(chunk.gatewayProvenance?.evidence("selected"))
        assertEquals(JsonPrimitive("MATCHED"), evidence["model_identity"])
        assertEquals(JsonPrimitive("a".repeat(64)), evidence["backend_endpoint_digest"])
        assertEquals(JsonNull, evidence["accelerator"])
        assertEquals(JsonNull, evidence["runtime_identity"])
        assertEquals(JsonNull, evidence["effective_controls"])
        assertEquals(JsonPrimitive("NOT_MEASURED"), evidence["effective_settings_source"])
        assertNull(chunk.gatewayMetadata)
    }

    @Test fun receiptMustMatchRequestedModelAndTruthfulObservedIdentity() {
        for (invalid in listOf(valid.copy(schemaVersion = 2), valid.copy(origin = "CONFIGURED_PROFILE"), valid.copy(requestedModel = "other"), valid.copy(httpStatus = 500), valid.copy(reportedModel = "other"), valid.copy(reportedModel = "bad\u0000model"))) {
            assertNull(invalid.evidence("selected"))
        }
        assertEquals(JsonPrimitive("DIFFERENT"), valid.copy(reportedModel = "other", modelIdentity = "DIFFERENT").evidence("selected")?.get("model_identity"))
        assertEquals(JsonPrimitive("UNAVAILABLE"), valid.copy(reportedModel = null, modelIdentity = "UNAVAILABLE").evidence("selected")?.get("model_identity"))
    }

    @Test fun onlyBoundedKnownWireControlsBecomeAcknowledgedEvidence() {
        val receipt = valid.copy(
            backendEndpointDigest = "http://private-server/secret",
            serializedControls = buildJsonObject {
                put("reasoning.enabled", false)
                put("chat_template_kwargs.enable_thinking", true)
                put("reasoning_effort", "low")
                put("prompt", "private prompt")
                put("token", "secret")
            }
        )
        val evidence = requireNotNull(receipt.evidence("selected"))
        assertEquals(JsonNull, evidence["backend_endpoint_digest"])
        val controls = evidence.getValue("serialized_controls").jsonObject
        assertEquals(3, controls.size)
        assertEquals(JsonPrimitive(false), controls["reasoning.enabled"])
        assertFalse(evidence.toString().contains("secret"))
        assertFalse(evidence.toString().contains("private prompt"))
    }

    @Test fun backendHeaderEventAndVisibleTimesRemainDistinctFromAndroidPublication() {
        val timing = GatewayGenerationTiming("physical_backend_request", 15.0, 30.0, 15.0, 40.0, 5.0, 10.0, "completion_buffered")
        val evidence = timing.observedFields()
        assertEquals(5.0, evidence["backend_response_headers_ms"])
        assertEquals(10.0, evidence["backend_first_event_ms"])
        assertEquals(30.0, evidence["backend_first_visible_output_ms"])
        assertFalse(evidence.containsKey("first_user_visible_text_ms"))
        assertTrue(timing.copy(measurementScope = "profile_estimate").observedFields().isEmpty())
        assertFalse(timing.copy(firstEventMs = Double.NaN).observedFields().containsKey("backend_first_event_ms"))
    }
}
