package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

data class RunOutputLength(val runId: String, val characters: Long)

@Dao
interface AgentRunDao : RunPolicySnapshotAccess {
    @Query("SELECT run_id AS runId, profile_uid AS profileUid, provider_snapshot AS provider, model_snapshot AS model, status, created_at AS createdAt, started_at AS startedAt, completed_at AS completedAt, input_tokens AS inputTokens, output_tokens AS outputTokens, total_tokens AS totalTokens FROM agent_runs WHERE display_role != 'INTERNAL' ORDER BY created_at DESC, run_id DESC LIMIT :limit")
    fun observeHomeActivity(limit: Int = 250): Flow<List<dev.chungjungsoo.gptmobile.data.model.SettingsHomeActivity>>

    @Transaction
    suspend fun upsert(run: AgentRun) = writePolicyRun(run, upsert = true)

    @Query("SELECT * FROM agent_runs WHERE run_id = :runId")
    suspend fun rawGetById(runId: String): AgentRun?

    @Query("SELECT * FROM agent_runs WHERE chat_id = :chatId ORDER BY created_at, run_id")
    suspend fun rawGetByChatId(chatId: Int): List<AgentRun>

    @Query("SELECT r.* FROM agent_runs r JOIN messages_v2 m ON m.message_id = r.assistant_message_id AND m.current_run_id = r.run_id AND m.ownership_fence = r.ownership_fence WHERE r.synthesis_snapshot IS NOT NULL AND r.status IN ('COMPLETED','FAILED','CANCELED','INTERRUPTED')")
    suspend fun rawCombinedTerminalOwners(): List<AgentRun>

    @Query("SELECT DISTINCT r.* FROM agent_runs r LEFT JOIN agent_run_policy_refs policy_ref ON policy_ref.runId = r.run_id LEFT JOIN run_policy_snapshots policy ON policy.chatId = policy_ref.chatId AND policy.digest = policy_ref.digest WHERE r.chat_id = :chatId ORDER BY r.created_at, r.run_id")
    fun rawObserveByChatId(chatId: Int): Flow<List<AgentRun>>

    @Query("SELECT DISTINCT r.* FROM agent_runs r LEFT JOIN agent_run_policy_refs policy_ref ON policy_ref.runId = r.run_id LEFT JOIN run_policy_snapshots policy ON policy.chatId = policy_ref.chatId AND policy.digest = policy_ref.digest WHERE r.status IN ('QUEUED', 'RUNNING', 'WAITING_PROVIDER_LIMIT', 'WAITING_REMOTE_JOB')")
    fun rawObserveActive(): Flow<List<AgentRun>>

    @Query("SELECT DISTINCT r.* FROM agent_runs r LEFT JOIN agent_run_policy_refs policy_ref ON policy_ref.runId = r.run_id LEFT JOIN run_policy_snapshots policy ON policy.chatId = policy_ref.chatId AND policy.digest = policy_ref.digest ORDER BY r.created_at DESC, r.run_id DESC LIMIT :limit")
    fun rawObserveRecent(limit: Int = 100): Flow<List<AgentRun>>

    // Join on both IDs: retries reuse the message, but must not reuse its latest answer.
    // Count in SQLite so analytics never loads full response bodies into memory.
    @Query(
        "SELECT r.run_id AS runId, CASE WHEN m.content LIKE '@gpt-archive:gzip:v1:%' THEN CAST(substr(m.content, 22, instr(substr(m.content, 22), ':') - 1) AS INTEGER) ELSE LENGTH(TRIM(m.content)) END AS characters " +
            "FROM (SELECT * FROM agent_runs ORDER BY created_at DESC, run_id DESC LIMIT :limit) r " +
            "JOIN messages_v2 m ON m.message_id = r.assistant_message_id AND m.current_run_id = r.run_id " +
            "WHERE r.output_tokens IS NULL AND r.status = 'COMPLETED' AND m.platform_type IS NOT NULL"
    )
    fun observeUnreportedOutputLengths(limit: Int = 10_000): Flow<List<RunOutputLength>>

    @Query(
        "UPDATE agent_runs SET status = :status, started_at = :startedAt, " +
            "completed_at = :completedAt, terminal_error = :terminalError WHERE run_id = :runId"
    )
    suspend fun updateStatus(
        runId: String,
        status: String,
        startedAt: Long?,
        completedAt: Long?,
        terminalError: String?
    )

    @Query(
        "UPDATE agent_runs SET status = 'RUNNING', started_at = :startedAt, " +
            "completed_at = NULL, terminal_error = NULL WHERE run_id = :runId AND status = 'QUEUED' AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.message_id = agent_runs.assistant_message_id AND m.current_run_id = agent_runs.run_id AND m.ownership_fence = agent_runs.ownership_fence)"
    )
    suspend fun markRunning(runId: String, startedAt: Long): Int

