package com.example.gptmobileai.database.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["id"],
            childColumns = ["roomId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["roomId"]),
        Index(value = ["timestamp"]),
        Index(value = ["roomId", "timestamp"]),
        Index(value = ["favorite"]),
        Index(value = ["isPinned"])
    ]
)
data class MessageV2(
    @PrimaryKey
    val id: String,
    val roomId: String,
    val content: String,
    val sender: String,
    val timestamp: Long = System.currentTimeMillis(),
    val favorite: Boolean = false,
    val isPinned: Boolean = false,
    val userAvatarUri: String? = null,
    val isEncrypted: Boolean = false,
    val isTemporary: Boolean = false,
    val branchId: String? = null,
    val parentBranchId: String? = null,
    val threadRootId: String? = null,
    val parentMessageId: String? = null,
    val branchPointTimestamp: Long? = null,
    val threadDepth: Int = 0,
    val childCount: Int = 0,
    val thinkingProcess: String? = null,
    val timeline: List<AssistantTimelineItem>? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    @ColumnInfo(name = "model_name")
    val modelName: String? = null
)

@Serializable
data class AssistantTimelineItem(
    val title: String,
    val description: String? = null,
    val isExpanded: Boolean = false,
    val toolCallId: String? = null,
    val status: String? = null,
    val durationMs: Long? = null
)

@Serializable
data class ChatAttachment(
    val type: String,
    val url: String,
    val name: String? = null,
    val size: Long? = null,
    val mimeType: String? = null,
    val localUri: String? = null
)
