package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.COMBINED_RECOVERY_MARKER
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import java.text.Normalizer

internal enum class CombinedResponseStatus {
    GENERATING,
    COMPLETED,
    FAILED
}

internal data class CombinedResponseProfile(
    val uid: String,
    val name: String,
    val assistantIndex: Int,
    val message: MessageV2?,
    val status: CombinedResponseStatus
)

internal fun combinedProfileLabel(name: String, fallback: String): String = Normalizer
    .normalize(name, Normalizer.Form.NFC)
    .replace(Regex("[^\\p{L}\\p{N}\\s]"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()
    .ifBlank { fallback }

internal fun MessageV2.isCombinedSynthesis(): Boolean =
    currentRunId?.startsWith("combined-synthesis:") == true || combinedSources.isNotEmpty()

internal fun combinedResponseStatus(
    message: MessageV2?,
    run: AgentRun?,
    preparing: Boolean = false
): CombinedResponseStatus = when {
    run?.status in setOf(AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED) -> CombinedResponseStatus.FAILED
    run?.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING) || preparing -> CombinedResponseStatus.GENERATING
    message?.content.isNullOrBlank() || isAssistantErrorMessage(message.content) -> CombinedResponseStatus.FAILED
    else -> CombinedResponseStatus.COMPLETED
}

/** Keep the exact source used for synthesis, including the lead response replaced by the final answer. */
private fun sourceMessage(message: MessageV2?, source: CombinedModelResponse): MessageV2 {
    if (message != null && !message.isCombinedSynthesis() && dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote(message.content).trim() == source.content.trim()) {
        return message.copy(activeRevisionIndex = ACTIVE_REVISION_LATEST)
    }
    val revision = message?.revisions?.firstOrNull {
        it.runId?.startsWith("combined-synthesis:") != true && dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote(it.content).trim() == source.content.trim()
    }
    return MessageV2(
        id = message?.id ?: 0,
        chatId = message?.chatId ?: 0,
        linkedMessageId = message?.linkedMessageId ?: 0,
        platformType = source.platformUid,
        content = source.content,
        thoughts = revision?.thoughts.orEmpty(),
        timeline = revision?.timeline.orEmpty(),
        currentRunId = revision?.runId,
        createdAt = revision?.createdAt ?: message?.createdAt ?: 0,
        isFavorite = message?.isFavorite ?: false
    )
}

internal fun combinedResponseProfiles(
    responses: List<MessageV2>,
    platformUids: List<String>,
    profileNames: Map<String, String>,
    participatingUids: Set<String>,
    preparing: Boolean,
    runsById: Map<String, AgentRun>
): List<CombinedResponseProfile> {
    val sources = responses.firstOrNull { it.isCombinedSynthesis() }?.combinedSources.orEmpty().associateBy { it.platformUid }
    // Historical participants come from persisted responses, even after a profile is paused or removed.
    val uids = (platformUids + responses.mapNotNull { it.platformType } + sources.keys).distinct()
    return uids.mapIndexedNotNull { order, uid ->
        val index = responses.indexOfFirst { it.platformType == uid }
        val original = responses.getOrNull(index)
        val source = sources[uid]
        val hasResponse = original?.let {
            !it.currentRunId.isNullOrBlank() || it.content.isNotBlank() || it.thoughts.isNotBlank()
        } == true
        if (source == null && !hasResponse && !(preparing && uid in participatingUids)) return@mapIndexedNotNull null
        val response = source?.let { sourceMessage(original, it) }
            ?: original?.copy(activeRevisionIndex = ACTIVE_REVISION_LATEST)
        val run = response?.currentRunId?.let(runsById::get)
        CombinedResponseProfile(
            uid = uid,
            name = combinedProfileLabel(source?.platformName ?: profileNames[uid].orEmpty(), "AI ${order + 1}"),
            assistantIndex = index.takeIf { it >= 0 } ?: platformUids.indexOf(uid),
            message = response,
            status = combinedResponseStatus(response, run, preparing && source == null && run == null && uid in participatingUids)
        )
    }
}

internal fun combinedAnswerStatus(
    synthesis: MessageV2?,
    profiles: List<CombinedResponseProfile>,
    runsById: Map<String, AgentRun>
): CombinedResponseStatus {
    if (synthesis != null) return combinedResponseStatus(synthesis, synthesis.currentRunId?.let(runsById::get))
    return if (profiles.isNotEmpty() && profiles.all { it.status == CombinedResponseStatus.FAILED }) {
        CombinedResponseStatus.FAILED
    } else {
        // The combined answer is still waiting for its synthesis run, even if every source just completed.
        CombinedResponseStatus.GENERATING
    }
}

/**
 * Fallback excerpts remain in the persisted answer for coverage and diagnosis.
 * Only Debug Mode exposes them in the chat UI.
 *
 * Legacy messages use "Additional source details"; new deliveries use an
 * explicit marker so ordinary "Further details" headings are left intact.
 */
internal fun combinedVisibleAnswer(answer: String, debugMode: Boolean): String {
    val marked = combinedRecoveryBoundary.find(answer)
    if (marked != null) {
        val mainAnswer = answer.substring(0, marked.range.first).trimEnd()
        if (!debugMode) return mainAnswer
        val appendix = answer.substring(marked.range.last + 1).trim()
        return if (appendix.isEmpty()) mainAnswer else "$mainAnswer\n\n$appendix"
    }

    val legacy = legacyCombinedRecoveryHeading.find(answer) ?: return answer
    val mainAnswer = answer.substring(0, legacy.range.first).trimEnd()
    if (!debugMode) return mainAnswer

    val appendix = answer.substring(legacy.range.last + 1)
        .replace(
            "The passages below were not fully integrated by the synthesis model. " +
                "They are retained from individual AI profiles, not independently verified facts.",
            ""
        )
        .replace(legacyCombinedSourceLine, "")
        .trim()
        .replace(Regex("\n{3,}"), "\n\n")
    return if (appendix.isEmpty()) mainAnswer else "$mainAnswer\n\n## Further details\n\n$appendix"
}

private val combinedRecoveryBoundary = Regex("(?m)^" + Regex.escape(COMBINED_RECOVERY_MARKER) + "[ \\t]*\\r?$")
private val legacyCombinedRecoveryHeading = Regex("(?m)^## Additional source details[ \\t]*\\r?$")
private val legacyCombinedSourceLine = Regex("(?m)^\\*\\*From [^\\r\\n]{1,180}:\\*\\*[ \\t]*\\r?$")

/**
 * Chronological TEXT events can bypass filtered fallbackText. Combined synthesis
 * produces one editorial answer: retain all other events and show that answer
 * once at the last TEXT position, not a copy of its unfiltered stored value.
 */
internal fun combinedVisibleTimeline(
    timeline: List<AssistantTimelineItem>,
    visibleAnswer: String
): List<AssistantTimelineItem> {
    if (timeline.isEmpty() || timeline.any { it.type == AssistantTimelineItemType.LEGACY_ORDER }) return timeline
    val lastText = timeline.indexOfLast { it.type == AssistantTimelineItemType.TEXT }
    if (lastText < 0) return timeline + AssistantTimelineItem(AssistantTimelineItemType.TEXT, visibleAnswer)
    return timeline.mapIndexedNotNull { index, item ->
        when {
            item.type != AssistantTimelineItemType.TEXT -> item
            index == lastText -> item.copy(content = visibleAnswer)
            else -> null
        }
    }
}
