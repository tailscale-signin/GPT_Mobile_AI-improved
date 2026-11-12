package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
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
        assertFalse(IssueReportIncidents.recentEvents().any { "private" in it })
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
