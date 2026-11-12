package dev.chungjungsoo.gptmobile.presentation.ui.setting

import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportOutbox
import dev.chungjungsoo.gptmobile.data.diagnostics.IssueReportRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IssueReportReceiptDeliveryTest {
    private val reportId = "75d84431-c695-4ff4-973b-3a80d63d5993"
    private val github = IssueReportRoute(IssueReportRoute.Kind.GITHUB, connectionUid = "approved-account", accountId = "42", accountLogin = "approved-author")
    private val service = IssueReportRoute(IssueReportRoute.Kind.SERVICE, origin = "https://approved-reports.example.invalid")

    @Test fun disabledGitHubCannotOfferConfirmationThroughAnotherSelectedRoute() {
        val saved = saved(github, IssueReportOutbox.DeliveryEvidence.MAY_HAVE_BEEN_SUBMITTED)
        assertFalse(issueReportRetryDestinationAvailable(saved, service, githubEnabled = false, serviceEnabled = true))
        assertTrue(issueReportRetryDestinationAvailable(saved, service, githubEnabled = true, serviceEnabled = true))
        assertEquals(github, saved.route)
        assertTrue(saved.mayHaveBeenSubmitted)
    }

    @Test fun aSavedServiceReportNeedsItsRouteEnabledWithoutSubstitutingGitHub() {
        val saved = saved(service, IssueReportOutbox.DeliveryEvidence.MAY_HAVE_BEEN_SUBMITTED)
        assertFalse(issueReportRetryDestinationAvailable(saved, github, githubEnabled = true, serviceEnabled = false))
        assertTrue(issueReportRetryDestinationAvailable(saved, github, githubEnabled = false, serviceEnabled = true))
        assertEquals(service, saved.route)
    }

    @Test fun anUndispatchedReportCanUseAnExplicitlySelectedEnabledDestination() {
        val saved = saved(github, IssueReportOutbox.DeliveryEvidence.NOT_DISPATCHED)
        assertTrue(issueReportRetryDestinationAvailable(saved, service, githubEnabled = false, serviceEnabled = true))
        assertFalse(issueReportRetryDestinationAvailable(saved, github, githubEnabled = false, serviceEnabled = true))
        assertFalse(issueReportRetryDestinationAvailable(saved, null, githubEnabled = true, serviceEnabled = true))
    }

    private fun saved(route: IssueReportRoute, evidence: IssueReportOutbox.DeliveryEvidence) = IssueReportOutbox.Entry(
        reportId = reportId,
        payload = "approved bytes",
        state = IssueReportOutbox.State.FAILED,
        storedAtMillis = 1,
        updatedAtMillis = 2,
        route = route,
        deliveryEvidence = evidence
    )

    @Test fun failedNativeQueueRetainsTheExactApprovedReportUntilRetrySucceeds() = runTest {
        val prompts = Channel<Boolean>(Channel.UNLIMITED)
        val choices = Channel<Boolean>(Channel.UNLIMITED)
        val retried = mutableListOf<String>()
        var acknowledged = false
        val delivery = launch {
            offerIssueReportRetry(reportId, prompt = { unavailable ->
                prompts.send(unavailable)
                choices.receive()
            }, retry = {
                retried += it
                if (retried.size == 1) throw IllegalStateException("Queue unavailable")
                true
            })
            acknowledged = true
        }
        assertFalse(prompts.receive())
        choices.send(true)
        assertTrue(prompts.receive())
        assertFalse(acknowledged)
        assertEquals(listOf(reportId), retried)
        choices.send(true)
        delivery.join()
        assertTrue(acknowledged)
        assertEquals(listOf(reportId, reportId), retried)
    }

    @Test fun explicitDismissalDoesNotSubmitAnotherReport() = runTest {
        val prompts = mutableListOf<Boolean>()
        var attempts = 0
        offerIssueReportRetry(reportId, prompt = {
            prompts += it
            !it
        }, retry = {
            attempts++
            false
        })
        assertEquals(listOf(false, true), prompts)
        assertEquals(1, attempts)
    }

    @Test fun leavingTheScreenKeepsTheFailedReceiptUnacknowledged() = runTest {
        val prompts = Channel<Boolean>(Channel.UNLIMITED)
        val choices = Channel<Boolean>(Channel.UNLIMITED)
        var acknowledged = false
        val delivery = launch {
            offerIssueReportRetry(reportId, prompt = { unavailable ->
                prompts.send(unavailable)
                choices.receive()
            }, retry = { false })
            acknowledged = true
        }
        assertFalse(prompts.receive())
        choices.send(true)
        assertTrue(prompts.receive())
        delivery.cancel()
        delivery.join()
        assertFalse(acknowledged)
    }

    @Test fun cancellationIsNotConvertedToAnotherRetry() = runTest {
        var prompts = 0
        var cancelled = false
        try {
            offerIssueReportRetry(reportId, prompt = {
                prompts++
                true
            }, retry = { throw CancellationException("Cancelled") })
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, prompts)
    }
}
