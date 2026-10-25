package dev.chungjungsoo.gptmobile.data.agent

import android.content.Context
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.chat.decodedArchiveText
import dev.chungjungsoo.gptmobile.data.context.ConfiguredDispatchAuthority
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight
import dev.chungjungsoo.gptmobile.data.network.ProviderLimitDecision
import dev.chungjungsoo.gptmobile.data.network.bindAuthority
import dev.chungjungsoo.gptmobile.data.network.providerLimitDecision
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.research.ResearchDeferredStage
import dev.chungjungsoo.gptmobile.data.research.researchHash
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Entity(tableName = "provider_waits", foreignKeys = [ForeignKey(entity = AgentRun::class, parentColumns = ["run_id"], childColumns = ["runId"], onDelete = ForeignKey.CASCADE)], indices = [Index("scope"), Index("retryAt")])
data class ProviderWait(@PrimaryKey val runId: String, val fence: Long, val scope: String, val retryAt: Long, val checkpoint: String)

@Serializable
internal enum class ProviderWaitMode { INITIAL, RESEARCH_STAGE, COMBINED_APPEND, TEXT_CONTINUATION }

@Serializable
internal data class ProviderContinuationCheckpoint(val rootWorkId: String, val sourceMessageId: Int, val prefixDigest: String)

@Serializable
internal data class ProviderWaitCheckpoint(
    val version: Int = 2,
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
    val participants: List<String>,
    val mode: ProviderWaitMode = ProviderWaitMode.INITIAL,
    val decision: ProviderLimitDecision? = null,
    val deferredStage: ResearchDeferredStage? = null,
    val limitPlatform: PlatformV2? = null,
    val limitRegisteredRevision: String = "",
    val limitPolicyRevision: String = "",
    val limitPermissionRevision: String = "",
    val continuation: ProviderContinuationCheckpoint? = null
)

internal data class ProviderWaitResume(val request: AgentRunRequest, val saved: ProviderWaitCheckpoint, val row: ProviderWait)

