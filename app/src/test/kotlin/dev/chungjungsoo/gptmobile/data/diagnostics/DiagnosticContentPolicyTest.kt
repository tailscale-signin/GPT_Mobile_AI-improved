package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticContentPolicyTest {
    @Test fun defaultExportRemovesPrivateFieldsRecursivelyWithoutLosingReceipts() {
        val fixture = Json.parseToJsonElement(
            """{"table":"messages_v2","rowId":42,"fields":{"message_id":42,"content":"PRIVATE ORIGINAL","thoughts":["PRIVATE REASONING"],"revisions":[{"content":"PRIVATE REVISION"}],"timeline":[{"content":"PRIVATE NOTICE","privacyProtection":{"confirmed":true,"contentDigest":"digest","ranges":[{"start":1,"endExclusive":4}]}}],"embedded":"{\"sourceText\":\"PRIVATE EMBEDDED\",\"outcome\":\"WAITING_FOR_MODEL\"}","arguments":{"unusualKey":"PRIVATE ARGUMENT"}},"privacy_dispatch_receipts":[{"outcome":"VALIDATED","policy_revision":"r1","detector_revision":"d1","coverage":"KNOWN_VALUES","payload_hmac":"proof","wire_bytes":128,"validation_elapsed_us":12,"surface_counts":{"history":2}}]}"""
        )
        val safe = DiagnosticContentRedactor().redact(fixture) as JsonObject
        assertFalse(safe.toString().contains("PRIVATE "))
        assertEquals(JsonPrimitive(42), safe["rowId"])
        val fields = safe["fields"] as JsonObject
        assertEquals(JsonPrimitive(42), fields["message_id"])
        assertTrue(fields["timeline"].toString().contains("endExclusive"))
        assertEquals(fixture.let { it as JsonObject }["privacy_dispatch_receipts"], safe["privacy_dispatch_receipts"])
        assertTrue(fields["embedded"].toString().contains("WAITING_FOR_MODEL"))
    }

    @Test fun topologyIsRemovedFromNestedConfigurationAndFrozenLogs() {
        val fixture = buildJsonObject {
            put("apiUrl", "http://100.100.8.9:8090/v1")
            put(
                "nested",
                buildJsonObject {
                    put("endpoint", "http://desktop:8090/v1")
                    put("ipv6", "http://[fd12:3456::1]:8080/v1")
                    put("cache", "/data/user/0/example/private-file")
                    put("windows", "D:\\Private\\fixture.txt")
                    put("publicProvider", "https://openrouter.ai/api/v1")
                    put("localPort", 8090)
                }
            )
            put("line", "eventId=synthetic-event · source=http://192.168.4.2:8080/v1 · thoughts=PRIVATE LOG THOUGHTS")
        }
        val safe = DiagnosticContentRedactor().redact(fixture)
        for (privateValue in listOf("100.100.8.9", "192.168.4.2", "fd12:3456", "desktop", "8090", "8080", "private-file", "fixture.txt", "PRIVATE LOG THOUGHTS")) assertFalse(privateValue, safe.toString().contains(privateValue))
        assertTrue(safe.toString().contains("synthetic-event"))
        assertTrue(safe.toString().contains("https://openrouter.ai/api/v1"))
    }

    @Test fun explicitForensicModePreservesOriginalsButNeverCredentials() {
        val fixture = buildJsonObject {
            put("content", "FORENSIC ORIGINAL synthetic-credential")
            put("thoughts", "FORENSIC THOUGHTS")
            put("apiUrl", "http://192.168.4.2:8080")
            put("token", "synthetic-credential")
            put("nested", JsonArray(listOf(buildJsonObject { put("authorization", "Bearer synthetic-credential") })))
        }
        val safe = DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC, listOf("synthetic-credential")).redact(fixture).toString()
        assertTrue(safe.contains("FORENSIC ORIGINAL"))
        assertTrue(safe.contains("FORENSIC THOUGHTS"))
        assertTrue(safe.contains("192.168.4.2:8080"))
        assertFalse(safe.contains("synthetic-credential"))
    }

    @Test fun configuredEndpointsNamesAndDeviceFieldsAreOmittedWithoutRemovingToolIdentity() {
        val fixture = Json.parseToJsonElement(
            """{"profile":{"uid":"profile-1","name":"PRIVATE CLIENT PROFILE","compatibleType":"OPENAI","model":"model-1","apiUrl":"https://inference.family-example.org/v1","batchApiUrl":"https://batch.family-example.org/v1"},"connection":{"connection_uid":"connection-1","name":"PRIVATE CONNECTION","alias":"PRIVATE ALIAS","type":"MCP","endpoint_url":"https://tools.family-example.org/mcp","auth_type":"NONE"},"run":{"gatewayBaseUrl":"https://gateway.family-example.org/v1","status":"FAILED","reasonCode":"PRIVATE_FINAL_REQUEST_REJECTED"},"device":{"hostname":"private-machine","deviceName":"PRIVATE DEVICE","port":4567},"embedded":"{\"api_url\":\"https://embedded.family-example.org/v1\",\"model\":\"model-2\"}","tool":{"name":"web_search","isError":true,"errorCode":"SYNTHETIC_FAILURE"}}"""
        )
        val redactor = DiagnosticContentRedactor()
        val safe = redactor.redact(fixture).toString()
        for (privateValue in listOf("PRIVATE ", "family-example.org", "private-machine", "4567")) assertFalse(privateValue, safe.contains(privateValue))
        for (structural in listOf("profile-1", "OPENAI", "model-1", "connection-1", "MCP", "FAILED", "PRIVATE_FINAL_REQUEST_REJECTED", "model-2", "web_search", "SYNTHETIC_FAILURE")) assertTrue(structural, safe.contains(structural))
        assertEquals(fixture, DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC).redact(fixture))
    }

    @Test fun freeTextToolAndResearchErrorsAreOmittedWhileFailureCodesRemain() {
        val fixture = Json.parseToJsonElement(
            """{"tool":{"error":"Tool echoed PRIVATE ORIGINAL","result":"PRIVATE RESULT","isError":true,"errorCode":"SYNTHETIC_FAILURE"},"job":{"jobId":"job-1","stage":"FAILED","reason":"Exception echoed PRIVATE REQUEST","reasonCode":"SOURCE_CHANGED"},"nested":{"failureMessage":"PRIVATE FAILURE","lastError":"PRIVATE LAST ERROR","failureReason":"PRIVATE RESEARCH FAILURE","terminalReason":"PRIVATE TERMINAL REASON"},"receipt":{"reason":"ASSIST_OFF","reasonCode":"ASSIST_OFF"}}"""
        )
        val safe = DiagnosticContentRedactor().redact(fixture).toString()
        assertFalse(safe.contains("PRIVATE "))
        for (structural in listOf("SYNTHETIC_FAILURE", "job-1", "FAILED", "SOURCE_CHANGED", "ASSIST_OFF")) assertTrue(structural, safe.contains(structural))
    }

    @Test fun labelledAndEmbeddedLogTopologyAndErrorContentAreRedactedBeforeFreezing() {
        val redactor = DiagnosticContentRedactor()
        val lines = listOf(
            "eventId=event-1 · apiUrl=https://inference.family-example.org/v1 · deviceName=PRIVATE DEVICE · outcome=FAILED",
            "eventId=event-2 · api_url=https://inference.family-example.org/v1 model=model-1 · failureMessage=PRIVATE FAILURE · errorCode=SYNTHETIC_FAILURE",
            "eventId=event-3 · {\"apiUrl\":\"https://inference.family-example.org/v1\",\"error\":\"PRIVATE ERROR\",\"status\":\"FAILED\"}",
            "eventId=event-4 · error={\"unlabelled\":\"PRIVATE ERROR OBJECT\"} · errorCode=SYNTHETIC_FAILURE"
        )
        lines.forEachIndexed { index, line ->
            val safe = redactor.logLine(line)
            assertFalse(safe, safe.contains("family-example.org"))
            assertFalse(safe, safe.contains("PRIVATE "))
            assertTrue(safe, safe.contains("event-${index + 1}"))
            assertTrue(safe, safe.contains("FAILED") || safe.contains("SYNTHETIC_FAILURE"))
            assertEquals(safe, redactor.logLine(safe))
        }
    }

    @Test fun ktorHeaderEndpointLabelsAreRemovedWhileHttpOutcomeAndCorrelationRemain() {
        val lines = listOf(
            "I/Network: eventId=network-1 · REQUEST: https://inference.family-example.org/v1/private-path",
            "parentEventId=network-1 · sequence=1 · METHOD: POST",
            "I/Network: eventId=network-2 · RESPONSE: 200",
            "parentEventId=network-2 · sequence=2 · FROM: https://inference.family-example.org/v1/private-path"
        )
        val redactor = DiagnosticContentRedactor()
        val safe = lines.map(redactor::logLine)
        assertFalse(safe.toString().contains("family-example.org"))
        assertFalse(safe.toString().contains("private-path"))
        assertEquals(lines[1], safe[1])
        assertEquals(lines[2], safe[2])
        assertTrue(safe[0].contains("eventId=network-1"))
        assertTrue(safe[3].contains("parentEventId=network-2"))
        assertEquals(safe, safe.map(redactor::logLine))
        assertEquals(lines, lines.map(DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC)::logLine))
    }

    @Test fun malformedOrOverLimitLogPayloadsCannotBypassFieldRedaction() {
        val lines = listOf(
            "eventId=malformed-1 · {\"error\":\"PRIVATE ORIGINAL\",broken",
            "eventId=malformed-2 · {\"apiUrl\":\"https://inference.family-example.org/v1\",\"broken\":",
            "eventId=malformed-3 · {\"padding\":\"${"x".repeat(16 * 1024)}\",\"failureMessage\":\"PRIVATE ORIGINAL\"}"
        )
        val redactor = DiagnosticContentRedactor()
        lines.forEachIndexed { index, line ->
            val safe = redactor.logLine(line)
            assertFalse(safe.contains("PRIVATE ORIGINAL"))
            assertFalse(safe.contains("family-example.org"))
            assertTrue(safe.contains("eventId=malformed-${index + 1}"))
            assertTrue(safe.contains("[unparsed diagnostic payload omitted]"))
            assertEquals(safe, redactor.logLine(safe))
        }
    }

    @Test fun privateLogFieldTailStaysPrivateAfterCredentialMarkerOrJsonValue() {
        val lines = listOf(
            "eventId=tail-1 · error=[redacted] PRIVATE ORIGINAL · errorCode=SYNTHETIC_FAILURE",
            "eventId=tail-2 · failureMessage={\"unlabelled\":\"PRIVATE JSON\"} PRIVATE TAIL · errorCode=SYNTHETIC_FAILURE",
            "eventId=tail-3 · apiUrl=[redacted] private-host.example · errorCode=SYNTHETIC_FAILURE"
        )
        val redactor = DiagnosticContentRedactor()
        lines.forEachIndexed { index, line ->
            val safe = redactor.logLine(line)
            assertFalse(safe, safe.contains("PRIVATE "))
            assertFalse(safe, safe.contains("private-host.example"))
            assertTrue(safe, safe.contains("eventId=tail-${index + 1}"))
            assertTrue(safe, safe.contains("errorCode=SYNTHETIC_FAILURE"))
            assertEquals(safe, redactor.logLine(safe))
        }
    }

    @Test fun redactionIsIdempotentAndKeepsZeroCoverageHonest() {
        val original = Json.parseToJsonElement("""{"content":"ORIGINAL","privacyProtection":{"confirmed":true,"ranges":[]},"inputTokens":125,"reasoning":{"enabled":true}}""")
        val policy = DiagnosticContentRedactor()
        val safe = policy.redact(original)
        assertEquals(safe, policy.redact(safe))
        assertTrue(safe.toString().contains("\"ranges\":[]"))
        assertTrue(safe.toString().contains("\"inputTokens\":125"))
        assertTrue(safe.toString().contains("\"enabled\":true"))
    }

    @Test fun defaultExportPreservesContentFreeFinalWireAndStreamEvidence() {
        val evidence = Json.parseToJsonElement(
            """{"privacy_dispatch_receipts":[{"version":1,"private_request_id":"synthetic-proof","policy_revision":7,"detector_revision":"privacy-detector-v2","coverage":"KNOWN_PROTECTED_VALUES_IN_TEXT_SURFACES","outcome":"KNOWN_VALUES_ABSENT","unrecognized_sensitive_values":"NOT_ASSESSED","provider_retention":"NOT_ASSESSED","payload_hmac":"synthetic-keyed-fingerprint","fingerprint_scope":"REQUEST_LOCAL_KEY","wire_bytes":2048,"validation_elapsed_us":17,"text_surfaces_checked":4,"known_private_values":2,"protected_ranges":3,"original_characters":180,"transformed_characters":210,"protected_characters":35,"configured_categories":["IDENTITIES"],"surface_counts":{"USER_MESSAGE":1,"ASSISTANT_HISTORY":2,"SYSTEM_PROMPT":1},"physical_attempt_id":"synthetic-attempt"}],"privacy_streams":[{"schema":"privacy.stream-observation.v1","private_request_id":"synthetic-proof","scope":"SELECTED_PROVIDER_STREAM_MONOTONIC_TIME","first_reasoning_ms":40,"first_upstream_answer_ms":80,"first_restored_answer_ms":81,"restoration_ns":700,"restored_chunks":3,"restored_ranges":2,"maximum_held_marker_characters":11,"repaired_marker_count":0,"marker_protocol_status":"COMPLETE","usefulness_status":"UNASSESSED","anomaly_codes":[],"completed":true,"contains_content":false}],"privacy_dispatch_receipts_omitted":0}"""
        )
        assertEquals(evidence, DiagnosticContentRedactor().redact(evidence))
        val embedded = buildJsonObject { put("usageEvidenceJson", evidence.toString()) }
        assertEquals(embedded, DiagnosticContentRedactor().redact(embedded))
    }
}
