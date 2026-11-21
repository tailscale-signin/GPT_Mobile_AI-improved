package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.agent.ProviderRecoveryFailure
import dev.chungjungsoo.gptmobile.data.github.GitHubCooldownActive
import dev.chungjungsoo.gptmobile.data.github.GitHubRateLimitManager
import dev.chungjungsoo.gptmobile.data.github.GitHubRateLimitResponseEvidence
import dev.chungjungsoo.gptmobile.data.github.GitHubRequestRejected
import dev.chungjungsoo.gptmobile.data.network.error.ErrorCategory
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure
import io.ktor.http.headersOf
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportIncidentsTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before
    @After
    fun reset() {
        IssueReportIncidents.initialize(context)
        LocalDiagnosticsPolicy.setEnabled(false)
        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.debugPreferenceChanged(false)
        IssueReportIncidents.clearRecent()
    }

    private fun selectionError() = dev.chungjungsoo.gptmobile.data.context.GitHubProfileSelectionRejected(
        profileMatches = false,
        registeredProfilePresent = true,
        modelMatches = false,
        requestedProfileHash = "a".repeat(64),
        requestedModelHash = "b".repeat(64),
        selectedProfileHash = "c".repeat(64),
        registeredModelHash = "d".repeat(64)
    )

    private fun assertCollectedSelectionDispatchUnavailable(report: IssueReport) {
        val context = requireNotNull(report.context)
        val diagnostic = requireNotNull(context.diagnostics)
        assertEquals("GITHUB_PROFILE_LOCKED", report.errorCode)
        assertEquals("provider_admission", context.stage)
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertTrue("dispatched" in context.unavailableFields)
        assertTrue("remoteAcceptance" in context.unavailableFields)
        assertEquals(false, context.retryable)
        assertEquals("RETRY_AFTER_CONFIGURATION_CHANGE", context.recoveryAction)
        assertEquals("PROVIDER", diagnostic.area)
        assertEquals(JsonPrimitive("ADMISSION"), diagnostic.values["provider_failure_stage"])
        assertEquals(JsonPrimitive("GITHUB_PROFILE_LOCKED"), diagnostic.values["provider_failure_code"])
        assertEquals(JsonPrimitive("APPLICATION"), diagnostic.values["incident_origin"])
        assertEquals(JsonPrimitive(false), diagnostic.values["request_replay_allowed"])
        for ((key, value) in selectionError().failureFields()) assertEquals(key, value, diagnostic.values[key])
        assertTrue("PROFILE_SELECTION_EVIDENCE_V1" in diagnostic.requiredServiceFeatures())
        assertFalse(diagnostic.values.containsKey("chat_failure_code_state"))
        val payload = report.approvedPayload()
        val serialized = IssueReport.json.parseToJsonElement(payload).jsonObject.getValue("context").jsonObject
        assertFalse(serialized.containsKey("dispatched"))
        assertFalse(serialized.containsKey("remoteAcceptance"))
        assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "execution_dispatch_state", "dispatched", "remote_acceptance", "partial_output_preserved").none { it in diagnostic.values })
        assertFalse(payload.contains("PRIVATE_"))
    }

    @Test fun actualProfileSelectionProjectionKeepsDispatchUnavailableForBothInputs() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (partial in listOf(false, true)) {
            val error = selectionError()
            val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partial))
            val fields = requireNotNull(detail.failureDetails)
            assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "dispatchState", "executionState", "dispatched", "inference_dispatched", "remote_acceptance", "remoteAcceptance", "partial_output_preserved").none { it in fields })
            IssueReportIncidents.chatFailure(
                detail.code,
                false,
                error,
                fields + mapOf("provider_kind" to "LLAMA", "run_id" to "selection-$partial", "raw_profile_uid" to "PRIVATE_PROFILE", "raw_model" to "PRIVATE_MODEL")
            )
            assertCollectedSelectionDispatchUnavailable(IssueReportIncidents.pending.value.last())
        }
    }

    @Test fun resumedSelectionCollectorKeepsEarlierZeroOutput429ReportSeparateFromFreshFence() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val earlierFence = dev.chungjungsoo.gptmobile.data.context.PreparationReplayFence()
        earlierFence.dispatched()
        IssueReportIncidents.chatFailure(
            "PROVIDER_RATE_LIMITED",
            true,
            null,
            mapOf("run_id" to "retained-run", "provider_kind" to "LLAMA", "diagnostic_area" to "PROVIDER", "failed_stage" to "provider_generation", "http_status" to 429, "dispatched" to earlierFence.hasAttempted, "remote_acceptance" to "NO", "output_tokens" to 0)
        )
        val earlier = IssueReportIncidents.pending.value.single()
        val approvedEarlier = earlier.approvedPayload()
        val resumedFence = dev.chungjungsoo.gptmobile.data.context.PreparationReplayFence()
        assertTrue(earlierFence.hasAttempted)
        assertFalse(resumedFence.hasAttempted)
        val error = selectionError()
        val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, resumedFence.hasAttempted))
        IssueReportIncidents.chatFailure(detail.code, false, error, requireNotNull(detail.failureDetails) + mapOf("run_id" to "retained-run", "provider_kind" to "LLAMA"))
        assertEquals(2, IssueReportIncidents.pending.value.size)
        assertCollectedSelectionDispatchUnavailable(IssueReportIncidents.pending.value.last())
        val retained = IssueReportIncidents.pending.value.first()
        assertEquals(earlier, retained)
        assertEquals(approvedEarlier, retained.approvedPayload())
        assertEquals(true, retained.context?.dispatched)
        assertEquals("NO", retained.context?.remoteAcceptance)
        assertEquals(429, retained.context?.httpStatus)
        assertEquals(JsonPrimitive(0), retained.context?.diagnostics?.values?.get("output_tokens"))
    }

    private fun cooldownError(secondary: Boolean = false): GitHubCooldownActive {
        val manager = GitHubRateLimitManager()
        manager.record(headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("130")), nowMillis = 100_000L)
        if (secondary) manager.cooldown(30L, 100_000L)
        return runCatching { manager.requireAvailable(if (secondary) "search" else "core", 110L) }.exceptionOrNull() as GitHubCooldownActive
    }

    private fun assertCollectedCooldownDispatchUnavailable(report: IssueReport, error: GitHubCooldownActive) {
        val context = requireNotNull(report.context)
        val diagnostic = requireNotNull(context.diagnostics)
        assertEquals("GITHUB_COOLDOWN_ACTIVE", report.errorCode)
        assertEquals("repository_admission", context.stage)
        assertNull(context.dispatched)
        assertNull(context.remoteAcceptance)
        assertNull(context.httpStatus)
        assertTrue(context.unavailableFields.containsAll(listOf("dispatched", "remoteAcceptance", "httpStatus")))
        assertEquals(false, context.retryable)
        assertEquals(20_000L, context.retryAfterMillis)
        assertEquals("RETRY_AFTER_BACKOFF", context.recoveryAction)
        assertEquals("PROVIDER", diagnostic.area)
        assertEquals(JsonPrimitive("ADMISSION"), diagnostic.values["provider_failure_stage"])
        assertEquals(JsonPrimitive("GITHUB_COOLDOWN_ACTIVE"), diagnostic.values["provider_failure_code"])
        assertEquals(JsonPrimitive("APPLICATION"), diagnostic.values["incident_origin"])
        assertEquals(JsonPrimitive(false), diagnostic.values["request_replay_allowed"])
        for (key in IssueReportDiagnostics.githubCooldownFields) assertEquals(key, error.failureFields()[key], diagnostic.values[key])
        assertTrue("GH_COOLDOWN_V1" in diagnostic.requiredServiceFeatures())
        assertFalse(diagnostic.values.containsKey("chat_failure_code_state"))
        val payload = report.approvedPayload()
        val serialized = IssueReport.json.parseToJsonElement(payload).jsonObject.getValue("context").jsonObject
        assertTrue(listOf("dispatched", "remoteAcceptance", "httpStatus").none { it in serialized })
        assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "execution_dispatch_state", "dispatched", "remote_acceptance", "partial_output_preserved").none { it in diagnostic.values })
        assertFalse(payload.contains("PRIVATE_"))
    }

    @Test fun actualCooldownCollectorKeepsOptionalSnapshotAndAggregateFactsUnavailable() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (secondary in listOf(false, true)) {
            val error = cooldownError(secondary)
            for ((wrapped, captured) in listOf(error, GitHubRequestRejected(0, "PRIVATE_WRAPPER").apply { initCause(error) }).withIndex()) {
                for (partial in listOf(false, true)) {
                    val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(captured, partial))
                    val fields = requireNotNull(detail.failureDetails)
                    assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "dispatchState", "executionState", "dispatched", "inference_dispatched", "remote_acceptance", "remoteAcceptance", "partial_output_preserved", "http_status").none { it in fields })
                    IssueReportIncidents.chatFailure(
                        detail.code,
                        false,
                        captured,
                        fields + mapOf("provider_kind" to "LLAMA", "run_id" to "cooldown-$secondary-$wrapped-$partial", "raw_resource" to "PRIVATE_RESOURCE", "raw_headers" to "PRIVATE_HEADERS_TOKEN")
                    )
                    assertCollectedCooldownDispatchUnavailable(IssueReportIncidents.pending.value.last(), error)
                }
            }
        }
    }

    @Test fun resumedCooldownCollectorKeepsEarlierZeroOutput429SeparateFromFreshFence() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val earlierFence = dev.chungjungsoo.gptmobile.data.context.PreparationReplayFence()
        earlierFence.dispatched()
        IssueReportIncidents.chatFailure(
            "PROVIDER_RATE_LIMITED",
            true,
            null,
            mapOf("run_id" to "retained-run", "provider_kind" to "LLAMA", "diagnostic_area" to "PROVIDER", "failed_stage" to "provider_generation", "http_status" to 429, "dispatched" to earlierFence.hasAttempted, "remote_acceptance" to "NO", "output_tokens" to 0)
        )
        val earlier = IssueReportIncidents.pending.value.single()
        val approvedEarlier = earlier.approvedPayload()
        val resumedFence = dev.chungjungsoo.gptmobile.data.context.PreparationReplayFence()
        assertTrue(earlierFence.hasAttempted)
        assertFalse(resumedFence.hasAttempted)
        val error = cooldownError()
        val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, resumedFence.hasAttempted))
        IssueReportIncidents.chatFailure(detail.code, false, error, requireNotNull(detail.failureDetails) + mapOf("run_id" to "retained-run", "provider_kind" to "LLAMA"))
        assertEquals(2, IssueReportIncidents.pending.value.size)
        assertCollectedCooldownDispatchUnavailable(IssueReportIncidents.pending.value.last(), error)
        val retained = IssueReportIncidents.pending.value.first()
        assertEquals(earlier, retained)
        assertEquals(approvedEarlier, retained.approvedPayload())
        assertEquals(true, retained.context?.dispatched)
        assertEquals("NO", retained.context?.remoteAcceptance)
        assertEquals(429, retained.context?.httpStatus)
        assertEquals(JsonPrimitive(0), retained.context?.diagnostics?.values?.get("output_tokens"))
    }

    @Test fun actualGitHubRateResponseCollectorKeepsItsHttpSourceAndMissingDeadlineDistinct() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (code in listOf("GITHUB_PRIMARY_RATE_LIMITED", "GITHUB_SECONDARY_RATE_LIMITED")) {
            val status = if (code == "GITHUB_PRIMARY_RATE_LIMITED") 403 else 429
            for (measured in listOf(false, true)) {
                val headers = if (measured) headersOf("X-RateLimit-Resource" to listOf("core"), "X-RateLimit-Remaining" to listOf("0"), "X-RateLimit-Reset" to listOf("130")) else headersOf()
                val responseEvidence = requireNotNull(GitHubRateLimitResponseEvidence.fromResponse(status, code, headers, 100_000L))
                val error = GitHubRequestRejected(status, "PRIVATE_HTTP_BODY_TOKEN", responseEvidence)
                for (partial in listOf(false, true)) {
                    val detail = requireNotNull(dev.chungjungsoo.gptmobile.data.network.providerExceptionDetails(error, partial, nowMillis = 999_000L))
                    IssueReportIncidents.chatFailure(detail.code, false, error, requireNotNull(detail.failureDetails) + mapOf("run_id" to "$code-$measured-$partial", "provider_kind" to "LLAMA"))
                    val report = IssueReportIncidents.pending.value.last()
                    val context = requireNotNull(report.context)
                    val capture = requireNotNull(context.diagnostics)
                    assertEquals(code, report.errorCode)
                    assertEquals("LLAMA", context.provider)
                    assertEquals("tool_execution", context.stage)
                    assertEquals(status, context.httpStatus)
                    assertNull(context.dispatched)
                    assertNull(context.remoteAcceptance)
                    assertTrue(context.unavailableFields.containsAll(listOf("dispatched", "remoteAcceptance")))
                    assertEquals(false, context.retryable)
                    assertEquals("RETRY_AFTER_BACKOFF", context.recoveryAction)
                    assertEquals(if (measured) 30_000L else null, context.retryAfterMillis)
                    assertEquals(JsonPrimitive(code), capture.values["provider_failure_code"])
                    assertEquals(JsonPrimitive("TOOL_EXECUTION"), capture.values["provider_failure_domain"])
                    assertEquals(JsonPrimitive("TOOL"), capture.values["incident_origin"])
                    assertEquals(JsonPrimitive(false), capture.values["request_replay_allowed"])
                    for (key in IssueReportDiagnostics.githubCooldownFields) assertEquals(key, responseEvidence[key], capture.values[key])
                    assertTrue("GH_COOLDOWN_V1" in capture.requiredServiceFeatures())
                    val payload = report.approvedPayload()
                    val serialized = IssueReport.json.parseToJsonElement(payload).jsonObject.getValue("context").jsonObject
                    assertFalse(serialized.containsKey("dispatched"))
                    assertFalse(serialized.containsKey("remoteAcceptance"))
                    assertTrue(setOf("attempt_dispatch_state", "dispatch_state", "execution_dispatch_state", "dispatched", "remote_acceptance", "partial_output_preserved").none { it in capture.values })
                    assertFalse(payload.contains("PRIVATE_"))
                }
            }
        }
    }

    @Test
    fun baselineQuestionIsNotAProviderFailureIncident() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val fields = mapOf("failed_stage" to "review_comparison", "dispatched" to false, "next_action" to "SELECT_REVIEW_BASELINE")
        IssueReportIncidents.chatFailure("REVIEW_INPUT_REQUIRED", false, null, fields)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())
        IssueReportIncidents.chatFailure("GITHUB_REVIEW_COMPARISON_REQUIRED", false, null, fields)
        assertEquals(1, IssueReportIncidents.pending.value.size)
    }

    @Test
    fun debugOptInProducesOneReportForRepeatedErrorAndOnlyProjectedLogs() {
        IssueReportIncidents.chatFailure("LOCAL_PREFILL_TIMEOUT", false)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())

        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.log("Android", "E", tracking = true)
        IssueReportIncidents.log("VoiceEngine", "I", tracking = false)
        repeat(2) { IssueReportIncidents.chatFailure("LOCAL_PREFILL_TIMEOUT", false) }
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("LOCAL_PREFILL_TIMEOUT", report.errorCode)
        assertEquals("chat", report.feature)
        assertTrue(report.events.any { it.endsWith("I voice") })
        assertTrue(report.events.any { it.endsWith("E chat LOCAL_PREFILL_TIMEOUT") })
        assertFalse(report.events.any { "Android" in it })
    }

    @Test
    fun temporarySessionSuppressesLogsAndDebugReports() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.log("VoiceEngine", "I", tracking = false)
        IssueReportIncidents.privacyChanged(true)
        IssueReportIncidents.chatFailure(null, false)
        IssueReportIncidents.log("VoiceEngine", "E", tracking = true)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())
        assertTrue(IssueReportIncidents.recentEvents().isEmpty())
    }

    @Test
    fun nonfatalFailureRetainsSpecificCodeAndSafeFramesWithoutPrivateExceptionText() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val error = SecurityException("private microphone request details")
        error.stackTrace = arrayOf(
            StackTraceElement("dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer", "capture", "MoonshineVoiceRecognizer.kt", 204),
            StackTraceElement("other.custom.PrivateClass", "method", "Private.java", 12)
        )

        IssueReportIncidents.failure(ReportFeature.VOICE, "VOICE_MICROPHONE_PERMISSION_REQUIRED", error)

        val report = IssueReportIncidents.pending.value.single()
        assertEquals("VOICE_MICROPHONE_PERMISSION_REQUIRED", report.errorCode)
        assertEquals("SecurityException", report.errorType)
        assertEquals(listOf("dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer.capture:204"), report.frames)
        val raw = IssueReport.json.encodeToString(IssueReport.serializer(), report)
        assertFalse(raw.contains("private microphone request details"))
        assertFalse(raw.contains("PrivateClass"))
    }

    @Test fun replayCapacityFailureReportsOnlyCurrentPlanningEvidence() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val fields = mapOf(
            "failed_stage" to "planning",
            "failure_origin" to "APPLICATION",
            "dispatched" to false,
            "replay_budget_state" to "NO_CONTINUATION_ALLOWANCE",
            "replay_allowance" to 0,
            "replay_exchange_count" to 2
        )
        IssueReportIncidents.chatFailure("REPLAY_EVIDENCE_REQUIRED", false, null, fields)
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("REPLAY_EVIDENCE_REQUIRED", report.errorCode)
        val diagnostics = requireNotNull(report.context?.diagnostics)
        assertEquals(JsonPrimitive("NO_CONTINUATION_ALLOWANCE"), diagnostics.values["replay_budget_state"])
        assertEquals(JsonPrimitive(0), diagnostics.values["replay_allowance"])
        assertEquals(JsonPrimitive(2), diagnostics.values["replay_exchange_count"])
        assertFalse("replay_tokens" in diagnostics.values)
        assertTrue("REPLAY_BUDGET_EVIDENCE_V1" in diagnostics.requiredServiceFeatures())
        val other = IssueReportIncidentCapsule.fields(ReportFeature.CHAT, "CHAT_FAILURE", "CHAT_FAILED", fields)
        assertTrue(IssueReportDiagnostics.replayBudgetFields.none { it in other })
    }

    @Test fun genericTerminalFailurePreservesSanitizedApplicationFramesAcrossTheFlowBoundary() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val error = IllegalArgumentException("PRIVATE_PROMPT https://private.example/token")
        error.stackTrace = arrayOf(
            StackTraceElement("dev.chungjungsoo.gptmobile.data.agent.ToolCatalogAdmissionKt", "planAuthorizedToolContext", "PRIVATE_FILE", 77),
            StackTraceElement("outside.PRIVATE_CLASS", "secret", "PRIVATE_FILE", 1)
        )
        val captured = IssueReportIncidentCapsule.exceptionFields(error, IssueReportIncidentCapsule.ExceptionCaptureSite.CHAT_REPOSITORY_TERMINAL_CATCH)
        IssueReportIncidents.chatFailure("UNKNOWN", false, null, captured + ("run_id" to "private-run"))
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("CHAT_FAILED", report.errorCode)
        assertEquals("IllegalArgumentException", report.errorType)
        assertEquals(listOf("dev.chungjungsoo.gptmobile.data.agent.ToolCatalogAdmissionKt.planAuthorizedToolContext:77"), report.frames)
        assertEquals(IssueReportContext.exceptionFingerprint(error), report.context?.exceptionFingerprint)
        assertNull(report.context?.stage)
        assertNull(report.context?.dispatched)
        assertNull(report.context?.retryable)
        val approved = report.approvedPayload()
        assertFalse(approved.contains("PRIVATE_"))
        assertFalse(approved.contains("private.example"))
        assertFalse(approved.contains("exception_application_frames"))
        assertFalse(approved.contains("exception_report_type"))
    }

    @Test fun transportedExceptionMetadataCannotPublishArbitraryFrameTextOrUnobservedCaptureSites() {
        val report = IssueReport.debug(ReportFeature.CHAT, "CHAT_FAILED", emptyList())
        val fields = mapOf(
            "incident_capture_site" to "CHAT_REPOSITORY_TERMINAL_CATCH",
            "incident_phase_provenance" to "CAPTURE_BOUNDARY_ONLY",
            "exception_report_type" to "private exception text",
            "exception_application_frames" to JsonArray(
                listOf("outside.private.Class.method:1", "https://private.example/token", "dev.melo.gptmobile.Invalid method:3", "dev.melo.gptmobile.Valid.method:42").map(::JsonPrimitive)
            )
        )
        val projected = report.withCapturedException(fields)
        assertNull(projected.errorType)
        assertEquals(listOf("dev.melo.gptmobile.Valid.method:42"), projected.frames)
        assertEquals(report, report.withCapturedException(fields + ("incident_capture_site" to "private-site")))
        assertEquals(report, report.withCapturedException(fields - "incident_phase_provenance"))
        val bounded = fields + ("exception_application_frames" to JsonArray((1..40).map { JsonPrimitive("dev.melo.gptmobile.Valid.method:$it") }))
        assertEquals(8, report.withCapturedException(bounded).frames.size)
    }

    @Test
    fun differentGenericChatFailureStagesAreNotSuppressedByTheSameErrorCode() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val admission = mapOf("failed_stage" to "provider_admission")
        val synthesis = mapOf("failed_stage" to "final_synthesis")
        IssueReportIncidents.chatFailure("UNRECOGNIZED_CAUSE", false, null, admission)
        IssueReportIncidents.chatFailure("UNRECOGNIZED_CAUSE", false, null, synthesis)
        IssueReportIncidents.chatFailure("UNRECOGNIZED_CAUSE", false, null, admission)

        val reports = IssueReportIncidents.pending.value
        assertEquals(2, reports.size)
        assertEquals(listOf("CHAT_FAILED", "CHAT_FAILED"), reports.map { it.errorCode })
        assertEquals(listOf("provider_admission", "final_synthesis"), reports.map { it.context?.stage })
    }

    @Test
    fun differentSanitizedExceptionFingerprintsAreNotSuppressedByTheSameErrorCode() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val first = IllegalArgumentException("PRIVATE_FIRST_EXCEPTION")
        val second = IllegalStateException("PRIVATE_SECOND_EXCEPTION")
        IssueReportIncidents.chatFailure("UNRECOGNIZED_CAUSE", false, first)
        IssueReportIncidents.chatFailure("UNRECOGNIZED_CAUSE", false, second)
        IssueReportIncidents.chatFailure("UNRECOGNIZED_CAUSE", false, first)

        val reports = IssueReportIncidents.pending.value
        assertEquals(2, reports.size)
        assertEquals(listOf("CHAT_FAILED", "CHAT_FAILED"), reports.map { it.errorCode })
        assertTrue(reports.mapNotNull { it.context?.exceptionFingerprint }.distinct().size == 2)
        assertFalse(reports.any { "PRIVATE_" in it.approvedPayload() })
    }

    @Test
    fun distinctKnownChatFailuresAreNotCollapsedIntoOneGenericIncident() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.chatFailure("GATEWAY_ENGINEERING_GITHUB_REQUIRED", false)
        IssueReportIncidents.chatFailure("GATEWAY_REMOTE_SURFACE_NOT_ADMITTED", false)
        IssueReportIncidents.chatFailure("GATEWAY_ENGINEERING_GITHUB_REQUIRED", false)

        assertEquals(
            listOf("GATEWAY_ENGINEERING_GITHUB_REQUIRED", "GATEWAY_REMOTE_SURFACE_NOT_ADMITTED"),
            IssueReportIncidents.pending.value.map { it.errorCode }
        )
    }

    @Test
    fun classifiedChatFailuresKeepTheirCodesWithoutExceptionText() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (message in listOf("BACKEND_TEMPLATE_INVALID: private details", "HTTP 503 private details", "HTTP 401 private details")) {
            val classification = ErrorClassification.classify(IllegalStateException(message))
            IssueReportIncidents.chatFailure(classification.errorCode, false)
        }

        val reports = IssueReportIncidents.pending.value
        assertEquals(listOf("BACKEND_TEMPLATE_INVALID", "SERVICE_UNAVAILABLE", "AUTHENTICATION_FAILED"), reports.map { it.errorCode })
        assertFalse(reports.flatMap { it.events }.any { "private details" in it })
    }

    @Test
    fun observedProvider403KeepsPermissionCauseAndDisablesAutomaticRetry() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.chatFailure(
            "HTTP_ERROR",
            false,
            null,
            mapOf(
                "run_id" to "private-permission-run",
                "provider" to "OPENROUTER",
                "failed_stage" to "model_invocation",
                "http_status" to 403,
                "dispatched" to true,
                "remote_acceptance" to "NO",
                "provider_rejection_category" to "PERMISSION",
                "diagnostic_area" to "PROVIDER",
                "message" to "private provider response body"
            )
        )
        val report = IssueReportIncidents.pending.value.single()
        val context = requireNotNull(report.context)
        assertEquals("SOURCE_ACCESS_BLOCKED", report.errorCode)
        assertEquals(403, context.httpStatus)
        assertEquals("OPENROUTER", context.provider)
        assertEquals(true, context.dispatched)
        assertEquals(false, context.retryable)
        assertEquals("RETRY_AFTER_CONFIGURATION_CHANGE", context.recoveryAction)
        assertEquals(JsonPrimitive("PERMISSION"), context.diagnostics?.values?.get("provider_rejection_category"))
        assertFalse(report.approvedPayload().contains("private provider response body"))
        assertFalse(report.approvedPayload().contains("private-permission-run"))

        IssueReportIncidents.chatFailure(
            "HTTP_ERROR",
            false,
            null,
            mapOf(
                "provider" to "OPENROUTER",
                "failed_stage" to "model_invocation",
                "http_status" to 403,
                "remote_acceptance" to "UNKNOWN",
                "provider_rejection_category" to "PERMISSION",
                "run_id" to "unverified-permission"
            )
        )
        assertEquals("CHAT_FAILED", IssueReportIncidents.pending.value.last().errorCode)
    }

    @Test
    fun rejectedProviderResponsesWithoutLegacyStageKeepOnlyTheObservedCause() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val fields = mapOf<String, Any?>(
            "provider" to "OPENROUTER",
            "diagnostic_area" to "PROVIDER",
            "provider_failure_domain" to "PROVIDER",
            "provider_failure_stage" to "GENERATION",
            "provider_failure_code" to "HTTP_ERROR",
            "dispatched" to true,
            "remote_acceptance" to "NO"
        )
        val cases = listOf(
            mapOf("http_status" to 401) to "AUTHENTICATION_FAILED",
            mapOf("http_status" to 400) to "SOURCE_HTTP_ERROR",
            mapOf("http_status" to 404, "provider_rejection_category" to "NOT_FOUND") to "SOURCE_UNAVAILABLE",
            mapOf("http_status" to 404, "provider_rejection_category" to "PERMISSION") to "SOURCE_ACCESS_BLOCKED",
            mapOf("http_status" to 404, "provider_rejection_category" to "UNAVAILABLE") to "SOURCE_UNAVAILABLE",
            mapOf("http_status" to 404, "provider_rejection_category" to "MODEL_UNAVAILABLE") to "SELECTED_MODEL_NOT_AVAILABLE",
            mapOf("failed_stage" to "claim_extraction", "provider_failure_stage" to "CLAIM_VERIFICATION", "http_status" to 400, "provider_rejection_category" to "INVALID_REQUEST") to "PROVIDER_PAYLOAD_ERROR",
            mapOf("failed_stage" to "claim_extraction", "provider_failure_domain" to "APPLICATION_VERIFICATION", "provider_failure_stage" to "CLAIM_VERIFICATION", "http_status" to 400) to "CHAT_FAILED",
            mapOf("http_status" to 404, "remote_acceptance" to "UNKNOWN") to "CHAT_FAILED",
            mapOf("http_status" to 422, "provider_rejection_category" to "INVALID_REQUEST") to "PROVIDER_PAYLOAD_ERROR",
            mapOf("http_status" to 401, "remote_acceptance" to "UNKNOWN", "requires_reconciliation" to true) to "CHAT_FAILED"
        )
        for ((index, case) in cases.withIndex()) {
            IssueReportIncidents.chatFailure("HTTP_ERROR", false, null, fields + case.first + ("run_id" to "rejection-$index"))
            val incident = IssueReportIncidents.pending.value.last()
            assertEquals(case.second, incident.errorCode)
            if (case.second == "AUTHENTICATION_FAILED") assertEquals(false, incident.context?.retryable)
        }
    }

    @Test
    fun rejectedProviderRateLimitKeepsItsCauseWithoutAResumableWait() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.chatFailure(
            "429",
            false,
            null,
            mapOf(
                "failed_stage" to JsonPrimitive("model_invocation"),
                "provider" to JsonPrimitive("OPENROUTER"),
                "http_status" to JsonPrimitive(429),
                "retry_after_ms" to JsonPrimitive(30156L),
                "dispatched" to JsonPrimitive(true),
                "retryable" to JsonPrimitive(true),
                "remote_acceptance" to JsonPrimitive("NO"),
                "message" to "private provider response"
            )
        )

        val report = IssueReportIncidents.pending.value.single()
        assertEquals("PROVIDER_RATE_LIMITED", report.errorCode)
        assertEquals("model_invocation", report.context?.stage)
        assertEquals("OPENROUTER", report.context?.provider)
        assertEquals(429, report.context?.httpStatus)
        assertEquals(30156L, report.context?.retryAfterMillis)
        assertEquals(true, report.context?.dispatched)
        assertEquals(true, report.context?.retryable)
        assertEquals("NO", report.context?.remoteAcceptance)
        assertEquals(null, report.context?.resumeAllowed)
        assertFalse(report.approvedPayload().contains("private provider response"))
    }

    @Test
    fun typedLocalRecoveryFailureRetainsItsCauseWithoutReusingTheOriginalRateLimitOrInvocation() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val original = buildJsonObject {
            put("code", "429")
            put(
                "details",
                buildJsonObject {
                    put("http_status", 429)
                    put("dispatched", true)
                    put("invocation_id", "private-original-invocation")
                    put("message", "private provider reply")
                }
            )
        }
        val correlation = buildJsonObject {
            put("checkpoint_binding", "a".repeat(64))
            put("checkpoint_digest", "b".repeat(64))
            put("resume_claim_id", "11111111-1111-4111-8111-111111111111")
            put("ownership_fence", 3)
            put("run_id", "private-recovery-run")
        }
        val failure = ProviderRecoveryFailure("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", original, correlation).event()
        IssueReportIncidents.chatFailure(failure.errorCode, true, null, requireNotNull(failure.failureDetails))
        val report = IssueReportIncidents.pending.value.single()
        val context = requireNotNull(report.context)
        assertEquals("CHAT_FAILED", report.errorCode)
        assertEquals(7, context.schemaVersion)
        assertEquals("lifecycle", context.stage)
        assertEquals("PROVIDER_WAIT_EVIDENCE_UNAVAILABLE", context.reasonCode)
        assertEquals("REVIEW_SAVED_RECOVERY", context.recoveryAction)
        assertEquals("PROVIDER_RATE_LIMITED", context.originalFailureCode)
        assertEquals(429, context.originalHttpStatus)
        assertEquals(null, context.httpStatus)
        assertEquals(false, context.dispatched)
        assertEquals(false, context.retryable)
        assertEquals(false, context.resumeAllowed)
        assertEquals("NO", context.remoteAcceptance)
        assertEquals(null, context.invocationIdHash)
        assertEquals(3L, context.ownershipFence)
        assertEquals(IssueReportContext.identifierHash("run", "private-recovery-run"), context.runIdHash)
        for (privateValue in listOf("private-original-invocation", "private provider reply", "private-recovery-run", "a".repeat(64), "b".repeat(64), "11111111-1111-4111-8111-111111111111")) assertFalse(report.approvedPayload().contains(privateValue))
    }

    @Test
    fun blockedContinuationKeepsReconciliationEvidenceInTheApprovedReport() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val identity = "a".repeat(64)
        IssueReportIncidents.chatFailure(
            "OUTPUT_CONTINUATION_PENDING_TOOLS",
            false,
            null,
            mapOf(
                "run_id" to "pending-run",
                "failed_stage" to "tool_reconciliation",
                "dispatched" to false,
                "requires_reconciliation" to true,
                "recovery_action" to "RECONCILE_OUTCOME",
                "pending_tool_count" to 2,
                "pending_tool_identity_hashes" to listOf(identity),
                "pending_tool_receipt_state" to "SOME_TERMINAL_RECEIPTS_MISSING",
                "pending_invocation_fingerprints" to listOf("private-invocation")
            )
        )
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("RECONCILIATION_REQUIRED", report.errorCode)
        val context = requireNotNull(report.context)
        assertEquals("RECONCILE_OUTCOME", context.recoveryAction)
        assertTrue("RUN_FAILURE_EVIDENCE_V1" in requireNotNull(context.diagnostics).requiredServiceFeatures())
        assertTrue(report.approvedPayload().contains(identity))
        assertFalse(report.approvedPayload().contains("private-invocation"))
    }

    @Test
    fun providerRateLimitFallbackDoesNotReplaceSpecificFailuresOrUnknownOutcomes() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val fields = mapOf<String, Any?>(
            "failed_stage" to "model_invocation",
            "provider" to "OPENROUTER",
            "http_status" to 429,
            "remote_acceptance" to "NO"
        )
        IssueReportIncidents.chatFailure("COMPLETION_REPAIR_BUDGET_EXHAUSTED", false, null, fields)
        IssueReportIncidents.chatFailure("RECONCILIATION_REQUIRED", false, null, fields)
        IssueReportIncidents.chatFailure("429", false, null, fields + mapOf("requires_reconciliation" to true, "run_id" to "reconciliation-run"))
        IssueReportIncidents.chatFailure("429", false, null, fields + mapOf("remote_acceptance" to "UNKNOWN", "run_id" to "unknown-run"))

        assertEquals(
            listOf("COMPLETION_REPAIR_BUDGET_EXHAUSTED", "RECONCILIATION_REQUIRED", "CHAT_FAILED", "CHAT_FAILED"),
            IssueReportIncidents.pending.value.map { it.errorCode }
        )
    }

    @Test
    fun rateLimitFallbackRequiresTypedProviderRejectionEvidence() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val fields = mapOf<String, Any?>(
            "failed_stage" to "model_invocation",
            "provider" to "OPENROUTER",
            "http_status" to 429,
            "remote_acceptance" to "NO"
        )
        val unrelated = listOf(
            fields + ("failed_stage" to "tool_execution"),
            fields - "provider",
            fields + ("http_status" to "429"),
            fields - "remote_acceptance",
            mapOf("message" to "HTTP 429 private provider response")
        )
        unrelated.forEachIndexed { index, evidence ->
            IssueReportIncidents.chatFailure(null, false, null, evidence + ("run_id" to "unrelated-$index"))
        }

        val reports = IssueReportIncidents.pending.value
        assertEquals(unrelated.size, reports.size)
        assertTrue(reports.all { it.errorCode == "CHAT_FAILED" })
        assertTrue(reports.none { it.approvedPayload().contains("private provider response") })
    }

    @Test
    fun activeGatewayJobAndExecutionSelectionFailuresKeepDistinctSafeReportCodes() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val conflict = ErrorClassification.classify(IllegalStateException("code=conversation_conflict active_job_id=private-job"))
        IssueReportIncidents.chatFailure(conflict.errorCode, false)
        IssueReportIncidents.chatFailure("EXECUTION_SELECTION_INVALID", false)

        assertEquals(listOf("CONVERSATION_CONFLICT", "EXECUTION_SELECTION_INVALID"), IssueReportIncidents.pending.value.map { it.errorCode })
        assertFalse(IssueReportIncidents.pending.value.flatMap { it.events }.any { "private-job" in it || "active_job_id" in it })
    }

    @Test
    fun reportAllowlistCoversProviderContractsAndSpecificErrorCategories() {
        val specificCategories = ErrorCategory.entries.map { it.name }.toSet() - setOf("CLIENT_CANCELLED", "UNKNOWN")
        val missing = (ProviderContractFailure.codes + specificCategories) - IssueReportCodes.allowed
        assertEquals(emptySet<String>(), missing)
    }

    @Test
    fun toolFailureRetainsOnlyAnAllowlistedCodeAndNeverTheRawProviderCode() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.log("Tool", "E", tracking = false, code = "INVALID_ARGUMENTS")
        IssueReportIncidents.log("Tool", "E", tracking = false, code = "PRIVATE_PROVIDER_TOKEN_VALUE")
        IssueReportIncidents.chatFailure("PRIVATE_PROVIDER_TOKEN_VALUE", true)

        assertEquals(
            listOf("INVALID_ARGUMENTS", "APP_ERROR", "PROVIDER_RATE_LIMITED"),
            IssueReportIncidents.pending.value.map { it.errorCode }
        )
        assertFalse(IssueReportIncidents.recentEvents().any { "PRIVATE_PROVIDER_TOKEN_VALUE" in it })
    }

    @Test
    fun privateSessionSuppressesDetailedFailureMetadata() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.privacyChanged(true)
        IssueReportIncidents.failure(ReportFeature.VOICE, "VOICE_RECOGNIZER_FAILED", IllegalStateException("private"))
        IssueReportIncidents.log("Tool", "E", tracking = true, code = "INVALID_ARGUMENTS", error = IllegalStateException("private"))

        assertTrue(IssueReportIncidents.pending.value.isEmpty())
        assertTrue(IssueReportIncidents.recentEvents().isEmpty())
    }

    @Test
    fun privacyEligibilityFailuresKeepTypedReasonsOnlyWhenCaptureIsPermitted() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val fields = mapOf(
            "run_id" to "PRIVATE_RUN",
            "failed_stage" to "provider_generation",
            "provider_kind" to "OPENROUTER",
            "privacy_reason_code" to "VERIFICATION_UNAVAILABLE",
            "privacy_status" to "NOT_VERIFIED",
            "dispatched" to true,
            "remote_acceptance" to "UNKNOWN",
            "request_replay_allowed" to false,
            "logical_continuation_allowed" to false,
            "message" to "PRIVATE_PROVIDER_BODY"
        )
        IssueReportIncidents.privacyChanged(true)
        IssueReportIncidents.chatFailure(PrivacyFailureEvidence.CODE, false, null, fields)
        assertTrue(IssueReportIncidents.pending.value.isEmpty())
        assertTrue(IssueReportIncidents.recentEvents().isEmpty())
        assertNull(IssueReportIncidents.diagnosticSnapshot("PROVIDER"))

        IssueReportIncidents.privacyChanged(false)
        IssueReportIncidents.chatFailure(PrivacyFailureEvidence.CODE, false, null, fields)
        val report = IssueReportIncidents.pending.value.single()
        val context = requireNotNull(report.context)
        val diagnostic = requireNotNull(context.diagnostics)
        assertEquals(PrivacyFailureEvidence.CODE, report.errorCode)
        assertEquals("provider_generation", context.stage)
        assertEquals(true, context.dispatched)
        assertEquals("UNKNOWN", context.remoteAcceptance)
        assertEquals(JsonPrimitive("GENERATION"), diagnostic.values["provider_failure_stage"])
        assertEquals(JsonPrimitive("VERIFICATION_UNAVAILABLE"), diagnostic.values["privacy_reason_code"])
        assertEquals(JsonPrimitive("NOT_VERIFIED"), diagnostic.values["privacy_status"])
        assertTrue(PrivacyFailureEvidence.FEATURE in diagnostic.requiredServiceFeatures())
        assertFalse(report.approvedPayload().contains("PRIVATE_"))
    }

    @Test
    fun toolTransportCodesSurviveTheRecorderAndRepeatedMcpFailureIsOneIncident() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val codes = listOf("DNS_RESOLUTION_FAILED", "NETWORK_DISCONNECTED", "CONNECTION_ABORTED", "NETWORK_TIMEOUT", "TLS_FAILURE")
        for (code in codes) {
            AppLogRecorder.record("MCP", "private connection details", "E", reportCode = code)
            AppLogRecorder.record("Tool", "private tool details", "E", reportCode = code)
        }

        assertEquals(codes, IssueReportIncidents.pending.value.map { it.errorCode })
        assertTrue(IssueReportIncidents.pending.value.all { it.context?.operation == "mcp" })
        assertFalse(IssueReportIncidents.recentEvents().any { "private" in it })
    }

    @Test
    fun githubAndModelBlockersRemainDistinctAcrossToolAndChatReports() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val codes = listOf(
            "GITHUB_CIRCUIT_OPEN",
            "GITHUB_PRIMARY_RATE_LIMITED",
            "GITHUB_ENDPOINT_PERMISSION_REQUIRED",
            "GITHUB_LIVE_OBSERVATION_REQUIRED",
            "MCP_INITIALIZATION_SCHEMA_INVALID",
            "SELECTED_MODEL_NOT_LOADED"
        )
        for (code in codes) {
            AppLogRecorder.record("Tool", "private operation context", "E", reportCode = code)
            IssueReportIncidents.chatFailure(code, false)
        }

        assertEquals(codes.flatMap { listOf(it, it) }, IssueReportIncidents.pending.value.map { it.errorCode })
        assertFalse(IssueReportIncidents.pending.value.any { it.errorCode in setOf("APP_ERROR", "CHAT_FAILED") })
        assertFalse(IssueReportIncidents.recentEvents().any { "private" in it })
    }

    @Test
    fun structuredFailureKeepsOnlyTheAllowlistedCodeAndWarningCause() {
        LocalDiagnosticsPolicy.setEnabled(true)
        AppLogRecorder.recordEvent(
            "MCP",
            "private event name",
            mapOf("errorCode" to "MCP_AUTHENTICATION_REQUIRED", "connection" to "private connection"),
            "W"
        )
        AppLogRecorder.recordEvent(
            "Tool",
            "private error event",
            mapOf("error_code" to "GITHUB_CIRCUIT_OPEN", "message" to "private message"),
            "E"
        )

        val report = IssueReportIncidents.pending.value.single()
        assertEquals("GITHUB_CIRCUIT_OPEN", report.errorCode)
        assertTrue(report.events.any { it.endsWith("W tools MCP_AUTHENTICATION_REQUIRED") })
        assertTrue(report.events.any { it.endsWith("E tools GITHUB_CIRCUIT_OPEN") })
        assertFalse(IssueReport.json.encodeToString(IssueReport.serializer(), report).contains("private"))
    }

    @Test
    fun interruptedRunAndUnavailableContinuationKeepTheirDistinctPublicCodes() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (code in listOf("GITHUB_RUN_INTERRUPTED", "OUTPUT_CONTINUATION_UNAVAILABLE", "OUTPUT_CONTINUATION_NO_PROGRESS", "PROVIDER_INVOCATION_FAILED")) {
            IssueReportIncidents.chatFailure(code, false)
            val report = IssueReportIncidents.pending.value.single { it.errorCode == code }
            assertEquals("chat", report.feature)
            assertTrue(report.events.last().endsWith("E chat $code"))
            assertTrue(report.approvedPayload().contains(code))
        }
    }

    @Test
    fun busyToolTrafficRetainsDistinctCodedWarningsInTheApprovedReport() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.log("MCP", "W", true, "MCP_AUTHENTICATION_REQUIRED")
        repeat(48) {
            IssueReportIncidents.log("Tool", "W", true, "GITHUB_CIRCUIT_OPEN")
            IssueReportIncidents.log("Tool", "I", true, "private-message")
        }
        IssueReportIncidents.failure(ReportFeature.CHAT, "CHAT_FAILED")
        val report = IssueReportIncidents.pending.value.single()
        assertEquals(IssueReport.EVENT_LIMIT, report.events.size)
        assertTrue(report.events.first().endsWith("W tools MCP_AUTHENTICATION_REQUIRED"))
        assertTrue(report.events.any { it.endsWith("W tools GITHUB_CIRCUIT_OPEN") })
        assertTrue(report.events.last().endsWith("E chat CHAT_FAILED"))
        assertFalse(report.approvedPayload().contains("private-message"))
        IssueReportIncidents.privacyChanged(true)
        IssueReportIncidents.privacyChanged(false)
        assertTrue(IssueReportIncidents.recentEvents().isEmpty())
    }

    @Test
    fun structuredFailureNeverInfersACodeFromMessagesOrNonStringFields() {
        LocalDiagnosticsPolicy.setEnabled(true)
        AppLogRecorder.recordEvent(
            "Tool",
            "GITHUB_CIRCUIT_OPEN",
            mapOf("errorCode" to listOf("GITHUB_CIRCUIT_OPEN"), "message" to "GITHUB_CIRCUIT_OPEN", "failure_code" to "PRIVATE_PROVIDER_TOKEN_VALUE"),
            "E"
        )

        val report = IssueReportIncidents.pending.value.single()
        assertEquals("APP_ERROR", report.errorCode)
        assertFalse(report.events.any { "GITHUB_CIRCUIT_OPEN" in it || "PRIVATE_PROVIDER_TOKEN_VALUE" in it })
    }

    @Test
    fun differentRunsAreNotCollapsedOrBorrowedIntoAContextlessFailure() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.chatFailure("CONVERSATION_CONFLICT", false, null, mapOf("run_id" to "first-run", "invocation_id" to "untrusted-alias", "http_status" to 409), "first-invocation")
        IssueReportIncidents.chatFailure("CONVERSATION_CONFLICT", false, null, mapOf("run_id" to "second-run"), "second-invocation")
        IssueReportIncidents.chatFailure("CONVERSATION_CONFLICT", false)

        val reports = IssueReportIncidents.pending.value
        assertEquals(3, reports.size)
        assertEquals(3, reports.map { it.reportId }.distinct().size)
        assertEquals(IssueReportContext.identifierHash("run", "first-run"), reports[0].context?.runIdHash)
        assertEquals(IssueReportContext.identifierHash("invocation", "first-invocation"), reports[0].context?.invocationIdHash)
        assertEquals(IssueReportContext.identifierHash("run", "second-run"), reports[1].context?.runIdHash)
        assertEquals(null, reports[2].context?.runIdHash)
        assertEquals(null, reports[2].context?.invocationIdHash)
        assertEquals(null, reports[2].context?.httpStatus)
        assertTrue(reports[2].context!!.unavailableFields.containsAll(listOf("runIdHash", "invocationIdHash", "httpStatus")))
    }

    @Test
    fun sourceOwnedToolResultPhasePreservesExecutionBoundaryWithoutInventingTheRemoteCause() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for ((index, phase) in listOf("READ_FAILED", JsonPrimitive("OBSERVED")).withIndex()) {
            IssueReportIncidents.failure(
                ReportFeature.TOOLS,
                "APP_ERROR",
                null,
                mapOf(
                    "run_id" to "private-run-$index",
                    "call_id" to "private-call-$index",
                    "tool" to "github_pr",
                    "tool_action" to "context",
                    "tool_dispatch_phase" to phase,
                    "http_status" to 200
                ),
                "TOOL_RESULT"
            )
            val report = IssueReportIncidents.pending.value.last()
            assertEquals("tool_execution", report.context?.stage)
            assertEquals(200, report.context?.httpStatus)
            assertEquals(JsonPrimitive("tool_execution"), report.context?.diagnostics?.values?.get("incident_phase"))
            assertEquals(JsonPrimitive("TOOL_CALL"), report.context?.diagnostics?.values?.get("causal_provenance"))
            assertEquals(JsonPrimitive("TYPED_EVIDENCE"), report.context?.diagnostics?.values?.get("incident_evidence_status"))
            assertFalse(report.approvedPayload().contains("private-run"))
            assertFalse(report.approvedPayload().contains("private-call"))
        }
    }

    @Test
    fun toolPrecheckAndGenericStructuredEventsMustNotInheritAResultExecutionStage() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.failure(
            ReportFeature.TOOLS,
            "APP_ERROR",
            null,
            mapOf("tool_dispatch_phase" to "PRECHECK_REJECTED", "run_id" to "precheck-run", "call_id" to "precheck-call"),
            "TOOL_RESULT"
        )
        IssueReportIncidents.failure(
            ReportFeature.TOOLS,
            "APP_ERROR",
            null,
            mapOf("tool_dispatch_phase" to "READ_FAILED", "run_id" to "structured-run", "call_id" to "structured-call"),
            "STRUCTURED_EVENT"
        )
        val reports = IssueReportIncidents.pending.value
        assertEquals(2, reports.size)
        assertNull(reports[0].context?.stage)
        assertNull(reports[1].context?.stage)
        assertEquals(JsonPrimitive("unknown"), reports[0].context?.diagnostics?.values?.get("incident_phase"))
        assertEquals(JsonPrimitive("unknown"), reports[1].context?.diagnostics?.values?.get("incident_phase"))
    }

    @Test
    fun deterministicCallIdsAreScopedToTheirRunWhileExactIncidentDuplicatesCollapse() {
        LocalDiagnosticsPolicy.setEnabled(true)
        for (run in listOf("first-run", "second-run")) {
            repeat(2) {
                IssueReportIncidents.failure(
                    ReportFeature.TOOLS,
                    "GITHUB_CIRCUIT_OPEN",
                    null,
                    mapOf("run_id" to run, "call_id" to "same-deterministic-checklist-call", "tool" to "github_read"),
                    "TOOL_RESULT"
                )
            }
        }

        val reports = IssueReportIncidents.pending.value
        assertEquals(2, reports.size)
        assertEquals(2, reports.map { it.reportId }.distinct().size)
        assertEquals(listOf("first-run", "second-run").map { IssueReportContext.identifierHash("run", it) }, reports.map { it.context?.runIdHash })
        assertEquals(1, reports.map { it.context?.callIdHash }.distinct().size)
        assertEquals(IssueReportContext.identifierHash("call", "same-deterministic-checklist-call"), reports.first().context?.callIdHash)
    }

    @Test
    fun explicitToolCauseSuppressesItsChatPropagationWithoutRewritingTheFirstReport() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val toolFields = mapOf("run_id" to "owned-run", "call_id" to "owned-call", "tool" to "github_context", "phase" to "tool_execution", "http_status" to 422)
        IssueReportIncidents.failure(ReportFeature.TOOLS, "GITHUB_VALIDATION_FAILED", null, toolFields, "TOOL_RESULT")
        val first = IssueReportIncidents.pending.value.single()
        val approved = first.approvedPayload()
        val propagated = mapOf("run_id" to "owned-run", "causal_id" to "owned-call", "causal_provenance" to "TOOL_CALL", "causal_failure_code" to "GITHUB_VALIDATION_FAILED", "failed_stage" to "review_comparison")
        IssueReportIncidents.chatFailure("GITHUB_VALIDATION_FAILED", false, null, propagated)
        assertEquals(listOf(first), IssueReportIncidents.pending.value)
        assertEquals(approved, first.approvedPayload())
        IssueReportIncidents.chatFailure("GITHUB_VALIDATION_FAILED", false, null, propagated + ("causal_id" to "different-call"))
        assertEquals(2, IssueReportIncidents.pending.value.size)
        IssueReportIncidents.chatFailure("GITHUB_VALIDATION_FAILED", false, null, propagated - "causal_id")
        assertEquals(3, IssueReportIncidents.pending.value.size)
    }

    @Test
    fun callAllowancePrecheckReportsBudgetInsteadOfUnexplainedToolFailure() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.log(
            "Tools",
            "E",
            tracking = false,
            code = "TOOL_FAILURE",
            fields = mapOf(
                "run_id" to "owned-call-allowance-run",
                "call_id" to "owned-precheck-call",
                "failed_stage" to "tool_precheck",
                "dispatched" to false,
                "dependency_admission_reasons" to listOf("CALL_ALLOWANCE")
            ),
            source = "TOOL_RESULT"
        )
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("TOOL_BUDGET_EXHAUSTED", report.errorCode)
        assertEquals(false, report.context?.dispatched)
        assertEquals("tool_precheck", report.context?.stage)

        IssueReportIncidents.log(
            "Tools",
            "E",
            tracking = false,
            code = "TOOL_FAILURE",
            fields = mapOf("run_id" to "different-run", "failed_stage" to "tool_precheck", "dispatched" to false),
            source = "TOOL_RESULT"
        )
        assertEquals("TOOL_FAILURE", IssueReportIncidents.pending.value.last().errorCode)
    }

    @Test
    fun genericChatFailureRetainsOnlyHashedThrowableCauseTypes() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val error = IllegalStateException("PRIVATE_PROVIDER_EXCEPTION_MESSAGE", java.io.IOException("PRIVATE_CAUSE"))
        IssueReportIncidents.chatFailure(
            "UNRECOGNIZED_ERROR_CODE",
            false,
            error,
            mapOf("run_id" to "private-generic-run")
        )

        val report = IssueReportIncidents.pending.value.single()
        val diagnostic = requireNotNull(report.context?.diagnostics)
        val expected = dev.chungjungsoo.gptmobile.data.network.sanitizedCauseTypes(error)
            .take(4).mapNotNull { IssueReportContext.identifierHash("exception-type", it) }
        assertTrue(expected.isNotEmpty())
        val retained = (diagnostic.values["exception_type_hashes"] as? JsonArray)
            ?.map { (it as JsonPrimitive).content }
        assertEquals(expected, retained)
        assertEquals(JsonPrimitive("CAUSAL_EVIDENCE_UNAVAILABLE"), diagnostic.values["incident_evidence_status"])
        assertFalse(report.approvedPayload().contains("PRIVATE_PROVIDER_EXCEPTION_MESSAGE"))
        assertFalse(report.approvedPayload().contains("PRIVATE_CAUSE"))
    }

    @Test
    fun laterProviderWaitObservationRetainsExplicitPersistenceForFollowupOnly() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.chatFailure("PROVIDER_RATE_LIMITED", true, null, mapOf("run_id" to "owned-run", "provider" to "OPENROUTER", "failed_stage" to "model_invocation", "http_status" to 429))
        val first = IssueReportIncidents.pending.value.single()
        val approved = first.approvedPayload()
        val observation = mapOf("run_id" to "owned-run", "profile_id" to "private-profile", "model" to "private-model", "stage" to "provider_wait", "provider_wait_state" to "WAITING_PROVIDER_LIMIT", "provider_wait_persisted" to true, "recovery_observation" to "RETRY_SCHEDULED", "retry_delay_ms" to 30_000L, "recovery_authority" to "VALID")
        IssueReportIncidents.providerRecoveryObservation(observation)
        val snapshot = requireNotNull(IssueReportIncidents.diagnosticSnapshot("PROVIDER"))
        assertEquals(JsonPrimitive(true), snapshot.values["provider_wait_persisted"])
        assertEquals(JsonPrimitive("provider_wait"), snapshot.values["incident_phase"])
        assertNull(snapshot.values["request_replay_allowed"])
        assertEquals(listOf(first), IssueReportIncidents.pending.value)
        assertEquals(approved, first.approvedPayload())
        assertFalse(snapshot.toString().contains("private"))
        IssueReportIncidents.privacyChanged(true)
        IssueReportIncidents.providerRecoveryObservation(observation)
        assertNull(IssueReportIncidents.diagnosticSnapshot("PROVIDER"))
    }

    @Test
    fun structuredRecorderRetainsTypedOperationFactsWithoutRawPayloadOrIdentifiers() {
        LocalDiagnosticsPolicy.setEnabled(true)
        AppLogRecorder.recordEvent(
            "Tool",
            "Private operation name",
            mapOf("error_code" to "GITHUB_CIRCUIT_OPEN", "tool" to "github_read__github_api", "run_id" to "recorded-run", "call_id" to "recorded-call", "phase" to "tool_precheck", "dispatched" to false, "retry_after_ms" to 5000L, "url" to "https://private.example", "message" to "private result"),
            "E"
        )
        val report = IssueReportIncidents.pending.value.single()
        assertEquals("STRUCTURED_EVENT", report.context?.source)
        assertEquals("github_read", report.context?.operation)
        assertEquals("tool_precheck", report.context?.stage)
        assertEquals(false, report.context?.dispatched)
        assertEquals(5000L, report.context?.retryAfterMillis)
        assertEquals(IssueReportContext.identifierHash("call", "recorded-call"), report.context?.callIdHash)
        val raw = report.approvedPayload()
        assertFalse(raw.contains("private", ignoreCase = true))
        assertFalse(raw.contains("recorded-run"))
        assertFalse(raw.contains("recorded-call"))
    }

    @Test
    fun crashPersistsOnlySafeFramesWithoutTheExceptionMessage() {
        LocalDiagnosticsPolicy.setEnabled(true)
        val error = IllegalStateException("private exception payload")
        error.stackTrace = arrayOf(
            StackTraceElement("dev.chungjungsoo.gptmobile.data.voice.VoiceSession", "update", "VoiceSession.kt", 162),
            StackTraceElement("other.custom.PrivateClass", "method", "Private.java", 12)
        )
        IssueReportIncidents.crash(error)
        val file = File(context.noBackupFilesDir, "pending_issue_crashes").listFiles().orEmpty().single()
        val raw = file.readText()
        val report = IssueReport.json.decodeFromString(IssueReport.serializer(), raw)
        assertEquals("JVM_CRASH", report.errorCode)
        assertEquals("IllegalStateException", report.errorType)
        assertEquals("APP", report.context?.diagnostics?.area)
        assertEquals(JsonPrimitive("JVM_CRASH"), report.context?.diagnostics?.values?.get("app_exit_kind"))
        assertEquals(listOf("dev.chungjungsoo.gptmobile.data.voice.VoiceSession.update:162"), report.frames)
        assertFalse(raw.contains("private exception payload"))
    }

    @Test
    fun diagnosticSnapshotUsesOnlyItsIncidentAndContextlessFailureDoesNotBorrowIt() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.chatFailure(
            "CHAT_FAILED",
            false,
            null,
            mapOf("diagnostic_area" to "PROVIDER", "run_id" to "owned-run", "model_hash" to "a".repeat(64), "output_tokens" to 27)
        )
        val first = IssueReportIncidents.pending.value.single()
        assertEquals(first.context?.diagnostics, IssueReportIncidents.diagnosticSnapshot("PROVIDER"))
        assertNull(IssueReportIncidents.diagnosticSnapshot("VOICE"))

        IssueReportIncidents.chatFailure("CHAT_FAILED", false)
        val second = IssueReportIncidents.pending.value.last()
        val generic = requireNotNull(second.context?.diagnostics)
        assertEquals(7, second.context?.schemaVersion)
        assertEquals(3, generic.schemaVersion)
        assertEquals("INCIDENT", generic.area)
        assertEquals(JsonPrimitive("unknown"), generic.values["incident_phase"])
        assertEquals(JsonPrimitive("UNAVAILABLE"), generic.values["causal_provenance"])
        assertEquals(JsonPrimitive("CAUSAL_EVIDENCE_UNAVAILABLE"), generic.values["incident_evidence_status"])
        for (field in listOf("causal_id_hash", "run_id_hash", "invocation_id_hash", "call_id_hash", "job_id_hash", "model_hash", "output_tokens")) {
            assertFalse(field in generic.values)
        }
        assertNull(second.context?.runIdHash)
        assertNull(second.context?.invocationIdHash)
        assertNull(second.context?.callIdHash)
        assertNull(second.context?.jobIdHash)
        assertEquals(first.context?.diagnostics, IssueReportIncidents.diagnosticSnapshot("PROVIDER"))
        assertFalse(first.approvedPayload().contains("owned-run"))
    }

    @Test
    fun privacyAndDebugOptOutClearSnapshotsEvenWhenGeneralLoggingRemainsEnabled() {
        LocalDiagnosticsPolicy.setEnabled(true)
        IssueReportIncidents.failure(ReportFeature.APP, "STARTUP_FAILED")
        assertEquals(JsonPrimitive("STARTUP_FAILED"), IssueReportIncidents.diagnosticSnapshot("APP")?.values?.get("app_exit_kind"))
        IssueReportIncidents.privacyChanged(true)
        assertNull(IssueReportIncidents.diagnosticSnapshot("APP"))
        IssueReportIncidents.privacyChanged(false)
        assertNull(IssueReportIncidents.diagnosticSnapshot("APP"))

        IssueReportIncidents.failure(ReportFeature.APP, "STARTUP_FAILED")
        assertTrue(IssueReportIncidents.diagnosticSnapshot("APP") != null)
        val wasEnabled = AppLogRecorder.enabled.value
        try {
            AppLogRecorder.setEnabled(true)
            LocalDiagnosticsPolicy.setEnabled(false)
            IssueReportIncidents.debugPreferenceChanged(false)
            LocalDiagnosticsPolicy.setEnabled(true)
            assertNull(IssueReportIncidents.diagnosticSnapshot("APP"))
        } finally {
            AppLogRecorder.setEnabled(wasEnabled)
        }
    }
}
