package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "tool_events",
    indices = [
        Index("run_id"),
        Index("event_id", unique = true)
    ]
)
data class ToolEvent(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,

    @ColumnInfo(name = "event_id")
    val eventId: String,

    @ColumnInfo(name = "run_id")
    val runId: String,

    @ColumnInfo(name = "call_id")
    val callId: String,

    @ColumnInfo(name = "sequence")
    val sequence: Int = 0,

    @ColumnInfo(name = "server_id")
    val serverId: String,

    @ColumnInfo(name = "tool_name")
    val toolName: String,

    @ColumnInfo(name = "input")
    val input: String,

    @ColumnInfo(name = "result")
    val result: String? = null,

    @ColumnInfo(name = "result_type")
    val resultType: String? = null,

    @ColumnInfo(name = "status")
    val status: String = "PENDING",

    @ColumnInfo(name = "is_error")
    val isError: Boolean = false,

    @ColumnInfo(name = "error")
    val error: String? = null,

    @ColumnInfo(name = "started_at")
    val startedAt: Long? = null,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)
