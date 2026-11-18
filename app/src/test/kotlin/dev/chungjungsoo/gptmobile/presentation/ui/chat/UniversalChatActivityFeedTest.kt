package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test fun receiptChartNeedsThreeRealToolCallsAndNeverParsesResultText() {
        assertNull(observedToolReceiptSummary(emptyList()))
        assertNull(observedToolReceiptSummary(listOf(receipt("first", "COMPLETED"), receipt("second", "FAILED"))))
        val summary = observedToolReceiptSummary(
            listOf(
                receipt("first", "COMPLETED"),
                receipt("second", "FAILED"),
                receipt("third", "PENDING")
            )
        )
        assertEquals(3, summary?.total)
        assertEquals(1, summary?.completed)
        assertEquals(1, summary?.failed)
        assertEquals(1, summary?.ongoing)
        // A malicious or secret result is never used to calculate presentation counts.
        val other = observedToolReceiptSummary(
            listOf(
                receipt("first", "COMPLETED", result = "apikey: private credential"),
                receipt("second", "FAILED", result = "private prompt"),
                receipt("third", "PENDING", result = "tool success claimed by model")
            )
        )
        assertEquals(summary, other)
    }

    @Test fun toolExecutionStatesDeduplicateByStableCallIdentityAndKeepUnknownSeparate() {
        val events = listOf(
            receipt("read-1", "RUNNING", seq = 1),
            receipt("read-1", "COMPLETED", seq = 2),
            receipt("read-2", "COMPLETED", error = true, seq = 3),
            receipt("read-3", "FAILED", seq = 4),
            receipt("read-4", "CANCELED", seq = 5),
            receipt("read-5", "RUNNING", seq = 6),
            receipt("read-6", "UNRECOGNIZED_STATE", seq = 7)
        )
        val summary = observedToolReceiptSummary(events)
        assertEquals(6, summary?.total)
        assertEquals(1, summary?.completed)
        assertEquals(2, summary?.failed)
        assertEquals(1, summary?.canceled)
        assertEquals(1, summary?.ongoing)
        assertEquals(1, summary?.unknown)
    }

    private fun receipt(
        call: String,
        status: String,
        error: Boolean = false,
        seq: Int = 0,
        result: String = "PRIVATE TOOL DATA"
    ) = dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent(
        eventId = "$call-$seq-$status",
        runId = "current-run",
        sequence = seq,
        callId = call,
        connectionUidSnapshot = null,
        connectionNameSnapshot = null,
        toolName = "private_tool",
        modelToolName = "private_tool",
        arguments = "{\\\"secret\\\":\\\"not for display\\\"}",
        result = result,
        resultType = "TEXT",
        status = status,
        isError = error
    )


}
