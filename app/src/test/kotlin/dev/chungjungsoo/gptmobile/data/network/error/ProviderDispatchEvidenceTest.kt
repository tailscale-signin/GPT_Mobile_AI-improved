package dev.chungjungsoo.gptmobile.data.network.error

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ProviderDispatchEvidenceTest {
    @Test fun admissionRefusalSeparatesTransportFromInference() {
        val result = ProviderDispatchEvidence.responseFields(Json.parseToJsonElement("""{"dispatch_state":"not_dispatched"}""").jsonObject, false, false, false)
        assertEquals(JsonPrimitive(true), result["http_request_dispatched"])
        assertEquals(JsonPrimitive(false), result["upstream_inference_dispatched"])
        assertEquals(JsonPrimitive(false), result["dispatched"])
        assertEquals(JsonPrimitive("UPSTREAM_INFERENCE"), result["dispatch_scope"])
        assertEquals(JsonPrimitive("NOT_DISPATCHED"), result["dispatch_state"])
    }

    @Test fun unknownOutcomeCannotBorrowTheLatestRefusalsNegativeInference() {
        val details = Json.parseToJsonElement("""{"dispatch_state":"not_dispatched","upstream_inference_dispatched":false}""").jsonObject
        val result = ProviderDispatchEvidence.responseFields(details, false, true, true)
        assertEquals(JsonPrimitive(true), result["http_request_dispatched"])
        assertEquals(JsonPrimitive("HTTP_REQUEST"), result["dispatch_scope"])
        assertFalse(result.containsKey("upstream_inference_dispatched"))
        assertFalse(result.containsKey("dispatch_state"))
    }

    @Test fun retainedPayloadOutranksANewerRefusalWithoutReclassifyingTransportAsInference() {
        val details = Json.parseToJsonElement("""{"dispatch_state":"not_dispatched","upstream_inference_dispatched":false}""").jsonObject
        val result = ProviderDispatchEvidence.responseFields(details, true, true, true)
        assertEquals(JsonPrimitive(true), result["upstream_inference_dispatched"])
        assertEquals(JsonPrimitive("UPSTREAM_INFERENCE"), result["dispatch_scope"])
        assertEquals(JsonPrimitive("DISPATCHED"), result["dispatch_state"])
        val unknown = ProviderDispatchEvidence.responseFields(null, false, false, false)
        assertFalse(unknown.containsKey("upstream_inference_dispatched"))
        assertEquals(JsonPrimitive("HTTP_REQUEST"), unknown["dispatch_scope"])
    }

    @Test fun malformedBooleanCannotDefeatConfirmedNonacceptance() {
        val result = ProviderDispatchEvidence.responseFields(JsonObject(mapOf("dispatched" to JsonPrimitive("false"))), false, true, false)
        assertEquals(JsonPrimitive(false), result["upstream_inference_dispatched"])
        val transportOnly = ProviderDispatchEvidence.responseFields(Json.parseToJsonElement("""{"dispatch_scope":"HTTP_REQUEST","dispatched":true}""").jsonObject, false, false, false)
        assertFalse(transportOnly.containsKey("upstream_inference_dispatched"))
    }
}
