package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticAuditRegressionTest {
    private fun record(level: String, message: String, duplicate: Boolean = false) =
        Json.parseToJsonElement(structuredDiagnosticLine("2026-10-11T20:00:00Z $level/Test: $message", duplicate = duplicate)).jsonObject

    @Test fun ordinaryEventsAndContinuationsAreNotIssues() {
        assertFalse(record("I", "REQUEST_ACCEPTED").getValue("countAsIssue").jsonPrimitive.boolean)
        assertFalse(Json.parseToJsonElement(structuredDiagnosticLine(" at stack.frame() ")).jsonObject.getValue("countAsIssue").jsonPrimitive.boolean)
    }

    @Test fun errorsAreCountedOnlyOnceWithoutDiscardingTheirRecords() {
        assertTrue(record("E", "request failed").getValue("countAsIssue").jsonPrimitive.boolean)
        assertFalse(record("E", "request failed", true).getValue("countAsIssue").jsonPrimitive.boolean)
        assertTrue(record("E", "request failed", true).getValue("duplicate").jsonPrimitive.boolean)
    }

    @Test fun multipleRetryFieldsAreTypedAndIdentifiersRetainLeadingZeros() {
        val fields = record("W", "Retry · attempt=1 delayMs=298 · requestId=00123 · retryable=true").getValue("fields").jsonObject
        assertEquals(1L, fields.getValue("attempt").jsonPrimitive.long)
        assertEquals(298L, fields.getValue("delayMs").jsonPrimitive.long)
        assertEquals("00123", fields.getValue("requestId").jsonPrimitive.content)
        assertTrue(fields.getValue("retryable").jsonPrimitive.boolean)
    }

    @Test fun platformAndOptionalEmbeddingWarningsAreNotAppCrashes() {
        assertFalse(classifyDiagnostic("E", "Android", "SecurityException asus_leak_detect_proj").actionable)
        assertFalse(classifyDiagnostic("W", "tflite", "Feedback manager requires a model with a single signature inference").actionable)
        assertTrue(classifyDiagnostic("E", "Android", "FATAL EXCEPTION main").actionable)
    }
}
