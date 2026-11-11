package dev.chungjungsoo.gptmobile.data.voice

import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.Serializable

@Serializable
enum class VoiceAdmissionStage { CHECKING, QUEUED, ACCEPTED, FAILED }

/** Saved request identity survives UI recreation; presentation notices never decide admission. */
@Serializable
data class VoiceTurnAdmission(
    val requestId: String,
    val chatId: Int,
    val profileUid: String,
    val stage: VoiceAdmissionStage = VoiceAdmissionStage.CHECKING,
    val userMessageId: Int? = null,
    val runIds: List<String> = emptyList(),
    val reason: String? = null
) {
    fun run(runs: Map<String, AgentRun>): AgentRun? = runs.values.singleOrNull {
        it.chatId == chatId && it.userMessageId == userMessageId && it.profileUid == profileUid && it.runId in runIds
    }

    fun reply(messages: List<MessageV2>, runs: Map<String, AgentRun>): Pair<AgentRun, MessageV2>? {
        if (stage != VoiceAdmissionStage.ACCEPTED) return null
        val run = run(runs) ?: return null
        val message = messages.singleOrNull { it.id == run.assistantMessageId && it.chatId == chatId && it.currentRunId == run.runId } ?: return null
        return run to message
    }
}
