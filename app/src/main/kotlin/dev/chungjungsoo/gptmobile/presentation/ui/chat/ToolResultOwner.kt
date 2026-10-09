package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent

/** Combined cards use the originating profile's plugin permissions. */
internal fun toolResultOwner(
    events: List<ToolEvent>,
    profilesByRun: Map<String, String>,
    fallback: String?,
    matches: (ToolEvent) -> Boolean
): String? = events.sortedWith(compareByDescending<ToolEvent> { it.completedAt ?: 0L }.thenByDescending { it.sequence })
    .firstOrNull(matches)?.let { if (profilesByRun.isEmpty()) fallback else profilesByRun[it.runId] }
