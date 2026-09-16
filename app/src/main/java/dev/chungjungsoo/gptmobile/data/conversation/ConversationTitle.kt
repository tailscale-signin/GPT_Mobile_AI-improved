package dev.chungjungsoo.gptmobile.data.conversation

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.Date

/**
 * Represents a conversation title history entry.
 *
 * This entity stores title changes for a conversation including:
 * - Original title
 * - AI-generated summary
 * - Timestamp of when the title was changed
 * - User-defined vs AI-generated title status
 */
@Entity(tableName = "conversation_titles")
@Serializable
data class ConversationTitle(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    var conversationId: String = "",
    var originalTitle: String = "",
    var aiGeneratedTitle: String = "",
    var isCustomized: Boolean = false,
    var createdAt: Date = Date(),
    var updatedAt: Date = Date(),
    var isDebugModeEnabled: Boolean = false,
    var debugInfo: String = ""
) {
    companion object {
        fun createFromConversation(conversation: Conversation): ConversationTitle {
            return ConversationTitle(
                conversationId = conversation.id,
                originalTitle = conversation.title,
                aiGeneratedTitle = conversation.title,
                isCustomized = conversation.isTitleCustomized,
                createdAt = conversation.createdAt,
                updatedAt = conversation.updatedAt
            )
        }
    }
}