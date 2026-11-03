package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation

/** Repair ordering in retained timelines too: introduce the activity before any preview. */
internal fun gitHubActivityLines(timeline: List<AssistantTimelineItem>, task: String, searchActive: Boolean = false): List<AssistantTimelineItem> {
    var previousPreview: String? = null
    var previousSummary: String? = null
    val lines = timeline.filter { it.progressCheckpoint && it.content.isNotBlank() }.filter { item ->
        if (item.statusSummary) {
            val keep = previousSummary != item.content
            previousSummary = item.content
            keep
        } else {
            val key = GitHubProgressPresentation.lineKey(item.content)
            val keep = key != previousPreview
            previousPreview = key
            keep
        }
    }
    val summary = lines.indexOfFirst { it.statusSummary }
    val ordered = when {
        summary == 0 -> lines
        summary > 0 -> listOf(lines[summary]) + lines.take(summary) + lines.drop(summary + 1)
        else -> listOf(
            AssistantTimelineItem(
                type = AssistantTimelineItemType.NOTICE,
                content = GitHubProgressPresentation.introduction(task),
                progressCheckpoint = true,
                statusSummary = true,
                progressId = "github-activity-introduction"
            )
        ) + lines
    }
    return if (searchActive && ordered.last().statusSummary) {
        ordered + AssistantTimelineItem(
            type = AssistantTimelineItemType.NOTICE,
            content = "Searching websites",
            progressCheckpoint = true,
            progressId = "github-search-preview"
        )
    } else {
        ordered
    }
}

/** A compact preview window always retains its preceding summary. */
internal fun gitHubVisibleActivityLines(lines: List<AssistantTimelineItem>, compact: Boolean): List<AssistantTimelineItem> {
    if (!compact || lines.size <= 3) return lines
    val start = lines.size - 3
    val tail = lines.drop(start)
    return if (tail.first().statusSummary) tail else listOfNotNull(lines.take(start).lastOrNull { it.statusSummary }) + tail
}
