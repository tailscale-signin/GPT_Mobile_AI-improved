package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import dev.melo.gptmobile.improved.data.model.ChatAttachment
import kotlinx.serialization.Serializable

@Serializable
data class AssistantRevision(
    val revisionId: String,
    val content: String,
    val createdAt: Long,
    val timeline: List<AssistantTimelineItem> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList()
)

@Entity(
    tableName = "messages_v2",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chat_id"])]
)
@TypeConverters(
    ChatAttachmentListConverter::class,
    AssistantRevisionListConverter::class,
    AssistantTimelineListConverter::class
)
data class MessageV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "message_id")
    val messageId: Int = 0,

    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "sender")
    val sender: Int,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "attachments", defaultValue = "[]")
    val attachments: List<ChatAttachment> = emptyList(),

    @ColumnInfo(name = "assistant_revisions", defaultValue = "[]")
    val assistantRevisions: List<AssistantRevision> = emptyList(),

    @ColumnInfo(name = "active_revision_index", defaultValue = "0")
    val activeRevisionIndex: Int = 0,

    @ColumnInfo(name = "timeline", defaultValue = "[]")
    val timeline: List<AssistantTimelineItem> = emptyList()
) {
    fun getEffectiveContent(): String {
        if (sender != 1 || assistantRevisions.isEmpty()) {
            return content
        }
        val safeIndex = activeRevisionIndex.coerceIn(0, assistantRevisions.lastIndex)
        return assistantRevisions[safeIndex].content
    }

    fun getEffectiveTimeline(): List<AssistantTimelineItem> {
        if (sender != 1 || assistantRevisions.isEmpty()) {
            return timeline
        }
        val safeIndex = activeRevisionIndex.coerceIn(0, assistantRevisions.lastIndex)
        val revTimeline = assistantRevisions[safeIndex].timeline
        return if (revTimeline.isNotEmpty()) revTimeline else timeline
    }

    fun getEffectiveAttachments(): List<ChatAttachment> {
        if (sender != 1 || assistantRevisions.isEmpty()) {
            return attachments
        }
        val safeIndex = activeRevisionIndex.coerceIn(0, assistantRevisions.lastIndex)
        val revAttachments = assistantRevisions[safeIndex].attachments
        return if (revAttachments.isNotEmpty()) revAttachments else attachments
    }
}
