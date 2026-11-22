package dev.chungjungsoo.gptmobile.data.diagnostics

import dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitSchedulingResult
import dev.chungjungsoo.gptmobile.data.network.error.ProviderStreamFailure
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceEvidenceConflict
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionCompletionDiagnosticsTest {
    @Test fun currentFailureDoesNotBorrowAnEarlierFailuresTypedObservations() {
        for (code in listOf("GATEWAY_OUTCOME_UNKNOWN", "OUTPUT_CONTINUATION_NO_PROGRESS")) {
            val current = DiagnosticFailureEvidence(
                "current",
                "MESSAGE_PROFILE_FAILURE",
                buildJsonObject {
                    put("failure_code", code)
                    put("invocation_id", "current-attempt")
                }
            )
            val older = DiagnosticFailureEvidence(
                "older",
                "OUTPUT_CHECKPOINT",
                buildJsonObject {
                    put("failure_code", if (code == "GATEWAY_OUTCOME_UNKNOWN") "PROVIDER_RATE_LIMITED" else code)
                    put("provider_failure_kind", "RATE_LIMIT")
                    put("http_status", 429)
                    put("generation_completed", false)
                    put("no_output_kind", "REASONING_ONLY")
                    put("reasoning_bytes", 19128)
                    put("no_output_repairs", 1)
                    put("invocation_id", "earlier-attempt")
                }
            )
            val summary = diagnosticFailureSummary("run", "BLOCKED", null, "test", 147, "build", listOf(current, older))
            assertEquals(JsonPrimitive(code), summary["failure_code"])
            assertFalse(summary.containsKey("provider_failure_kind"))
            assertFalse(summary.containsKey("http_status"))
            assertFalse(summary.containsKey("no_output_kind"))
            assertFalse(summary.containsKey("reasoning_bytes"))
            assertFalse(summary.containsKey("generation_completed"))
        }
    }

    @Test fun immutableConflictSurvivesStoredMessageAndPublicProjectionWithoutPayloads() {
        val event = WorkspaceEvidenceConflict("private-record", "a".repeat(64), "b".repeat(64), "IMMUTABLE_PAYLOAD_CHANGED").event()
        val details = requireNotNull(event.failureDetails)
        val stored = ProfileFailureRecord.fromProvider(event.message, event.errorCode, details)
        val summary = messageSummary(stored)
        assertEquals(JsonPrimitive("WORKSPACE_EVIDENCE_CONFLICT"), summary["failure_code"])
        assertEquals(JsonPrimitive("application_persistence"), summary["failed_stage"])
        assertEquals(JsonPrimitive("BLOCKED"), summary["task_outcome"])
        assertEquals(JsonPrimitive("IMMUTABLE_PAYLOAD_CHANGED"), summary["evidence_conflict_reason"])
        assertEquals(details["record_identity_sha256"], summary["record_identity_sha256"])
        assertFalse(summary.toString().contains("private-record"))

        val report = capture(requireNotNull(event.errorCode), details)
        assertEquals("application_persistence", report.stage)
        assertEquals("REVIEW_APPLICATION_EVIDENCE", report.recoveryAction)
        val diagnostics = requireNotNull(report.diagnostics)
        assertTrue(ExecutionCompletionDiagnostics.FEATURE in diagnostics.requiredServiceFeatures())
        for (key in listOf("evidence_record_hash", "evidence_proposed_payload_hash", "evidence_retained_payload_hash")) assertNotNull(diagnostics.values[key])
        assertFalse(diagnostics.toString().contains("a".repeat(64)))
        assertFalse(diagnostics.toString().contains("b".repeat(64)))
        assertNotNull(diagnostics.validated())
    }

    @Test fun reasoningOnlyFailureKeepsRepairLimitAndIndependentExecutionOutcome() {
        val details = buildJsonObject {
            put("failed_stage", "final_synthesis")
            put("no_output_kind", "REASONING_ONLY")
            put("visible_bytes", 0)
            put("reasoning_bytes", 19128)
            put("tool_call_count", 0)
            put("no_output_repairs", 1)
            put("no_output_repair_limit", 1)
            put("repair_scheduled", false)
            put("draft_sha256", "a".repeat(64))
            put("segment_sha256", "b".repeat(64))
            put("evidence_digest", "c".repeat(64))
            put("next_action", "UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST")
            put("private_reasoning", "never publish this")
        }
        val summary = messageSummary(ProfileFailureRecord.fromProvider("private message", "OUTPUT_CONTINUATION_NO_PROGRESS", details))
        assertEquals(JsonPrimitive("REASONING_ONLY"), summary["no_output_kind"])
        assertEquals(JsonPrimitive(19128), summary["reasoning_bytes"])
        assertEquals(JsonPrimitive("BLOCKED"), summary["task_outcome"])
        assertEquals(JsonPrimitive(false), summary["repair_scheduled"])
        assertFalse(summary.toString().contains("private"))
        val context = capture("OUTPUT_CONTINUATION_NO_PROGRESS", details)
        assertEquals("UPDATE_OUTPUT_CAPACITY_OR_REMAINING_REQUEST", context.recoveryAction)
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive(19128), diagnostics.values["no_output_reasoning_bytes"])
        assertEquals(JsonPrimitive(1), diagnostics.values["no_output_repair_limit"])
        assertFalse(diagnostics.toString().contains("never publish"))
        assertFalse(diagnostics.toString().contains("a".repeat(64)))
        assertTrue(ExecutionCompletionDiagnostics.publicFields("CHAT_FAILED", details).isEmpty())
    }

    @Test fun acceptedHttpBillingKeepsSemanticFailureAndGenerationOutcomeAfterRestart() {
        val event = ProviderStreamFailure.event("insufficient_quota", "private provider body", httpStatus = 200)
        val summary = messageSummary(ProfileFailureRecord.fromProvider(event.message, event.code, event.failureDetails))
        assertEquals(JsonPrimitive("BILLING_UNAVAILABLE"), summary["failure_code"])
        assertEquals(JsonPrimitive("BUDGET"), summary["provider_failure_kind"])
        assertEquals(JsonPrimitive("insufficient_quota"), summary["upstream_error_code"])
        assertEquals(JsonPrimitive(200), summary["http_status"])
        assertEquals(JsonPrimitive(false), summary["generation_completed"])
        assertEquals(JsonPrimitive(false), summary["retryable"])
        assertFalse(summary.toString().contains("private"))
        assertTrue("BILLING_UNAVAILABLE" in IssueReportCodes.allowed)
    }

    @Test fun checkpointJournalCorrelationAndExplicitWaitRefusalUseClosedPublicFields() {
        val details = buildJsonObject {
            put("failure_origin", "local_recovery")
            put("recovery_code", "PROVIDER_WAIT_CHECKPOINT_CHANGED")
            put("failed_stage", "lifecycle")
            put("expected_tool_journal_digest", "a".repeat(64))
            put("observed_tool_journal_digest", "b".repeat(64))
            put("tool_checkpoint_binding_sha256", "c".repeat(64))
            put("queued_tool_count", 2)
            put("unsettled_tool_count", 1)
            put("settled_tool_count", 3)
            put("checkpoint_validation_stage", "TOOL_JOURNAL")
        }
        val context = capture("PROVIDER_WAIT_CHECKPOINT_CHANGED", details)
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive(2), diagnostics.values["provider_wait_queued_tool_count"])
        assertEquals(JsonPrimitive("TOOL_JOURNAL"), diagnostics.values["checkpoint_validation_stage"])
        assertNotNull(diagnostics.values["provider_wait_expected_tool_journal_hash"])
        assertNotNull(diagnostics.values["provider_wait_observed_tool_journal_hash"])
        assertFalse(diagnostics.toString().contains("a".repeat(64)))
        val refusal = ProviderWaitSchedulingResult.refused(ProviderWaitSchedulingResult.NoWaitReason.OWNER_CHANGED).fields()
        val wait = requireNotNull(capture("PROVIDER_RATE_LIMITED", refusal).diagnostics)
        assertEquals(JsonPrimitive("NOT_SCHEDULED"), wait.values["provider_wait_decision_state"])
        assertEquals(JsonPrimitive("OWNER_CHANGED"), wait.values["provider_wait_no_wait_reason"])
        assertEquals(JsonPrimitive(false), wait.values["provider_wait_persisted"])
        assertTrue(ExecutionCompletionDiagnostics.FEATURE in wait.requiredServiceFeatures())
        assertNull(ExecutionCompletionDiagnostics.validValue("provider_wait_no_wait_reason", JsonPrimitive("private exception")))
        assertNull(ExecutionCompletionDiagnostics.validValue("no_output_reasoning_bytes", JsonPrimitive("19128")))
        assertNull(ExecutionCompletionDiagnostics.validValue("provider_semantic_status", JsonPrimitive(200)))
    }

    @Test fun fuzzySearchReasonSurvivesPublicPrecheckWithoutQueryText() {
        val reason = "GITHUB_SEARCH_FUZZY_QUERY_INVALID"
        val fields = mapOf("diagnostic_area" to "TOOL", "failed_stage" to "tool_precheck", "dispatched" to false, "reason_code" to reason, "tool_reason_code" to reason)
        val context = IssueReportContext.fromFields("TOOL_RESULT", IssueReportIncidentCapsule.fields(ReportFeature.TOOLS, "TOOL_RESULT", "INVALID_ARGUMENTS", fields), 1_800_000_000_000)
        assertEquals(reason, context.reasonCode)
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive(reason), diagnostics.values["tool_reason_code"])
        assertTrue(ExecutionCompletionDiagnostics.FEATURE in diagnostics.requiredServiceFeatures())
    }

    private fun capture(code: String, details: JsonObject) = IssueReportContext.fromFields(
        "CHAT_FAILURE",
        IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", code, details),
        1_800_000_000_000
    )

    private fun messageSummary(record: ProfileFailureRecord): JsonObject {
        val restored = Json.decodeFromString(ProfileFailureRecord.serializer(), Json.encodeToString(ProfileFailureRecord.serializer(), record))
        val timeline = JsonArray(
            listOf(
                buildJsonObject {
                    put("type", "NOTICE")
                    put("modelAuthored", false)
                    put("profileFailure", Json.encodeToJsonElement(ProfileFailureRecord.serializer(), restored))
                }
            )
        )
        val evidence = requireNotNull(diagnosticMessageFailureEvidence(77, timeline))
        val summary = diagnosticFailureSummary("run", "BLOCKED", null, "test", 147, "build", listOf(evidence))
        assertEquals(JsonPrimitive("MESSAGE_PROFILE_FAILURE"), summary.getValue("field_sources").jsonObject["failure_code"])
        return summary
    }
}
