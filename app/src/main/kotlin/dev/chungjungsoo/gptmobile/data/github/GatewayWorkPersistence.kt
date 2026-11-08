package dev.chungjungsoo.gptmobile.data.github

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import androidx.room.Upsert
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.ChatPlatformModelV2
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "gateway_scopes")
data class GatewayScopeRecord(
    @PrimaryKey val id: String,
    val connectionUid: String,
    val endpoint: String,
    val gatewayId: String,
    val principalId: String,
    val identityScope: String,
    val connectionState: String,
    val observedAt: Long,
    val catalogCursor: String? = null,
    val catalogHasMore: Boolean = false,
    val error: String? = null
)

@Entity(
    tableName = "gateway_conversations",
    foreignKeys = [ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["localChatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("localChatId"), Index(value = ["scopeId", "conversationId"], unique = true)]
)
data class GatewayConversationRecord(
    @PrimaryKey val id: String,
    val scopeId: String,
    val conversationId: String,
    val localChatId: Int?,
    val profileUid: String,
    val profileName: String,
    val model: String,
    val repository: String?,
    val branch: String?,
    val title: String,
    val revision: Long,
    val executionBinding: String?,
    val originalContext: String?,
    val historyComplete: Boolean,
    val historyLimitation: String?,
    val updatedAt: Long,
    val sourceSha: String? = null,
    val seenMessageSequence: Long = 0,
    val seenRevision: Long = 0,
    val projectionOwner: String = "CONTINUITY"
)

@Entity(
    tableName = "gateway_jobs",
    foreignKeys = [ForeignKey(entity = GatewayConversationRecord::class, parentColumns = ["id"], childColumns = ["conversationKey"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("conversationKey"), Index(value = ["scopeId", "kind", "jobId"], unique = true), Index("acceptedAt")]
)
data class GatewayJobRecord(
    @PrimaryKey val id: String,
    val scopeId: String,
    val jobId: String,
    val conversationKey: String,
    val kind: String,
    val revision: Long,
    val schedulingState: String,
    val phase: String,
    val acceptedAt: Long?,
    val finishedAt: Long?,
    val observedAt: Long,
    val observedMonotonic: Long,
    val clockSession: String,
    val serverTime: Long,
    val payload: String,
    val engineeringPayload: String? = null
)

@Entity(
    tableName = "gateway_messages",
    primaryKeys = ["conversationKey", "sequence"],
    foreignKeys = [ForeignKey(entity = GatewayConversationRecord::class, parentColumns = ["id"], childColumns = ["conversationKey"], onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["conversationKey", "messageId"], unique = true), Index(value = ["conversationKey", "logicalMessageId", "partIndex"], unique = true)]
)
data class GatewayMessageRecord(
    val conversationKey: String,
    val sequence: Long,
    val messageId: String,
    val jobId: String,
    val role: String,
    val kind: String,
    val content: String,
    val createdAt: Long,
    val fingerprint: String,
    val localMessageId: Int?,
    val logicalMessageId: String = messageId,
    val partIndex: Int = 0,
    val partCount: Int = 1,
    val contentSha256: String? = null,
    val projectionComplete: Boolean = false,
    val contentVerified: Boolean = false
)

@Entity(
    tableName = "gateway_replay_cursors",
    foreignKeys = [ForeignKey(entity = GatewayConversationRecord::class, parentColumns = ["id"], childColumns = ["conversationKey"], onDelete = ForeignKey.CASCADE)]
)
data class GatewayReplayCursor(
    @PrimaryKey val conversationKey: String,
    val sequence: Long = 0,
    val cursor: String? = null,
    val hasMore: Boolean = true,
    val historyComplete: Boolean = true,
    val gapBeforeSequence: Long? = null,
    val historyLimitation: String? = null
)

@Entity(
    tableName = "gateway_commands",
    foreignKeys = [ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["localChatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("localChatId"), Index(value = ["scopeId", "jobId"])]
)
data class GatewayCommandRecord(
    @PrimaryKey val id: String,
    val scopeId: String,
    val conversationKey: String?,
    val localChatId: Int,
    val userMessageId: Int?,
    val assistantMessageId: Int?,
    val localRunId: String?,
    val action: String,
    val payloadDigest: String,
    val payload: String,
    val state: String,
    val jobId: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val error: String? = null
)

@Entity(
    tableName = "gateway_engineering_bindings",
    foreignKeys = [ForeignKey(entity = ChatRoomV2::class, parentColumns = ["chat_id"], childColumns = ["localChatId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("localChatId")]
)
data class GatewayEngineeringRecord(
    @PrimaryKey val requestId: String,
    val scopeId: String,
    val localChatId: Int,
    val jobId: String?,
    val binding: String,
    val syncedRevision: Int,
    val seenRevision: Int,
    val notifiedRevision: Int
)

@Entity(
    tableName = "gateway_engineering_updates",
    primaryKeys = ["requestId", "revision"],
    foreignKeys = [ForeignKey(entity = GatewayEngineeringRecord::class, parentColumns = ["requestId"], childColumns = ["requestId"], onDelete = ForeignKey.CASCADE)]
)
data class GatewayEngineeringUpdateRecord(val requestId: String, val revision: Int, val state: String, val message: String, val createdAt: Double)

@Dao
interface GatewayWorkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putScope(value: GatewayScopeRecord)

    @Query("SELECT * FROM gateway_scopes WHERE id=:id")
    suspend fun scope(id: String): GatewayScopeRecord?

    @Query("SELECT * FROM gateway_scopes")
    suspend fun scopes(): List<GatewayScopeRecord>

    @Query("SELECT * FROM gateway_scopes")
    fun observeScopes(): Flow<List<GatewayScopeRecord>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertConversation(value: GatewayConversationRecord): Long

    @Update suspend fun updateConversation(value: GatewayConversationRecord)

    @Query("UPDATE gateway_conversations SET seenMessageSequence=MAX(seenMessageSequence,:sequence), seenRevision=MAX(seenRevision,:revision) WHERE id=:id")
    suspend fun markSeen(id: String, sequence: Long, revision: Long)

    @Query("SELECT * FROM gateway_conversations WHERE id=:id")
    suspend fun conversation(id: String): GatewayConversationRecord?

    @Query("SELECT * FROM gateway_conversations WHERE localChatId=:chatId ORDER BY updatedAt DESC")
    suspend fun conversations(chatId: Int): List<GatewayConversationRecord>

    @Query("SELECT * FROM gateway_conversations WHERE id IN (SELECT conversationKey FROM gateway_jobs ORDER BY COALESCE(acceptedAt,observedAt) DESC LIMIT :limit)")
    fun observeConversations(limit: Int): Flow<List<GatewayConversationRecord>>

    @Query("SELECT * FROM gateway_conversations WHERE localChatId=:chatId")
    fun observeConversationsForChat(chatId: Int): Flow<List<GatewayConversationRecord>>

    @Query("SELECT * FROM gateway_conversations WHERE id IN (SELECT gateway_jobs.conversationKey FROM gateway_jobs INNER JOIN gateway_scopes ON gateway_jobs.scopeId=gateway_scopes.id WHERE gateway_scopes.connectionUid=:gatewayUid ORDER BY COALESCE(gateway_jobs.acceptedAt,gateway_jobs.observedAt) DESC LIMIT :limit)")
    fun observeConversationsForGateway(gatewayUid: String, limit: Int): Flow<List<GatewayConversationRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putJob(value: GatewayJobRecord)

    @Query("SELECT * FROM gateway_jobs WHERE scopeId=:scopeId AND kind=:kind AND jobId=:jobId")
    suspend fun job(scopeId: String, kind: String, jobId: String): GatewayJobRecord?

    @Query("SELECT * FROM gateway_jobs WHERE conversationKey=:conversationKey")
    suspend fun conversationJobs(conversationKey: String): List<GatewayJobRecord>

    @Query("DELETE FROM gateway_conversations WHERE id=:id AND conversationId LIKE 'legacy_%'")
    suspend fun removeLegacyConversation(id: String)

    @Query("SELECT * FROM gateway_jobs ORDER BY COALESCE(acceptedAt,observedAt) DESC LIMIT :limit")
    fun observeJobs(limit: Int): Flow<List<GatewayJobRecord>>

    @Query("SELECT gateway_jobs.* FROM gateway_jobs INNER JOIN gateway_scopes ON gateway_jobs.scopeId=gateway_scopes.id WHERE gateway_scopes.connectionUid=:gatewayUid ORDER BY COALESCE(gateway_jobs.acceptedAt,gateway_jobs.observedAt) DESC LIMIT :limit")
    fun observeJobsForGateway(gatewayUid: String, limit: Int): Flow<List<GatewayJobRecord>>

    @Query("SELECT gateway_jobs.* FROM gateway_jobs INNER JOIN gateway_conversations ON gateway_jobs.conversationKey=gateway_conversations.id WHERE gateway_conversations.localChatId=:chatId ORDER BY COALESCE(acceptedAt,observedAt) DESC LIMIT 100")
    fun observeJobsForChat(chatId: Int): Flow<List<GatewayJobRecord>>

    @Query("SELECT COUNT(*) FROM gateway_jobs")
    fun observeJobCount(): Flow<Int>

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:conversationKey AND sequence=:sequence")
    suspend fun message(conversationKey: String, sequence: Long): GatewayMessageRecord?

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:conversationKey AND messageId=:messageId")
    suspend fun messageById(conversationKey: String, messageId: String): GatewayMessageRecord?

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:conversationKey AND jobId=:jobId AND role='user' ORDER BY sequence DESC LIMIT 1")
    suspend fun userMessage(conversationKey: String, jobId: String): GatewayMessageRecord?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertMessage(value: GatewayMessageRecord)

    @Update suspend fun updateMessageProjection(value: GatewayMessageRecord)

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:conversationKey AND sequence>:after ORDER BY sequence LIMIT 100")
    suspend fun storedMessages(conversationKey: String, after: Long): List<GatewayMessageRecord>

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:conversationKey AND logicalMessageId=:logicalId ORDER BY partIndex LIMIT 16 OFFSET :offset")
    suspend fun messageParts(conversationKey: String, logicalId: String, offset: Int): List<GatewayMessageRecord>

    @Query("SELECT COUNT(*) FROM gateway_messages WHERE conversationKey=:conversationKey AND logicalMessageId=:logicalId")
    suspend fun partCount(conversationKey: String, logicalId: String): Int

    @Query("UPDATE gateway_messages SET localMessageId=:messageId, projectionComplete=1 WHERE conversationKey=:conversationKey AND logicalMessageId=:logicalId")
    suspend fun markProjected(conversationKey: String, logicalId: String, messageId: Int?)

    @Query("UPDATE gateway_messages SET contentVerified=1 WHERE conversationKey=:conversationKey AND logicalMessageId=:logicalId")
    suspend fun markContentVerified(conversationKey: String, logicalId: String)

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:conversationKey AND sequence>:after ORDER BY sequence LIMIT 8")
    suspend fun transcriptPage(conversationKey: String, after: Long): List<GatewayMessageRecord>

    @Query("SELECT * FROM gateway_messages WHERE conversationKey=:conversationKey ORDER BY sequence DESC LIMIT :limit")
    fun observeMessages(conversationKey: String, limit: Int): Flow<List<GatewayMessageRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putCursor(value: GatewayReplayCursor)

    @Query("SELECT * FROM gateway_replay_cursors WHERE conversationKey=:conversationKey")
    suspend fun cursor(conversationKey: String): GatewayReplayCursor?

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCommand(value: GatewayCommandRecord): Long

    @Update suspend fun updateCommand(value: GatewayCommandRecord)

    @Query("SELECT * FROM gateway_commands WHERE id=:id")
    suspend fun command(id: String): GatewayCommandRecord?

    @Query("SELECT * FROM gateway_commands WHERE scopeId=:scopeId AND jobId=:jobId ORDER BY createdAt DESC LIMIT 1")
    suspend fun commandForJob(scopeId: String, jobId: String): GatewayCommandRecord?

    @Query("SELECT * FROM gateway_commands WHERE state!='ACCEPTED' ORDER BY createdAt DESC LIMIT 200")
    fun observeCommands(): Flow<List<GatewayCommandRecord>>

    @Query("SELECT gateway_commands.* FROM gateway_commands INNER JOIN gateway_scopes ON gateway_commands.scopeId=gateway_scopes.id WHERE gateway_scopes.connectionUid=:gatewayUid AND gateway_commands.state!='ACCEPTED' ORDER BY gateway_commands.createdAt DESC LIMIT 200")
    fun observeCommandsForGateway(gatewayUid: String): Flow<List<GatewayCommandRecord>>

    @Query("SELECT * FROM gateway_commands WHERE localChatId=:chatId ORDER BY createdAt DESC LIMIT 1")
    fun observeCommandForChat(chatId: Int): Flow<GatewayCommandRecord?>

    @Upsert suspend fun putBinding(value: GatewayEngineeringRecord)

    @Query("SELECT * FROM gateway_engineering_bindings WHERE requestId=:requestId")
    suspend fun binding(requestId: String): GatewayEngineeringRecord?

    @Query("SELECT * FROM gateway_engineering_bindings ORDER BY rowid")
    suspend fun bindings(): List<GatewayEngineeringRecord>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun putUpdates(value: List<GatewayEngineeringUpdateRecord>)

    @Query("SELECT * FROM gateway_engineering_updates WHERE requestId=:requestId ORDER BY revision DESC LIMIT 10000")
    suspend fun updates(requestId: String): List<GatewayEngineeringUpdateRecord>

    @Query("SELECT * FROM chats_v2 WHERE chat_id=:chatId")
    suspend fun chat(chatId: Int): ChatRoomV2?

    @Insert suspend fun insertChat(value: ChatRoomV2): Long

    @Query("SELECT * FROM agent_runs WHERE gateway_job_id=:jobId AND RTRIM(gateway_base_url,'/')=:endpoint AND profile_uid=:profileUid AND model_snapshot=:model LIMIT 2")
    suspend fun originalOrdinaryRuns(jobId: String, endpoint: String, profileUid: String, model: String): List<AgentRun>

    @Query("SELECT model FROM chat_platform_model_v2 WHERE chat_id=:chatId AND platform_uid=:profileUid")
    suspend fun chatModel(chatId: Int, profileUid: String): String?

    @Upsert suspend fun putChatModel(value: ChatPlatformModelV2)

    @Query("SELECT * FROM messages_v2 WHERE message_id=:id")
    suspend fun chatMessage(id: Int): MessageV2?

    @Insert suspend fun insertChatMessage(value: MessageV2): Long

    @Update suspend fun updateChatMessage(value: MessageV2)

    @Query("UPDATE agent_runs SET status='WAITING_REMOTE_JOB', gateway_job_id=:jobId, gateway_base_url=:endpoint WHERE run_id=:runId AND chat_id=:chatId AND assistant_message_id=:assistantId AND status IN ('QUEUED','WAITING_REMOTE_JOB')")
    suspend fun retainRemoteOwnership(runId: String, chatId: Int, assistantId: Int, jobId: String?, endpoint: String): Int

    @Query("UPDATE agent_runs SET status=:status, completed_at=:completedAt, terminal_error=:error, gateway_last_sequence=:sequence WHERE run_id=:runId AND assistant_message_id=:assistantId AND gateway_job_id=:jobId AND status='WAITING_REMOTE_JOB'")
    suspend fun finishRemote(runId: String, assistantId: Int, jobId: String, status: String, completedAt: Long, error: String?, sequence: Int): Int
}
