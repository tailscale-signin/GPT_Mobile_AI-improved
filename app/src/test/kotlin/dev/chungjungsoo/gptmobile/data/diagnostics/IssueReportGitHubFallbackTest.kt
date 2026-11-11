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
