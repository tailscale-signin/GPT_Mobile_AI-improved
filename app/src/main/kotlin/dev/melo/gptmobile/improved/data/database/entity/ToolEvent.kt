package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.Serializable

@Serializable
enum class ToolEventStatus {
    PENDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELLED
}

@Serializable
enum class ToolEventResultType {
    TEXT,
    JSON,
    IMAGE,
    EMPTY
}

@Serializable
data class ToolEventError(
    val code: String? = null,
    val message: String
)

@Entity(
    tableName = "tool_events",
    foreignKeys = [
        ForeignKey(
            entity = MessageV2::class,
            parentColumns = ["message_id"],
            childColumns = ["message_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["call_id"])
    ]
)
data class ToolEvent(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "event_id")
    val eventId: Long = 0,

    @ColumnInfo(name = "message_id")
    val messageId: Int,

    @ColumnInfo(name = "call_id")
    val callId: String,

    @ColumnInfo(name = "tool_name")
    val toolName: String,

    @ColumnInfo(name = "arguments")
    val arguments: String,

    @ColumnInfo(name = "status")
    val status: ToolEventStatus,

    @ColumnInfo(name = "result")
    val result: String? = null,

    @ColumnInfo(name = "result_type")
    val resultType: ToolEventResultType = ToolEventResultType.EMPTY,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)
