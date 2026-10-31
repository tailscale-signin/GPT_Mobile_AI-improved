package dev.chungjungsoo.gptmobile.data.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPrivacyTest {
    @Test fun completeRequestMetricsSurviveWhilePromptTextRemainsPrivate() {
        val metrics = "FINAL_REQUEST · promptTokens=7620 · outputTokens=8192 · contextTokens=32768 · measurement=UTF8_ESTIMATE"
        assertEquals(metrics, redactDiagnosticPayload(metrics))
        val exported = structuredDiagnosticLine("2026-10-31T12:00:00Z I/Admission: $metrics")
        assertTrue(exported.contains("7620"))
        assertFalse(redactDiagnosticPayload("FINAL_REQUEST · prompt=private-canary").contains("private-canary"))
        val network = structuredDiagnosticLine("2026-10-31T12:00:00Z I/Network: unexpected end of stream")
        assertTrue(network.contains("transport_availability"))
        assertTrue(network.contains("\"countAsIssue\":true"))
        assertTrue(network.contains("\"causalAttribution\":\"UNVERIFIED\""))
    }

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
