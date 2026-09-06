package dev.melo.gptmobile.improved.data.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class AgentRunStatus {
    IDLE,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class AgentRunDraft(
    val runUid: String,
    val profileUid: String,
    val chatId: Int,
    val turnIndex: Int,
    val status: AgentRunStatus,
    val prompt: String,
    val totalSteps: Int = 0,
    val createdAt: Long = System.currentTimeMillis() / 1000
)

data class PersistAgentTurnRequest(
    val runUid: String,
    val profileUid: String,
    val chatId: Int,
    val turnIndex: Int,
    val prompt: String,
    val response: String,
    val totalSteps: Int
)

data class PersistAgentRetryRequest(
    val runUid: String,
    val turnIndex: Int,
    val prompt: String,
    val response: String,
    val totalSteps: Int
)

@Entity(
    tableName = "agent_runs",
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
        Index(value = ["profile_uid"]),
        Index(value = ["run_uid"], unique = true)
    ]
)
data class AgentRun(
    @PrimaryKey
    @ColumnInfo(name = "run_uid")
    val runUid: String,

    @ColumnInfo(name = "profile_uid")
    val profileUid: String,

    @ColumnInfo(name = "chat_id")
    val chatId: Int,

    @ColumnInfo(name = "turn_index")
    val turnIndex: Int,

    @ColumnInfo(name = "status")
    val status: AgentRunStatus,

    @ColumnInfo(name = "prompt")
    val prompt: String,

    @ColumnInfo(name = "total_steps")
    val totalSteps: Int = 0,

    @ColumnInfo(name = "created_at")
    val createdAt: Long = System.currentTimeMillis() / 1000,

    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null
)
