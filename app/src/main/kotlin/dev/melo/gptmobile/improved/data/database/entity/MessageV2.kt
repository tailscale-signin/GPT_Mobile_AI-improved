package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import dev.melo.gptmobile.improved.data.model.ChatAttachment
import kotlinx.serialization.Serializable

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
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["chat_id", "created_at", "message_id"]),
        Index(value = ["is_favorite"])
    ]
)
@TypeConverters(
    ChatAttachmentListConverter::class,
    AssistantRevisionListConverter::class,
    AssistantTimelineListConverter::class
)
data class MessageV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "message_id")
    val id: Int = 0,

    @ColumnInfo(name = "chat_id")
    val chatId: Int = 0,

    @ColumnInfo(name = "sender", defaultValue = "0")
    val sender: Int = 0,

    @ColumnInfo(name = "thoughts", defaultValue = "''")
    val thoughts: String = "",

    @ColumnInfo(name = "content")
    val content: String = "",

    @ColumnInfo(name = "attachments", defaultValue = "[]")
    val attachments: List<ChatAttachment> = emptyList(),

    @ColumnInfo(name = "revisions", defaultValue = "[]")
    val revisions: List<AssistantRevision> = emptyList(),

    @ColumnInfo(name = "assistant_revisions", defaultValue = "[]")
    val assistantRevisions: List<AssistantRevision> = emptyList(),

    @ColumnInfo(name = "active_revision_index", defaultValue = "-1")
    val activeRevisionIndex: Int = ACTIVE_REVISION_LATEST,

    @ColumnInfo(name = "linked_message_id", defaultValue = "0")
    val linkedMessageId: Int = 0,

    @ColumnInfo(name = "platform_type")
    val platformType: String? = null,

    @ColumnInfo(name = "current_run_id")
    val currentRunId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "timeline", defaultValue = "[]")
    val timeline: List<AssistantTimelineItem> = emptyList(),

    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false
) {
    // Binary compatibility for legacy callers expecting `messageId`
    val messageId: Int get() = id

    fun getEffectiveContent(): String {
        val effectiveRevisions = revisions.ifEmpty { assistantRevisions }
        if (effectiveRevisions.isNotEmpty() && activeRevisionIndex in effectiveRevisions.indices) {
            return effectiveRevisions[activeRevisionIndex].content
        }
        return content
    }

    fun getEffectiveTimeline(): List<AssistantTimelineItem> {
        val effectiveRevisions = revisions.ifEmpty { assistantRevisions }
        if (effectiveRevisions.isNotEmpty() && activeRevisionIndex in effectiveRevisions.indices) {
            val revTimeline = effectiveRevisions[activeRevisionIndex].timeline
            if (revTimeline.isNotEmpty()) return revTimeline
        }
        return timeline
    }

    fun getEffectiveAttachments(): List<ChatAttachment> {
        return attachments
    }
}

@Serializable
data class AssistantRevision(
    val revisionId: String = "",
    val content: String = "",
    val thoughts: String = "",
    val createdAt: Long = System.currentTimeMillis() / 1000,
    val runId: String? = null,
    val timeline: List<AssistantTimelineItem> = emptyList(),
    val attachments: List<ChatAttachment> = emptyList()
)

const val ACTIVE_REVISION_LATEST = -1

fun MessageV2.hasHistoricalRevisionSelected(): Boolean {
    val effectiveRevisions = revisions.ifEmpty { assistantRevisions }
    return activeRevisionIndex in effectiveRevisions.indices
}

fun MessageV2.effectiveContent(): String = getEffectiveContent()

fun MessageV2.effectiveThoughts(): String {
    val effectiveRevisions = revisions.ifEmpty { assistantRevisions }
    return effectiveRevisions.getOrNull(activeRevisionIndex)?.thoughts ?: thoughts
}

fun MessageV2.effectiveTimeline(): List<AssistantTimelineItem> = getEffectiveTimeline()

fun MessageV2.effectiveRunId(): String? = if (hasHistoricalRevisionSelected()) {
    revisions.ifEmpty { assistantRevisions }[activeRevisionIndex].runId
} else {
    currentRunId
}

fun MessageV2.isEffectivelyBlank(): Boolean = effectiveContent().isBlank() &&
    effectiveThoughts().isBlank() &&
    effectiveTimeline().isEmpty() &&
    attachments.isEmpty()

fun MessageV2.resetActiveRevision(): MessageV2 = copy(activeRevisionIndex = ACTIVE_REVISION_LATEST)

fun MessageV2.selectRevision(index: Int): MessageV2 {
    val effectiveRevisions = revisions.ifEmpty { assistantRevisions }
    return copy(activeRevisionIndex = if (index in effectiveRevisions.indices) index else ACTIVE_REVISION_LATEST)
}

fun MessageV2.snapshotLatestAssistantRevision(timestamp: Long = System.currentTimeMillis() / 1000): AssistantRevision? {
    if (platformType == null) return null
    if (content.isBlank() && thoughts.isBlank() && timeline.isEmpty()) return null

    return AssistantRevision(
        content = content,
        thoughts = thoughts,
        createdAt = timestamp,
        runId = currentRunId,
        timeline = timeline,
        attachments = attachments
    )
}
