package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPrivacyTest {
    @Test fun `nested and incomplete tool payloads cannot expose search text`() {
        listOf(
            "error={\"nested\":{\"query\":\"private-canary\"}}",
            "error={\"arguments\":{\"q\":\"private-canary",
            "query=private-canary",
            "request=https://example.org/search?q=private-canary"
        ).forEach { assertFalse(redactDiagnosticPayload(it).contains("private-canary")) }
        assertEquals("attempt=3 · durationMs=42", redactDiagnosticPayload("attempt=3 · durationMs=42"))
    }

    @Test fun `source truncation survives export`() {
        val stored = diagnosticMessageForStorage("x".repeat(9000))
        assertTrue(stored.contains("truncatedAtSource=true"))
        assertTrue(structuredDiagnosticLine("2026-10-12T12:00:00Z E/Tool: $stored").contains("\"truncatedAtSource\":true"))
    }

    @Test fun `continuations retain causal identity without becoming incidents`() {
        val row = structuredDiagnosticLine("  at synthetic.Frame", "test", false, "root-1", 2, "root-1:2")
        assertTrue(row.contains("\"parentEventId\":\"root-1\""))
        assertTrue(row.contains("\"countAsIssue\":false"))
    }
}
