package dev.chungjungsoo.gptmobile.data.workspace

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.Serializable

/** Versioned user work products. Execution remains owned by agent_runs and pending_prompts. */
@Entity(tableName = "workspace_records", foreignKeys = [ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["chatId"], onDelete = ForeignKey.CASCADE)], indices = [Index("chatId"), Index("kind"), Index("runId")])
@kotlinx.serialization.Serializable
data class WorkspaceRecord(
    @PrimaryKey val id: String,
    val kind: String,
    val title: String,
    val payload: String,
    val chatId: Int? = null,
    val runId: String? = null,
    val updatedAt: Long = System.currentTimeMillis(),
    val version: Int = 1
)

@Dao
interface WorkspaceDao {
    @Upsert suspend fun save(record: WorkspaceRecord)

    @Query("SELECT * FROM workspace_records WHERE chatId = :chatId ORDER BY updatedAt, id")
    suspend fun forChat(chatId: Int): List<WorkspaceRecord>

    @Query("SELECT * FROM workspace_records ORDER BY updatedAt DESC LIMIT 500")
    fun observe(): Flow<List<WorkspaceRecord>>

    @Query("SELECT * FROM workspace_records WHERE runId = :runId AND kind = 'context' ORDER BY updatedAt DESC")
    fun observeRun(runId: String): Flow<List<WorkspaceRecord>>

    @Query("SELECT * FROM workspace_records WHERE id = :id")
    suspend fun get(id: String): WorkspaceRecord?

    @Query("DELETE FROM workspace_records WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM workspace_records WHERE kind = :kind ORDER BY updatedAt DESC LIMIT 100")
    suspend fun list(kind: String): List<WorkspaceRecord>
}

@Serializable
data class ContextReceipt(
    val profileUid: String,
    val model: String,
    val messageIds: List<Int>,
    val facts: Map<String, String>,
    val documents: String,
    val tools: List<String>,
    val attachments: List<String>,
    val systemDigest: String,
    val notice: String,
    val outputLimit: Int?,
    val reasoning: Boolean?,
    val delegation: Boolean,
    val requestDigest: String,
    val protectedReference: String? = null
)

@Serializable
data class ContextExclusions(val facts: Set<String> = emptySet(), val attachments: Set<String> = emptySet(), val documents: Boolean = false)

@Serializable
data class ResearchPin(val url: String, val excerpt: String, val claim: String, val eventId: String, val retrievedAt: Long, val kind: String)

@Serializable
data class TaskRecipe(val prompt: String, val profileUid: String, val outputFormat: String = "A concise, sourced brief", val localOnly: Boolean = true, val scheduled: Boolean = false, val intervalHours: Long = 24, val lastQueuedAt: Long = 0)