/** Durable scheduling only for a never-sent request or a typed, known rejection. */
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

    suspend fun defer(request: AgentRunRequest): Boolean {
        val outcome = FreeProviderPreflight.check(request.platform) ?: return false
        val rawDecision = providerLimitDecision(request.platform, outcome) ?: return false
        val guard = try {
            // A renewed quota wait retains the original grant across wake/launch.
            // Capturing current settings alone could authorize a revoked saved wait.
            kotlinx.coroutines.withContext(request.providerWaitAuthority?.requestGuard(settings) ?: kotlin.coroutines.EmptyCoroutineContext) {
                ConfiguredDispatchAuthority.capture(settings, request.platform, request.chatId)
            }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return false
        }
        val decision = rawDecision.bindAuthority(guard)
        // The caller's preflight and this read can straddle a wall/monotonic clock
        // sample. Persist this freshly admitted deadline without rewriting evidence.
        if (decision.retryAt <= System.currentTimeMillis()) return false
        return save(request, decision, ProviderWaitMode.INITIAL, null, running = false)
    }

    internal suspend fun deferRunning(request: AgentRunRequest, decision: ProviderLimitDecision, stage: ResearchDeferredStage? = null): Boolean {
        if (!decision.knownNotAccepted) return false
        val combined = request.chatToolConfig?.combined
        val mode = when {
            combined?.role?.finalization == true -> ProviderWaitMode.COMBINED_APPEND
            decision.continuationPrefixDigest != null -> ProviderWaitMode.TEXT_CONTINUATION
            stage != null -> ProviderWaitMode.RESEARCH_STAGE
            else -> ProviderWaitMode.INITIAL
        }
        if (mode == ProviderWaitMode.RESEARCH_STAGE && (stage == null || stage.limit != decision || stage.stage.isBlank() || stage.promptDigest.isBlank())) return false
        if (mode != ProviderWaitMode.RESEARCH_STAGE && (decision.profileUid != request.platform.uid || decision.model != request.platform.model)) return false
        val dao = database.agentPersistenceDao()
        val run = database.agentRunDao().getById(request.runId) ?: return false
        val message = dao.recoveryMessage(run.assistantMessageId) ?: return false
        if (mode == ProviderWaitMode.INITIAL && (message.content.isNotBlank() || message.thoughts.isNotBlank() || dao.rawGetToolEventsForRun(run.runId).isNotEmpty())) return false
        if (mode == ProviderWaitMode.TEXT_CONTINUATION && (message.content.isBlank() || researchHash(message.content) != decision.continuationPrefixDigest || run.synthesisKey != null)) return false
        if (mode == ProviderWaitMode.COMBINED_APPEND) {
            val raw = run.synthesisSnapshot ?: return false
            if (researchHash(raw) != run.synthesisKey) return false
            val frozen = runCatching { json.decodeFromString<CombinedInputSnapshot>(raw) }.getOrNull() ?: return false
            if (frozen.combined?.requested?.appendContinuations != true || frozen.deliveryProtocol != "PASSAGES_V3") return false
            var checkpoint = dao.combinedDeliveryCheckpoint(run.runId, frozen.liveRevisionId(run.runId))
            val previous = checkpoint?.let { runCatching { json.decodeFromString<CombinedRecordCheckpoint>(it.stateJson) }.getOrNull() }
            if (previous == null || previous.lastAttemptOutcome != "KNOWN_PROVIDER_REJECTION" || previous.retryAtMillis != decision.retryAt) {
                // Main admission can discover a new local cooldown before opening the
                // record session. Bind that never-sent attempt only at a safe cursor.
                val authority = decision.authority ?: return false
                authority.validate(settings)
                val maxCharacters = settings.getFeatureSettings().tokenBudget.responseCharacterLimit.takeIf { it > 0 } ?: 1_048_576
                checkpoint = CombinedAppendPreflight.save(dao, run, frozen, decision, maxCharacters)
            }
            val savedCheckpoint = checkpoint ?: return false
            val state = runCatching { json.decodeFromString<CombinedRecordCheckpoint>(savedCheckpoint.stateJson) }.getOrNull() ?: return false
            if (savedCheckpoint.fence != run.ownershipFence || !state.appendReady || state.ended || state.noProgressAttempts >= 2 || state.lastAttemptOutcome != "KNOWN_PROVIDER_REJECTION" || state.retryAtMillis != decision.retryAt) return false
        }
        return save(request, decision, mode, stage, running = true)
    }

    private suspend fun save(request: AgentRunRequest, decision: ProviderLimitDecision, mode: ProviderWaitMode, stage: ResearchDeferredStage?, running: Boolean): Boolean {
        val profiles = settings.fetchPlatformV2s()
        val registered = profiles.singleOrNull { it.uid == request.platform.uid } ?: return false
        val limitProfile = profiles.singleOrNull { it.uid == decision.profileUid } ?: return false
        val run = database.agentRunDao().getById(request.runId) ?: return false
        if (run.status != (if (running) "RUNNING" else "QUEUED") || !running && run.startedAt != null) return false
        if (!decision.knownNotAccepted || request.userMessages.any { it.id <= 0 }) return false
        val continuation = if (mode == ProviderWaitMode.TEXT_CONTINUATION) {
            val inherited = request.assistantMessage.timeline.mapNotNull { it.recovery }.firstOrNull { it.generationId == request.runId }
            val recovered = inherited?.let { resolveTaskRecovery(request.userMessages, it) } ?: resolveTaskRecovery(request.userMessages)
            val sourceId = request.stage?.acceptedTask?.originatingUserMessageId ?: recovered?.sourceMessageId ?: run.userMessageId
            val source = request.userMessages.singleOrNull { it.id == sourceId && it.chatId == request.chatId && it.platformType == null } ?: return false
            if (TaskRecoveryAction.fromText(source.content) != null) return false
            ProviderContinuationCheckpoint("${request.chatId}:$sourceId", sourceId, requireNotNull(decision.continuationPrefixDigest))
        } else {
            null
        }
        val replies = providerWaitReplies(request, run.assistantMessageId)
        val limited = limitProfile.copy(model = decision.model)
        val checkpoint = ProviderWaitCheckpoint(
            chatId = request.chatId, platform = request.platform.copy(token = null),
            registeredRevision = ConfiguredDispatchAuthority.profileRevision(registered),
            policyRevision = ConfiguredDispatchAuthority.policyRevision(settings.getFeatureSettings(), registered.uid, request.chatId),
            permissionRevision = settings.providerPermissionRevision(request.platform),
            users = request.userMessages.map { it.id }, replies = replies.map { row -> row.map { it.id } },
            inputDigest = inputDigest(request.userMessages + replies.flatten()), tools = request.chatToolConfig, stage = request.stage,
            participants = request.researchParticipantUids, mode = mode, decision = decision, deferredStage = stage,
            limitPlatform = limited.copy(token = null), limitRegisteredRevision = ConfiguredDispatchAuthority.profileRevision(limitProfile),
            limitPolicyRevision = ConfiguredDispatchAuthority.policyRevision(settings.getFeatureSettings(), limited.uid, request.chatId),
            limitPermissionRevision = settings.providerPermissionRevision(limited), continuation = continuation
        )
        val row = ProviderWait(request.runId, run.ownershipFence, decision.scope, decision.retryAt, json.encodeToString(checkpoint))
        val acceptedAuthority = decision.authority ?: return false
        if (!acceptedAuthority.matches(limited)) return false
        // Read the checkpoint first, then validate the authority captured before the
        // rejected operation. A settings change cannot become a new scheduling grant.
        acceptedAuthority.validate(settings)
        val persisted = if (running) database.agentPersistenceDao().deferRunningProvider(row) else database.agentPersistenceDao().deferProvider(row)
        if (!persisted) return false
        database.agentPersistenceDao().providerWaits().filter { it.scope == row.scope }.minOfOrNull { it.retryAt }?.let { ProviderLimitResumeWorker.enqueue(context, row.scope, it) }
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
        // A crash between the atomic claim and dispatch leaves a never-started QUEUED owner.
        database.agentPersistenceDao().providerWaits().groupBy { it.scope }.forEach { (scope, rows) ->
            val safe = rows.filter { row -> database.agentRunDao().getById(row.runId)?.let { it.status == "WAITING_PROVIDER_LIMIT" || it.status == "QUEUED" && it.startedAt == null } == true }
            safe.minOfOrNull { it.retryAt }?.let { ProviderLimitResumeWorker.enqueue(context, scope, it) }
        }
    }

    suspend fun ready(scope: String): List<AgentRunRequest> = readyActions(scope).filter { it.saved.mode == ProviderWaitMode.INITIAL }.map { it.request }

    internal suspend fun readyActions(scope: String): List<ProviderWaitResume> {
        dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
        val dao = database.agentPersistenceDao()
        val result = mutableListOf<ProviderWaitResume>()
        var nextDeadline: Long? = null
        for (row in dao.providerWaits().filter { it.scope == scope }) {
            val run = database.agentRunDao().getById(row.runId) ?: continue
            if (run.ownershipFence != row.fence || run.status != "WAITING_PROVIDER_LIMIT" && !(run.status == "QUEUED" && run.startedAt == null)) continue
            val saved = runCatching { json.decodeFromString<ProviderWaitCheckpoint>(row.checkpoint) }.getOrNull()
            if (saved == null) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_CHECKPOINT_INVALID")
                continue
            }
            val profiles = settings.fetchPlatformV2s()
            val current = profiles.singleOrNull { it.uid == saved.platform.uid }
            val limited = profiles.singleOrNull { it.uid == saved.limitPlatform?.uid }
            val room = dao.getChatRoom(saved.chatId)
            val decision = saved.decision
            suspend fun valid(profile: PlatformV2?, model: String, registered: String, policy: String, permission: String, participant: Boolean): Boolean =
                profile?.enabled == true &&
                    (!participant || profile.uid in room?.activePlatform.orEmpty()) &&
                    ConfiguredDispatchAuthority.profileRevision(profile) == registered &&
                    ConfiguredDispatchAuthority.policyRevision(settings.getFeatureSettings(), profile.uid, saved.chatId) == policy &&
                    settings.providerPermissionRevision(profile.copy(model = model)) == permission &&
                    !settings.providerActivationRequired(profile.copy(model = model))
            val capturedAuthorityValid = try {
                val authority = decision?.authority
                authority?.validate(settings)
                authority != null && saved.limitPlatform?.let { authority.matches(it) } == true
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                false
            }
            val authorized = saved.version == 2 &&
                capturedAuthorityValid &&
                decision?.knownNotAccepted == true &&
                decision.scope == row.scope &&
                decision.retryAt == row.retryAt &&
                valid(current, saved.platform.model, saved.registeredRevision, saved.policyRevision, saved.permissionRevision, participant = true) &&
                // A configured Research worker/reviewer may be outside the chat participants.
                // Its original role, route and permissions remain bound by captured authority.
                valid(limited, saved.limitPlatform?.model.orEmpty(), saved.limitRegisteredRevision, saved.limitPolicyRevision, saved.limitPermissionRevision, participant = false)
            if (!authorized) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_REVIEW_REQUIRED")
                continue
            }
            val platform = saved.platform.copy(token = current?.token)
            val limitedPlatform = requireNotNull(saved.limitPlatform).copy(token = limited?.token)
            val blocked = FreeProviderPreflight.check(limitedPlatform)
            val deadline = maxOf(row.retryAt, blocked?.retryAt ?: 0)
            if (deadline > System.currentTimeMillis()) {
                nextDeadline = minOf(nextDeadline ?: deadline, deadline)
                continue
            }
            if (blocked != null) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_REVIEW_REQUIRED")
                continue
            }
            val ids = (saved.users + saved.replies.flatten() + run.assistantMessageId).distinct()
            val all = ids.mapNotNull { dao.recoveryMessage(it) }.associateBy { it.id }
            val users = saved.users.mapNotNull(all::get)
            val replies = saved.replies.map { ids -> ids.mapNotNull(all::get) }
            val answer = all[run.assistantMessageId]
            if (answer == null || answer.currentRunId != row.runId || answer.ownershipFence != row.fence || users.size != saved.users.size || replies.map { it.size } != saved.replies.map { it.size } || inputDigest(users + replies.flatten()) != saved.inputDigest) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_INPUT_CHANGED")
                continue
            }
            if (saved.mode == ProviderWaitMode.TEXT_CONTINUATION && (saved.continuation?.prefixDigest != decision.continuationPrefixDigest || answer.content.isBlank() || researchHash(answer.content) != saved.continuation?.prefixDigest)) {
                dao.blockProviderWait(row.runId, "PROVIDER_WAIT_PREFIX_CHANGED")
                continue
            }
            if (saved.mode == ProviderWaitMode.INITIAL && run.status == "WAITING_PROVIDER_LIMIT" && dao.claimProviderWait(row.runId, row.fence) != 1) continue
            if (saved.mode != ProviderWaitMode.INITIAL && run.status != "WAITING_PROVIDER_LIMIT") continue
            val contextReplies = replies.mapIndexed { index, messages -> if (index == replies.lastIndex) messages + answer else messages }
                .ifEmpty { listOf(listOf(answer)) }
            result += ProviderWaitResume(AgentRunRequest(row.runId, saved.chatId, answer, platform, users, contextReplies, saved.tools, saved.stage, saved.participants, requireNotNull(decision.authority)), saved, row)
        }
        nextDeadline?.let { ProviderLimitResumeWorker.enqueue(context, scope, it, append = true) }
        return result
    }

    internal suspend fun resumeResearch(action: ProviderWaitResume): AgentRunRequest? {
        val request = action.request
        val saved = action.saved
        val deferred = saved.deferredStage ?: return null
        if (saved.mode != ProviderWaitMode.RESEARCH_STAGE || !deferred.limit.knownNotAccepted || deferred.limit != saved.decision) return null
        requireNotNull(saved.decision.authority) { "PROVIDER_WAIT_AUTHORITY_UNAVAILABLE" }.validate(settings)
        val sourceId = deferred.rootWorkId.substringAfterLast(':').toIntOrNull() ?: return null
        if (deferred.rootWorkId != "${request.chatId}:$sourceId" || request.userMessages.none { it.id == sourceId }) return null
        val runId = "provider-resume:" + researchHash("${request.runId}:${action.row.fence}:${action.row.checkpoint}")
        val source = request.userMessages.lastOrNull() ?: return null
        val prior = database.agentRunDao().getById(request.runId) ?: return null
        val persisted = database.agentPersistenceDao().persistProviderResearchRetry(
            action.row,
            PersistAgentRetryRequest(
                source,
                request.assistantMessage,
                AgentRunDraft(runId, request.platform.uid, prior.providerSnapshot, request.platform.model, System.currentTimeMillis() / 1000, synthesisSnapshot = prior.synthesisSnapshot),
                recovery = TaskResumeDescriptor(deferred.rootWorkId, sourceId, runId, TaskRecoveryAction.RETRY_FAILED_STAGE)
            )
        ) ?: return null
        val replies = request.assistantMessages.mapIndexed { index, row -> if (index == request.assistantMessages.lastIndex) row.filter { it.id != persisted.assistantMessage.id } + persisted.assistantMessage else row }
            .ifEmpty { listOf(listOf(persisted.assistantMessage)) }
        return request.copy(runId = runId, assistantMessage = persisted.assistantMessage, assistantMessages = replies)
    }

    internal suspend fun resumeTextContinuation(action: ProviderWaitResume): AgentRunRequest? {
        val request = action.request
        val saved = action.saved
        val continuation = saved.continuation ?: return null
        if (saved.mode != ProviderWaitMode.TEXT_CONTINUATION || saved.decision?.knownNotAccepted != true || saved.decision.continuationPrefixDigest != continuation.prefixDigest) return null
        requireNotNull(saved.decision.authority) { "PROVIDER_WAIT_AUTHORITY_UNAVAILABLE" }.validate(settings)
        val runId = "provider-continuation:" + researchHash("${request.runId}:${action.row.fence}:${action.row.checkpoint}")
        val source = request.userMessages.lastOrNull() ?: return null
        val prior = database.agentRunDao().getById(request.runId) ?: return null
        val persisted = database.agentPersistenceDao().persistProviderContinuationRetry(
            action.row,
            PersistAgentRetryRequest(
                source,
                request.assistantMessage,
                AgentRunDraft(runId, request.platform.uid, prior.providerSnapshot, request.platform.model, System.currentTimeMillis() / 1000, synthesisSnapshot = prior.synthesisSnapshot),
                recovery = TaskResumeDescriptor(continuation.rootWorkId, continuation.sourceMessageId, runId, TaskRecoveryAction.CONTINUE_RUN, continuation.prefixDigest)
            )
        ) ?: return null
        val replies = request.assistantMessages.mapIndexed { index, row -> if (index == request.assistantMessages.lastIndex) row.filter { it.id != persisted.assistantMessage.id } + persisted.assistantMessage else row }
            .ifEmpty { listOf(listOf(persisted.assistantMessage)) }
        return request.copy(runId = runId, assistantMessage = persisted.assistantMessage, assistantMessages = replies)
    }

    private suspend fun inputDigest(messages: List<MessageV2>): String = providerWaitInputDigest(database.agentPersistenceDao(), messages)
}

/** ContextBuilder never sends the current response row as provider input. */
internal fun providerWaitReplies(request: AgentRunRequest, assistantMessageId: Int): List<List<MessageV2>> =
    request.assistantMessages.mapIndexed { index, row ->
        // Sibling contributors keep streaming while this profile is paused. Binding
        // those mutable outputs would invalidate an otherwise unchanged request.
        if (index >= request.userMessages.lastIndex) emptyList() else row.filter { it.id > 0 && it.id != assistantMessageId }
    }

/** Every wait mode compares the same complete archive/document projection on save and wake. */
internal suspend fun providerWaitInputDigest(dao: dev.chungjungsoo.gptmobile.data.database.dao.CombinedDocumentDao, messages: List<MessageV2>): String {
    val complete = messages.map { CombinedDocuments.resolve(dao, it.decodedArchiveText()).copy(activeRevisionIndex = -1) }
    return researchHash(Json { encodeDefaults = true }.encodeToString(complete))
}
