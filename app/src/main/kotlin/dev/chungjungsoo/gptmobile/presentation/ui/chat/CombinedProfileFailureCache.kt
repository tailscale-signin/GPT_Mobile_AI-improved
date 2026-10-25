package dev.chungjungsoo.gptmobile.presentation.ui.chat

import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** Streaming siblings do not change an already assessed contributor's answer contract. */
internal class CombinedProfileFailureCache {
    private data class Input(val userId: Int, val request: String, val message: MessageV2, val run: AgentRun, val name: String?, val model: String?, val waitDeadline: ProviderWaitDeadline?)
    private data class Entry(val input: Input, val failure: CombinedProfileFailure?)
    private val entries = mutableMapOf<Pair<Int, String>, Entry>()

    fun retainTurns(userIds: Set<Int>) {
        entries.keys.removeAll { it.first !in userIds }
    }

    fun failures(userId: Int, request: String, responses: List<MessageV2>, runs: Map<String, AgentRun>, names: Map<String, String>, models: Map<String, String>, waitDeadlines: Map<String, ProviderWaitDeadline> = emptyMap()): List<CombinedProfileFailure> =
        responses.mapNotNull { message ->
            val uid = message.platformType ?: return@mapNotNull null
            val key = userId to uid
            val run = runs[message.currentRunId]
            if (run == null || run.status in setOf(AgentRunStatus.QUEUED, AgentRunStatus.RUNNING) || message.isCombinedSynthesis()) {
                entries.remove(key)
                return@mapNotNull null
            }
            val input = Input(userId, request, message, run, names[uid], models[uid], waitDeadlines[run.runId])
            val prior = entries[key]
            if (prior != null && prior.input == input) {
                prior.failure
            } else {
                val failure = combinedProfileFailures(userId, request, listOf(message), runs, names, models, waitDeadlines).singleOrNull()
                entries[key] = Entry(input, failure)
                failure
            }
        }
}
