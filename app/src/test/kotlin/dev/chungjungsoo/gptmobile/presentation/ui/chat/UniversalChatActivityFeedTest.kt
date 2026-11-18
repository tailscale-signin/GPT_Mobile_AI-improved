package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Test

class UniversalChatActivityFeedTest {
    @Test fun publicProgressOnlyNeverProjectsPrivateDelegationOrDiagnostics() {
        fun notice(text: String) = AssistantTimelineItem(
            AssistantTimelineItemType.NOTICE,
            text,
            progressCheckpoint = true,
            modelAuthored = true
        )
        val visible = notice("Searching relevant references")
        val private = notice("Internal delegation response").copy(delegationInvocationId = "private")
        val diagnostic = notice("Context estimate: 20000")
        val unmarked = notice("Private reasoning").copy(progressCheckpoint = false)
        val thought = AssistantTimelineItem(AssistantTimelineItemType.THINKING, "Private thought", progressCheckpoint = true)
        assertEquals(
            listOf("Searching relevant references"),
            universalChatPublicCheckpoints(listOf(visible, visible, private, diagnostic, unmarked, thought))
        )
    }

    @Test fun richSummaryKeepsOnlyItsFirstPublicHeadline() {
        val summary = AssistantTimelineItem(
            AssistantTimelineItemType.NOTICE,
            "### Research findings\\n- Three sources were checked",
            progressCheckpoint = true,
            statusSummary = true
        )
        assertEquals(listOf("Research findings"), universalChatPublicCheckpoints(listOf(summary)))
    }
}
