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

data class OutcomeRecordKey(val id: String, val chatId: Int, val storedRow: Long, val updatedAt: Long, val messageId: Int? = null)

data class GitHubTimingRun(val runId: String, val createdAtSeconds: Long, val completedAtSeconds: Long?, val status: String)
data class GitHubTimingInvocation(val runId: String, val startMillis: Long, val durationMillis: Long)
data class GitHubTimingTool(val runId: String, val startSeconds: Long?, val endSeconds: Long?)
data class GitHubTimingWait(val runId: String, val checkpoint: String)

@Dao
interface WorkspaceDao {
    @Query("SELECT * FROM workspace_records WHERE chatId = :chatId AND kind = 'github_evidence' ORDER BY updatedAt DESC LIMIT 80")
    fun observeGitHubEvidence(chatId: Int): Flow<List<WorkspaceRecord>>

    @Query("SELECT w.id FROM workspace_records w JOIN chats_v2 c ON c.chat_id=w.chatId WHERE w.chatId=:chatId AND c.is_temporary=0 AND w.id GLOB 'github-evidence:*:task_contract' ORDER BY w.updatedAt DESC, w.rowid DESC LIMIT 25")
    suspend fun recentTaskContracts(chatId: Int): List<String>

    @Query("SELECT r.status FROM workspace_records w JOIN agent_runs r ON r.run_id=w.runId AND r.chat_id=w.chatId WHERE w.chatId=:chatId AND w.id IN (:snapshot,:contract) ORDER BY CASE WHEN w.id=:snapshot THEN 0 ELSE 1 END LIMIT 1")
    suspend fun taskSnapshotRunState(chatId: Int, snapshot: String, contract: String): String?

    // Timing inspection loads bounded scalar metadata, never archived tool results or model output.
    @Query("SELECT DISTINCT r.run_id AS runId, r.created_at AS createdAtSeconds, r.completed_at AS completedAtSeconds, r.status FROM workspace_records w JOIN agent_runs r ON r.run_id=w.runId AND r.chat_id=w.chatId WHERE w.chatId=:chatId AND substr(w.id,1,length(:prefix))=:prefix ORDER BY r.created_at, r.run_id LIMIT 65")
    suspend fun githubTimingRuns(chatId: Int, prefix: String): List<GitHubTimingRun>

    @Query("SELECT parentRunId AS runId, startedAt AS startMillis, durationMs AS durationMillis FROM model_invocations WHERE parentRunId IN (:runIds) AND durationMs>0 AND status!='NOT_DISPATCHED' ORDER BY startedAt, id LIMIT 4097")
    suspend fun githubTimingInvocations(runIds: List<String>): List<GitHubTimingInvocation>

    @Query("SELECT run_id AS runId, started_at AS startSeconds, completed_at AS endSeconds FROM tool_events WHERE run_id IN (:runIds) AND tool_name!='github_decide' AND substr(tool_name,1,15)!='github_decide__' AND started_at IS NOT NULL AND completed_at IS NOT NULL ORDER BY started_at, event_id LIMIT 4097")
    suspend fun githubTimingTools(runIds: List<String>): List<GitHubTimingTool>

    @Query("SELECT w.runId, substr(w.checkpoint,1,262145) AS checkpoint FROM provider_waits w JOIN agent_runs r ON r.run_id=w.runId AND r.ownership_fence=w.fence WHERE w.runId IN (:runIds) ORDER BY w.runId")
    suspend fun githubTimingWaits(runIds: List<String>): List<GitHubTimingWait>

    @Query("SELECT w.id, w.chatId, w.rowid AS storedRow, w.updatedAt, CASE WHEN m.current_run_id=r.run_id THEN r.assistant_message_id ELSE r.user_message_id END AS messageId FROM workspace_records w JOIN chats_v2 c ON c.chat_id=w.chatId LEFT JOIN agent_runs r ON r.run_id=w.runId AND r.chat_id=c.chat_id AND r.display_role != 'INTERNAL' LEFT JOIN messages_v2 m ON m.message_id=r.assistant_message_id AND m.chat_id=c.chat_id WHERE c.is_temporary=0 AND w.rowid<:beforeRow AND ((w.kind='github_evidence' AND w.id GLOB 'github-evidence:*:ev_*') OR (w.kind='github_engineering_memory' AND (w.id GLOB 'engineering:*:decision:*' OR w.id GLOB 'engineering:*:feedback:*'))) ORDER BY w.rowid DESC LIMIT 64")
    suspend fun outcomeEvidenceKeys(beforeRow: Long): List<OutcomeRecordKey>

    @Query("SELECT * FROM workspace_records WHERE kind = 'github_engineering_memory' AND runId = :scope ORDER BY updatedAt DESC LIMIT 256")
    suspend fun engineeringMemory(scope: String): List<WorkspaceRecord>

    @Query("SELECT * FROM workspace_records WHERE chatId = :chatId AND kind = 'github_checks' ORDER BY updatedAt DESC LIMIT 20")
    fun observeGitHubChecks(chatId: Int): Flow<List<WorkspaceRecord>>

    @Query("SELECT * FROM workspace_records WHERE chatId = :chatId AND substr(id, 1, length(:prefix)) = :prefix ORDER BY updatedAt, id LIMIT 4097")
    suspend fun githubTaskEvidence(chatId: Int, prefix: String): List<WorkspaceRecord>

    /** Host-created task metadata survives a changed or removed conversation binding. */
    @Query("SELECT EXISTS(SELECT 1 FROM workspace_records WHERE chatId = :chatId AND runId = :runId AND ((kind = 'github_evidence' AND id GLOB 'github-evidence:*') OR (kind = 'synthesis_checkpoint' AND (id GLOB 'github-active-task:*' OR id GLOB 'github-review:*' OR id GLOB 'github-output:*' OR id GLOB 'github-evidence:*'))))")
    suspend fun hasGitHubTaskForRun(chatId: Int, runId: String): Boolean

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
