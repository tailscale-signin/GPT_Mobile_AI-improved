package dev.chungjungsoo.gptmobile.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dev.chungjungsoo.gptmobile.data.model.PlatformType
import java.util.Date

@Entity(
    tableName = "messages_v2",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoomV2::class,
            parentColumns = ["chat_room_id"],
            childColumns = ["chat_room_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["chat_room_id"]),
        Index(value = ["created_at"])
    ]
)
data class MessageV2(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "message_id")
    val id: Int = 0,
    @ColumnInfo(name = "chat_room_id") val chatRoomId: Int,
    @ColumnInfo(name = "platform_type") val platformType: PlatformType?,
    @ColumnInfo(name = "model") val model: String?,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "image_paths", defaultValue = "[]") val imagePaths: List<String> = emptyList(),
    @ColumnInfo(name = "audio_path") val audioPath: String? = null,
    @ColumnInfo(name = "file_attachments", defaultValue = "[]") val fileAttachments: List<String> = emptyList(),
    @ColumnInfo(name = "revisions", defaultValue = "[]") val revisions: List<String> = emptyList(),
    @ColumnInfo(name = "current_revision_index", defaultValue = "0") val currentRevisionIndex: Int = 0,
    @ColumnInfo(name = "is_favorite", defaultValue = "0") val isFavorite: Boolean = false,
    @ColumnInfo(name = "created_at") val createdAt: Date
)
