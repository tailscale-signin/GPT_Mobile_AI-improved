package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportDiagnosticsTest {
    private val time = 1_700_000_000_000L
    private val hash = "a".repeat(64)

    @Test fun failureDetailsAreReadableBoundedAndNegotiated() {
        val input = mapOf("diagnostic_area" to "TOOL", "tool_action" to "headers", "tool_error_code" to "INVALID_ARGUMENTS", "tool_reason_code" to "TOOL_ARGUMENT_SCHEMA_REJECTED", "tool_dispatch_phase" to "PREFLIGHT_REJECTED", "unknown_field_count" to 1, "private_argument" to "secret")
        val diagnostics = requireNotNull(IssueReportContext.fromFields("TOOL_RESULT", input, time).diagnostics)
        assertEquals(JsonPrimitive("headers"), diagnostics.values["tool_action"])
        assertEquals(JsonPrimitive(1), diagnostics.values["unknown_field_count"])
        assertEquals(setOf("INCIDENT_FAILURE_DETAILS_V1"), diagnostics.requiredServiceFeatures())
        assertTrue(diagnostics.isValid())
        val payload = report(IssueReportContext.fromFields("TOOL_RESULT", input, time)).approvedPayload()
        assertFalse(payload.contains("secret"))
        val unsafe = requireNotNull(IssueReportContext.fromFields("TOOL_RESULT", input + mapOf("tool_action" to "private/action", "tool_error_code" to "PRIVATE_CODE", "unknown_field_count" to -1), time).diagnostics)
        assertFalse("tool_action" in unsafe.values)
        assertFalse("tool_error_code" in unsafe.values)
        assertFalse("unknown_field_count" in unsafe.values)
    }

    @Test fun providerObservationSurvivesApprovalWithExactUsageLimitAndBackendEvidence() {
        val context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf(
                "diagnostic_area" to "PROVIDER", "invocation_id_hash" to hash, "profile_hash" to hash, "model_hash" to "b".repeat(64),
                "configured_output_limit" to 4096, "effective_output_limit" to 2048, "output_tokens" to 2048, "reasoning_tokens" to 1800,
                "visible_output_bytes" to 0, "reasoning_output_bytes" to 7200, "finish_reason" to "LENGTH", "transport_complete" to true,
                "backend_attempts" to 3, "backend_completed_attempts" to 2, "backend_failed_attempt" to 3, "backend_output_limit" to 2048,
                "backend_status" to "FAILED", "backend_terminal_status" to 503, "backend_terminal_code_hash" to hash, "backend_finish_reason" to "LENGTH",
                "output_byte_scope" to "LAST_BACKEND_ATTEMPT", "usage_source" to "gateway_reported_partial",
                "privacy_zdr_required" to true, "privacy_provider_allowlist_count" to 2, "privacy_policy_hash" to hash,
                "requested_model_id" to "private-model", "prompt" to "private-prompt", "token" to "private-token", "endpoint" to "https://private.example"
            ),
            time
        )
        assertEquals(5, context.schemaVersion)
        val payload = report(context).approvedPayload()
        val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), payload)
        assertEquals(payload, restored.approvedPayload())
        val facts = requireNotNull(restored.context?.diagnostics).values
        assertEquals(JsonPrimitive(2048), facts["output_tokens"])
        assertEquals(JsonPrimitive(1800), facts["reasoning_tokens"])
        assertEquals(JsonPrimitive(4096), facts["configured_output_limit"])
        assertEquals(JsonPrimitive(0), facts["visible_output_bytes"])
        assertEquals(JsonPrimitive("LAST_BACKEND_ATTEMPT"), facts["output_byte_scope"])
        assertEquals(JsonPrimitive(hash), facts["invocation_id_hash"])
        assertEquals(emptyList<String>(), context.diagnostics?.unavailableFields)
        for (privateValue in listOf("private-model", "private-prompt", "private-token", "private.example", "requested_model_id")) assertFalse(payload.contains(privateValue))
        assertTrue(IssueReportContext.validDiagnosticsIn(IssueReport.json.parseToJsonElement(payload).jsonObject["context"]))
    }

    @Test fun ordinaryEventsAndRestoredLegacyContextsDoNotOptIntoFive() {
        val cases = listOf(
            1 to mapOf("http_status" to 503),
            2 to mapOf("failed_stage" to "acceptance_verification"),
            3 to mapOf("failed_stage" to "review_comparison"),
            4 to mapOf("failure_origin" to "local_recovery", "recovery_code" to "PROVIDER_WAIT_CHECKPOINT_CHANGED")
        )
        for ((version, fields) in cases) {
            val original = report(IssueReportContext.fromFields("CHAT_FAILURE", fields, time)).approvedPayload()
            val richerUnselectedEvent = report(IssueReportContext.fromFields("CHAT_FAILURE", fields + mapOf("model_hash" to hash, "output_tokens" to 12), time)).approvedPayload()
            assertEquals(original, richerUnselectedEvent)
            val restored = IssueReport.json.decodeFromString(IssueReport.serializer(), original)
            assertEquals(version, restored.context?.schemaVersion)
            assertEquals(original, restored.approvedPayload())
            assertFalse(original.contains("diagnostics"))
        }
    }

    @Test fun snapshotCorrelationUsesTheSameExplicitOwnedIdsAsItsIncident() {
        val context = IssueReportContext.fromFields(
            "CHAT_FAILURE",
            mapOf(
                "diagnostic_area" to "ACCEPTANCE",
                "run_id" to "owned-run",
                "invocationId" to "owned-invocation",
                "toolCallId" to "owned-call",
                "gatewayJobId" to "owned-job",
                "run_id_hash" to hash,
                "active_job_id" to "other-blocking-job",
                "checkpoint_hash" to hash
            ),
            time
        )
        val snapshot = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive(context.runIdHash), snapshot.values["run_id_hash"])
        assertEquals(JsonPrimitive(context.invocationIdHash), snapshot.values["invocation_id_hash"])
        assertEquals(JsonPrimitive(context.callIdHash), snapshot.values["call_id_hash"])
        assertEquals(JsonPrimitive(context.jobIdHash), snapshot.values["job_id_hash"])
        assertFalse(snapshot.values["job_id_hash"] == JsonPrimitive(context.activeJobIdHash))
        val raw = report(context).approvedPayload()
        for (privateValue in listOf("owned-run", "owned-invocation", "owned-call", "owned-job", "other-blocking-job")) assertFalse(raw.contains(privateValue))
        val noIds = IssueReportContext.fromFields("CHAT_FAILURE", mapOf("diagnostic_area" to "ACCEPTANCE", "checkpoint_hash" to hash), time)
        assertFalse("run_id_hash" in requireNotNull(noIds.diagnostics).values)
        assertNull(noIds.runIdHash)
    }

    @Test fun noCaptureAndUnknownFactsStayExplicitWithoutInventedZeroMeasurements() {
        val snapshot = requireNotNull(IssueReportDiagnostics.fromFields("MANUAL_REPORT", mapOf("diagnostic_area" to "VOICE", "diagnostic_capture_mode" to "NO_CAPTURE", "voice_read_calls" to 0), time))
        assertTrue(snapshot.values.isEmpty())
        assertTrue(snapshot.unavailableFields.containsAll(listOf("voice_engine", "voice_phase", "voice_capture_attempts")))
        val manual = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = time + 10).withDiagnostics(snapshot)
        assertEquals(time + 10, manual.occurredAtMillis)
        assertEquals(time, manual.diagnostics?.capturedAtMillis)
        assertEquals(5, manual.schemaVersion)
        assertNull(IssueReportDiagnostics.fromFields("VOICE_STATE", mapOf("voice_engine" to "SYSTEM"), time))
        assertNull(IssueReportDiagnostics.fromFields("VOICE_STATE", mapOf("diagnostic_area" to "private-area"), time))
        assertNull(IssueReportDiagnostics.fromFields("VOICE_STATE", mapOf("diagnostic_area" to "VOICE", "diagnostic_capture_mode" to false), time))
        assertNull(IssueReportDiagnostics.fromFields("private-source", mapOf("diagnostic_area" to "VOICE"), time))
    }

    @Test fun voiceTimingKeepsObservedEngineBasisAndUnknownInputCounters() {
        val snapshot = capture(
            "VOICE",
            mapOf(
                "voice_engine" to "SYSTEM", "voice_phase" to "SPEAKING", "voice_first_audio_basis" to "UTTERANCE_START",
                "voice_response_to_queue_ms" to 22L, "voice_queue_to_synthesis_ms" to 81L, "voice_synthesis_to_first_audio_ms" to 11L,
                "voice_response_to_first_audio_ms" to 114L, "voice_permission_granted" to true, "voice_generation" to Long.MAX_VALUE,
                "voice_session_id_hash" to hash, "voice_read_calls" to "4", "voice_captured_samples" to -1, "voice_muted" to "false",
                "voice_nonzero_samples" to 16_000_001, "voice_warmup_ms" to 86_400_001
            )
        )
        val payload = report(IssueReportContext.fromFields("VOICE_STATE", occurredAtMillis = time).withDiagnostics(snapshot)).approvedPayload()
        val restored = requireNotNull(IssueReport.json.decodeFromString(IssueReport.serializer(), payload).context?.diagnostics)
        assertEquals(JsonPrimitive("UTTERANCE_START"), restored.values["voice_first_audio_basis"])
        assertEquals(JsonPrimitive(114), restored.values["voice_response_to_first_audio_ms"])
        for (field in listOf("voice_read_calls", "voice_captured_samples", "voice_nonzero_samples", "voice_muted", "voice_warmup_ms")) assertFalse(field in restored.values)
        assertTrue("voice_capture_attempts" in restored.unavailableFields)
    }

    @Test fun acceptanceColumnsPreservePositionAndCopyMutableJsonArrayInputs() {
        val ids = mutableListOf<JsonElement>(JsonPrimitive(hash), JsonPrimitive("b".repeat(64)))
        val reasons = mutableListOf<JsonElement>(JsonPrimitive("c".repeat(64)), JsonPrimitive("d".repeat(64)))
        val snapshot = capture(
            "ACCEPTANCE",
            mapOf(
                "unmet_criterion_id_hashes" to JsonArray(ids), "unmet_criterion_reason_hashes" to JsonArray(reasons),
                "unmet_criterion_categories" to listOf("DEVICE_EXECUTION", "CI_VERIFICATION"), "unmet_criterion_states" to listOf("NOT_TESTED", "BLOCKED"),
                "unmet_criterion_blockers" to listOf("DEVICE_EXECUTION_NOT_OBSERVED", "CHECK_SUBSCRIPTION_REQUIRED"),
                "unmet_criterion_count" to 2, "unmet_criterion_omitted_count" to 0, "acceptance_evidence_scope" to "RETAINED_VERDICT",
                "effective_call_limit" to 12, "used_calls" to 10, "remaining_calls" to 2, "checkpoint_hash" to hash
            )
        )
        val before = IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot)
        ids[0] = JsonPrimitive("private-injected-value")
        reasons.clear()
        assertEquals(before, IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot))
        val columns = snapshot.values
        assertEquals(JsonPrimitive("DEVICE_EXECUTION"), (columns.getValue("unmet_criterion_categories") as JsonArray)[0])
        assertEquals(JsonPrimitive(hash), (columns.getValue("unmet_criterion_id_hashes") as JsonArray)[0])
        assertEquals(JsonPrimitive("RETAINED_VERDICT"), columns["acceptance_evidence_scope"])
        val unaligned = snapshot.copy(values = columns + ("unmet_criterion_states" to JsonArray(listOf(JsonPrimitive("BLOCKED")))))
        assertNull(unaligned.validated())
    }

    @Test fun largestCriterionCapturePreservesEveryColumnWithinTheApprovalBound() {
        val snapshot = capture(
            "ACCEPTANCE",
            mapOf(
                "run_id_hash" to hash, "invocation_id_hash" to hash, "call_id_hash" to hash, "job_id_hash" to hash, "profile_hash" to hash, "model_hash" to hash,
                "unmet_criterion_id_hashes" to List(8) { hash }, "unmet_criterion_reason_hashes" to List(8) { hash },
                "unmet_criterion_categories" to List(8) { "BUILD_REVISION_BINDING" }, "unmet_criterion_states" to List(8) { "NOT_TESTED" },
                "unmet_criterion_blockers" to List(8) { "WORKFLOW_RUN_OR_JOB_REQUIRED" }, "checkpoint_hash" to hash,
                "effective_call_limit" to 16_000_000, "used_calls" to 16_000_000, "remaining_calls" to 16_000_000,
                "verification_reserve" to 16_000_000, "pending_continuations" to 16_000_000, "unresolved_outcomes" to 16_000_000,
                "unmet_criterion_count" to 16_000_000, "unmet_criterion_omitted_count" to 15_999_992, "acceptance_evidence_scope" to "CURRENT_VERDICT"
            )
        )
        assertTrue(snapshot.isValid())
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        assertTrue(snapshot.omittedFields.isEmpty())
        assertTrue(snapshot.omittedFields.none { it in snapshot.values })
        val context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = time).withDiagnostics(snapshot)
        val maximumDescription = report(context).copy(source = "manual", feature = null, errorCode = null, title = "T".repeat(120), description = "D".repeat(1200))
        assertTrue(maximumDescription.approvedPayload().encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
    }

    @Test fun constructorAndRawJsonInjectionCannotBypassApprovalValidation() {
        val safe = capture("TOOL", mapOf("tool_name_hash" to hash, "invalid_field_classes" to listOf("MISSING_REQUIRED", "WRONG_TYPE")))
        for ((key, value) in listOf(
            "prompt" to JsonPrimitive("private-prompt"),
            "tool_schema_hash" to JsonPrimitive("private-schema"),
            "missing_field_count" to JsonPrimitive("1"),
            "invalid_field_count" to JsonPrimitive(true),
            "validation_error_count" to JsonPrimitive(-1),
            "invalid_field_classes" to JsonArray(listOf(JsonPrimitive("private-error"))),
            "voice_engine" to JsonPrimitive("SYSTEM")
        )) {
            val injected = safe.copy(values = safe.values + (key to value))
            assertFalse(injected.isValid())
            val context = IssueReportContext.fromFields("TOOL_RESULT", occurredAtMillis = time).withDiagnostics(safe).copy(diagnostics = injected)
            assertThrows(IllegalArgumentException::class.java) { report(context).approvedPayload() }
        }
        val raw = IssueReport.json.encodeToJsonElement(IssueReportDiagnostics.serializer(), safe).jsonObject
        assertNotNull(IssueReportDiagnostics.fromJson(raw))
        assertNull(IssueReportDiagnostics.fromJson(JsonObject(raw + ("privateModel" to JsonPrimitive("private-model")))))
        assertNull(IssueReportDiagnostics.fromJson(JsonObject(raw + ("schemaVersion" to JsonPrimitive("1")))))
        val context = IssueReportContext.fromFields("TOOL_RESULT", occurredAtMillis = time).withDiagnostics(safe)
        assertThrows(IllegalArgumentException::class.java) { report(context.copy(schemaVersion = 4)).approvedPayload() }
        assertThrows(IllegalArgumentException::class.java) { report(context.copy(diagnostics = null)).approvedPayload() }
        assertThrows(IllegalArgumentException::class.java) { report(context.copy(source = "private-source")).approvedPayload() }
        assertThrows(IllegalArgumentException::class.java) { report(context.copy(provider = "private-model")).approvedPayload() }
    }

    private fun capture(area: String, fields: Map<String, Any?>) = requireNotNull(IssueReportDiagnostics.fromFields("STRUCTURED_EVENT", fields + ("diagnostic_area" to area), time))

    private fun report(context: IssueReportContext) = IssueReport(
        "11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture", feature = "chat", errorCode = "CHAT_FAILED", context = context
    )
}
