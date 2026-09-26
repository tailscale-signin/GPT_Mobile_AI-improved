package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId

/** Keep source location results visible after a combined answer replaces its lead response. */
internal fun locationEventsForResponse(
    selected: MessageV2?,
    responses: List<MessageV2>,
    combined: Boolean,
    activeProfileUids: Set<String>,
    eventsByRun: Map<String, List<ToolEvent>>
): List<ToolEvent> {
    if (selected == null) return emptyList()
    if (!combined) return eventsByRun[selected.effectiveRunId()].orEmpty()

    val runIds = mutableSetOf<String>()
    selected.effectiveRunId()?.let(runIds::add)
    if (selected.combinedSources.isNotEmpty()) {
        selected.combinedSources.forEach { source ->
            val response = responses.firstOrNull { it.platformType == source.platformUid } ?: return@forEach
            // The lead candidate is saved as a revision when synthesis starts. Match
            // the actual candidate, so a later retry cannot replace its map result.
            val sourceRunId = if (response.content.trim() == source.content.trim()) {
                response.currentRunId
            } else {
                response.revisions.firstOrNull { it.content.trim() == source.content.trim() }?.runId
            }
            sourceRunId?.let(runIds::add)
        }
    } else {
        responses.filter { it.platformType in activeProfileUids }
            .mapNotNullTo(runIds) { it.effectiveRunId() }
    }
    return runIds.flatMap { eventsByRun[it].orEmpty() }
        .distinctBy { it.eventId }
        .sortedWith(compareBy<ToolEvent> { it.completedAt ?: it.startedAt ?: 0L }.thenBy { it.sequence })
}
