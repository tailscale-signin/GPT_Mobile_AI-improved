package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent

/** Render the stored arrival sequence once. Updates never relocate earlier events. */
@Composable
internal fun AssistantChronologicalContent(
    timeline: List<AssistantTimelineItem>,
    toolEvents: List<ToolEvent>,
    fallbackText: String,
    fallbackThoughts: String,
    contentIdentity: Any,
    isLoading: Boolean,
    debugMode: Boolean,
    showReasoning: Boolean
) {
    val events = toolEvents.associateBy { it.sequence }
    val legacy = timeline.isEmpty() || timeline.any { it.type == AssistantTimelineItemType.LEGACY_ORDER }
    val items = if (legacy) {
        buildList {
            if (showReasoning && fallbackThoughts.isNotBlank()) add(AssistantTimelineItem(AssistantTimelineItemType.THINKING, fallbackThoughts))
            addAll(toolEvents.sortedBy { it.sequence }.map { AssistantTimelineItem(AssistantTimelineItemType.TOOL, toolSequence = it.sequence) })
            if (fallbackText.isNotBlank()) add(AssistantTimelineItem(AssistantTimelineItemType.TEXT, fallbackText))
            addAll(timeline.filter { it.type == AssistantTimelineItemType.NOTICE })
        }
    } else {
        timeline
    }
    Column(Modifier.fillMaxWidth().padding(8.dp)) {
        if (legacy && (toolEvents.isNotEmpty() || (fallbackThoughts.isNotBlank() && fallbackText.isNotBlank()))) {
            Text(stringResource(R.string.legacy_assistant_order_unavailable), style = MaterialTheme.typography.labelSmall)
        }
        items.forEachIndexed { index, item ->
            key(contentIdentity, index, item.type, item.toolSequence) {
                when (item.type) {
                    AssistantTimelineItemType.THINKING -> if (showReasoning) {
                        ThinkingBlock(
                            thoughts = item.content,
                            contentIdentity = "$contentIdentity:$index",
                            isLoading = isLoading && index == items.lastIndex
                        )
                    }
                    AssistantTimelineItemType.TEXT -> {
                        val parsed = androidx.compose.runtime.remember(item.content) { dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser.extractThinking(item.content) }
                        if (showReasoning && !parsed.thinking.isNullOrBlank()) ThinkingBlock(thoughts = parsed.thinking.orEmpty(), contentIdentity = "$contentIdentity:embedded:$index")
                        if (parsed.response.isNotBlank()) ChatMarkdown(content = parsed.response, contentIdentity = "$contentIdentity:$index", modifier = Modifier.padding(vertical = 8.dp))
                    }
                    AssistantTimelineItemType.TOOL -> events[item.toolSequence]?.let { event ->
                        InlineExecutionTrace(listOf(event), listOf(item), "$contentIdentity:$index", debugMode)
                    }
                    AssistantTimelineItemType.NOTICE -> if (item.recalledFacts.isNotEmpty()) {
                        InlineExecutionTrace(emptyList(), listOf(item), "$contentIdentity:$index", debugMode)
                    } else if (item.content.isNotBlank()) {
                        if (item.progressCheckpoint) {
                            ThinkingBlock(
                                thoughts = item.content,
                                title = stringResource(R.string.timeline_progress),
                                initiallyExpanded = true,
                                contentIdentity = "$contentIdentity:$index"
                            )
                        } else {
                            Text(item.content, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(vertical = 8.dp))
                        }
                    }
                    AssistantTimelineItemType.LEGACY_ORDER -> Unit
                }
            }
        }
        if (isLoading) Text("• • •", color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(12.dp))
    }
}
