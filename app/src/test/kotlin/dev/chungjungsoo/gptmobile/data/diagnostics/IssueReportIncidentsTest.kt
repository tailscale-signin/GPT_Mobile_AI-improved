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
