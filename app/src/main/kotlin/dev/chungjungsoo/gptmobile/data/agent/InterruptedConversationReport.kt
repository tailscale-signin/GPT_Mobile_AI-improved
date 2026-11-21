package dev.chungjungsoo.gptmobile.data.agent

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType

/** Only public progress and recorded tool sequence numbers may appear in an interrupted answer. */
internal fun interruptedConversationReport(timeline: List<AssistantTimelineItem>): String {
    val progress = timeline.lastOrNull {
        it.type == AssistantTimelineItemType.NOTICE &&
            (it.progressCheckpoint || it.statusSummary) &&
            it.content.isNotBlank() &&
            it.delegationInvocationId == null &&
            it.recalledFacts.isEmpty() &&
            it.personalData.isEmpty()
    }?.content?.trim()?.take(1200)
    val operations = timeline.asSequence().filter { it.type == AssistantTimelineItemType.TOOL }.mapNotNull { it.toolSequence }.distinct().count()
    return buildString {
        append("Work was interrupted before a final answer was saved.")
        if (progress != null) append("\n\nLast saved update\n").append(progress)
        if (operations > 0) append("\n\nThe activity history contains ").append(operations).append(if (operations == 1) " tool entry." else " tool entries.")
    }
}
