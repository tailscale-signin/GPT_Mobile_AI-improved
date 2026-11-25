package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.github.GitHubProgressPresentation
import java.util.Locale

private val summaryWhitespace = Regex("\\s+")
private data class ProgressSummaryIdentity(val id: String?, val sequence: Long?, val modelAuthored: Boolean, val lineage: dev.chungjungsoo.gptmobile.data.diagnostics.OutputLineage?)
private val legacyActivitySummary = Regex("^I have (?:completed [0-9]+ repository operations\\.|received the request; no repository operation has completed yet\\.) ")

/** Only explicitly public prose can replace a generic activity summary, including retained messages. */
internal fun gitHubPublicSummary(timeline: List<AssistantTimelineItem>): AssistantTimelineItem? =
    timeline.asReversed().asSequence().filter { it.modelAuthored }.mapNotNull(::publicActivityCheckpoint).firstOrNull()

/** The transcript, headline and animated accent all use the same public event boundary. */
private fun publicActivityCheckpoint(item: AssistantTimelineItem): AssistantTimelineItem? {
    if (item.type != AssistantTimelineItemType.NOTICE ||
        !item.progressCheckpoint ||
        item.content.isBlank() ||
        item.delegationInvocationId != null ||
        item.delegationProfile != null ||
        item.delegationRemote ||
        item.delegatedTool ||
        item.toolSequence != null ||
        item.toolMetrics != null ||
        item.recalledFacts.isNotEmpty() ||
        isContextDiagnostic(item.content)
    ) {
        return null
    }
    if (!item.modelAuthored) return item
    val text = dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.text(item.content)
    return item.copy(content = text, statusSummary = true).takeIf { text.isNotBlank() && !isContextDiagnostic(text) }
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
    if (awaitingDelivery) {
        val segments = timeline.filter { it.type == AssistantTimelineItemType.TEXT && it.content.isNotEmpty() }
        // Only publish the committed primary stream. Older unbound text and private handoffs wait for delivery.
        val visible = segments.isNotEmpty() &&
            segments.all {
                it.outputLineage?.origin == dev.chungjungsoo.gptmobile.data.diagnostics.OutputOrigin.MODEL_TEXT &&
                    !it.outputLineage.invocationId.isNullOrBlank() &&
                    !it.progressCheckpoint &&
                    !it.statusSummary &&
                    it.delegationInvocationId == null &&
                    it.delegationProfile == null &&
                    !it.delegatedTool &&
                    !it.delegationRemote &&
                    it.toolSequence == null &&
                    it.toolMetrics == null &&
                    it.recalledFacts.isEmpty()
            } &&
            segments.joinToString("") { it.content } == text &&
            dev.chungjungsoo.gptmobile.data.conversation.AnswerProjection.text(text) == text
        return if (visible) GitHubResponsePresentation(text, timeline) else GitHubResponsePresentation("", timeline.filterNot { it.type == AssistantTimelineItemType.TEXT })
    }
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

/** Presentation order is the last activation order, independent of retained evidence slots. */
internal data class GitHubActivityProjection(val lines: List<AssistantTimelineItem>, val hasActivePreview: Boolean)

internal fun gitHubActivityProjection(timeline: List<AssistantTimelineItem>, task: String, searchActive: Boolean = false, active: Boolean = false): GitHubActivityProjection {
    val previews = mutableSetOf<String>()
    val summaryPositions = mutableMapOf<String, Int>()
    val summaryEvents = mutableMapOf<ProgressSummaryIdentity, Int>()
    val counterPositions = mutableMapOf<String, Int>()
    val semanticPositions = mutableMapOf<String, Int>()
    val activationOrder = mutableMapOf<String, Long>()
    val coalesced = mutableListOf<AssistantTimelineItem>()
    var latestPublic: AssistantTimelineItem? = null
    var hasPublicSummary = false
    timeline.forEachIndexed { sourceIndex, retained ->
        val item = publicActivityCheckpoint(retained) ?: return@forEachIndexed
        if (!active && !item.modelAuthored && !item.statusSummary && GitHubProgressPresentation.isTransientActivity(item.content)) {
            return@forEachIndexed
        }
        hasPublicSummary = hasPublicSummary || item.modelAuthored
        // Compacted counters retain their original slot. A persisted sequence always
        // follows legacy notices, and ties retain the first recorded activation.
        val previousSequence = latestPublic?.progressSequence
        val sequence = item.progressSequence
        if (previousSequence == null || sequence != null && sequence > previousSequence) latestPublic = item
        val counterKey = GitHubProgressPresentation.liveKey(item.content).takeUnless { item.statusSummary }
        when {
            counterKey != null -> {
                val current = item.copy(content = item.content.substringBefore(". "), progressId = item.progressId ?: counterKey, statusSummary = false)
                val position = counterPositions[counterKey]
                val previousSequence = position?.let { coalesced[it].progressSequence }
                val sequence = current.progressSequence
                if (previousSequence != null && sequence != null && sequence <= previousSequence) return@forEachIndexed
                activationOrder[current.progressId!!] = sequence ?: sourceIndex.toLong()
                if (position == null) {
                    counterPositions[counterKey] = coalesced.size
                    coalesced.add(current)
                } else {
                    coalesced[position] = current
                }
            }
            item.statusSummary && (item.modelAuthored || item.progressId != null || item.progressSequence != null) -> {
                // Admission already distinguishes new evidence from repeated prose.
                // Equal text can describe a real state returning after a different
                // finding; only the same persisted event is a replay.
                val identified = item.progressId != null || item.progressSequence != null
                val key = ProgressSummaryIdentity(item.progressId, item.progressSequence, item.modelAuthored, item.outputLineage)
                val previous = if (identified) summaryEvents[key] else null
                if (previous != null) {
                    coalesced[previous] = item
                } else if (identified || coalesced.lastOrNull { it.statusSummary } != item) {
                    if (identified) summaryEvents[key] = coalesced.size
                    coalesced.add(item)
                }
            }
            item.statusSummary -> {
                // Local fallback reports can be rehydrated with a new next-action
                // phrase after reconnect. Compare only their recorded findings.
                // Preserve distinct selected-model explanations without rewriting them.
                val isFactualRich = !item.modelAuthored && dev.chungjungsoo.gptmobile.data.github.GitHubRichSummaryPresentation.isRich(item.content)
                val key = if (isFactualRich) {
                    dev.chungjungsoo.gptmobile.data.github.GitHubRichSummaryPresentation.publicationKey(item.content)
                } else {
                    item.content.replace(summaryWhitespace, " ").trim().lowercase(Locale.ROOT)
                }
                val previous = summaryPositions[key]
                if (previous == null) {
                    summaryPositions[key] = coalesced.size
                    coalesced.add(item)
                } else if (isFactualRich) {
                    // Keep the newest actionable wording in the existing slot.
                    coalesced[previous] = item
                }
            }
            else -> {
                val previewKey = GitHubProgressPresentation.lineKey(item.content)
                val semanticKey = GitHubProgressPresentation.semanticActivityKey(item.content)
                val id = semanticKey?.let { "story:$it" }
                    ?: item.progressId ?: "legacy-preview:${item.progressSequence ?: sourceIndex}:$previewKey"
                if (semanticKey != null) {
                    val current = item.copy(content = GitHubProgressPresentation.legacyNarrative(item.content), progressId = id)
                    val position = semanticPositions[semanticKey]
                    val earlier = position?.let { coalesced[it].progressSequence }
                    val sequence = current.progressSequence
                    if (earlier != null && sequence != null && sequence <= earlier) return@forEachIndexed
                    activationOrder[id] = sequence ?: sourceIndex.toLong()
                    if (position == null) {
                        semanticPositions[semanticKey] = coalesced.size
                        coalesced.add(current)
                    } else {
                        coalesced[position] = current
                    }
                } else if (previews.add(id)) {
                    activationOrder[id] = item.progressSequence ?: sourceIndex.toLong()
                    coalesced.add(item.copy(progressId = id))
                }
            }
        }
    }
    // Counter rows are compacted in persistence, so their list positions are the
    // original receipt slots. The persisted monotonic sequence survives a reopen
    // and reconnect and tells us when that row most recently became active.
    val previewsInOrder = coalesced.filterNot { it.statusSummary }
        // Legacy receipts predate the persisted sequence clock. Never compare
        // their source indices numerically with a new clock that starts at one.
        .sortedWith(compareBy<AssistantTimelineItem> { it.progressSequence != null }.thenBy { activationOrder[it.progressId] ?: Long.MIN_VALUE })
        // Compare adjacent activations only after sorting. A compacted counter
        // can occupy an older receipt slot between two equal activity texts.
        .fold(mutableListOf<AssistantTimelineItem>()) { result, item ->
            if (result.lastOrNull()?.let { GitHubProgressPresentation.lineKey(it.content) == GitHubProgressPresentation.lineKey(item.content) } == true) {
                result[result.lastIndex] = item
            } else {
                result.add(item)
            }
            result
        }
    val newestPair = previewsInOrder.takeLast(2)
    val summariesInOrder = coalesced.filter { item ->
        item.statusSummary &&
            !(
                hasPublicSummary &&
                    !item.modelAuthored &&
                    (item.progressId == "github-activity-introduction" || legacyActivitySummary(item.content))
                )
    }
    val ordered = previewsInOrder.dropLast(newestPair.size) + summariesInOrder + newestPair
    val summary = ordered.indexOfFirst { it.statusSummary }
    val introduced = when {
        summary == 0 -> ordered
        summary > 0 -> listOf(ordered[summary]) + ordered.take(summary) + ordered.drop(summary + 1)
        else -> listOf(
            AssistantTimelineItem(
                type = AssistantTimelineItemType.NOTICE,
                content = GitHubProgressPresentation.introduction(task),
                progressCheckpoint = true,
                statusSummary = true,
                modelAuthored = false,
                progressId = "github-activity-introduction"
            )
        ) + ordered
    }
    if (searchActive && previewsInOrder.isEmpty()) {
        val search = AssistantTimelineItem(
            type = AssistantTimelineItemType.NOTICE,
            content = "Searching websites",
            progressCheckpoint = true,
            progressId = "github-search-preview"
        )
        return GitHubActivityProjection(introduced + search, hasActivePreview = true)
    }
    return GitHubActivityProjection(introduced, hasActivePreview = latestPublic?.statusSummary == false)
}

/** Count entries, independently of line wrapping or font size, retaining summary context. */
internal fun gitHubVisibleActivityLines(lines: List<AssistantTimelineItem>, compact: Boolean, entryLimit: Int = 20): List<AssistantTimelineItem> {
    require(entryLimit >= 2)
    if (!compact || lines.size <= entryLimit) return lines
    val summaryIndex = lines.indexOfLast { it.statusSummary }.takeIf { it >= 0 }
    val counters = lines.indices.filter { !lines[it].statusSummary && GitHubProgressPresentation.liveKey(lines[it].content) != null }
    val pinned = (listOfNotNull(summaryIndex, lines.lastIndex - 1, lines.lastIndex) + counters.asReversed())
        .distinct().take(entryLimit).toSet()
    val recent = lines.indices.filter { it !in pinned }.takeLast(entryLimit - pinned.size)
    return (listOfNotNull(summaryIndex) + (pinned + recent).filter { it != summaryIndex }.sorted()).map(lines::get)
}
