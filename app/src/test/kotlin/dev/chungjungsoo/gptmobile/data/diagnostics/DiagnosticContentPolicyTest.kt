package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceBook
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidenceExcerpts
import dev.chungjungsoo.gptmobile.data.github.GitHubEvidencePolicy
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
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

    @Test fun providerHttpErrorDetailsArePrivateByDefaultButExplicitForensicPreservesThem() {
        val line = "eventId=provider-detail · HTTP_ERROR · status=400 · model=model-1 · detail=PRIVATE PROVIDER ORIGINAL synthetic-credential · errorCode=SYNTHETIC_FAILURE"
        val redactor = DiagnosticContentRedactor(credentials = listOf("synthetic-credential"))
        val safe = redactor.logLine(line)
        assertFalse(safe, safe.contains("PRIVATE PROVIDER ORIGINAL"))
        assertFalse(safe, safe.contains("synthetic-credential"))
        for (field in listOf("eventId=provider-detail", "HTTP_ERROR", "status=400", "model=model-1", "errorCode=SYNTHETIC_FAILURE")) assertTrue(safe, safe.contains(field))
        assertEquals(safe, redactor.logLine(safe))
        val forensic = DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC, listOf("synthetic-credential"))
        assertEquals(line.replace("synthetic-credential", "[redacted]"), forensic.logLine(line))
    }

    @Test fun memoryFailureDetailsArePrivateBeforeFrozenLogRecordsAreExported() {
        val line = "eventId=memory-detail · ENRICHMENT_EXCEPTION · cause=IllegalStateException · detail=PRIVATE MEMORY ORIGINAL synthetic-credential"
        val redactor = DiagnosticContentRedactor(credentials = listOf("synthetic-credential"))
        val frozen = redactor.logLine(line)
        val record = buildJsonObject {
            put("source", "critical-log")
            put("line", frozen)
        }
        val safe = redactor.redact(record) as JsonObject
        assertFalse(safe.toString().contains("PRIVATE MEMORY ORIGINAL"))
        assertFalse(safe.toString().contains("synthetic-credential"))
        assertEquals(JsonPrimitive("critical-log"), safe["source"])
        for (field in listOf("eventId=memory-detail", "ENRICHMENT_EXCEPTION", "cause=IllegalStateException")) assertTrue(safe.toString(), safe.toString().contains(field))
        assertEquals(JsonPrimitive(frozen), safe["line"])
        assertEquals(safe, redactor.redact(safe))
        val forensic = DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC, listOf("synthetic-credential"))
        assertEquals(line.replace("synthetic-credential", "[redacted]"), forensic.logLine(line))
    }

    @Test fun jsonDetailStringsArePrivateWithoutDroppingStructuredFailureEvidence() {
        val fixture = Json.parseToJsonElement(
            """{"rowId":42,"detail":"PRIVATE ORIGINAL synthetic-credential","nested":{"detail":"PRIVATE NESTED ORIGINAL","errorCode":"SYNTHETIC_FAILURE","http_status":400},"details":{"code":"SYNTHETIC_FAILURE","failed_stage":"provider_generation","dispatched":true,"remote_acceptance":"UNKNOWN","retry_after_ms":2000},"receipt":{"reason":"ASSIST_OFF","reasonCode":"ASSIST_OFF"},"embedded":"{\"detail\":\"PRIVATE EMBEDDED ORIGINAL\",\"http_status\":400,\"code\":\"SYNTHETIC_FAILURE\"}"}"""
        ) as JsonObject
        val redactor = DiagnosticContentRedactor(credentials = listOf("synthetic-credential"))
        val safe = redactor.redact(fixture) as JsonObject
        assertFalse(safe.toString().contains("PRIVATE "))
        assertFalse(safe.toString().contains("synthetic-credential"))
        assertEquals(fixture["rowId"], safe["rowId"])
        assertEquals(fixture["details"], safe["details"])
        assertEquals(fixture["receipt"], safe["receipt"])
        assertEquals(JsonPrimitive(400), (safe["nested"] as JsonObject)["http_status"])
        assertTrue(safe["embedded"].toString().contains("SYNTHETIC_FAILURE"))
        assertEquals(safe, redactor.redact(safe))
        val forensic = DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC, listOf("synthetic-credential")).redact(fixture) as JsonObject
        assertEquals(JsonPrimitive("PRIVATE ORIGINAL [redacted]"), forensic["detail"])
        assertTrue(forensic["embedded"].toString().contains("PRIVATE EMBEDDED ORIGINAL"))
        assertFalse(forensic.toString().contains("synthetic-credential"))
        assertEquals(fixture["details"], forensic["details"])
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

    @Test fun actualRetainedTaskAndEvidenceProducersLosePrivateTextButKeepStructuralEvidence() = runBlocking {
        val record = retainedTaskDiagnosticRecord()
        val raw = record.toString()
        retainedTaskPrivateCanaries.forEach { assertTrue(it, raw.contains(it)) }
        val redactor = DiagnosticContentRedactor(credentials = listOf("private-secret"))
        val safe = redactor.redact(record) as JsonObject
        retainedTaskPrivateCanaries.forEach { assertFalse(it, safe.toString().contains(it)) }
        assertFalse(safe.toString().contains("private-secret"))
        assertEquals(safe, redactor.redact(safe))
        val before = (record.getValue("fields") as JsonObject).getValue("payload") as JsonObject
        val after = (safe.getValue("fields") as JsonObject).getValue("payload") as JsonObject
        val taskBefore = before.getValue("task_contract") as JsonObject
        val taskAfter = after.getValue("task_contract") as JsonObject
        for (key in listOf("binding", "source_message_id", "request_digest", "tool_call_limit", "effective_tool_call_limit")) assertEquals(key, taskBefore[key], taskAfter[key])
        assertEquals((taskBefore.getValue("acceptance_criteria") as JsonArray).size, (taskAfter.getValue("acceptance_criteria") as JsonArray).size)
        val contractBefore = taskBefore.getValue("acceptance_contract") as JsonObject
        val contractAfter = taskAfter.getValue("acceptance_contract") as JsonObject
        val criteriaBefore = contractBefore.getValue("criteria") as JsonArray
        val criteriaAfter = contractAfter.getValue("criteria") as JsonArray
        assertTrue(criteriaBefore.isNotEmpty())
        criteriaBefore.zip(criteriaAfter).forEach { (original, redacted) ->
            original as JsonObject
            redacted as JsonObject
            for (key in listOf("id", "source_span", "depends_on", "evidence_producer")) assertEquals(key, original[key], redacted[key])
        }
        val catalogBefore = (before.getValue("evidence_catalog") as JsonArray).single() as JsonObject
        val catalogAfter = (after.getValue("evidence_catalog") as JsonArray).single() as JsonObject
        for (key in listOf("receipt_id", "tool", "trusted", "success", "observed_at_ms", "source_head_sha", "error_code")) assertEquals(key, catalogBefore[key], catalogAfter[key])
        val facts = catalogAfter.getValue("facts") as JsonObject
        assertEquals(JsonPrimitive(7), facts["/total_count"])
        assertEquals(JsonPrimitive("a".repeat(40)), facts["/commit_sha"])
        assertEquals(JsonPrimitive("completed"), facts["/status"])
        assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), facts["/topic"])
        val excerpt = (catalogAfter.getValue("excerpts") as JsonObject).values.single() as JsonObject
        assertEquals(JsonPrimitive(1), excerpt["start_line"])
        assertEquals(JsonPrimitive(1), excerpt["end_line"])
        assertEquals(JsonPrimitive("a".repeat(40)), excerpt["revision"])
        val checked = (after.getValue("verification") as JsonObject).getValue("claims") as JsonArray
        assertEquals(JsonPrimitive("VERIFIED"), (checked[0] as JsonObject)["status"])
        assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), (checked[0] as JsonObject)["observed"])
        assertEquals(JsonPrimitive(7), (checked[2] as JsonObject)["observed"])
        val embedded = Json.parseToJsonElement((after.getValue("embedded_task_contract") as JsonPrimitive).content)
        assertEquals(taskAfter, embedded)
    }

    @Test fun citationValuesRequireTypedMetadataAndCannotHideContentInsideEncodedJson() {
        fun citation(pointer: String, value: JsonElement) = buildJsonObject {
            put("receipt_id", "synthetic-source")
            put("pointer", pointer)
            put("value", value)
            put("value_json", value.toString())
        }
        val redactor = DiagnosticContentRedactor()
        val cases = listOf(
            citation("/topic", JsonPrimitive("PRIVATE CITED TEXT")),
            citation("/status", JsonPrimitive("PRIVATE STATUS WORDING")),
            citation("/amount", JsonPrimitive(90210)),
            citation("/sha", JsonPrimitive("PRIVATE INVALID HASH")),
            citation("/total_count", buildJsonObject { put("unusual", "PRIVATE NESTED TEXT") })
        )
        cases.forEach { original ->
            val safe = redactor.redact(original) as JsonObject
            assertEquals(original["receipt_id"], safe["receipt_id"])
            assertEquals(original["pointer"], safe["pointer"])
            assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), safe["value"])
            assertEquals(safe["value"], Json.parseToJsonElement((safe.getValue("value_json") as JsonPrimitive).content))
            assertEquals(safe, redactor.redact(safe))
        }
        val metadata = listOf(
            citation("/id", JsonPrimitive(42)),
            citation("/total_count", JsonPrimitive(7)),
            citation("/sha256", JsonPrimitive("b".repeat(64))),
            citation("/success", JsonPrimitive(true)),
            citation("/error_code", JsonPrimitive("SOURCE_CHANGED"))
        )
        metadata.forEach { assertEquals(it, redactor.redact(it)) }
    }

    @Test fun actualRetainedTaskAndSourcesNeedExplicitForensicModeAndStillLoseCredentials() = runBlocking {
        val record = retainedTaskDiagnosticRecord()
        val safe = DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC, listOf("private-secret")).redact(record)
        retainedTaskPrivateCanaries.forEach { assertTrue(it, safe.toString().contains(it)) }
        assertFalse(safe.toString().contains("private-secret"))
        assertEquals(ChatDebugRedactor(listOf("private-secret")).redact(record), safe)
    }

    @Test fun repositoryCheckpointPathsAreOmittedWhileCoverageAndSourceIdentityRemain() {
        // The retained checklist hydrates into fields.payload before staged redaction.
        val fixture = Json.parseToJsonElement(
            """{"table":"workspace_records","rowId":7,"fields":{"id":"checklist-current-synthetic","kind":"github_evidence","payload":{"review_comparison":{"state":"READ_CHANGED_SOURCE","head_sha":"synthetic-head","base_sha":"synthetic-base","comparison_verified":true,"source_work_queue":{"policy":"BOUNDED_CHANGED_SOURCE_SEED","maximum_windows":8,"selected_windows":2,"attempted_windows":1,"pending_windows":1,"selected_paths":["clients/Synthetic-Alpha/proposal.kt","clients/Synthetic-Beta/revised.kt"],"selected_path_preview_complete":true},"comparison_coverage":{"identity_sha256":"synthetic-identity","changed_files_observed":2,"changed_files_with_source_observation":1,"changed_files_without_source_observation":1,"pending_source_paths":["clients/Synthetic-Beta/revised.kt"],"pending_source_preview_complete":true,"source_review_verified":false}}}}}"""
        ) as JsonObject
        val redactor = DiagnosticContentRedactor()
        val safe = redactor.redact(fixture) as JsonObject
        assertFalse(safe.toString().contains("clients/"))
        assertEquals(fixture["rowId"], safe["rowId"])
        val originalFields = fixture["fields"] as JsonObject
        val fields = safe["fields"] as JsonObject
        assertEquals(originalFields["id"], fields["id"])
        val originalReview = (originalFields["payload"] as JsonObject)["review_comparison"] as JsonObject
        val review = (fields["payload"] as JsonObject)["review_comparison"] as JsonObject
        assertEquals(originalReview.filterKeys { it !in setOf("source_work_queue", "comparison_coverage") }, review.filterKeys { it !in setOf("source_work_queue", "comparison_coverage") })
        for ((section, pathField) in mapOf("source_work_queue" to "selected_paths", "comparison_coverage" to "pending_source_paths")) {
            val original = originalReview[section] as JsonObject
            val retained = review[section] as JsonObject
            assertEquals(JsonArray((original[pathField] as JsonArray).map { JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED) }), retained[pathField])
            assertEquals(original.filterKeys { it != pathField }, retained.filterKeys { it != pathField })
        }
        assertEquals(safe, redactor.redact(safe))
        assertEquals(fixture, DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC).redact(fixture))
    }

    @Test fun repositoryToolResultsAndStringifiedCheckpointDisplaysOmitOriginalPaths() {
        val validation = Json.parseToJsonElement(
            """{"schema":"github.read_validation.v1","error_code":"GITHUB_PATH_IS_DIRECTORY","status":"PRECHECK_REJECTED","failed_stage":"resolve_path","read_dispatched":false,"path":"clients/Synthetic-Alpha","commit_sha":"synthetic-head","entry_type":"tree","corrective_actions":[{"tool":"github_search","arguments":{"mode":"paths","query":"*","path_prefix":"clients/Synthetic-Alpha/"}}]}"""
        )
        val index = Json.parseToJsonElement(
            """{"commit_sha":"synthetic-head","prepared_through":3,"read_deadline_reached":true,"deferred_files":[{"path":"clients/Synthetic-Beta/revised.kt","sha":"synthetic-blob","reason":"REPEATED_READ_DEADLINE","attempts":2}],"files":[{"filename":"clients/Synthetic-Beta/revised.kt","previous_filename":"clients/Synthetic-Alpha/proposal.kt","status":"renamed","sha":"synthetic-renamed-blob"}]}"""
        )
        // ToolResultCheckpoint stores JSON content and display as strings. A
        // projected row can already contain the decoded object in either slot.
        val checkpoint = buildJsonObject {
            put("payload", validation)
            put("payload_type", "JSON")
            put("display", validation.toString())
            put("index_preparation", index)
        }
        val fixture = buildJsonObject {
            put("table", "tool_events")
            put("fields", buildJsonObject { put("result", checkpoint) })
            put("token", "synthetic-repository-credential")
        }
        val redactor = DiagnosticContentRedactor(credentials = listOf("synthetic-repository-credential"))
        val safe = redactor.redact(fixture)
        assertFalse(safe.toString().contains("clients/"))
        assertFalse(safe.toString().contains("synthetic-repository-credential"))
        for (structural in listOf("GITHUB_PATH_IS_DIRECTORY", "PRECHECK_REJECTED", "resolve_path", "synthetic-head", "synthetic-blob", "synthetic-renamed-blob", "REPEATED_READ_DEADLINE", "renamed", "github_search")) assertTrue(structural, safe.toString().contains(structural))
        val retained = (((safe as JsonObject)["fields"] as JsonObject)["result"] as JsonObject)["index_preparation"] as JsonObject
        assertEquals(JsonPrimitive(3), retained["prepared_through"])
        assertEquals(JsonPrimitive(true), retained["read_deadline_reached"])
        val deferred = (retained["deferred_files"] as JsonArray).single() as JsonObject
        assertEquals(JsonPrimitive(2), deferred["attempts"])
        assertEquals((index as JsonObject).filterKeys { it != "files" && it != "deferred_files" }, retained.filterKeys { it != "files" && it != "deferred_files" })
        assertEquals(1, (retained["files"] as JsonArray).size)
        assertEquals(safe, redactor.redact(safe))
        val log = redactor.logLine("eventId=synthetic-path-log · evidence=$index · outcome=READ_INCOMPLETE")
        assertFalse(log.contains("clients/"))
        assertTrue(log.contains("eventId=synthetic-path-log"))
        assertTrue(log.contains("outcome=READ_INCOMPLETE"))
        assertTrue(log.contains("REPEATED_READ_DEADLINE"))
        assertEquals(log, redactor.logLine(log))
        val forensic = DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC, listOf("synthetic-repository-credential")).redact(fixture)
        assertEquals(fixture["fields"], (forensic as JsonObject)["fields"])
        assertFalse(forensic.toString().contains("synthetic-repository-credential"))
    }

    @Test fun repositoryPathRulePreservesSchemaObjectsCountersHashesAndUnrelatedUrls() {
        val fixture = Json.parseToJsonElement(
            """{"properties":{"path":{"type":"string"},"filename":{"type":"string"},"path_prefix":{"type":"string"}},"required":["path"],"eligible_paths":12,"selected_path_preview_complete":true,"path_sha256":"synthetic-path-digest","source_path_count":2,"files":{"count":3},"paths":4,"legacy_counts":{"selected_paths":2,"path":false},"status":"GITHUB_PATH_IS_DIRECTORY","documentationUrl":"https://example.org/reference/path","note":"Path observations retain counts and source identities."}"""
        )
        assertEquals(fixture, DiagnosticContentRedactor().redact(fixture))
        val structuredPaths = Json.parseToJsonElement("""{"selectedPaths":[{"path":"clients/Synthetic-Gamma/source.kt","sha":"synthetic-blob","status":"READ"},7,true]}""") as JsonObject
        val retained = (DiagnosticContentRedactor().redact(structuredPaths) as JsonObject)["selectedPaths"] as JsonArray
        assertEquals(3, retained.size)
        assertEquals(JsonPrimitive("synthetic-blob"), (retained[0] as JsonObject)["sha"])
        assertEquals(JsonPrimitive("READ"), (retained[0] as JsonObject)["status"])
        assertEquals(JsonPrimitive(7), retained[1])
        assertEquals(JsonPrimitive(true), retained[2])
        assertFalse(retained.toString().contains("clients/"))
    }

    @Test fun exportedToolCheckpointsOmitOriginalContentInEveryHydratedShape() {
        val execution = Json.parseToJsonElement("""{"schema":"gptmobile.tool-execution.v1","error_code":"GITHUB_PATH_IS_DIRECTORY","phase":"PREFLIGHT_REJECTED","dispatched":false,"requires_reconciliation":false,"attempts":2,"message":"PRIVATE execution prose"}""")
        val contents = listOf(
            JsonPrimitive("PRIVATE original prose"),
            JsonPrimitive("{\"patient\":\"PRIVATE malformed"),
            JsonPrimitive("{\"patient\":\"PRIVATE excerpt\n[Result truncated to the run's output budget.]"),
            JsonPrimitive("Structured content: {\"patient\":\"PRIVATE prefixed\"}"),
            Json.parseToJsonElement("""{"patient":"PRIVATE unknown field","nested":{"beneficiary":"PRIVATE nested"}}"""),
            Json.parseToJsonElement("""["PRIVATE array",{"unrecognized":"PRIVATE object"}]""")
        )
        val redactor = DiagnosticContentRedactor()
        for (content in contents) {
            val checkpoint = buildJsonObject {
                put("payload", content)
                put("payload_type", "JSON")
                put("display", content)
                put("execution", execution)
                put("unknown_shell_field", "PRIVATE shell extension")
            }
            for (saved in listOf(checkpoint, JsonPrimitive(checkpoint.toString()))) {
                val fixture = toolEventRow(saved, "CHECKPOINT_V1")
                val safe = redactor.redact(fixture) as JsonObject
                assertFalse(safe.toString().contains("PRIVATE"))
                val fields = safe["fields"] as JsonObject
                val result = fields["result"] as JsonObject
                assertEquals(setOf("payload_type", "payload", "display", "execution"), result.keys)
                assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), result["payload"])
                assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), result["display"])
                assertEquals(JsonPrimitive("JSON"), result["payload_type"])
                assertEquals(redactor.redact(execution), result["execution"])
                assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), fields["connection_name_snapshot"])
                assertEquals((fixture["fields"] as JsonObject).filterKeys { it !in setOf("result", "connection_name_snapshot") }, fields.filterKeys { it !in setOf("result", "connection_name_snapshot") })
                assertEquals(safe, redactor.redact(safe))
            }
        }
    }

    @Test fun exportedNonCheckpointToolResultsStayPrivateAfterJsonProjection() {
        val results = listOf(
            Json.parseToJsonElement("""{"patient":"PRIVATE JSON","status":"PRIVATE provider prose"}"""),
            Json.parseToJsonElement("""[{"beneficiary":"PRIVATE array"}]"""),
            JsonPrimitive("PRIVATE text"),
            JsonPrimitive("{\"patient\":\"PRIVATE malformed"),
            JsonPrimitive(17)
        )
        val redactor = DiagnosticContentRedactor()
        for (type in listOf("JSON", "TEXT", "RESOURCE_LINKS", "UNSUPPORTED", "FUTURE_TYPE")) {
            for (result in results) {
                val fixture = toolEventRow(result, type)
                val safe = redactor.redact(fixture) as JsonObject
                val fields = safe["fields"] as JsonObject
                assertFalse(safe.toString().contains("PRIVATE"))
                assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), fields["result"])
                assertEquals((fixture["fields"] as JsonObject).filterKeys { it !in setOf("result", "connection_name_snapshot") }, fields.filterKeys { it !in setOf("result", "connection_name_snapshot") })
                assertEquals(fixture.filterKeys { it != "fields" }, safe.filterKeys { it != "fields" })
                assertEquals(safe, redactor.redact(safe))
            }
        }
    }

    @Test fun toolCheckpointProvenanceRetainsTypedScopeWithoutFreeReferencesOrRecipientNames() {
        val label = Json.parseToJsonElement(
            """{"version":1,"sourceToolId":"memory:recall","sourceProfileUid":"profile-1","sourceConnectionUid":"connection-1","sourceRef":"PRIVATE source reference","categories":["MEMORY","HEALTH","PRIVATE category"],"sourceRecipients":["PRIVATE recipient name","PRIVATE service name"],"retentionChatId":19,"rangeStart":1000,"rangeEnd":2000,"localOnly":true,"unknown":"PRIVATE unknown"}"""
        )
        val invalid = Json.parseToJsonElement("""{"version":"PRIVATE version","sourceProfileUid":"PRIVATE owner name","sourceRef":{"patient":"PRIVATE nested"},"rangeStart":"PRIVATE range","localOnly":"PRIVATE flag","categories":[{"patient":"PRIVATE category"}],"sourceRecipients":{"patient":"PRIVATE recipient"}}""")
        val checkpoint = buildJsonObject {
            put("payload_type", "TEXT")
            put("payload", "PRIVATE payload")
            put("display", "PRIVATE display")
            put("personal_data", JsonArray(listOf(label, invalid, JsonPrimitive("PRIVATE malformed label"))))
        }
        val redactor = DiagnosticContentRedactor()
        val safe = redactor.redact(toolEventRow(checkpoint, "CHECKPOINT_V1")) as JsonObject
        assertFalse(safe.toString().contains("PRIVATE"))
        val retained = (((safe["fields"] as JsonObject)["result"] as JsonObject)["personal_data"] as JsonArray)
        assertEquals(3, retained.size)
        val first = retained[0] as JsonObject
        val original = label as JsonObject
        val safeFields = setOf("version", "sourceToolId", "sourceProfileUid", "sourceConnectionUid", "retentionChatId", "rangeStart", "rangeEnd", "localOnly")
        assertEquals(original.filterKeys { it in safeFields }, first.filterKeys { it in safeFields })
        assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), first["sourceRef"])
        assertEquals(JsonArray(List(2) { JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED) }), first["sourceRecipients"])
        assertEquals(JsonPrimitive("MEMORY"), (first["categories"] as JsonArray)[0])
        assertEquals(JsonPrimitive("HEALTH"), (first["categories"] as JsonArray)[1])
        assertFalse(first.containsKey("unknown"))
        assertEquals(safe, redactor.redact(safe))
    }

    @Test fun malformedToolCheckpointShellsFailClosedAndNullResultsStayNull() {
        val malformed = listOf(
            JsonPrimitive("{\"payload\":\"PRIVATE broken"),
            Json.parseToJsonElement("""{"payload_type":"PRIVATE type","payload":{"patient":"PRIVATE payload"},"display":"PRIVATE display"}"""),
            Json.parseToJsonElement("""{"payload_type":"JSON","execution":{"patient":"PRIVATE fake metadata"}}"""),
            Json.parseToJsonElement("""["PRIVATE wrong shape"]""")
        )
        val redactor = DiagnosticContentRedactor()
        for (checkpoint in malformed) {
            val safe = redactor.redact(toolEventRow(checkpoint, "CHECKPOINT_V1")) as JsonObject
            assertFalse(safe.toString().contains("PRIVATE"))
            assertEquals(JsonPrimitive(DiagnosticContentRedactor.PRIVATE_CONTENT_OMITTED), (safe["fields"] as JsonObject)["result"])
        }
        val empty = redactor.redact(toolEventRow(JsonNull, "TEXT")) as JsonObject
        assertEquals(JsonNull, (empty["fields"] as JsonObject)["result"])
    }

    @Test fun toolResultProjectionPreservesOtherRecordsAndExplicitForensicContentConsent() {
        val workspace = Json.parseToJsonElement("""{"table":"workspace_records","rowId":9,"fields":{"kind":"github_evidence","payload":{"schema":"github.verification.v1","state":"READ_CHANGED_SOURCE","head_sha":"synthetic-head","passed":2,"total":3}}}""")
        assertEquals(workspace, DiagnosticContentRedactor().redact(workspace))
        val checkpoint = buildJsonObject {
            put("payload_type", "JSON")
            put("payload", buildJsonObject { put("patient", "PRIVATE original synthetic-tool-credential") })
            put("display", "PRIVATE display")
            put(
                "execution",
                buildJsonObject {
                    put("schema", "gptmobile.tool-execution.v1")
                    put("error_code", "TOOL_FAILURE")
                }
            )
        }
        val fixture = toolEventRow(checkpoint, "CHECKPOINT_V1")
        val safe = DiagnosticContentRedactor(credentials = listOf("synthetic-tool-credential")).redact(fixture).toString()
        val forensic = DiagnosticContentRedactor(DiagnosticContentMode.FULL_FORENSIC, listOf("synthetic-tool-credential")).redact(fixture).toString()
        assertFalse(safe.contains("PRIVATE"))
        assertTrue(forensic.contains("PRIVATE original"))
        assertTrue(forensic.contains("PRIVATE display"))
        assertTrue(forensic.contains("PRIVATE connection"))
        assertFalse(forensic.contains("synthetic-tool-credential"))
        assertTrue(forensic.contains("TOOL_FAILURE"))
    }

    private fun toolEventRow(result: JsonElement, type: String): JsonObject = buildJsonObject {
        put("table", "tool_events")
        put("rowId", 7)
        put("tableHighWatermark", 12)
        put(
            "fields",
            buildJsonObject {
                put("event_id", "synthetic-event")
                put("run_id", "synthetic-run")
                put("call_id", "synthetic-call")
                put("connection_uid_snapshot", "synthetic-connection-uid")
                put("connection_name_snapshot", "PRIVATE connection")
                put("tool_name", "synthetic_tool")
                put("model_tool_name", "synthetic_tool")
                put("result_type", type)
                put("status", "COMPLETED")
                put("is_error", false)
                put("started_at", 1000)
                put("completed_at", 2000)
                put("result", result)
            }
        )
    }
}

