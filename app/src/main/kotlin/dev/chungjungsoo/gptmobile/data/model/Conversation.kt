package dev.chungjungsoo.gptmobile.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.serialization.Serializable

/**
 * Conversation data model with auto-continue settings
 */
@Serializable
@Entity(tableName = "conversations")
data class Conversation(
    @PrimaryKey(autoGenerate = true)
    val id: Int = 0,
    
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isAutoContinueEnabled: Boolean = false,
    val autoContinueSettings: AutoContinueSettings = AutoContinueSettings(),
    val isArchived: Boolean = false,
    val isPinned: Boolean = false
)

/**
 * Auto-continue settings for conversation
 */
@Serializable
data class AutoContinueSettings(
    val maxTokens: Int = 2048,
    val maxToolCalls: Int = 10,
    val maxMessages: Int = 50,
    val isTokenLimitEnabled: Boolean = true,
    val isToolCallLimitEnabled: Boolean = true,
    val isMessageLimitEnabled: Boolean = true
)

/**
 * Extension functions for Conversation
 */
fun Conversation.toChatRoomV2(): ChatRoomV2 {
    return ChatRoomV2(
        chatId = id,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isArchived = isArchived,
        isPinned = isPinned
    )
}

fun ChatRoomV2.toConversation(): Conversation {
    return Conversation(
        id = chatId,
        title = title,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isAutoContinueEnabled = false, // Default value, should be set from settings
        autoContinueSettings = AutoContinueSettings(), // Default value
        isArchived = isArchived,
        isPinned = isPinned
    )
}