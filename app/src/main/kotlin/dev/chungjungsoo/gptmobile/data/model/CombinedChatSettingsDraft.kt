package dev.chungjungsoo.gptmobile.data.model

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
data class CombinedChatSettingsDraft(
    val conversationIdentity: String,
    val chatId: Int,
    val baseRevision: Long,
    val settings: ConversationCombinedSettings = ConversationCombinedSettings(),
    val participantUids: List<String>,
    val models: Map<String, String>,
    val creativity: Float,
    val operationId: String = UUID.randomUUID().toString(),
    val primaryProfileUid: String? = null
) {
    fun validated(): CombinedChatSettingsDraft {
        require(conversationIdentity.isNotBlank()) { "Conversation identity is unavailable." }
        require(operationId.isNotBlank() && baseRevision >= 0) { "Invalid settings revision." }
        require(settings.isSupported) { "These Combined settings require a newer app version." }
        require(participantUids.isNotEmpty() && participantUids.distinct().size == participantUids.size) { "Select at least one AI profile." }
        require(participantUids.all { it.isNotBlank() && !models[it].isNullOrBlank() }) { "Choose a model for every selected profile." }
        require(creativity.isFinite() && creativity in 0f..2f) { "Creativity must be between 0 and 2." }
        return copy(
            settings = settings.copy(creativity = creativity).normalized(),
            models = models.mapValues { it.value.trim() }
        )
    }
}

@Serializable
enum class CombinedSettingsSavePhase { DRAFT, VALIDATING, COMMITTING, COMMITTED, FAILED, ABORTED }

data class CombinedSettingsSaveUiState(
    val phase: CombinedSettingsSavePhase = CombinedSettingsSavePhase.DRAFT,
    val error: String? = null
) {
    val isCommitting: Boolean get() = phase == CombinedSettingsSavePhase.COMMITTING || phase == CombinedSettingsSavePhase.VALIDATING
}

/** Both snapshots exclude immediate Tools/Privacy fields. */
@Serializable
data class CombinedSettingsSaveOperation(
    val operationId: String,
    val conversationIdentity: String,
    val chatId: Int,
    val previous: CombinedChatSettingsDraft,
    val requested: CombinedChatSettingsDraft,
    val phase: CombinedSettingsSavePhase = CombinedSettingsSavePhase.COMMITTING,
    val error: String? = null,
    val roomSettled: Boolean = false
) {
    fun committedView(room: CombinedChatSettingsDraft): CombinedChatSettingsDraft =
        if (!roomSettled && phase != CombinedSettingsSavePhase.COMMITTED) previous else room
}
