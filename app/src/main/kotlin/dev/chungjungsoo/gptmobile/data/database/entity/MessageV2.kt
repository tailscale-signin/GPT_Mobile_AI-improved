package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import dev.chungjungsoo.gptmobile.data.dto.ChatAttachment
import dev.chungjungsoo.gptmobile.data.dto.ChatAttachmentDto
import dev.chungjungsoo.gptmobile.data.dto.toDto
import dev.chungjungsoo.gptmobile.data.dto.toModel
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
@Entity(
    tableName = "messages_v2",
    indices = [
        Index(value = ["chat_id"]),
        Index(value = ["is_favorite"])
    ]
)
data class MessageV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "message_id")
    val id: Int = 0,

    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "thoughts")
    val thoughts: String = "",

    @ColumnInfo(name = "revisions", defaultValue = "'[]'")
    val revisions: List<AssistantRevision> = emptyList(),

    @ColumnInfo(name = "current_revision_index", defaultValue = "0")
    val activeRevisionIndex: Int = ACTIVE_REVISION_LATEST,

    @ColumnInfo(name = "attachments")
    val attachments: List<Attachment> = emptyList(),

    @ColumnInfo(name = "linked_message_id")
    val linkedMessageId: Int = 0,

    @ColumnInfo(name = "platform_type")
    val platformType: String?,

    @ColumnInfo(name = "current_run_id")
    val currentRunId: String? = null,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "timeline", defaultValue = "'[]'")
    val timeline: List<AssistantTimelineItem> = emptyList(),

    @ColumnInfo(name = "is_favorite", defaultValue = "0")
    val isFavorite: Boolean = false,

    @ColumnInfo(name = "timestamp", defaultValue = "0")
    val timestamp: Long = 0
)

@Serializable
data class AssistantRevision(
    val content: String,
    val thoughts: String = "",
    val createdAt: Long,
    val runId: String? = null,
    val timeline: List<AssistantTimelineItem> = emptyList()
)

const val ACTIVE_REVISION_LATEST = -1

fun MessageV2.hasHistoricalRevisionSelected(): Boolean = activeRevisionIndex in revisions.indices

fun MessageV2.effectiveContent(): String = revisions
    .getOrNull(activeRevisionIndex)
    ?.content
    ?: content

fun MessageV2.effectiveThoughts(): String = revisions
    .getOrNull(activeRevisionIndex)
    ?.thoughts
    ?: thoughts

fun MessageV2.effectiveTimeline(): List<AssistantTimelineItem> = revisions
    .getOrNull(activeRevisionIndex)
    ?.timeline
    ?: timeline

fun MessageV2.effectiveRunId(): String? = if (hasHistoricalRevisionSelected()) {
    revisions[activeRevisionIndex].runId
} else {
    currentRunId
}

fun MessageV2.isEffectivelyBlank(): Boolean = effectiveContent().isBlank() &&
    effectiveThoughts().isBlank() &&
    effectiveTimeline().isEmpty() &&
    attachments.isEmpty()

fun MessageV2.resetActiveRevision(): MessageV2 = copy(activeRevisionIndex = ACTIVE_REVISION_LATEST)

fun MessageV2.selectRevision(index: Int): MessageV2 = copy(
    activeRevisionIndex = if (index in revisions.indices) index else ACTIVE_REVISION_LATEST
)

fun MessageV2.snapshotLatestAssistantRevision(timestamp: Long = System.currentTimeMillis() / 1000): AssistantRevision? {
    if (platformType == null) return null
    if (content.isBlank() && thoughts.isBlank() && timeline.isEmpty()) return null

    return AssistantRevision(
        content = content,
        thoughts = thoughts,
        createdAt = timestamp,
        runId = currentRunId,
        timeline = timeline
    )
}

@Serializable
data class AssistantTimelineItem(
    val type: AssistantTimelineItemType,
    val content: String? = null,
    val callId: String? = null,
    val toolSequence: Int? = null,
    val timestamp: Long = System.currentTimeMillis() / 1000
)

enum class AssistantTimelineItemType {
    THINKING,
    TOOL,
    TEXT,
    NOTICE,
    LEGACY_ORDER,
    ANSWER
}

@Serializable
data class Attachment(
    val filePathForDisplay: String,
    val localFilePath: String = filePathForDisplay,
    val preparedFilePath: String = "",
    val mimeType: String = "",
    val originalFileName: String = "",
    val fileSize: Long = 0L,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
    val providerAttachmentIds: Map<String, String> = emptyMap()
)

fun Attachment.toDto(): ChatAttachmentDto = ChatAttachmentDto(
    filePathForDisplay = filePathForDisplay,
    localFilePath = localFilePath,
    preparedFilePath = preparedFilePath,
    mimeType = mimeType,
    originalFileName = originalFileName,
    fileSize = fileSize,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    providerAttachmentIds = providerAttachmentIds
)

fun ChatAttachment.toEntity(): Attachment = Attachment(
    filePathForDisplay = filePathForDisplay,
    localFilePath = localFilePath,
    preparedFilePath = preparedFilePath,
    mimeType = mimeType,
    originalFileName = originalFileName,
    fileSize = fileSize,
    imageWidth = imageWidth,
    imageHeight = imageHeight,
    providerAttachmentIds = providerAttachmentIds
)

class ChatAttachmentListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromString(value: String?): List<Attachment> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<Attachment>>(value)
        }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromList(value: List<Attachment>?): String {
        if (value.isNullOrEmpty()) return "[]"
        return json.encodeToString(value)
    }
}

class AssistantRevisionListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromString(value: String?): List<AssistantRevision> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AssistantRevision>>(value)
        }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromList(value: List<AssistantRevision>?): String {
        if (value.isNullOrEmpty()) return "[]"
        return json.encodeToString(value)
    }
}

class AssistantTimelineListConverter {
    private val json = Json { ignoreUnknownKeys = true }

    @TypeConverter
    fun fromString(value: String?): List<AssistantTimelineItem> {
        if (value.isNullOrBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<AssistantTimelineItem>>(value)
        }.getOrDefault(emptyList())
    }

    @TypeConverter
    fun fromList(value: List<AssistantTimelineItem>?): String {
        if (value.isNullOrEmpty()) return "[]"
        return json.encodeToString(value)
    }
}
