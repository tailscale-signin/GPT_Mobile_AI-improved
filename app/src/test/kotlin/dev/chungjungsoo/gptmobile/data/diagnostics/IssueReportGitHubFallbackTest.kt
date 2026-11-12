package dev.chungjungsoo.gptmobile.data.diagnostics

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportGitHubFallbackTest {
    @Test fun manualReportOpensABoundedPrefilledGitHubIssue() {
        val report = report(
            title = "Voice playback failed",
            description = "Kokoro stopped after opening voice chat.",
            events = listOf("VoiceTiming `synthesis_failed`")
        )

        val url = IssueReportGitHubFallback.url(report)
        val query = query(url)

        assertTrue(url.startsWith("${IssueReportGitHubFallback.TRACKER}/new?"))
        assertEquals("Voice playback failed", query.getValue("title"))
        assertTrue(query.getValue("body").contains("Kokoro stopped after opening voice chat."))
        assertTrue(query.getValue("body").contains("Version: 0.9.66.7 (141)"))
        assertFalse(query.getValue("body").contains("`synthesis_failed`"))
        assertTrue(url.length <= 7_500)
    }

    @Test fun debugReportGetsAUsefulTitleWhenNoManualTitleExists() {
        val url = IssueReportGitHubFallback.url(
            report(title = null, description = null).copy(feature = "voice", errorCode = "VOICE_PLAYBACK_FAILED")
        )

        assertEquals("voice: VOICE_PLAYBACK_FAILED", query(url).getValue("title"))
    }

    @Test fun unicodeDescriptionsCannotDisplaceTheAppAndReportIdentity() {
        for (description in listOf("界".repeat(1200), "😀".repeat(600), "界\n".repeat(600))) {
            val report = report(title = "界".repeat(120), description = description)
            val url = IssueReportGitHubFallback.url(report)
            val body = query(url).getValue("body")

            assertTrue(url.length <= 7_500)
            assertTrue(body.contains("Version: ${report.version} (${report.build})"))
            assertTrue(body.contains("Commit: ${report.commit}"))
            assertTrue(body.contains("Report ID: ${report.reportId}"))
            assertTrue(body.contains("shortened to fit the issue form"))
            assertFalse(body.contains('\uFFFD'))
            assertFalse(body.codePoints().anyMatch { it in 0xD800..0xDFFF })
        }
    }

    @Test fun longDebugReportKeepsItsFailureIdentityAndDoesNotSplitTheTitle() {
        val report = report(title = "a".repeat(119) + "😀", description = "界".repeat(1200)).copy(
            feature = "voice",
            errorCode = "VOICE_MICROPHONE_PERMISSION_REQUIRED",
            errorType = "SecurityException",
            frames = List(8) { "dev.chungjungsoo.gptmobile.data.voice.MoonshineVoiceRecognizer.capture:204" },
            events = List(16) { "-12345ms E voice VOICE_MICROPHONE_PERMISSION_REQUIRED" }
        )

        val url = IssueReportGitHubFallback.url(report)
        val query = query(url)
        val body = query.getValue("body")
        assertTrue(url.length <= 7_500)
        assertEquals("a".repeat(119), query.getValue("title"))
        assertTrue(body.contains("Report ID: ${report.reportId}"))
        assertTrue(body.contains("Commit: ${report.commit}"))
        assertTrue(body.contains("Error code: ${report.errorCode}"))
        assertTrue(body.contains("Error type: ${report.errorType}"))
        assertFalse(body.contains('\uFFFD'))
    }

    private fun report(title: String?, description: String?, events: List<String> = emptyList()) = IssueReport(
        reportId = "11111111-2222-3333-4444-555555555555",
        source = "manual",
        version = "0.9.66.7",
        build = 141,
        commit = "abcdef0",
        android = "16",
        sdk = 36,
        device = "ASUS ROG Phone 9 Pro",
        title = title,
        description = description,
        events = events
    )

    private fun query(url: String): Map<String, String> = URI(url).rawQuery.split('&').associate { value ->
        val (key, encoded) = value.split('=', limit = 2)
        key to URLDecoder.decode(encoded, StandardCharsets.UTF_8.name())
    }
}
