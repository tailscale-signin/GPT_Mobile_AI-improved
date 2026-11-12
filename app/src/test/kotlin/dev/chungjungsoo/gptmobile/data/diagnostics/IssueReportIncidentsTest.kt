package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.chungjungsoo.gptmobile.data.network.error.ErrorCategory
import dev.chungjungsoo.gptmobile.data.network.error.ErrorClassification
import dev.chungjungsoo.gptmobile.data.network.error.ProviderContractFailure
import java.io.File
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        assertEquals(listOf("dev.chungjungsoo.gptmobile.data.voice.VoiceSession.update:162"), report.frames)
        assertFalse(raw.contains("private exception payload"))
    }
}
