package dev.chungjungsoo.gptmobile.data.conversation

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable
import java.util.Date

/**
 * Represents a message within a conversation.
 *
 * This entity stores all information related to a single message including:
 * - Message content and role (user, assistant)
 * - Timestamps for creation and modification
 * - Message metadata
 * - Debug information for development
 */
@Entity(tableName = "messages")
@Serializable
data class Message(
    @PrimaryKey
    val id: String = java.util.UUID.randomUUID().toString(),
    var conversationId: String = "",
    var content: String = "",
    var role: MessageRole = MessageRole.USER,
    var createdAt: Date = Date(),
    var updatedAt: Date = Date(),
    var isStreaming: Boolean = true,
    var isToolCall: Boolean = false,
    var toolCallId: String? = null,
    var toolCallName: String? = null,
    var toolCallArguments: String? = null,
    var isDebugModeEnabled: Boolean = false,
    var debugInfo: String = "",
    var isAutoContinue: Boolean = false,
    var autoContinueReason: String = ""
) {
    companion object {
        fun createSystemMessage(content: String): Message {
            return Message(content = content, role = MessageRole.SYSTEM)
        }

        fun createUserMessage(content: String): Message {
            return Message(content = content, role = MessageRole.USER)
        }

        fun createAssistantMessage(content: String): Message {
            return Message(content = content, role = MessageRole.ASSISTANT)
        }
    }
}

enum class MessageRole {
    USER,
    ASSISTANT,
    SYSTEM
}