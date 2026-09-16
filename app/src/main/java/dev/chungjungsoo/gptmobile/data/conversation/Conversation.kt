package dev.chungjungsoo.gptmobile.data.conversation

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import kotlinx.serialization.Serializable
import java.util.Date

/**
 * Represents a conversation in the chat system.
 *
 * This entity stores all information related to a conversation including:
 * - Title (with auto-generated summaries)
 * - Creation and modification timestamps
 * - User-defined vs AI-generated title status
 * - Conversation metadata
 */
@Entity(tableName = "conversations")
@Serializable
data class Conversation(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    var title: String = "",
    var isTitleCustomized: Boolean = false,
    var createdAt: Date = Date(),
    var updatedAt: Date = Date(),
    var messageCount: Int = 0,
    var isFavorite: Boolean = false,
    var isArchived: Boolean = false,
    var contextWindow: Int = 0,
    var modelId: String = "",
    var temperature: Float = 0.7f,
    var maxTokens: Int = 2048,
    var topP: Float = 0.9f,
    var frequencyPenalty: Float = 0.0f,
    var presencePenalty: Float = 0.0f,
    var systemPrompt: String = "",
    var isStreaming: Boolean = true,
    var isToolCallingEnabled: Boolean = true,
    var isRagEnabled: Boolean = false,
    var isVoiceEnabled: Boolean = false,
    var isImageEnabled: Boolean = false,
    var isMultimodalEnabled: Boolean = false,
    var isAutoContinueEnabled: Boolean = false,
    var maxToolCalls: Int = 10,
    var maxContextLength: Int = 4096,
    var maxResponseLength: Int = 2048,
    var isDebugModeEnabled: Boolean = false,
    var debugInfo: String = ""
) {
    companion object {
        const val DEFAULT_TITLE = "New Conversation"
    }
}
