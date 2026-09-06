package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import dev.melo.gptmobile.improved.data.model.ChatAttachment

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatRoom::class,
            parentColumns = ["chat_id"],
            childColumns = ["chat_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["chat_id"])]
)
@TypeConverters(ChatAttachmentListConverter::class)
data class Message(
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
    val attachments: List<ChatAttachment> = emptyList()
)
