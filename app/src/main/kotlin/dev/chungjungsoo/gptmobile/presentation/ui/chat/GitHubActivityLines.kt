package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import java.util.Locale

private val summaryWhitespace = Regex("\\s+")
private val legacyActivitySummary = Regex("^I have (?:completed [0-9]+ repository operations\\.|received the request; no repository operation has completed yet\\.) ")

/** Only explicitly public prose can replace a generic activity summary, including retained messages. */
internal fun gitHubPublicSummary(timeline: List<AssistantTimelineItem>): AssistantTimelineItem? =
    timeline.asReversed().firstNotNullOfOrNull(::publicProgressSummary)

private fun publicProgressSummary(item: AssistantTimelineItem): AssistantTimelineItem? =
    if (item.type != AssistantTimelineItemType.NOTICE ||
        !item.progressCheckpoint ||
        !item.modelAuthored ||
        item.delegationInvocationId != null ||
        item.delegationProfile != null ||
        item.delegatedTool ||
        item.toolSequence != null ||
        item.toolMetrics != null ||
        item.recalledFacts.isNotEmpty()
    ) {
        null
    } else {
        val publicText = dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.text(item.content)
        item.copy(content = publicText, statusSummary = true).takeIf { publicText.isNotBlank() }
    }

private fun legacyActivitySummary(text: String): Boolean {
    val normalized = text.replace(summaryWhitespace, " ").trim()
    return legacyActivitySummary.containsMatchIn(normalized) &&
        normalized.contains("Next, I will ") &&
        normalized.endsWith("Any remaining checks or blocked operations stay visible until their results can be verified.")
}

internal data class GitHubResponsePresentation(val text: String, val timeline: List<AssistantTimelineItem>, val progressOnly: Boolean = false)

/** Reuse retained public prose in the normal answer renderer, without certifying it as a final answer. */
internal fun gitHubResponsePresentation(text: String, timeline: List<AssistantTimelineItem>, awaitingDelivery: Boolean = false): GitHubResponsePresentation {
    if (awaitingDelivery) return GitHubResponsePresentation("", timeline.filterNot { it.type == AssistantTimelineItemType.TEXT })
    val unchanged = GitHubResponsePresentation(text, timeline)
    val summary = gitHubPublicSummary(timeline) ?: return unchanged
    fun generated(value: String): Boolean {
        val normalized = value.replace(summaryWhitespace, " ").trim()
        if (timeline.any { it.statusSummary && !it.modelAuthored && it.content.replace(summaryWhitespace, " ").trim() == normalized }) return true
        // Older messages sometimes saved this exact ticker template as response
        // text. Do not suppress arbitrary prose merely because it sounds vague.
        return legacyActivitySummary(normalized)
    }
    if (dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.hasAnswer(text) && !generated(text)) return unchanged
    if (timeline.any { it.type == AssistantTimelineItemType.TEXT && dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.hasAnswer(it.content) && !generated(it.content) }) return unchanged
    return GitHubResponsePresentation(
        summary.content,
        timeline.filterNot { it.type == AssistantTimelineItemType.TEXT } +
            summary.copy(type = AssistantTimelineItemType.TEXT, progressCheckpoint = false, statusSummary = false),
        progressOnly = true
    )
}

/** Repair ordering in retained timelines too: introduce the activity before any preview. */
internal fun gitHubActivityLines(timeline: List<AssistantTimelineItem>, task: String, searchActive: Boolean = false): List<AssistantTimelineItem> {
    val previews = mutableSetOf<String>()
    val summaries = mutableSetOf<String>()
    val counterPositions = mutableMapOf<String, Int>()
    val coalesced = mutableListOf<AssistantTimelineItem>()
    val publicSummaryAvailable = gitHubPublicSummary(timeline) != null
    timeline.asSequence().filter { it.progressCheckpoint && it.content.isNotBlank() }.forEach { retained ->
        // A selected-model progress notice is public summary prose even when an
        // older persistence path omitted statusSummary. Preserve its authorship
        // and invocation identity; measured operational notices remain visible.
        val item = publicProgressSummary(retained) ?: retained
        if (publicSummaryAvailable &&
            !item.modelAuthored &&
            item.statusSummary &&
            (item.progressId == "github-activity-introduction" || legacyActivitySummary(item.content))
        ) {
            return@forEach
        }
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
                val key = item.content.replace(summaryWhitespace, " ").trim().lowercase(Locale.ROOT)
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
