package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class DiagnosticExecutionReceiptTest {
    @Test fun speechBindingFailureKeepsItsPhaseAndDoesNotClaimRateLimiting() {
        val record = Json.parseToJsonElement(
            structuredDiagnosticLine("2026-11-05T12:00:00Z W/VoiceRecognition: recognition_error=10 · errorCode=SPEECH_SERVICE_START_FAILED · failedStage=service_start · dispatched=true · incidentId=voice:session:1")
        ).jsonObject
        assertEquals("SPEECH_SERVICE_START_FAILED", record.getValue("errorCode").jsonPrimitive.content)
        assertEquals("service_start", record.getValue("stage").jsonPrimitive.content)
        assertEquals("true", record.getValue("dispatched").jsonPrimitive.content)
        assertEquals("voice:session:1", record.getValue("incidentId").jsonPrimitive.content)
        assertEquals("speech_service_start", record.getValue("category").jsonPrimitive.content)
    }

    @Test fun unknownDispatchIsDistinctFromARejectedPrecheck() {
        fun receipt(dispatch: String, state: String) = Json.parseToJsonElement(
            structuredDiagnosticLine("2026-11-05T12:00:00Z E/Tool: FAILED · errorCode=none · dispatched=$dispatch · dispatchState=$state")
        ).jsonObject
        val unknown = receipt("unknown", "UNKNOWN_OUTCOME")
        assertEquals(JsonNull, unknown.getValue("dispatched"))
        assertEquals(JsonNull, unknown.getValue("errorCode"))
        assertEquals("UNKNOWN_OUTCOME", unknown.getValue("dispatchState").jsonPrimitive.content)
        val earlierUnknown = receipt("false", "UNKNOWN_OUTCOME")
        assertEquals(JsonNull, earlierUnknown.getValue("dispatched"))
        assertEquals("UNKNOWN_OUTCOME", earlierUnknown.getValue("dispatchState").jsonPrimitive.content)
        val precheck = receipt("false", "PRECHECK_REJECTED")
        assertEquals("false", precheck.getValue("dispatched").jsonPrimitive.content)
        assertEquals("PRECHECK_REJECTED", precheck.getValue("dispatchState").jsonPrimitive.content)
    }

    @Test fun fallbackFailureFieldsRemainStructuredAfterPrivatePayloadRedaction() {
        val record = Json.parseToJsonElement(
            structuredDiagnosticLine("2026-11-05T12:00:00Z E/Tool: FAILED · failedStage=unknown · stage=tool_dispatch · failureCode=NETWORK_DNS · retryable=true · prompt=private-canary")
        ).jsonObject
        assertEquals("tool_dispatch", record.getValue("stage").jsonPrimitive.content)
        assertEquals("NETWORK_DNS", record.getValue("errorCode").jsonPrimitive.content)
        assertEquals("true", record.getValue("retryable").jsonPrimitive.content)
        org.junit.Assert.assertFalse(record.toString().contains("private-canary"))
    }
}
