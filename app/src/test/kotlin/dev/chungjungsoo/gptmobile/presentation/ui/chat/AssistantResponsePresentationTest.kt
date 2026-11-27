package dev.chungjungsoo.gptmobile.presentation.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AssistantResponsePresentationTest {
    @Test fun terminalFailureRemainsVisibleBeforeTheTimelineNoticeIsRestored() {
        assertEquals("Original backend failure", visibleResponseFailure(null, "Original backend failure", "FAILED"))
        assertEquals("Retained tool result needs a final answer", visibleResponseFailure("Retained tool result needs a final answer", null, "PARTIAL"))
        assertNull(visibleResponseFailure("Earlier failure", "Earlier failure", "COMPLETED"))
    }

    @Test fun processDeathWithoutATimelineErrorStillOffersRecoveryAndDiscardRemovesTheControl() {
        assertTrue(visibleResponseFailure(null, null, "INTERRUPTED").orEmpty().contains("saved"))
        assertNull(visibleResponseFailure("SERVICE_STOPPED", null, "CANCELED"))
        assertNull(visibleResponseFailure(null, null, "RUNNING"))
    }

    @Test fun transportAndTimeoutFailuresExposeCheckpointResumeAcrossTerminalStatuses() {
        for (code in listOf("NETWORK_TIMEOUT", "NETWORK_DISCONNECTED", "DNS_RESOLUTION_FAILED", "CONNECTION_ABORTED", "TRANSPORT_EOF", "PROVIDER_UNAVAILABLE", "GATEWAY_OUTCOME_UNKNOWN", "TOOL_OUTCOME_UNKNOWN")) {
            val failure = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider("", code)
            for (status in listOf("FAILED", "PARTIAL", "EVIDENCE_MISSING", "BLOCKED")) {
                assertTrue("$status $code", showsInterruptedResponseControls(status, failure))
            }
        }
        assertTrue(showsInterruptedResponseControls("INTERRUPTED", null))
    }

    @Test fun resumePresentationCannotReviveActiveCanceledCompletedOrRejectedWork() {
        val timeout = dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.classify("NETWORK_TIMEOUT")
        for (status in listOf("RUNNING", "QUEUED", "WAITING_PROVIDER_LIMIT", "WAITING_FOR_APPROVAL", "CANCELED", "COMPLETED")) {
            assertFalse(status, showsInterruptedResponseControls(status, timeout))
        }
        for (code in listOf("GITHUB_CHOICE_PENDING", "AUTHENTICATION_FAILED", "PRIVACY_CONFIGURATION", "PROVIDER_CONTENT_POLICY", "GITHUB_TASK_IDENTITY_CHANGED", "PROVIDER_WAIT_EVIDENCE_UNAVAILABLE")) {
            assertFalse(code, showsInterruptedResponseControls("FAILED", dev.chungjungsoo.gptmobile.data.agent.ProfileFailureRecord.fromProvider("", code)))
        }
    }

    @Test fun renderingUsesBoundedPagesBeforeTheDurableStorageLimit() {
        assertFalse(requiresPagedAssistantResponse(4096))
        assertTrue(requiresPagedAssistantResponse(4097))
        assertTrue(requiresPagedAssistantResponse(5892))
        assertTrue(requiresPagedAssistantResponse(32_768))
    }

    @Test fun completedReportUsesSmallerVisualPagesWithoutDroppingTextOrChangingStoragePages() {
        val report = ("## Verified evidence\n\nA measured result with a complete receipt.\n\n".repeat(100)).take(5892)
        val frames = dev.chungjungsoo.gptmobile.data.agent.CombinedMarkdownPaging.frames(report, ASSISTANT_RENDER_PAGE_CHARACTERS).toList()
        assertTrue(frames.size >= 3)
        assertTrue(frames.all { it.end - it.start <= ASSISTANT_RENDER_PAGE_CHARACTERS })
        assertEquals(report, frames.joinToString("") { report.substring(it.start, it.end) })
        assertEquals(1, dev.chungjungsoo.gptmobile.data.agent.CombinedPayload.pages(report).count())
    }
}