    @Query(
        "UPDATE agent_runs SET status = :status, completed_at = :completedAt, terminal_error = :terminalError " +
            "WHERE run_id = :runId AND status = 'RUNNING' AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.message_id = agent_runs.assistant_message_id AND m.current_run_id = agent_runs.run_id AND m.ownership_fence = agent_runs.ownership_fence)"
    )
    suspend fun finishRunning(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Int

    @Query(
        "UPDATE agent_runs SET status = :status, completed_at = :completedAt, terminal_error = :terminalError " +
            "WHERE run_id = :runId AND status = 'QUEUED' AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.message_id = agent_runs.assistant_message_id AND m.current_run_id = agent_runs.run_id AND m.ownership_fence = agent_runs.ownership_fence)"
    )
    suspend fun finishQueued(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Int

    @Query(
        "UPDATE agent_runs SET status = :status, completed_at = :completedAt, terminal_error = :terminalError " +
            "WHERE run_id = :runId AND status IN ('QUEUED', 'RUNNING', 'WAITING_PROVIDER_LIMIT', 'WAITING_REMOTE_JOB') AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.message_id = agent_runs.assistant_message_id AND m.current_run_id = agent_runs.run_id AND m.ownership_fence = agent_runs.ownership_fence)"
    )
    suspend fun finishActive(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Int

    @Query(
        "UPDATE agent_runs SET status = 'INTERRUPTED', completed_at = :completedAt " +
            "WHERE status IN ('QUEUED', 'RUNNING') AND NOT (status = 'QUEUED' AND started_at IS NULL AND EXISTS (SELECT 1 FROM provider_waits w WHERE w.runId = agent_runs.run_id AND w.fence = agent_runs.ownership_fence))"
    )
    suspend fun interruptActiveRuns(completedAt: Long): Int

    @Query("UPDATE agent_runs SET gateway_job_id = :jobId, gateway_base_url = :baseUrl WHERE run_id = :runId")
    suspend fun bindGatewayJob(runId: String, jobId: String, baseUrl: String): Int

    @Query("UPDATE agent_runs SET gateway_last_sequence = :sequence WHERE run_id = :runId AND gateway_last_sequence < :sequence")
    suspend fun advanceGatewaySequence(runId: String, sequence: Int): Int

    @Query(
        "UPDATE agent_runs SET input_tokens = COALESCE(:inputTokens, input_tokens), " +
            "output_tokens = COALESCE(:outputTokens, output_tokens), " +
            "total_tokens = COALESCE(:totalTokens, total_tokens) WHERE run_id = :runId"
    )
    suspend fun updateUsage(
        runId: String,
        inputTokens: Int?,
        outputTokens: Int?,
        totalTokens: Int?
    ): Int

    @Query("SELECT * FROM agent_runs WHERE gateway_job_id IS NOT NULL AND status IN ('QUEUED', 'RUNNING', 'INTERRUPTED')")
    suspend fun rawGetRecoverableGatewayRuns(): List<AgentRun>

    suspend fun getById(runId: String): AgentRun? = hydratePolicyRun(rawGetById(runId))
    suspend fun getByChatId(chatId: Int): List<AgentRun> = hydratePolicyRuns(rawGetByChatId(chatId))
    suspend fun getReadableByChatId(chatId: Int): List<AgentRun> = hydratePolicyRuns(rawGetByChatId(chatId), readableProjection = true)
    suspend fun combinedTerminalOwners(): List<AgentRun> = hydratePolicyRuns(rawCombinedTerminalOwners(), readableProjection = true).filterNot { it.terminalError?.startsWith("POLICY_SNAPSHOT_") == true }
    suspend fun getRecoverableGatewayRuns(): List<AgentRun> = hydratePolicyRuns(rawGetRecoverableGatewayRuns(), readableProjection = true).filterNot { it.terminalError?.startsWith("POLICY_SNAPSHOT_") == true }
    fun observeByChatId(chatId: Int): Flow<List<AgentRun>> = rawObserveByChatId(chatId).map { hydratePolicyRuns(it, readableProjection = true) }.flowOn(Dispatchers.IO)
    fun observeActive(): Flow<List<AgentRun>> = rawObserveActive().map { hydratePolicyRuns(it, readableProjection = true) }.flowOn(Dispatchers.IO)
    fun observeRecent(limit: Int = 100): Flow<List<AgentRun>> = rawObserveRecent(limit).map { hydratePolicyRuns(it, readableProjection = true) }.flowOn(Dispatchers.IO)
}
