package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.RunPolicySnapshotCodec
import dev.chungjungsoo.gptmobile.data.database.RunPolicySnapshotException
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunPolicyRef
import dev.chungjungsoo.gptmobile.data.database.entity.RunPolicySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Shared by both public run DAOs. No caller outside persistence receives a storage reference. */
interface RunPolicySnapshotAccess {
    @Query("SELECT is_temporary FROM chats_v2 WHERE chat_id = :chatId")
    suspend fun policyChatTemporary(chatId: Int): Boolean?

    @Query("SELECT p.* FROM run_policy_snapshots p WHERE p.chatId = :chatId AND p.digest IN (:digests)")
    suspend fun policyRows(chatId: Int, digests: List<String>): List<RunPolicySnapshot>

    @Query("SELECT DISTINCT p.* FROM run_policy_snapshots p JOIN agent_run_policy_refs r ON r.chatId = p.chatId AND r.digest = p.digest WHERE r.runId IN (:runIds)")
    suspend fun policyRowsForRuns(runIds: List<String>): List<RunPolicySnapshot>

    @Query("SELECT * FROM agent_run_policy_refs WHERE runId IN (:runIds)")
    suspend fun policyRefsForRuns(runIds: List<String>): List<AgentRunPolicyRef>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertPolicyRows(rows: List<RunPolicySnapshot>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPolicyRefs(refs: List<AgentRunPolicyRef>)

    @Query("DELETE FROM agent_run_policy_refs WHERE runId = :runId")
    suspend fun removePolicyRefs(runId: String)

    @Query("DELETE FROM run_policy_snapshots WHERE chatId = :chatId AND NOT EXISTS (SELECT 1 FROM agent_run_policy_refs r WHERE r.chatId = run_policy_snapshots.chatId AND r.digest = run_policy_snapshots.digest)")
    suspend fun removeUnusedPolicies(chatId: Int)

    @Insert
    suspend fun rawInsertPolicyRun(run: AgentRun)

    @Upsert
    suspend fun rawUpsertPolicyRun(run: AgentRun)

    @Transaction
    suspend fun writePolicyRun(run: AgentRun, upsert: Boolean) = withContext(Dispatchers.IO) {
        val inline = if (RunPolicySnapshotCodec.isReference(run.synthesisSnapshot)) hydratePolicyRuns(listOf(run)).single() else run
        val temporary = policyChatTemporary(run.chatId)
            ?: throw RunPolicySnapshotException("POLICY_SNAPSHOT_CHAT_DELETED")
        val normalized = if (temporary) null else RunPolicySnapshotCodec.normalize(inline)
        // Removing old links can collect their orphan rows. Reinsert the immutable new set afterwards.
        removePolicyRefs(run.runId)
        if (normalized != null) {
            insertPolicyRows(normalized.policies)
            val retained = policyRows(run.chatId, normalized.policies.map { it.digest }).associateBy { it.digest }
            if (normalized.policies.any { retained[it.digest] != it }) throw RunPolicySnapshotException("POLICY_SNAPSHOT_INVALID")
        }
        val stored = if (normalized == null) inline else inline.copy(synthesisSnapshot = normalized.snapshot)
        if (upsert) rawUpsertPolicyRun(stored) else rawInsertPolicyRun(stored)
        if (normalized != null) insertPolicyRefs(normalized.policies.map { AgentRunPolicyRef(run.runId, run.chatId, it.digest) })
        removeUnusedPolicies(run.chatId)
    }

    suspend fun hydratePolicyRun(run: AgentRun?): AgentRun? = run?.let { hydratePolicyRuns(listOf(it)).single() }

    suspend fun hydratePolicyRuns(runs: List<AgentRun>, readableProjection: Boolean = false): List<AgentRun> = withContext(Dispatchers.IO) {
        val ids = runs.map { it.runId }
        if (ids.isEmpty()) return@withContext runs
        // Keep below SQLite's bind limit. Current links/rows are fetched even when parsed data is cached.
        val refs = ids.chunked(500).flatMap { policyRefsForRuns(it) }
        val rows = refs.map { it.runId }.distinct().chunked(500).flatMap { policyRowsForRuns(it) }.distinct()
        runs.map { run ->
            try {
                RunPolicySnapshotCodec.hydrate(run, rows, refs).also { hydrated ->
                    dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.prepareDecoded(hydrated.synthesisSnapshot, hydrated.chatId)
                }
            } catch (error: RunPolicySnapshotException) {
                if (!readableProjection) throw error
                RunPolicySnapshotCodec.blockedProjection(run, error)
            }
        }
    }
}
