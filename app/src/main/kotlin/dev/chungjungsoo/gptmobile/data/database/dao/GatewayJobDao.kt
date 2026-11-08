package dev.chungjungsoo.gptmobile.data.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayConversationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringBindingRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayEngineeringEventRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayJobRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessagePartRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMessageRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewayMigrationRecord
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow

@Dao
interface GatewayJobDao {
    @Query("SELECT * FROM gateway_jobs WHERE connectionProfileUid=:profileUid AND endpoint=:endpoint ORDER BY observedAtMillis DESC, key")
    fun observeJobs(profileUid: String, endpoint: String): Flow<List<GatewayJobRecord>>

    @Query("SELECT * FROM gateway_jobs WHERE `key`=:key")
    suspend fun job(key: String): GatewayJobRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveJobs(jobs: List<GatewayJobRecord>)

    @Query("UPDATE gateway_jobs SET seenRevision=:revision WHERE `key`=:key")
    suspend fun markSeen(key: String, revision: Long)

    @Query("SELECT * FROM gateway_conversations WHERE `key`=:key")
    suspend fun conversation(key: String): GatewayConversationRecord?

    @Query("SELECT * FROM gateway_conversations WHERE localChatId=:chatId")
    suspend fun conversationsForChat(chatId: Int): List<GatewayConversationRecord>

    @Query("SELECT model FROM chat_platform_model_v2 WHERE chat_id=:chatId AND platform_uid=:profileUid")
    suspend fun selectedModel(chatId: Int, profileUid: String): String?

    @Query("SELECT * FROM gateway_submissions WHERE chatId=:chatId ORDER BY createdAtMillis")
    suspend fun submissionsForChat(chatId: Int): List<GatewaySubmissionRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveConversation(conversation: GatewayConversationRecord)

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:key ORDER BY createdAt, messageId")
    fun observeTranscript(key: String): Flow<List<GatewayMessageRecord>>

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:key ORDER BY createdAt, messageId")
    suspend fun messages(key: String): List<GatewayMessageRecord>

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:key AND messageId=:messageId")
    suspend fun message(key: String, messageId: String): GatewayMessageRecord?

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:key AND turnId=:turnId AND role='user' ORDER BY createdAt LIMIT 1")
    suspend fun userForTurn(key: String, turnId: String): GatewayMessageRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMessage(message: GatewayMessageRecord)

    @Query("SELECT * FROM gateway_message_parts WHERE conversationKey=:key AND logicalMessageId=:messageId AND revision=:revision AND partIndex=:index")
    suspend fun messagePart(key: String, messageId: String, revision: Long, index: Int): GatewayMessagePartRecord?

    @Query("SELECT * FROM gateway_message_parts WHERE conversationKey=:key AND messageId=:messageId LIMIT 1")
    suspend fun physicalMessagePart(key: String, messageId: String): GatewayMessagePartRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun saveMessagePart(part: GatewayMessagePartRecord)

    @Query("SELECT COUNT(*) FROM gateway_message_parts WHERE conversationKey=:key AND logicalMessageId=:messageId AND revision=:revision")
    suspend fun messagePartCount(key: String, messageId: String, revision: Long): Int

    @Query("SELECT * FROM gateway_message_parts WHERE conversationKey=:key AND logicalMessageId=:messageId AND revision=:revision ORDER BY partIndex LIMIT 8 OFFSET :offset")
    suspend fun messageParts(key: String, messageId: String, revision: Long, offset: Int): List<GatewayMessagePartRecord>

    @Query("UPDATE gateway_message_parts SET contentVerified=1 WHERE conversationKey=:key AND logicalMessageId=:messageId AND revision=:revision")
    suspend fun verifyMessageParts(key: String, messageId: String, revision: Long)

    @Query("DELETE FROM gateway_message_parts WHERE conversationKey=:key AND logicalMessageId=:messageId AND revision<:revision")
    suspend fun pruneMessageParts(key: String, messageId: String, revision: Long)