internal val retainedTaskPrivateCanaries = listOf("PRIVATE TASK CANARY", "PRIVATE ACCEPTANCE CANARY", "PRIVATE SOURCE CANARY", "PRIVATE FACT CANARY", "PRIVATE UNMEASURED CANARY")

/** Real retained producers, shared with the staged ZIP/resume regression. */
internal suspend fun retainedTaskDiagnosticRecord(): JsonObject {
    val request = "Review PRIVATE TASK CANARY private-secret.\nAcceptance criteria:\n- Explain PRIVATE ACCEPTANCE CANARY."
    val saved = mutableMapOf<String, JsonObject>()
    val book = GitHubEvidenceBook(
        binding = "synthetic-task-binding",
        repository = "fixture/repository",
        branch = "main",
        trustedTools = setOf("github_read"),
        now = { 1000L },
        load = { saved[it] },
        save = { key, value -> saved[key] = value }
    )
    book.retainTaskContract(request, sourceMessageId = 19, tools = emptyList(), callLimit = 48)
    val task = saved.getValue("task_contract")
    val source = buildJsonObject {
        put("tool", "github_read")
        put("trusted", true)
        put("success", true)
        put("observed_at_ms", 1000)
        put("epoch", 0)
        put("source_head_sha", "a".repeat(40))
        put("error_code", "SYNTHETIC_METADATA_CODE")
        put("arguments", buildJsonObject { put("commit_sha", "a".repeat(40)) })
        put(
            "result",
            buildJsonObject {
                put("commit_sha", "a".repeat(40))
                put("total_count", 7)
                put("status", "completed")
                put("topic", "PRIVATE FACT CANARY private-secret")
                put(
                    "items",
                    JsonArray(
                        listOf(
                            buildJsonObject {
                                put("path", "src/Fixture.kt")
                                put("sha", "b".repeat(40))
                                put("content", "PRIVATE SOURCE CANARY private-secret")
                                put("start_line", 1)
                                put("end_line", 1)
                            }
                        )
                    )
                )
            }
        )
    }
    val receipts = mapOf("synthetic-source" to source)
    val excerpt = GitHubEvidenceExcerpts.collect(source).entries.single()
    val fact = GitHubEvidencePolicy.facts(source.getValue("result")).getValue("/topic")
    fun claim(kind: String, pointer: String, value: JsonElement) = buildJsonObject {
        put("kind", kind)
        put("receipt_id", "synthetic-source")
        put("pointer", pointer)
        put("value", value)
    }
    val claims = listOf(
        claim("excerpt", excerpt.key, excerpt.value.getValue("value")),
        claim("fact", "/topic", fact),
        claim("fact", "/total_count", JsonPrimitive(7)),
        buildJsonObject {
            put("kind", "not_measured")
            put("subject", "PRIVATE UNMEASURED CANARY")
        }
    )
    val answer = buildJsonObject { put("claims", JsonArray(claims)) }
    val payload = buildJsonObject {
        put("task_contract", task)
        put("embedded_task_contract", task.toString())
        put("verification", GitHubEvidencePolicy.render(answer, receipts, now = 1000L, epoch = 0L, task = request).second)
        put("evidence_catalog", GitHubEvidencePolicy.catalog(receipts))
        put("extraction", answer)
        put(
            "encoded_claim",
            buildJsonObject {
                put("receipt_id", "synthetic-source")
                put("pointer", "/topic")
                put("value_json", fact.toString())
            }
        )
    }
    return buildJsonObject {
        put("table", "workspace_records")
        put("rowId", 41)
        put(
            "fields",
            buildJsonObject {
                put("id", "synthetic-retained-task")
                put("kind", "synthesis_checkpoint")
                put("payload", payload)
            }
        )
    }
}
