package dev.chungjungsoo.gptmobile.presentation.ui.setting

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
