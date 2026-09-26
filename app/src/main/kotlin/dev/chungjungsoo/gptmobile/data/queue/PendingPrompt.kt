package dev.chungjungsoo.gptmobile.data.queue

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Entity(
    tableName = "pending_prompts",
    foreignKeys = [ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("chatId")]
)
data class PendingPrompt(
    @PrimaryKey val id: String,
    val chatId: Int,
    val text: String,
    val payload: String,
    val position: Long,
    val paused: Boolean = false,
    val userMessageId: Int? = null
) {
    fun details(): PendingPromptPayload = Json { ignoreUnknownKeys = true }.decodeFromString(payload)
}

@Serializable
data class PendingPromptPayload(
    val attachments: List<ChatAttachment> = emptyList(),
    val profileUids: List<String> = emptyList(),
    val models: Map<String, String> = emptyMap(),
    val tools: ChatMcpToolConfig = ChatMcpToolConfig()
)
