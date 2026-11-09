package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticPrivacyTest {
    @Test fun nestedPrivateArgumentsKeepExecutionReceiptAndFollowingFieldsStructured() {
        val receipt = """{"phase":"REJECTED","original_arguments":{"query":"private-canary"},"tool_trace":{"call_id":"call-1","resolved_arguments":{"content":"private-canary"}},"mutation_requests":0,"requires_reconciliation":false}"""
        val stored = diagnosticMessageForStorage("Finished · executionReceipt=$receipt · durationMs=42")
        assertFalse(stored.contains("private-canary"))
        val fields = Json.parseToJsonElement(structuredDiagnosticLine("2026-11-08T12:00:00Z W/Tool: $stored")).jsonObject.getValue("fields").jsonObject
        val retained = fields.getValue("executionReceipt") as JsonObject
        assertEquals("REJECTED", retained.getValue("phase").jsonPrimitive.content)
        assertEquals("0", retained.getValue("mutation_requests").jsonPrimitive.content)
        assertEquals("false", retained.getValue("requires_reconciliation").jsonPrimitive.content)
        assertEquals("call-1", retained.getValue("tool_trace").jsonObject.getValue("call_id").jsonPrimitive.content)
        assertEquals("42", fields.getValue("durationMs").jsonPrimitive.content)
        assertEquals(stored, redactLogMessage(stored))
    }

    @Test fun credentialAndPathRedactionCannotConsumeJsonSiblings() {
        val receipt = """{"phase":"REJECTED","headers":{"Authorization":"Bearer private-canary","x-request-id":"req-1"},"path":"/data/user/private-canary","endpoint":"https://example.org?q=private-canary","http_status":403}"""
        val safe = redactLogMessage(receipt)
        assertFalse(safe.contains("private-canary"))
        val retained = Json.parseToJsonElement(safe).jsonObject
        assertEquals("403", retained.getValue("http_status").jsonPrimitive.content)
        assertEquals("req-1", retained.getValue("headers").jsonObject.getValue("x-request-id").jsonPrimitive.content)
        assertEquals("[private-path]", retained.getValue("path").jsonPrimitive.content)
    }

    @Test fun malformedOrExcessivelyNestedPayloadsFailClosed() {
        listOf(
            "receipt={\"original_arguments\":{\"query\":\"private-canary",
            "receipt=" + "{\"nested\":".repeat(40) + "{\"query\":\"private-canary\"}" + "}".repeat(40),
            "query=private-canary {\"safe\":\"still-private-canary\"}"
        ).forEach { assertFalse(redactLogMessage(it).contains("private-canary")) }
    }

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

    @Test fun fullRedactionPreservesReceiptsWhileRemovingCredentialsAcrossLines() {
        val redacted = redactLogMessage(
            "errorCode=NETWORK_TIMEOUT · failedStage=service_start · dispatched=true\n" +
                "Authorization: Bearer private-canary\n" +
                "profile=PlatformV2(apiKey=private-canary)\n" +
                "path=/data/user/0/private-canary/file.txt address=192.168.1.2\n" +
                "endpoint=https://user:private-canary@example.org/search?q=private-canary"
        )
        assertTrue(redacted.contains("errorCode=NETWORK_TIMEOUT · failedStage=service_start · dispatched=true"))
        assertFalse(redacted.contains("private-canary"))
        assertFalse(redacted.contains("192.168.1.2"))
        assertTrue(redacted.contains("PlatformV2([redacted])"))
    }
}
