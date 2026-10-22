package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StructuredDiagnosticExportTest {
    @Test fun recorderPrefixDoesNotContaminateProcessIdOrEventClassification() {
        val record = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-21T12:00:00Z I/MCP: eventId=abc-123 monoNs=900 bootMs=800 processId=7654 DISCOVERY_NEGOTIATION · outcome=legacy_fallback")).jsonObject
        assertEquals("7654", record.getValue("fields").jsonObject.getValue("processId").jsonPrimitive.content)
        assertFalse(record.getValue("fields").jsonObject.getValue("processId").jsonPrimitive.isString)
        assertEquals("DISCOVERY_NEGOTIATION", record.getValue("eventName").jsonPrimitive.content)
        assertEquals("compatibility_probe", record.getValue("category").jsonPrimitive.content)
        assertEquals("memory_recovery", classifyDiagnostic("I", "Runtime", "eventId=abc monoNs=123 processId=42 OOM_RECOVERY · retained=true").category)
        assertEquals("fatal", classifyDiagnostic("I", "Runtime", "eventId=abc monoNs=123 processId=42 java.lang.OutOfMemoryError: Failed to allocate 1024 bytes").category)
    }

    @Test fun identityDoesNotAbsorbEventLabelAndCountersKeepTheirTypes() {
        val record = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-10T12:00:00Z I/CombinedSynthesis: eventId=abc-123 · SYNTHESIS_QUALITY · candidateUnits=48 · missing=29 · semanticCoverageVerified=false")).jsonObject
        val fields = record.getValue("fields").jsonObject
        assertEquals("abc-123", fields.getValue("eventId").jsonPrimitive.content)
        assertFalse(fields.getValue("candidateUnits").jsonPrimitive.isString)
        assertFalse(fields.getValue("missing").jsonPrimitive.isString)
        assertFalse(fields.getValue("semanticCoverageVerified").jsonPrimitive.isString)
    }

    @Test fun `export keeps status usage source and correlation independently`() {
        val record = Json.parseToJsonElement(structuredDiagnosticLine("2026-10-10T12:00:00Z E/Delegation: Child returned · parentRun=run-1 · status=FAILED · usageTotal=3337 · usageTotalEstimated=true")).jsonObject
        assertEquals("FAILED", record.getValue("fields").jsonObject.getValue("status").jsonPrimitive.content)
        assertEquals("3337", record.getValue("fields").jsonObject.getValue("usageTotal").jsonPrimitive.content)
        assertEquals("true", record.getValue("fields").jsonObject.getValue("usageTotalEstimated").jsonPrimitive.content)
        assertEquals("run-1", record.getValue("fields").jsonObject.getValue("parentRun").jsonPrimitive.content)
    }

    @Test fun `stack continuations remain exportable and credentials stay redacted`() {
        val record = structuredDiagnosticLine("  at client: Authorization: Bearer secret-value")
        assertEquals("continuation", Json.parseToJsonElement(record).jsonObject.getValue("kind").jsonPrimitive.content)
        assertFalse(record.contains("secret-value"))
    }

    @Test fun `event identity at message start survives repeated capture`() {
        val a = "2026-10-10T12:00:00Z E/Runtime: eventId=abc-123 · Initialization failed"
        val b = "2026-10-10T12:01:00Z E/Runtime: eventId=abc-123 · Initialization failed"
        assertEquals("abc-123", diagnosticEventIdentity(a))
        assertEquals(diagnosticEventIdentity(a), diagnosticEventIdentity(b))
        val continuation = Json.parseToJsonElement(structuredDiagnosticLine("  at runtime()", parentEventId = "abc-123", sequence = 1, captureIdentity = "abc-123:1")).jsonObject
        assertEquals("abc-123", continuation.getValue("parentEventId").jsonPrimitive.content)
        assertEquals("false", continuation.getValue("countAsIssue").jsonPrimitive.content)
    }
}
