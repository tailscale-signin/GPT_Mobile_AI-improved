package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportManualCaptureTest {
    private val now = 1_794_520_000_000L
    private val events = listOf("-1ms E chat CHAT_FAILED")

    @Test
    fun explicitSelectionPreservesReportIdentityAndActualObservationTime() {
        val report = manual()
        val snapshot = capture(now - 5_000L, 27)
        val preview = IssueReportManualCapture.preview(report, true, "PROVIDER", snapshot, events, now)

        assertEquals(report.reportId, preview.reportId)
        assertEquals(report.context?.occurredAtMillis, preview.context?.occurredAtMillis)
        assertEquals("MANUAL_REPORT", preview.context?.source)
        assertEquals(5, preview.context?.schemaVersion)
        assertEquals("SELECTED_CAPTURE", preview.context?.diagnostics?.captureMode)
        assertEquals(snapshot.capturedAtMillis, preview.context?.diagnostics?.capturedAtMillis)
        assertEquals(JsonPrimitive(27), preview.context?.diagnostics?.values?.get("output_tokens"))
        assertEquals(events, preview.events)
    }

    @Test
    fun wrongAreaExpiredFutureAndInvalidObservationsBecomeExplicitNoCapture() {
        val valid = capture(now, 27)
        val invalid = valid.copy(values = valid.values + ("private_prompt" to JsonPrimitive("private text")))
        for (snapshot in listOf(capture(now - 600_001L, 27), capture(now + 1L, 27), invalid, null)) {
            val preview = IssueReportManualCapture.preview(manual(), true, "PROVIDER", snapshot, events, now)
            assertNoCapture(preview, "PROVIDER")
            assertFalse(preview.approvedPayload().contains("private text"))
        }
        assertNoCapture(IssueReportManualCapture.preview(manual(), true, "VOICE", valid, events, now), "VOICE")
    }

    @Test
    fun submissionUsesRenderedObservationEvenWhenNewCaptureIsAvailable() {
        val report = manual()
        val first = IssueReportManualCapture.preview(report, true, "PROVIDER", capture(now - 1L, 27), events, now)
        val second = IssueReportManualCapture.preview(report, true, "PROVIDER", capture(now, 86), events, now)
        val approved = IssueReportManualCapture.submission(first, "Response stopped", "It stopped before finishing.", true)

        assertEquals(first.reportId, approved.reportId)
        assertEquals(first.context, approved.context)
        assertEquals(JsonPrimitive(27), approved.context?.diagnostics?.values?.get("output_tokens"))
        assertEquals(JsonPrimitive(86), second.context?.diagnostics?.values?.get("output_tokens"))
        assertTrue(approved.approvedPayload().contains("SELECTED_CAPTURE"))
    }

    @Test
    fun previewCopiesMutableInputsBeforeApproval() {
        val original = capture(now, 27)
        val fields = original.values.toMutableMap()
        val log = events.toMutableList()
        val preview = IssueReportManualCapture.preview(manual(), true, "PROVIDER", original.copy(values = fields), log, now)
        fields["output_tokens"] = JsonPrimitive(86)
        log.clear()

        assertEquals(JsonPrimitive(27), preview.context?.diagnostics?.values?.get("output_tokens"))
        assertEquals(events, preview.events)
    }

    @Test
    fun optingOutAtSubmissionRemovesCaptureAndEventsFromApprovedPayload() {
        val preview = IssueReportManualCapture.preview(manual(), true, "PROVIDER", capture(now, 27), events, now)
        val approved = IssueReportManualCapture.submission(preview, "A problem", "Steps to reproduce.", false)

        assertEquals(preview.reportId, approved.reportId)
        assertEquals(preview.context?.occurredAtMillis, approved.context?.occurredAtMillis)
        assertEquals(1, approved.context?.schemaVersion)
        assertNull(approved.context?.diagnostics)
        assertTrue(approved.events.isEmpty())
        assertFalse(approved.approvedPayload().contains("output_tokens"))
    }

    @Test
    fun linkedFollowUpIsANewReportAndDoesNotChangeOriginalApprovedBytes() {
        val original = IssueReportManualCapture.submission(
            IssueReportManualCapture.preview(manual(), true, "PROVIDER", capture(now - 5_000L, 27), events, now),
            "Original problem",
            "Original reproduction.",
            true
        )
        val originalBytes = original.approvedPayload()
        val followUp = IssueReportManualCapture.submission(
            IssueReportManualCapture.preview(manual(), true, "PROVIDER", capture(now, 86), events, now),
            "Problem happened again",
            "Current reproduction.",
            true,
            relatedIssueNumber = 882
        )

        assertNotEquals(original.reportId, followUp.reportId)
        assertEquals("Follow-up to issue #882.\n\nCurrent reproduction.", followUp.description)
        assertEquals(JsonPrimitive(86), followUp.context?.diagnostics?.values?.get("output_tokens"))
        assertEquals(originalBytes, original.approvedPayload())
    }

    @Test
    fun noSelectionOrOptOutCannotImplicitlyAttachLatestFailure() {
        for ((include, area) in listOf(true to null, true to "UNKNOWN", false to "PROVIDER")) {
            val preview = IssueReportManualCapture.preview(manual(), include, area, capture(now, 27), events, now)
            assertEquals(1, preview.context?.schemaVersion)
            assertNull(preview.context?.diagnostics)
            if (!include) assertTrue(preview.events.isEmpty())
        }
    }

    @Test
    fun followUpRedactsWholeCredentialsBeforeApplyingItsFinalLengthBound() {
        val prefix = "Follow-up to issue #882.\n\n"
        val description = "x".repeat(IssueReport.DESCRIPTION_LIMIT - prefix.length - 8) + " " + "ghp_ABCDEFGH12345678"
        val approved = IssueReportManualCapture.submission(manual(), "Safe report", description, false, 882)

        assertFalse(requireNotNull(approved.description).contains("ghp_"))
        assertFalse(approved.approvedPayload().contains("ABCDEFGH"))
        assertTrue(requireNotNull(approved.description).length <= IssueReport.DESCRIPTION_LIMIT)
        val allowedDescription = "x".repeat(IssueReportManualCapture.descriptionLimit(882))
        val full = IssueReportManualCapture.submission(manual(), "Full reproduction", allowedDescription, false, 882)
        assertEquals(prefix + allowedDescription, full.description)
    }

    private fun manual(): IssueReport = IssueReport.manual("", "", emptyList()).copy(
        context = IssueReportContext.fromFields("MANUAL_REPORT", occurredAtMillis = now - 10_000L)
    )

    private fun capture(at: Long, outputTokens: Int): IssueReportDiagnostics = requireNotNull(
        IssueReportDiagnostics.fromFields(
            "CHAT_FAILURE",
            mapOf("diagnostic_area" to "PROVIDER", "model_hash" to "a".repeat(64), "output_tokens" to outputTokens, "finish_reason" to "LENGTH"),
            at
        )
    )

    private fun assertNoCapture(preview: IssueReport, area: String) {
        val capture = requireNotNull(preview.context?.diagnostics)
        assertEquals(area, capture.area)
        assertEquals("NO_CAPTURE", capture.captureMode)
        assertTrue(capture.values.isEmpty())
        assertTrue(capture.unavailableFields.isNotEmpty())
        assertTrue(capture.isValid())
    }
}
