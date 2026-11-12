package dev.chungjungsoo.gptmobile.data.diagnostics

import android.app.Application
import java.io.File
import java.util.UUID
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class IssueReportOutboxTest {
    @get:Rule val files = TemporaryFolder()
    private val report = IssueReport("75d84431-c695-4ff4-973b-3a80d63d5993", "manual", "1.0", 1, "abcdef0", "16", 36, "Fixture", title = "Retained report", description = "The observed failure 😀")

    @Test fun approvedPayloadSurvivesRestartAndLostConfirmationWithoutCreatingANewIdentity() = runTest {
        val directory = files.newFolder()
        var clock = 1_000L
        var outbox = IssueReportOutbox(directory) { clock }
        val approved = outbox.approve(report)
        assertEquals(report.approvedPayload(), approved.payload)
        val sent = mutableListOf<Pair<String, String>>()
        assertEquals(
            IssueReportDelivery.Result.RETRY,
            IssueReportDelivery.attempt(outbox, report.reportId, { id, payload ->
                sent += id to payload
                IssueReportSubmission.Failure("REPORT_CONNECTION_FAILED", retryable = true)
            }, now = clock)
        )
        outbox = IssueReportOutbox(directory) { clock }
        assertEquals(1, outbox.load(report.reportId)?.automaticAttempts)
        clock += 60_000
        assertEquals(
            IssueReportDelivery.Result.CONFIRMED,
            IssueReportDelivery.attempt(outbox, report.reportId, { id, payload ->
                sent += id to payload
                IssueReportSubmission.Confirmed(742)
            }, now = clock)
        )
        assertEquals(listOf(report.reportId to approved.payload, report.reportId to approved.payload), sent)
        val receipt = requireNotNull(IssueReportOutbox(directory).load(report.reportId))
        assertEquals(IssueReportOutbox.State.SENT, receipt.state)
        assertEquals(742, receipt.issueNumber)
        assertEquals(IssueReportDelivery.Result.CONFIRMED, IssueReportDelivery.attempt(outbox, report.reportId, { _, _ -> error("Already confirmed report must not dispatch") }))
        assertThrows(IllegalStateException::class.java) { outbox.approve(report.copy(description = "Changed after approval")) }
    }

    @Test fun processDeathAtTheAttemptCeilingRequiresAnExplicitSameReportRetry() = runTest {
        val directory = files.newFolder()
        val outbox = IssueReportOutbox(directory)
        val approved = outbox.approve(report)
        repeat(IssueReportDelivery.MAX_AUTOMATIC_ATTEMPTS) { outbox.sending(report.reportId) }
        val restarted = IssueReportOutbox(directory)
        var sends = 0
        val send: (String, String) -> IssueReportSubmission.Outcome = { id, payload ->
            sends++
            assertEquals(report.reportId, id)
            assertEquals(approved.payload, payload)
            IssueReportSubmission.Confirmed(742)
        }
        assertEquals(IssueReportDelivery.Result.FAILED, IssueReportDelivery.attempt(restarted, report.reportId, send))
        assertEquals(0, sends)
        assertTrue(requireNotNull(restarted.load(report.reportId)).retryable)
        restarted.queue(report.reportId, manualRetry = true)
        assertEquals(IssueReportDelivery.Result.CONFIRMED, IssueReportDelivery.attempt(restarted, report.reportId, send))
        assertEquals(1, sends)
    }

    @Test fun retryAfterSurvivesRestartAndManualRetryCannotAdvanceItsDeadline() = runTest {
        val directory = files.newFolder()
        val outbox = IssueReportOutbox(directory)
        outbox.approve(report)
        var sends = 0
        assertEquals(
            IssueReportDelivery.Result.RETRY,
            IssueReportDelivery.attempt(outbox, report.reportId, { _, _ ->
                sends++
                IssueReportSubmission.Failure("RATE_LIMITED", true, 429, 100_000L)
            }, now = 1_000L)
        )
        val restarted = IssueReportOutbox(directory)
        restarted.queue(report.reportId, manualRetry = true)
        val retry: (String, String) -> IssueReportSubmission.Outcome = { _, _ ->
            sends++
            IssueReportSubmission.Confirmed(742)
        }
        assertEquals(IssueReportDelivery.Result.RETRY, IssueReportDelivery.attempt(restarted, report.reportId, retry, now = 99_999L))
        assertEquals(1, sends)
        assertEquals(100_000L, restarted.load(report.reportId)?.retryNotBeforeMillis)
        assertEquals(IssueReportDelivery.Result.CONFIRMED, IssueReportDelivery.attempt(restarted, report.reportId, retry, now = 100_000L))
        assertEquals(2, sends)
    }

    @Test fun permanentRejectionAndMissingServiceRemainSavedWithoutAutomaticResubmission() = runTest {
        for (failure in listOf(IssueReportSubmission.Failure("INVALID_REPORT", false, 422), IssueReportSubmission.Failure("REPORT_SERVICE_UNCONFIGURED", true, automaticRetry = false))) {
            val outbox = IssueReportOutbox(files.newFolder())
            val approved = outbox.approve(report)
            assertEquals(IssueReportDelivery.Result.FAILED, IssueReportDelivery.attempt(outbox, report.reportId, { _, _ -> failure }))
            val saved = requireNotNull(outbox.load(report.reportId))
            assertEquals(IssueReportOutbox.State.FAILED, saved.state)
            assertEquals(failure.retryable, saved.retryable)
            assertEquals(approved.payload, saved.payload)
            assertEquals(IssueReportDelivery.Result.FAILED, IssueReportDelivery.attempt(outbox, report.reportId, { _, _ -> error("Stopped reports require explicit Retry") }))
        }
    }

    @Test fun corruptStoredPayloadCannotPostUnderAnotherReportIdentity() = runTest {
        val directory = files.newFolder()
        val outbox = IssueReportOutbox(directory)
        val approved = outbox.approve(report)
        val wrong = approved.copy(payload = report.copy(reportId = UUID.randomUUID().toString()).approvedPayload())
        File(directory, "${report.reportId}.json").writeText(IssueReport.json.encodeToString(IssueReportOutbox.Entry.serializer(), wrong))
        assertThrows(IllegalStateException::class.java) { outbox.load(report.reportId) }
        var sends = 0
        val outcome = runCatching {
            IssueReportDelivery.attempt(outbox, report.reportId, { _, _ ->
                sends++
                IssueReportSubmission.Confirmed(742)
            })
        }
        assertTrue(outcome.isFailure)
        assertEquals(0, sends)
    }

    @Test fun capacityNeverDiscardsPendingReportsAndConfirmedHistoryIsBounded() {
        val outbox = IssueReportOutbox(files.newFolder())
        val pending = (1..IssueReportOutbox.MAX_PENDING_REPORTS).map { report.copy(reportId = UUID.randomUUID().toString()) }
        pending.forEach { outbox.approve(it) }
        val failure = assertThrows(IllegalStateException::class.java) { outbox.approve(report) }
        assertEquals(IssueReportOutbox.CAPACITY_MESSAGE, failure.message)
        assertEquals(pending.map { it.reportId }.toSet(), outbox.entries().map { it.reportId }.toSet())
        outbox.sent(pending.first().reportId, 742)
        outbox.approve(report)
        assertEquals(IssueReportOutbox.MAX_PENDING_REPORTS, outbox.entries().count { it.state != IssueReportOutbox.State.SENT })

        val history = IssueReportOutbox(files.newFolder())
        history.approve(report)
        repeat(IssueReportOutbox.MAX_CONFIRMED_REPORTS + 3) { index ->
            val item = report.copy(reportId = UUID.randomUUID().toString())
            history.approve(item)
            history.sent(item.reportId, index + 1)
        }
        assertEquals(IssueReportOutbox.MAX_CONFIRMED_REPORTS, history.entries().count { it.state == IssueReportOutbox.State.SENT })
        assertEquals(report.approvedPayload(), history.load(report.reportId)?.payload)
        assertFalse(history.entries().none { it.reportId == report.reportId })
    }
}
