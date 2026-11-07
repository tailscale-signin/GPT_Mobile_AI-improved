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