    @Query("SELECT p.* FROM gateway_message_parts p WHERE p.conversationKey=:key AND p.revision=(SELECT MAX(n.revision) FROM gateway_message_parts n WHERE n.conversationKey=p.conversationKey AND n.logicalMessageId=p.logicalMessageId) ORDER BY p.createdAt,p.logicalMessageId,p.partIndex LIMIT 8 OFFSET :offset")
    suspend fun transcriptParts(key: String, offset: Int): List<GatewayMessagePartRecord>

    @Query("SELECT COUNT(*) FROM gateway_message_parts WHERE conversationKey=:key AND contentVerified=0")
    suspend fun incompleteMessageParts(key: String): Int

    @Query("SELECT * FROM gateway_engineering_bindings WHERE (:chatId IS NULL OR chatId=:chatId) ORDER BY updatedAtMillis, requestId")
    suspend fun bindings(chatId: Int?): List<GatewayEngineeringBindingRecord>

    @Query("SELECT * FROM gateway_engineering_bindings WHERE requestId=:requestId")
    suspend fun binding(requestId: String): GatewayEngineeringBindingRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun importBinding(binding: GatewayEngineeringBindingRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveBinding(binding: GatewayEngineeringBindingRecord)

    @Query("SELECT * FROM (SELECT * FROM gateway_engineering_events WHERE requestId=:requestId ORDER BY revision DESC LIMIT 10000) ORDER BY revision")
    suspend fun events(requestId: String): List<GatewayEngineeringEventRecord>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun saveEvents(events: List<GatewayEngineeringEventRecord>)

    @Query("DELETE FROM gateway_engineering_events WHERE requestId=:requestId AND revision < (SELECT MIN(revision) FROM (SELECT revision FROM gateway_engineering_events WHERE requestId=:requestId ORDER BY revision DESC LIMIT 10000))")
    suspend fun pruneEvents(requestId: String)

    @Query("SELECT * FROM gateway_migrations WHERE `key`=:key")
    suspend fun migration(key: String): GatewayMigrationRecord?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveMigration(marker: GatewayMigrationRecord)

    @Query("SELECT * FROM gateway_submissions WHERE state IN ('PENDING','DISPATCHING','OUTCOME_UNKNOWN','MIGRATED_REVIEW') ORDER BY createdAtMillis DESC")
    suspend fun pendingEngineering(): List<GatewaySubmissionRecord>

    @Query("SELECT COUNT(*) FROM gateway_submissions WHERE chatId=:chatId AND executionProfileUid=:profileUid AND state='MIGRATED_REVIEW'")
    suspend fun migratedAdmissionCount(chatId: Int, profileUid: String): Int

    @Query("SELECT * FROM gateway_submissions WHERE state='PENDING_ORDINARY' AND (:chatId IS NULL OR chatId=:chatId) ORDER BY createdAtMillis DESC")
    suspend fun pendingOrdinary(chatId: Int? = null): List<GatewaySubmissionRecord>

    @Query("SELECT * FROM gateway_submissions WHERE runId=:runId ORDER BY createdAtMillis DESC, commandId DESC LIMIT 1")
    suspend fun latestSubmissionForRun(runId: String): GatewaySubmissionRecord?

    @Query("SELECT * FROM gateway_submissions WHERE conversationKey=:key AND userMessageId IS NOT NULL ORDER BY createdAtMillis LIMIT 1")
    suspend fun originalSubmission(key: String): GatewaySubmissionRecord?

    @Query("UPDATE agent_runs SET gateway_job_id=:jobId, gateway_base_url=:endpoint, status=CASE WHEN :restoreInterrupted THEN 'INTERRUPTED' ELSE status END, completed_at=CASE WHEN :restoreInterrupted THEN NULL ELSE completed_at END, terminal_error=CASE WHEN :restoreInterrupted THEN 'GATEWAY_OUTCOME_UNKNOWN: Accepted command receipt recovered after transport failure. ' || COALESCE(terminal_error,'') ELSE terminal_error END WHERE run_id=:runId AND assistant_message_id=:messageId AND ownership_fence=:fence AND status=:expectedStatus AND terminal_error IS :expectedError AND status IN ('QUEUED','RUNNING','INTERRUPTED','WAITING_REMOTE_JOB','FAILED','PARTIAL','EVIDENCE_MISSING','BLOCKED') AND COALESCE(terminal_error,'') != 'BACKUP_RESTORED' AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.message_id=:messageId AND m.current_run_id=:runId AND m.ownership_fence=:fence)")
    suspend fun bindOrdinaryReceipt(runId: String, messageId: Int, fence: Long, jobId: String, endpoint: String, expectedStatus: String, expectedError: String?, restoreInterrupted: Boolean): Int

    @Query("SELECT * FROM gateway_submissions WHERE state='PENDING_INSTRUCTION' AND (:chatId IS NULL OR chatId=:chatId) ORDER BY createdAtMillis LIMIT 32")
    suspend fun pendingInstructions(chatId: Int? = null): List<GatewaySubmissionRecord>

    @Query("SELECT * FROM gateway_submissions WHERE commandId=:commandId")
    suspend fun submission(commandId: String): GatewaySubmissionRecord?

    @Query("SELECT * FROM gateway_submissions WHERE conversationKey=:key AND commandId=:turnId LIMIT 1")
    suspend fun submissionForTurn(key: String, turnId: String): GatewaySubmissionRecord?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSubmission(submission: GatewaySubmissionRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveSubmission(submission: GatewaySubmissionRecord)

    @Query("SELECT * FROM chats_v2 WHERE chat_id=:chatId")
    suspend fun localChat(chatId: Int?): ChatRoomV2?

    @Insert
    suspend fun insertLocalMessage(message: MessageV2): Long

    @Query("SELECT * FROM messages_v2 WHERE message_id=:messageId")
    suspend fun localMessage(messageId: Int): MessageV2?

    /** A stream owned by a live run wins over a recovery projection. */
    @Query("SELECT COUNT(*) FROM agent_runs r JOIN messages_v2 m ON m.message_id=r.assistant_message_id AND m.current_run_id=r.run_id AND m.ownership_fence=r.ownership_fence WHERE r.assistant_message_id=:messageId AND r.status IN ('QUEUED','RUNNING','WAITING_PROVIDER_LIMIT','STOPPING')")
    suspend fun liveMessageOwners(messageId: Int): Int

    @Query("UPDATE agent_runs SET status='WAITING_REMOTE_JOB', completed_at=NULL WHERE run_id=:runId AND assistant_message_id=:messageId AND status='INTERRUPTED' AND COALESCE(terminal_error,'') != 'BACKUP_RESTORED' AND ownership_fence=:fence AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.message_id=:messageId AND m.current_run_id=:runId AND m.ownership_fence=:fence)")
    suspend fun recoverRemoteRun(runId: String, messageId: Int, fence: Long): Int

    @Query("UPDATE agent_runs SET status=:status, completed_at=:completedAt, terminal_error=CASE WHEN :status='COMPLETED' THEN NULL ELSE terminal_error END WHERE run_id=:runId AND status='WAITING_REMOTE_JOB' AND ownership_fence=:fence AND EXISTS (SELECT 1 FROM messages_v2 m WHERE m.message_id=agent_runs.assistant_message_id AND m.current_run_id=agent_runs.run_id AND m.ownership_fence=:fence)")
    suspend fun finishRemoteRun(runId: String, status: String, completedAt: Long, fence: Long): Int

    @Query("UPDATE messages_v2 SET content=:content, timeline=:timeline WHERE message_id=:messageId AND ownership_fence=:fence AND current_run_id IS :runId")
    suspend fun projectMessage(messageId: Int, content: String, timeline: List<AssistantTimelineItem>, fence: Long, runId: String?): Int

    @Query("UPDATE chats_v2 SET updated_at=MAX(updated_at,:at) WHERE chat_id=:chatId")
    suspend fun touchChat(chatId: Int, at: Long)
}
