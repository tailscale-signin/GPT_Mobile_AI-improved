package dev.chungjungsoo.gptmobile.data.diagnostics

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportIncidentCapsuleTest {
    private val time = 1_800_000_000_000L

    @Test fun dispatchPolicyReportRetainsClosedSectionsAndRequiresCompatibleService() {
        val context = capture(
            ReportFeature.CHAT,
            "CHAT_FAILURE",
            "RECONCILIATION_REQUIRED",
            mapOf(
                "failed_stage" to "tool_dispatch",
                "authority_invalidation_code" to "DISPATCH_POLICY_CHANGED",
                "blocked_stage" to "delivery",
                "changed_policy_fields" to listOf("profile.secretRef", "providerPermissions", "tools", "profile.apiUrl", "private-section"),
                "profile.secretRef" to "PRIVATE_SECRET_VALUE",
                "profile.apiUrl" to "https://PRIVATE_HOST.example/token",
                "resume_required" to true
            )
        )
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonArray(listOf("PROFILE", "PERMISSIONS", "TOOLS").map(::JsonPrimitive)), diagnostics.values["dispatch_policy_sections"])
        assertEquals(JsonPrimitive("delivery"), diagnostics.values["dispatch_policy_stage"])
        assertEquals(JsonPrimitive(true), diagnostics.values["dispatch_policy_resume_required"])
        assertEquals(JsonArray(listOf("profile.apiUrl", "profile.secretRef", "providerPermissions", "tools").map(::JsonPrimitive)), diagnostics.values["changed_policy_fields"])
        assertEquals(JsonPrimitive(5), diagnostics.values["changed_policy_field_count"])
        assertEquals(JsonPrimitive(1), diagnostics.values["changed_policy_unknown_count"])
        assertEquals(JsonPrimitive("PARTIAL"), diagnostics.values["changed_policy_observation"])
        assertEquals(setOf("DISPATCH_POLICY_DIAGNOSTICS_V1", "CHANGED_POLICY_FIELDS_V1"), diagnostics.requiredServiceFeatures())
        val payload = report(context).approvedPayload()
        assertFalse(payload.contains("PRIVATE_"))
        assertFalse(payload.contains("private-section"))
    }

    @Test fun changedPolicyProjectionKeepsOnlyClosedNamesAndPreciseBoundedCounts() {
        val known = IssueReportIncidentCapsule.policyFieldNames.sorted().take(12)
        val raw = JsonArray(known.map(::JsonPrimitive) + listOf(JsonPrimitive(known.first()), JsonPrimitive("profile.PRIVATE_SCOPE"), JsonPrimitive(7), JsonObject(mapOf("PRIVATE_NAME" to JsonPrimitive("PRIVATE_VALUE")))))
        val context = capture(ReportFeature.TOOLS, "TOOL_RESULT", "DISPATCH_POLICY_CHANGED", mapOf("changed_policy_fields" to raw, "changed_policy_unknown_count" to 999))
        val diagnostics = requireNotNull(context.diagnostics)
        val values = diagnostics.values
        assertEquals(JsonArray(known.take(8).map(::JsonPrimitive)), values["changed_policy_fields"])
        assertEquals(JsonPrimitive(16), values["changed_policy_field_count"])
        assertEquals(JsonPrimitive(1), values["changed_policy_unknown_count"])
        assertEquals(JsonPrimitive(2), values["changed_policy_malformed_count"])
        assertEquals(JsonPrimitive(4), values["changed_policy_omitted_count"])
        assertEquals(JsonPrimitive("PARTIAL"), values["changed_policy_observation"])
        val approved = report(context).approvedPayload()
        assertFalse(approved.contains("PRIVATE_"))
        assertEquals(approved, report(context).approvedPayload())
        assertNull(diagnostics.copy(values = values + ("changed_policy_fields" to JsonArray(listOf(JsonPrimitive("profile.PRIVATE_SCOPE"))))).validated())
        assertNull(diagnostics.copy(values = values + ("changed_policy_observation" to JsonPrimitive("OBSERVED"))).validated())
        assertNull(diagnostics.copy(values = values - "changed_policy_unknown_count").validated())
    }

    @Test fun missingMalformedAndOversizedPolicyEvidenceStayDistinctFromObservedEmptyLists() {
        val missing = requireNotNull(capture(ReportFeature.CHAT, "CHAT_FAILURE", "DISPATCH_POLICY_CHANGED", emptyMap()).diagnostics).values
        assertTrue(missing.keys.none { it.startsWith("changed_policy_") })
        for (raw in listOf(null, "PRIVATE_VALUE", true, JsonPrimitive(7), JsonObject(mapOf("PRIVATE_NAME" to JsonPrimitive("PRIVATE_VALUE"))))) {
            val context = capture(ReportFeature.CHAT, "CHAT_FAILURE", "DISPATCH_POLICY_CHANGED", mapOf("changed_policy_fields" to raw))
            val values = requireNotNull(context.diagnostics).values
            assertEquals(JsonPrimitive("MALFORMED"), values["changed_policy_observation"])
            assertNull(values["changed_policy_fields"])
            assertNull(values["changed_policy_field_count"])
            assertFalse(report(context).approvedPayload().contains("PRIVATE_"))
        }
        val empty = requireNotNull(capture(ReportFeature.CHAT, "CHAT_FAILURE", "DISPATCH_POLICY_CHANGED", mapOf("changed_policy_fields" to emptyList<String>())).diagnostics).values
        assertEquals(JsonPrimitive("OBSERVED"), empty["changed_policy_observation"])
        assertEquals(JsonPrimitive(0), empty["changed_policy_field_count"])
        assertNull(empty["changed_policy_fields"])
        val oversized = requireNotNull(capture(ReportFeature.CHAT, "CHAT_FAILURE", "DISPATCH_POLICY_CHANGED", mapOf("changed_policy_fields" to List(257) { "PRIVATE_VALUE" })).diagnostics).values
        assertEquals(JsonPrimitive("MALFORMED"), oversized["changed_policy_observation"])
        assertEquals(JsonPrimitive(257), oversized["changed_policy_field_count"])
        assertNull(oversized["changed_policy_fields"])
        assertNull(oversized["changed_policy_unknown_count"])
    }

    @Test fun everyKnownPolicyNameSurvivesWithoutChangingAuthorityOrUnknownOutcomeEvidence() {
        assertEquals(67, IssueReportIncidentCapsule.policyFieldNames.size)
        for (field in IssueReportIncidentCapsule.policyFieldNames) {
            val context = capture(
                ReportFeature.CHAT,
                "CHAT_FAILURE",
                "RECONCILIATION_REQUIRED",
                mapOf("changed_policy_fields" to listOf(field), "requires_reconciliation" to true, "failed_stage" to "tool_dispatch")
            )
            val values = requireNotNull(context.diagnostics).values
            assertEquals(JsonArray(listOf(JsonPrimitive(field))), values["changed_policy_fields"])
            assertEquals(JsonPrimitive("OBSERVED"), values["changed_policy_observation"])
            assertEquals(true, context.requiresReconciliation)
            assertNull(context.dispatched)
            assertNull(context.retryable)
            assertNull(context.remoteAcceptance)
            assertNull(values["request_replay_allowed"])
            assertNull(IssueReportIncidentCapsule.identity(context, "RECONCILIATION_REQUIRED"))
        }
    }

    @Test fun caughtExceptionRetainsBoundedOriginEvidenceWithoutInventingFailurePhase() {
        val error = IllegalStateException("private-message https://private.example/token", java.io.IOException("private-inner"))
        error.stackTrace = arrayOf(StackTraceElement("dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImpl", "completeChat", "private-file", 2591))
        error.cause!!.stackTrace = arrayOf(StackTraceElement("outside.private.Package", "privateMethod", "private-file", 99))
        val observed = IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH)
        val context = capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", observed + mapOf("run_id" to "private-run", "provider_kind" to "OPENROUTER"))
        val values = requireNotNull(context.diagnostics).values
        assertEquals(JsonPrimitive("CHAT_REPOSITORY_TERMINAL_CATCH"), values["incident_capture_site"])
        assertEquals(JsonPrimitive("CAPTURE_BOUNDARY_ONLY"), values["incident_phase_provenance"])
        assertEquals(JsonPrimitive("unknown"), values["incident_phase"])
        assertEquals(
            JsonArray(listOf("java.lang.IllegalStateException", "java.io.IOException").map { JsonPrimitive(requireNotNull(IssueReportContext.identifierHash("exception-type", it))) }),
            values["exception_type_hashes"]
        )
        assertEquals(
            IssueReportContext.fromFields("EXCEPTION", occurredAtMillis = time).withException("IllegalStateException", listOf("dev.chungjungsoo.gptmobile.data.repository.ChatRepositoryImpl.completeChat:2591")).exceptionFingerprint,
            context.exceptionFingerprint
        )
        assertNull(context.stage)
        assertNull(context.httpStatus)
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertNull(context.retryable)
        assertNull(context.invocationIdHash)
        assertNull(IssueReportIncidentCapsule.identity(context, "CHAT_FAILED"))
        val approved = report(context).approvedPayload()
        assertFalse(approved.contains("private"))
        assertFalse(approved.contains("IllegalStateException"))
        assertFalse(approved.contains("ChatRepositoryImpl.completeChat"))
        assertFalse(approved.contains("cause_types"))
        assertEquals(approved, report(context).approvedPayload())
    }

    @Test fun newCaptureBoundariesPreserveTheObservedSiteWithoutInventingAThrowableOrDispatch() {
        for (site in listOf(IssueReportIncidentCapsule.ExceptionCaptureSite.PROVIDER_ADAPTER_STREAM_CATCH, IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_ERROR_VALUE)) {
            val observed = IssueReportIncidentCapsule.boundaryFields(site)
            assertEquals(setOf("incident_capture_site", "incident_phase_provenance"), observed.keys)
            val context = capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", observed)
            val diagnostic = requireNotNull(context.diagnostics)
            assertEquals(JsonPrimitive(site.name), diagnostic.values["incident_capture_site"])
            assertEquals(JsonPrimitive("CAPTURE_BOUNDARY_ONLY"), diagnostic.values["incident_phase_provenance"])
            assertEquals(JsonPrimitive("unknown"), diagnostic.values["incident_phase"])
            assertEquals(setOf("CAPTURE_BOUNDARY_EVIDENCE_V1"), diagnostic.requiredServiceFeatures())
            assertNull(context.exceptionFingerprint)
            assertNull(diagnostic.values["exception_type_hashes"])
            assertNull(context.stage)
            assertNull(context.dispatched)
            assertNull(context.retryable)
            assertNull(context.remoteAcceptance)
            val approved = report(context).withCapturedException(observed).approvedPayload()
            assertFalse(approved.contains("errorType"))
            assertTrue(IssueReportContext.validDiagnosticsIn(IssueReport.json.parseToJsonElement(approved).jsonObject["context"]))
        }
    }

    @Test fun legacyCaptureVocabularyDoesNotRequireTheAdditiveHostedCapability() {
        for (site in listOf(IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH, IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_PREFLIGHT, IssueReportIncidentCapsule.ExceptionCaptureSite.OPENAI_ADAPTER_STREAM_CATCH)) {
            val context = capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", IssueReportIncidentCapsule.boundaryFields(site))
            assertFalse("CAPTURE_BOUNDARY_EVIDENCE_V1" in requireNotNull(context.diagnostics).requiredServiceFeatures())
        }
    }

    @Test fun exceptionObservationRejectsOpenCaptureNamesAndMalformedFingerprints() {
        val observed = IssueReportIncidentCapsule.exceptionFields(IllegalArgumentException("private"), IssueReportIncidentCapsule.ExceptionCaptureSite.OPENAI_ADAPTER_STREAM_CATCH)
        val context = capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", observed)
        val diagnostic = requireNotNull(context.diagnostics)
        assertNull(diagnostic.copy(values = diagnostic.values + ("incident_capture_site" to JsonPrimitive("private-site"))).validated())
        assertNull(diagnostic.copy(values = diagnostic.values + ("incident_phase_provenance" to JsonPrimitive("ASSUMED_PROVIDER_DISPATCH"))).validated())
        assertNull(IssueReportContext.fromFields("CHAT_FAILURE", mapOf("exception_fingerprint" to "private-stack")).exceptionFingerprint)
        val changed = IllegalArgumentException("private-changed-message").also { it.stackTrace = emptyArray() }
        val same = IllegalArgumentException("private-different-message").also { it.stackTrace = emptyArray() }
        assertEquals(IssueReportContext.exceptionFingerprint(changed), IssueReportContext.exceptionFingerprint(same))
    }

    @Test fun genericChatFailureStatesMissingEvidenceWithoutInventingProviderPhaseOrReplay() {
        val context = capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", mapOf("run_id" to "private-run", "provider" to "OPENROUTER", "message" to "private-message", "token" to "private-token"))
        val values = requireNotNull(context.diagnostics).values
        assertEquals(7, context.schemaVersion)
        assertEquals(JsonPrimitive("unknown"), values["incident_phase"])
        assertEquals(JsonPrimitive("UNAVAILABLE"), values["causal_provenance"])
        assertEquals(JsonPrimitive("CAUSAL_EVIDENCE_UNAVAILABLE"), values["incident_evidence_status"])
        assertNull(context.stage)
        assertNull(context.dispatched)
        assertNull(context.retryable)
        assertNull(values["request_replay_allowed"])
        assertNull(IssueReportIncidentCapsule.identity(context, "CHAT_FAILED"))
        assertFalse(report(context).approvedPayload().contains("private"))
    }

    @Test fun exactPropagatedToolCauseJoinsLayersButDifferentCallsRunsAndMissingIdsDoNot() {
        val fields = mapOf("run_id" to "run-one", "call_id" to "call-one", "phase" to "tool_execution")
        val tool = capture(ReportFeature.TOOLS, "TOOL_RESULT", "GITHUB_VALIDATION_FAILED", fields)
        val propagated = mapOf("run_id" to "run-one", "causal_id" to "call-one", "causal_provenance" to "TOOL_CALL", "causal_failure_code" to "GITHUB_VALIDATION_FAILED", "failed_stage" to "review_comparison")
        val chat = capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", propagated)
        val identity = IssueReportIncidentCapsule.identity(tool, "GITHUB_VALIDATION_FAILED")
        assertEquals(identity, IssueReportIncidentCapsule.identity(chat, "CHAT_FAILED"))
        assertNotEquals(identity, IssueReportIncidentCapsule.identity(capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", propagated + ("run_id" to "run-two")), "CHAT_FAILED"))
        assertNotEquals(identity, IssueReportIncidentCapsule.identity(capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", propagated + ("causal_id" to "call-two")), "CHAT_FAILED"))
        assertNull(IssueReportIncidentCapsule.identity(capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", propagated - "causal_id"), "CHAT_FAILED"))
        val approved = report(tool).approvedPayload()
        capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", propagated + ("http_status" to 422))
        assertEquals(approved, report(tool).approvedPayload())
    }

    @Test fun localRecoveryKeepsHistorical429SeparateFromItsCurrentRefusalAndObservedScheduling() {
        val context = capture(
            ReportFeature.CHAT,
            "CHAT_FAILURE",
            "CHAT_FAILED",
            mapOf(
                "run_id" to "private-run", "failure_origin" to "local_recovery", "recovery_code" to "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", "failed_stage" to "lifecycle",
                "original_interruption" to mapOf("code" to "429", "details" to mapOf("http_status" to 429, "message" to "private provider response")),
                "provider_wait_state" to "CHECKPOINT_UNAVAILABLE", "recovery_observation" to "TERMINAL", "provider_wait_persisted" to false, "recovery_authority" to "UNVERIFIED"
            )
        )
        assertEquals("PROVIDER_RATE_LIMITED", context.originalFailureCode)
        assertEquals(429, context.originalHttpStatus)
        assertNull(context.httpStatus)
        assertEquals("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", context.reasonCode)
        val values = requireNotNull(context.diagnostics).values
        assertEquals(JsonPrimitive("LOCAL_RECOVERY"), values["incident_origin"])
        assertEquals(JsonPrimitive(false), values["provider_wait_persisted"])
        assertNull(values["request_replay_allowed"])
        assertFalse(report(context).approvedPayload().contains("private"))
    }

    @Test fun publicToolValidationKeepsOnlyClosedNamesTypesAndHashesAndRejectsMalformedShapes() {
        val hash = "a".repeat(64)
        val context = capture(
            ReportFeature.TOOLS,
            "TOOL_RESULT",
            "INVALID_ARGUMENTS",
            mapOf(
                "run_id" to "private-run", "call_id" to "private-call", "phase" to "tool_precheck", "native_tool_action" to "create",
                "validation_field_names" to listOf("issue_number"), "validation_path_hashes" to listOf(hash), "unknown_field_hashes" to listOf(hash), "unknown_field_count" to 1,
                "argument_field_names" to listOf("action", "issue_number"), "argument_field_types" to listOf("STRING", "STRING"),
                "saved_view_owner_operation" to "github_issue", "saved_view_scope_hash" to hash, "saved_view_cursor_issued" to true,
                "github_error_resources" to listOf("PullRequest"), "github_error_fields" to listOf("head"), "github_error_codes" to listOf("invalid"),
                "private_raw_arguments" to "private-title", "resolved_argument_field_names" to listOf("private_field"), "resolved_argument_field_types" to listOf("STRING")
            )
        )
        val diagnostics = requireNotNull(context.diagnostics)
        assertEquals(JsonPrimitive("create"), diagnostics.values["native_tool_action"])
        assertEquals(JsonArray(listOf(JsonPrimitive("issue_number"))), diagnostics.values["validation_field_names"])
        assertNull(diagnostics.values["resolved_argument_field_names"])
        assertNull(diagnostics.values["resolved_argument_field_types"])
        assertFalse(report(context).approvedPayload().contains("private"))
        assertNull(diagnostics.copy(values = diagnostics.values + ("github_error_fields" to JsonArray(listOf(JsonPrimitive("private-secret"))))).validated())
        assertNull(diagnostics.copy(values = diagnostics.values + ("request_body" to JsonPrimitive("private-body"))).validated())
    }

    @Test fun boundedAcceptanceCapsuleKeepsAnAlignedSampleAndHonestOmissionCount() {
        val context = capture(
            ReportFeature.CHAT,
            "CHAT_FAILURE",
            "GITHUB_TASK_INCOMPLETE",
            mapOf(
                "diagnostic_area" to "ACCEPTANCE", "run_id" to "private-run", "failed_stage" to "acceptance_verification", "checkpoint_hash" to "a".repeat(64),
                "unmet_criterion_id_hashes" to List(8) { "b".repeat(64) }, "unmet_criterion_reason_hashes" to List(8) { "c".repeat(64) },
                "unmet_criterion_categories" to List(8) { "BUILD_REVISION_BINDING" }, "unmet_criterion_states" to List(8) { "NOT_TESTED" },
                "unmet_criterion_blockers" to List(8) { "WORKFLOW_RUN_OR_JOB_REQUIRED" }, "unmet_criterion_count" to 11, "unmet_criterion_omitted_count" to 3,
                "evidence_ref_hashes" to List(8) { "d".repeat(64) },
                "effective_call_limit" to 256, "used_calls" to 82, "remaining_calls" to 174, "pending_continuations" to 16,
                "required_capability_count" to 53, "verified_capability_count" to 44, "blocked_capability_count" to 2, "untested_capability_count" to 7,
                "acceptance_capability_scope" to "FULL_CATALOG", "acceptance_pagination_mode" to "COMPLETE_INVENTORY_REQUESTED",
                "changed_policy_fields" to IssueReportIncidentCapsule.policyFieldNames.toList()
            )
        )
        val snapshot = requireNotNull(context.diagnostics)
        val retained = (snapshot.values.getValue("unmet_criterion_id_hashes") as JsonArray).size
        assertTrue(retained in 1..7)
        assertEquals(retained, (snapshot.values.getValue("unmet_criterion_states") as JsonArray).size)
        assertEquals(JsonPrimitive(11 - retained), snapshot.values["unmet_criterion_omitted_count"])
        assertEquals(8, (snapshot.values["changed_policy_fields"] as JsonArray).size)
        assertEquals(JsonPrimitive(67), snapshot.values["changed_policy_field_count"])
        assertEquals(JsonPrimitive(59), snapshot.values["changed_policy_omitted_count"])
        assertEquals(JsonPrimitive("FULL_CATALOG"), snapshot.values["acceptance_capability_scope"])
        assertEquals(JsonPrimitive("COMPLETE_INVENTORY_REQUESTED"), snapshot.values["acceptance_pagination_mode"])
        assertTrue("ACCEPTANCE_SCOPE_EVIDENCE_V1" in snapshot.requiredServiceFeatures())
        assertFalse("acceptance_capability_scope" in snapshot.omittedFields)
        assertFalse("acceptance_pagination_mode" in snapshot.omittedFields)
        assertTrue(snapshot.isValid())
        assertTrue(IssueReport.json.encodeToString(IssueReportDiagnostics.serializer(), snapshot).encodeToByteArray().size <= IssueReportDiagnostics.MAX_BYTES)
        assertTrue(report(context).approvedPayload().encodeToByteArray().size <= IssueReport.MAX_PAYLOAD_BYTES)
    }

    @Test fun newCapsuleCannotBeSmuggledIntoAnOldSchemaAndLegacyApprovedBytesStayIdentical() {
        val context = capture(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", emptyMap())
        val encoded = IssueReport.json.encodeToJsonElement(IssueReportContext.serializer(), context) as JsonObject
        assertTrue(IssueReportContext.validDiagnosticsIn(encoded))
        assertFalse(IssueReportContext.validDiagnosticsIn(JsonObject(encoded + ("schemaVersion" to JsonPrimitive(6)))))
        val legacy = report(IssueReportContext.fromFields("CHAT_FAILURE", mapOf("provider" to "OPENROUTER"), time)).approvedPayload()
        assertEquals(legacy, IssueReport.json.decodeFromString(IssueReport.serializer(), legacy).approvedPayload())
        assertFalse(legacy.contains("incident_phase"))
        assertEquals("GITHUB_VALIDATION_FAILED", IssueReportCodes.toolResult("GITHUB_PR_VALIDATION_FAILED"))
    }

    @Test fun providerRejectionAndPrivacyEvidenceRemainTypedAndBoundedWithoutRawPayloadOrPolicyChanges() {
        val context = capture(
            ReportFeature.CHAT,
            "CHAT_FAILURE",
            "REQUEST_REJECTED",
            mapOf(
                "diagnostic_area" to "PROVIDER", "run_id" to "private-run", "failed_stage" to "model_invocation", "http_status" to 410,
                "provider_rejection_category" to "GONE", "provider_error_type_hash" to "a".repeat(64), "provider_parameter" to "max_tokens", "payload_contract" to "ANTHROPIC_MESSAGES",
                "privacy_catalog_entries" to 8, "privacy_model_endpoint_count" to 2, "privacy_compatible_endpoint_count" to 0, "privacy_allowlist_excluded_count" to 2,
                "privacy_metadata_age_ms" to 3000L, "privacy_blockers" to listOf("PROVIDER_ALLOWLIST"), "raw_provider_error" to "private response"
            )
        )
        val values = requireNotNull(context.diagnostics).values
        assertEquals(JsonPrimitive("GONE"), values["provider_rejection_category"])
        assertEquals(JsonPrimitive(0), values["privacy_compatible_endpoint_count"])
        assertNull(values["privacy_fallbacks_allowed"])
        assertNull(values["request_replay_allowed"])
        assertFalse(report(context).approvedPayload().contains("private"))
        assertNull(IssueReportIncidentCapsule.validValue("provider_parameter", JsonPrimitive("private-parameter")))
        assertNull(IssueReportIncidentCapsule.validValue("privacy_blockers", JsonArray(List(5) { JsonPrimitive("PROVIDER_ALLOWLIST") })))
    }

    private fun capture(feature: ReportFeature, source: String, code: String, fields: Map<String, Any?>) =
        IssueReportContext.fromFields(source, IssueReportIncidentCapsule.fields(feature, source, code, fields), time)

    private fun report(context: IssueReportContext) = IssueReport("11111111-1111-4111-8111-111111111111", "debug", "1.0", 1, "abcdef0", "16", 36, "Fixture", feature = "chat", errorCode = "CHAT_FAILED", context = context)
}
