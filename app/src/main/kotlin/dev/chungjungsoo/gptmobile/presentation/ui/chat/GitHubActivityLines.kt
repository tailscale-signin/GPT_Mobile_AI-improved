package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import java.util.Locale

/** Repair ordering in retained timelines too: introduce the activity before any preview. */
internal fun gitHubActivityLines(timeline: List<AssistantTimelineItem>, task: String, searchActive: Boolean = false): List<AssistantTimelineItem> {
    val previews = mutableSetOf<String>()
    val summaries = mutableSetOf<String>()
    val counterPositions = mutableMapOf<String, Int>()
    val coalesced = mutableListOf<AssistantTimelineItem>()
    timeline.filter { it.progressCheckpoint && it.content.isNotBlank() }.forEach { item ->
        val counterKey = GitHubProgressPresentation.liveKey(item.content).takeUnless { item.statusSummary }
        when {
            counterKey != null -> {
                val current = item.copy(content = item.content.substringBefore(". "), progressId = counterKey, statusSummary = false)
                val position = counterPositions[counterKey]
                if (position == null) {
                    counterPositions[counterKey] = coalesced.size
                    coalesced.add(current)
                } else {
                    coalesced[position] = current
                }
            }
            item.statusSummary -> {
                // Summaries are already authored public prose. Do not collapse distinct
                // model explanations through the one-line preview vocabulary.
                val key = item.content.replace(Regex("\\s+"), " ").trim().lowercase(Locale.ROOT)
                if (summaries.add(key)) coalesced.add(item)
            }
            previews.add(GitHubProgressPresentation.lineKey(item.content)) -> coalesced.add(item)
        }
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
                modelAuthored = false,
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

/** Count entries, independently of line wrapping or font size, retaining summary context. */
internal fun gitHubVisibleActivityLines(lines: List<AssistantTimelineItem>, compact: Boolean, entryLimit: Int = 20): List<AssistantTimelineItem> {
    require(entryLimit >= 2)
    if (!compact || lines.size <= entryLimit) return lines
    val start = lines.size - entryLimit
    val summaryIndex = lines.indices.take(start + 1).lastOrNull { lines[it].statusSummary }
    val counters = lines.indices.filter { !lines[it].statusSummary && GitHubProgressPresentation.liveKey(lines[it].content) != null }
    val pinned = (listOfNotNull(summaryIndex) + counters.takeLast((entryLimit - 1).coerceAtLeast(0))).toSet()
    val recent = lines.indices.filter { it !in pinned }.takeLast(entryLimit - pinned.size)
    return (listOfNotNull(summaryIndex) + (pinned + recent).filter { it != summaryIndex }.sorted()).map(lines::get)
}
