package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class StructuredDiagnosticExportTest {
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
}
