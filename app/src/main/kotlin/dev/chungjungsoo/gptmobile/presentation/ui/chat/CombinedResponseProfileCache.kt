package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2

/** One bounded projection per visible profile; unrelated run activity cannot invalidate it. */
internal class CombinedResponseProfileCache {
    private data class Input(val message: MessageV2?, val source: CombinedModelResponse?, val preparing: Boolean, val statuses: List<Pair<String, String?>>)
    private data class Entry(val input: Input, val projection: CombinedResponseProjection)
    private val entries = mutableMapOf<String, Entry>()

    fun retainProfiles(uids: Set<String>) {
        entries.keys.retainAll(uids)
    }

    fun projection(uid: String, message: MessageV2?, source: CombinedModelResponse?, preparing: Boolean, runs: Map<String, AgentRun>): CombinedResponseProjection {
        val relevantRunIds = (listOfNotNull(message?.currentRunId, source?.attemptId) + message?.revisions.orEmpty().mapNotNull { it.runId }).distinct()
        val input = Input(message, source, preparing, relevantRunIds.map { it to runs[it]?.status })
        val previous = entries[uid]
        if (previous != null && previous.input == input) return previous.projection
        return combinedResponseProjection(message, source, preparing, runs).also { entries[uid] = Entry(input, it) }
    }
}

@Composable
internal fun rememberCombinedResponseProfiles(
    responses: List<MessageV2>,
    platformUids: List<String>,
    profileNames: Map<String, String>,
    participatingUids: Set<String>,
    preparing: Boolean,
    runsById: Map<String, AgentRun>
): List<CombinedResponseProfile> = combinedResponseProfiles(responses, platformUids, profileNames, participatingUids, preparing, runsById, remember { CombinedResponseProfileCache() })
