package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation

/** Repair ordering in retained timelines too: introduce the activity before any preview. */
internal fun gitHubActivityLines(timeline: List<AssistantTimelineItem>, task: String, searchActive: Boolean = false): List<AssistantTimelineItem> {
    val previews = mutableSetOf<String>()
    val summaries = mutableSetOf<String>()
    val latestCounters = timeline.lastOrNull { it.progressCheckpoint && GitHubProgressPresentation.liveKey(it.content) != null }
    val lines = timeline.filter { it.progressCheckpoint && it.content.isNotBlank() }.filter { item ->
        if (GitHubProgressPresentation.liveKey(item.content) != null) {
            item === latestCounters
        } else if (item.statusSummary) {
            summaries.add(GitHubProgressPresentation.lineKey(item.content))
        } else {
            val key = GitHubProgressPresentation.lineKey(item.content)
            previews.add(key)
        }
    }
    val coalesced = lines.map { item ->
        GitHubProgressPresentation.liveKey(item.content)?.let { key -> item.copy(content = item.content.substringBefore(". "), progressId = key, statusSummary = false) } ?: item
    }
    val summary = coalesced.indexOfFirst { it.statusSummary }
    val ordered = when {
        summary == 0 -> coalesced
        summary > 0 -> listOf(coalesced[summary]) + coalesced.take(summary) + coalesced.drop(summary + 1)
        else -> listOf(
            AssistantTimelineItem(
                type = AssistantTimelineItemType.NOTICE,
                content = GitHubProgressPresentation.introduction(task),
                progressCheckpoint = true,
                statusSummary = true,
                progressId = "github-activity-introduction"
            )
        ) + coalesced
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

/** Count displayed text lines, preserving a leading summary in the recent window. */
internal fun gitHubVisibleActivityLines(lines: List<AssistantTimelineItem>, compact: Boolean, lineCounts: List<Int> = lines.map { it.content.lines().size }): List<AssistantTimelineItem> {
    require(lineCounts.size == lines.size)
    if (!compact || lineCounts.sum() <= 20) return lines
    var start = lines.lastIndex
    var count = lineCounts.last()
    while (start > 0 && count + lineCounts[start - 1] <= 20) {
        start--
        count += lineCounts[start]
    }
    if (lines[start].statusSummary) return lines.drop(start)
    val summaryIndex = lines.take(start).indexOfLast { it.statusSummary }
    if (summaryIndex < 0) return lines.drop(start)
    while (start < lines.lastIndex && count + lineCounts[summaryIndex] > 20) {
        count -= lineCounts[start++]
    }
    return listOf(lines[summaryIndex]) + lines.drop(start)
}
