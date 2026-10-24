package dev.chungjungsoo.gptmobile.data.agent

import android.content.Context
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.FreeAiProvider
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.researchHash
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "provider_waits", foreignKeys = [ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)], indices = [Index("scope"), Index("retryAt")])
data class ProviderWait(
    @PrimaryKey val runId: String,
    val fence: Long,
    val scope: String,
    val retryAt: Long,
    val checkpoint: String
)

@Serializable
internal data class ProviderWaitCheckpoint(
    val version: Int = 1,
    val chatId: Int,
    val platform: PlatformV2,
    val registeredRevision: String,
    val policyRevision: String,
    val permissionRevision: String,
    val users: List<Int>,
    val replies: List<List<Int>>,
    val inputDigest: String,
    val tools: ChatMcpToolConfig?,
    val stage: AgentStageRequest?,
    val participants: List<String>
)

/** Only never-dispatched requests enter this automatic wait. Transmitted failures require recovery. */
@Singleton
class ProviderWaitStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val database: ChatDatabaseV2,
    private val settings: SettingRepository
) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    suspend fun defer(request: AgentRunRequest, retryAt: Long): Boolean {
        val registered = settings.fetchPlatformV2s().singleOrNull { it.uid == request.platform.uid } ?: return false
        val run = database.agentRunDao().getById(request.runId) ?: return false
        if (run.status != "QUEUED" || run.startedAt != null) return false
        val messages = request.userMessages + request.assistantMessages.flatten().filter { it.id > 0 }
        if (request.userMessages.any { it.id <= 0 }) return false
        val provider = FreeAiProvider.fromApiUrl(request.platform.apiUrl) ?: return false
        val scope = researchHash("${provider.name}|${if (provider == FreeAiProvider.OVHCLOUD) request.platform.model else "shared"}|${dev.chungjungsoo.gptmobile.data.network.freeCredentialIdentity(provider, request.platform.token)}")
        val checkpoint = ProviderWaitCheckpoint(
            chatId = request.chatId, platform = request.platform.copy(token = null),
            registeredRevision = ConfiguredDispatchAuthority.profileRevision(registered),
            policyRevision = ConfiguredDispatchAuthority.policyRevision(settings.getFeatureSettings(), registered.uid, request.chatId),
            permissionRevision = settings.providerPermissionRevision(registered),
            users = request.userMessages.map { it.id }, replies = request.assistantMessages.map { row -> row.filter { it.id > 0 }.map { it.id } },
            inputDigest = inputDigest(messages), tools = request.chatToolConfig, stage = request.stage, participants = request.researchParticipantUids
        )
        if (!database.agentPersistenceDao().deferProvider(ProviderWait(request.runId, run.ownershipFence, scope, retryAt, json.encodeToString(checkpoint)))) return false
        // A later waiter must not postpone an already queued earlier deadline.
        val firstDeadline = database.agentPersistenceDao().providerWaits()
            .filter { it.scope == scope && database.agentRunDao().getById(it.runId)?.status == "WAITING_PROVIDER_LIMIT" }
            .minOfOrNull { it.retryAt } ?: retryAt
        ProviderLimitResumeWorker.enqueue(context, scope, firstDeadline)
        return true
    }

    suspend fun pendingIds(chatId: Int? = null, profileUid: String? = null): Set<String> = database.agentPersistenceDao().providerWaits().mapNotNullTo(hashSetOf()) { row ->
        val run = database.agentRunDao().getById(row.runId)
        row.runId.takeIf { run != null && (chatId == null || run.chatId == chatId) && (profileUid == null || run.profileUid == profileUid) }
    }

    suspend fun combinedRoots(runIds: Set<String>): List<String> = database.agentPersistenceDao().providerWaits().filter { it.runId in runIds }.mapNotNull { row ->
        runCatching { json.decodeFromString<ProviderWaitCheckpoint>(row.checkpoint).tools?.combined?.rootBudgetId }.getOrNull()
    }

    suspend fun reconcile() {
        database.agentPersistenceDao().providerWaits().filter { database.agentRunDao().getById(it.runId)?.status == "WAITING_PROVIDER_LIMIT" }.groupBy { it.scope }.forEach { (scope, rows) ->
            ProviderLimitResumeWorker.enqueue(context, scope, rows.minOf { it.retryAt })
        }
    }

    suspend fun ready(scope: String): List<AgentRunRequest> {
        val dao = database.agentPersistenceDao()
        val result = mutableListOf<AgentRunRequest>()
        var nextDeadline: Long? = null
        for (row in dao.providerWaits().filter { it.scope == scope }) {
            val run = database.agentRunDao().getById(row.runId) ?: continue
            if (run.status != "WAITING_PROVIDER_LIMIT" || run.ownershipFence != row.fence) continue
            val saved = runCatching { json.decodeFromString<ProviderWaitCheckpoint>(row.checkpoint) }.getOrNull()
            if (saved == null) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_CHECKPOINT_INVALID")
                continue
            }
            val current = settings.fetchPlatformV2s().singleOrNull { it.uid == saved.platform.uid }
            val room = dao.getChatRoom(saved.chatId)
            val authorized = saved.version == 1 &&
                current?.enabled == true &&
                room != null &&
                current.uid in room.activePlatform &&
                ConfiguredDispatchAuthority.profileRevision(current) == saved.registeredRevision &&
                ConfiguredDispatchAuthority.policyRevision(settings.getFeatureSettings(), current.uid, saved.chatId) == saved.policyRevision &&
                settings.providerPermissionRevision(current) == saved.permissionRevision &&
                !settings.providerActivationRequired(current)
            if (!authorized) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_REVIEW_REQUIRED")
                continue
            }
            val platform = saved.platform.copy(token = current?.token)
            val blocked = dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(platform)
            val deadline = maxOf(row.retryAt, blocked?.retryAt ?: 0)
            if (deadline > System.currentTimeMillis()) {
                nextDeadline = minOf(nextDeadline ?: deadline, deadline)
                continue
            }
            if (blocked != null) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_REVIEW_REQUIRED")
                continue
            }
            val all = dao.rawGetMessages(saved.chatId).associateBy { it.id }
            val users = saved.users.mapNotNull(all::get)
            val replies = saved.replies.map { ids -> ids.mapNotNull(all::get) }
            val answer = all[run.assistantMessageId]
            if (answer == null || inputDigest(users + replies.flatten()) != saved.inputDigest) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_INPUT_CHANGED")
                continue
            }
            if (dao.claimProviderWait(row.runId, row.fence) != 1) continue
            result += AgentRunRequest(row.runId, saved.chatId, answer, platform, users, replies, saved.tools, saved.stage, saved.participants)
        }
        nextDeadline?.let { ProviderLimitResumeWorker.enqueue(context, scope, it, append = true) }
        return result
    }

    private fun inputDigest(messages: List<dev.chungjungsoo.gptmobile.data.database.entity.MessageV2>): String = researchHash(json.encodeToString(messages.map { it.copy(activeRevisionIndex = -1) }))
}
