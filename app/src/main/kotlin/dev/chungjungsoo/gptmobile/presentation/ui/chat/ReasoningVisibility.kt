package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType

internal fun processTimelineForDisplay(timeline: List<AssistantTimelineItem>, showReasoning: Boolean, debugMode: Boolean) = timeline.filter {
    when (it.type) {
        AssistantTimelineItemType.THINKING, AssistantTimelineItemType.TEXT -> showReasoning
        AssistantTimelineItemType.TOOL -> debugMode
        AssistantTimelineItemType.NOTICE -> false
        AssistantTimelineItemType.LEGACY_ORDER -> true
    }
}
