package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Test

class UniversalChatActivityFeedTest {
    @Test fun equivalentPublicCheckpointsDoNotRepeat() {
        fun step(text: String) = AssistantTimelineItem(AssistantTimelineItemType.NOTICE, text, progressCheckpoint = true, statusSummary = true)
        assertEquals(
            listOf("Reading source files."),
            universalChatPublicCheckpoints(listOf(step("Reading source files."), step("READING   SOURCE FILES...")))
        )
        org.junit.Assert.assertTrue(universalActivityOpening("Compare two alternatives").startsWith("Organizing the comparison"))
    }

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
        val internalMetrics = notice("Internal tool latency details").copy(
            toolMetrics = dev.chungjungsoo.gptmobile.data.agent.ToolPayloadMetrics(durationMs = 20)
        )
        val unmarked = notice("Private reasoning").copy(progressCheckpoint = false)
        val thought = AssistantTimelineItem(AssistantTimelineItemType.THINKING, "Private thought", progressCheckpoint = true)
        assertEquals(
            listOf("Searching relevant references"),
            universalChatPublicCheckpoints(listOf(visible, visible, private, diagnostic, internalMetrics, unmarked, thought))
        )
    }

    @Test fun generationHeaderUsesOnlyPublicPreviewNotPrivateStatus() {
        val private = AssistantTimelineItem(
            AssistantTimelineItemType.NOTICE,
            "Private delegation result: secret",
            statusSummary = true,
            delegationInvocationId = "delegate"
        )
        val visible = AssistantTimelineItem(
            AssistantTimelineItemType.NOTICE,
            "Reviewing cited sources",
            progressCheckpoint = true,
            statusSummary = true
        )
        assertEquals("Reviewing cited sources", generationActivityLabel(emptyList(), listOf(visible, private)))
        assertEquals("Preparing response", generationActivityLabel(emptyList(), listOf(private)))
    }

    @Test fun richSummaryKeepsOnlyItsFirstPublicHeadline() {
        val summary = AssistantTimelineItem(
            AssistantTimelineItemType.NOTICE,
            "### Research findings\n- Three sources were checked",
            progressCheckpoint = true,
            statusSummary = true
        )
        assertEquals(listOf("Research findings"), universalChatPublicCheckpoints(listOf(summary)))
    }
}
