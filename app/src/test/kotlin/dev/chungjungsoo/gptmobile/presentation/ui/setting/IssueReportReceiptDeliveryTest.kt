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
    private val url = "https://github.com/example/app/issues/new?title=Retained&body=Original"

    @Test fun failedBrowserLaunchRetainsTheExactDraftUntilRetrySucceeds() = runTest {
        val prompts = Channel<Boolean>(Channel.UNLIMITED)
        val choices = Channel<Boolean>(Channel.UNLIMITED)
        val opened = mutableListOf<String>()
        var acknowledged = false
        val delivery = launch {
            offerIssueReportFallback(
                url,
                prompt = { unavailable ->
                    prompts.send(unavailable)
                    choices.receive()
                },
                openUri = {
                    opened += it
                    if (opened.size == 1) throw IllegalStateException("No browser")
                }
            )
            acknowledged = true
        }
        assertFalse(prompts.receive())
        choices.send(true)
        assertTrue(prompts.receive())
        assertFalse(acknowledged)
        assertEquals(listOf(url), opened)
        choices.send(true)
        delivery.join()
        assertTrue(acknowledged)
        assertEquals(listOf(url, url), opened)
    }

    @Test fun explicitDismissalStopsRetryingTheUnavailableBrowser() = runTest {
        val prompts = mutableListOf<Boolean>()
        var attempts = 0
        offerIssueReportFallback(
            url,
            prompt = {
                prompts += it
                !it
            },
            openUri = {
                attempts++
                throw IllegalStateException("No browser")
            }
        )
        assertEquals(listOf(false, true), prompts)
        assertEquals(1, attempts)
    }

    @Test fun leavingTheScreenKeepsTheFailedReceiptUnacknowledged() = runTest {
        val prompts = Channel<Boolean>(Channel.UNLIMITED)
        val choices = Channel<Boolean>(Channel.UNLIMITED)
        var acknowledged = false
        val delivery = launch {
            offerIssueReportFallback(
                url,
                prompt = { unavailable ->
                    prompts.send(unavailable)
                    choices.receive()
                },
                openUri = { throw IllegalStateException("No browser") }
            )
            acknowledged = true
        }
        assertFalse(prompts.receive())
        choices.send(true)
        assertTrue(prompts.receive())
        delivery.cancel()
        delivery.join()
        assertFalse(acknowledged)
    }

    @Test fun browserCancellationIsNotConvertedToAnotherRetry() = runTest {
        var prompts = 0
        var cancelled = false
        try {
            offerIssueReportFallback(
                url,
                prompt = {
                    prompts++
                    true
                },
                openUri = { throw CancellationException("Cancelled") }
            )
        } catch (_: CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled)
        assertEquals(1, prompts)
    }
}
