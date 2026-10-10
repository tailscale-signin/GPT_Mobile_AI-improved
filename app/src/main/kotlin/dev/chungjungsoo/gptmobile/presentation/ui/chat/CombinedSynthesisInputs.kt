package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import dev.chungjungsoo.gptmobile.util.stripAssistantErrorNote

/** Build from the latest candidate, never a historical revision selected for inspection. */
internal fun combinedSynthesisInputs(
    responses: List<MessageV2>,
    runs: Map<String, AgentRun>,
    names: Map<String, String>,
    models: Map<String, String>
): List<CombinedModelResponse>? {
    if (responses.any { message ->
            val status = runs[message.currentRunId]?.status
            status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING) ||
                (message.currentRunId != null && status == null) ||
                (message.currentRunId == null && message.content.isBlank())
        }
    ) {
        return null
    }
    val synthesis = responses.firstOrNull { it.isCombinedSynthesis() }
    val sources = responses.mapNotNull { message ->
        val uid = message.platformType ?: return@mapNotNull null
        val original = message.combinedSources.firstOrNull { it.platformUid == uid }
        val content = stripAssistantErrorNote(original?.content ?: message.content).trim()
        if (content.isBlank() || isAssistantErrorMessage(content)) return@mapNotNull null
        CombinedModelResponse(uid, names[uid] ?: original?.platformName ?: "AI", models[uid] ?: original?.modelName.orEmpty(), content)
    }
    if (sources.isEmpty()) return null
    if (synthesis != null &&
        runs[synthesis.currentRunId]?.status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.FAILED, AgentRunStatus.CANCELED, AgentRunStatus.INTERRUPTED) &&
        synthesis.combinedSources.map { it.platformUid to it.content.trim() } == sources.map { it.platformUid to it.content.trim() }
    ) {
        return null
    }
    return sources
}
