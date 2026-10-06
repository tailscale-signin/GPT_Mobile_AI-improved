package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus

data class GenerationTiming(val startedAt: Long, val completedAt: Long?)

/** Combined timing covers the workers and final assembly, rather than just the merge. */
internal fun responseGenerationTiming(run: AgentRun?, sourceRuns: List<AgentRun> = emptyList()): GenerationTiming? {
    val workers = sourceRuns.ifEmpty { listOfNotNull(run) }
    val start = workers.mapNotNull { it.startedAt?.takeIf { time -> time > 0 } }.minOrNull()
        ?: workers.filter { it.status != AgentRunStatus.QUEUED }.minOfOrNull { it.createdAt }
        ?: return null
    val end = run?.completedAt ?: if (run == null && workers.all { it.completedAt != null }) workers.maxOfOrNull { requireNotNull(it.completedAt) } else null
    return GenerationTiming(start, end)
}

internal fun workedTimeText(durationSeconds: Long): String {
    val duration = durationSeconds.coerceAtLeast(0)
    val minutes = duration / 60
    val seconds = duration % 60
    return if (minutes > 0) "Worked for ${minutes}m ${seconds}s" else "Worked for ${seconds}s"
}
