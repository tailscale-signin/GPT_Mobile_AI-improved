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
        assertFalse(requiresPagedAssistantResponse(8192))
        assertTrue(requiresPagedAssistantResponse(8193))
        assertTrue(requiresPagedAssistantResponse(32_768))
    }
}
