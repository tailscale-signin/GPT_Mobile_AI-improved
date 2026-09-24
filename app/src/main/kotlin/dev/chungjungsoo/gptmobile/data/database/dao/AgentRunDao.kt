package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentRunDao {
    @Upsert
    suspend fun upsert(run: AgentRun)

    @Query("SELECT * FROM agent_runs WHERE run_id = :runId")
    suspend fun getById(runId: String): AgentRun?

    @Query("SELECT * FROM agent_runs WHERE chat_id = :chatId ORDER BY created_at, run_id")
    suspend fun getByChatId(chatId: Int): List<AgentRun>

    @Query("SELECT * FROM agent_runs WHERE chat_id = :chatId ORDER BY created_at, run_id")
    fun observeByChatId(chatId: Int): Flow<List<AgentRun>>

    @Query("SELECT * FROM agent_runs ORDER BY created_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<AgentRun>>

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
            "completed_at = NULL, terminal_error = NULL WHERE run_id = :runId AND status = 'QUEUED'"
    )
    suspend fun markRunning(runId: String, startedAt: Long): Int

    @Query(
        "UPDATE agent_runs SET status = :status, completed_at = :completedAt, terminal_error = :terminalError " +
            "WHERE run_id = :runId AND status = 'RUNNING'"
    )
    suspend fun finishRunning(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Int

    @Query(
        "UPDATE agent_runs SET status = :status, completed_at = :completedAt, terminal_error = :terminalError " +
            "WHERE run_id = :runId AND status = 'QUEUED'"
    )
    suspend fun finishQueued(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Int

    @Query(
        "UPDATE agent_runs SET status = :status, completed_at = :completedAt, terminal_error = :terminalError " +
            "WHERE run_id = :runId AND status IN ('QUEUED', 'RUNNING')"
    )
    suspend fun finishActive(
        runId: String,
        status: String,
        completedAt: Long,
        terminalError: String?
    ): Int

    @Query(
        "UPDATE agent_runs SET status = 'INTERRUPTED', completed_at = :completedAt " +
            "WHERE status IN ('QUEUED', 'RUNNING')"
    )
    suspend fun interruptActiveRuns(completedAt: Long): Int

    @Query("UPDATE agent_runs SET gateway_job_id = :jobId, gateway_base_url = :baseUrl WHERE run_id = :runId")
    suspend fun bindGatewayJob(runId: String, jobId: String, baseUrl: String): Int

    @Query("UPDATE agent_runs SET gateway_last_sequence = :sequence WHERE run_id = :runId AND gateway_last_sequence < :sequence")
    suspend fun advanceGatewaySequence(runId: String, sequence: Int): Int

    @Query("SELECT * FROM agent_runs WHERE gateway_job_id IS NOT NULL AND status IN ('QUEUED', 'RUNNING', 'INTERRUPTED')")
    suspend fun getRecoverableGatewayRuns(): List<AgentRun>
}
