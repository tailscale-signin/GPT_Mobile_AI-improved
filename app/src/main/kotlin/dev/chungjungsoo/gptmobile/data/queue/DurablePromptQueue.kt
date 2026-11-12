package dev.chungjungsoo.gptmobile.data.queue

import android.content.Context
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.agent.temperatureFor
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.GatewaySubmissionRecord
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.github.engineeringConfig
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailability
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityRepository
import dev.chungjungsoo.gptmobile.data.network.gateway.GatewayAvailabilityTarget
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Room owns accepted input; this singleton dispatcher survives navigation between chat screens. */
@Singleton
class DurablePromptQueue @Inject constructor(
    private val database: ChatDatabaseV2,
    private val chats: ChatRepository,
    private val settings: SettingRepository,
    private val coordinator: AgentRunCoordinator,
    @param:ApplicationContext private val context: Context,
    private val gatewayStore: GatewayQueueStore,
    private val gatewayAvailability: GatewayAvailabilityRepository,
    private val gatewayScopes: dev.chungjungsoo.gptmobile.data.github.GatewayVerifiedScopeStore
) {
    private val dao get() = database.pendingPromptDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val preferences = context.getSharedPreferences("prompt_queue", Context.MODE_PRIVATE)
    private val wake = MutableStateFlow(0)
    val pending = dao.observePending()

    /** The consumed row is the durable correlation between request and accepted turn. */
    fun observeRequest(chatId: Int, requestId: String) = pending.map {
        dao.forChat(chatId).singleOrNull { prompt -> runCatching { prompt.details().requestId == requestId }.getOrDefault(false) }
    }.distinctUntilChanged()

    private val _gatewayStates = MutableStateFlow<Map<Int, GatewayQueuedChatState>>(emptyMap())
    val gatewayStates = _gatewayStates.asStateFlow()
    private val submittedRuns = mutableSetOf<String>()

    private data class QueueSource(val prompts: List<PendingPrompt>, val leases: List<GatewaySubmissionRecord>, val profiles: List<PlatformV2>, val features: AppFeatureSettings)

    private fun targetKey(selected: GatewayQueuedSelection): String = "gateway_queue:${selected.profileUid}:${dev.chungjungsoo.gptmobile.data.research.researchHash("${selected.model}|${selected.profileRevision}")}"

    private suspend fun targets(source: QueueSource): List<GatewayAvailabilityTarget> =
        (source.prompts.filterNot { it.paused }.mapNotNull { runCatching { it.details().gateway }.getOrNull() } + source.leases.mapNotNull { runCatching { gatewayStore.turn(it).accepted.gateway }.getOrNull() })
            .distinct().map { selected ->
                val profile = source.profiles.singleOrNull { it.uid == selected.profileUid }
                val revision = profile?.takeIf { selected.validate(it) == null }?.let { gatewayScopes.configurationRevision(it) }
                GatewayAvailabilityTarget(
                    targetKey(selected),
                    selected.profileUid,
                    profile?.name.orEmpty(),
                    selected.model,
                    profile?.takeIf { revision != null }?.copy(model = selected.model)?.engineeringConfig()?.copy(profileRevision = revision),
                    false
                )
            }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
            try {
                if (hasGatewayWork()) scheduleWake()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // A background scheduler cannot revoke input that Room already owns.
            }
            val source = combine(pending, gatewayStore.active, settings.observePlatformV2s(), settings.observeFeatureSettings(), ::QueueSource)
                .stateIn(scope, SharingStarted.Eagerly, QueueSource(emptyList(), emptyList(), emptyList(), AppFeatureSettings()))
            val availability = source.map(::targets).distinctUntilChanged().flatMapLatest(gatewayAvailability::observe)
                .stateIn(scope, SharingStarted.Eagerly, emptyMap())
            combine(source, database.agentRunDao().observeActive(), coordinator.activeRuns, availability, wake) { snapshot, active, live, ready, _ ->
                Triple(snapshot, active.map { it.chatId }.toSet() + live.values.map { it.chatId }, ready)
            }.collect { (snapshot, busyChats, ready) ->
                val prompts = snapshot.prompts
                val profiles = snapshot.profiles
                val states = mutableMapOf<Int, GatewayQueuedChatState>()
                val counts = prompts.filter { runCatching { it.details().gateway != null }.getOrDefault(false) }.groupingBy { it.chatId }.eachCount()
                for (lease in snapshot.leases) {
                    states[lease.chatId] = reconcile(lease, profiles, ready).copy(queuedCount = counts[lease.chatId] ?: 0)
                }
                val externalBlocker = if (counts.isNotEmpty() && database.gatewayJobDao().queuedDispatches().isEmpty()) {
                    try {
                        gatewayStore.externalWorkBlocker()
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        "An earlier Gateway job could not be reconciled. Restore its original connection and check saved jobs before starting new work."
                    }
                } else {
                    null
                }
                prompts.groupBy { it.chatId }.forEach { (chatId, entries) ->
                    val prompt = entries.first()
                    try {
                        val payload = prompt.details()
                        val selected = payload.gateway
                        if (selected != null) {
                            val profile = profiles.singleOrNull { it.uid == selected.profileUid }
                            val blocker = when {
                                prompt.paused -> "This Gateway message is paused."
                                profile == null -> "The original Gateway profile is unavailable."
                                snapshot.features.conversationGateway[chatId] != selected -> "The Gateway chat selection changed. Restore its accepted profile and model or remove this queued message."
                                else -> selected.validate(profile)
                            }
                            if (blocker != null) {
                                states.putIfAbsent(chatId, GatewayQueuedChatState(GatewayQueuedState.BLOCKED, counts[chatId] ?: 1, blocker))
                                return@forEach
                            }
                            if (database.gatewayJobDao().queuedDispatches().isNotEmpty() || chatId in busyChats) {
                                states.putIfAbsent(chatId, GatewayQueuedChatState(GatewayQueuedState.QUEUED, counts[chatId] ?: 1, "Waiting for the current Gateway job to finish."))
                                return@forEach
                            }
                            if (database.gatewayJobDao().queuedDeliveries(chatId).isNotEmpty()) {
                                states.putIfAbsent(chatId, GatewayQueuedChatState(GatewayQueuedState.RECONCILING, counts[chatId] ?: 1, "The previous job has ended. Waiting for its saved reply before continuing this chat."))
                                return@forEach
                            }
                            if (externalBlocker != null) {
                                states.putIfAbsent(chatId, GatewayQueuedChatState(GatewayQueuedState.RECONCILING, counts[chatId] ?: 1, externalBlocker))
                                return@forEach
                            }
                            val observation = ready[targetKey(selected)]
                            if (observation?.isOnline != true || !observation.conversationSyncSupported || observation.freshnessRemainingMillis(android.os.SystemClock.elapsedRealtime()) == 0L) {
                                states.putIfAbsent(chatId, waitingState(observation, counts[chatId] ?: 1))
                                return@forEach
                            }
                        }
                        if (chatId in busyChats || prompt.paused) return@forEach
                        val pausedProfiles = preferences.getStringSet("paused_$chatId", emptySet()).orEmpty()
                        if (payload.profileUids.any { it in pausedProfiles }) return@forEach
                        if (!database.agentPersistenceDao().queuedTurnReady(chatId, pausedProfiles)) return@forEach
                        val room = database.agentPersistenceDao().getChatRoom(chatId) ?: return@forEach
                        if (payload.tools.combined == null && payload.profileUids.any { it !in room.activePlatform }) {
                            // Preserve the requested model snapshot for explicit review; never silently reroute.
                            dao.pause(prompt.id, true)
                            return@forEach
                        }
                        val targets = payload.profileUids.mapNotNull { uid -> profiles.firstOrNull { it.uid == uid && it.enabled } }
                        if (targets.isEmpty() || targets.size != payload.profileUids.size) {
                            dao.pause(prompt.id, true)
                            return@forEach
                        }
                        val remote = targets.any { it.compatibleType != dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM }
                        val features = settings.getFeatureSettings()
                        val save = features.combinedSettingsJournal[chatId]
                        if (save != null && !save.roomSettled && save.phase != dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.COMMITTED) return@forEach
                        if ((payload.localOnly && remote) || (payload.requiresSpendAllowance && remote && (!features.spendBudget.enforced || features.tokenBudget.outputTokens <= 0))) {
                            dao.pause(prompt.id, true)
                            return@forEach
                        }
                        val before = chats.fetchMessagesV2(chatId)
                        val resolved = targets.map {
                            it.copy(
                                model = payload.models[it.uid] ?: it.model,
                                reasoning = payload.tools.reasoning ?: it.reasoning,
                                temperature = if (payload.tools.combined == null) it.temperature else payload.tools.combined.requested.temperatureFor(it.uid, it.temperature)
                            )
                        }
                        val accepted = payload.tools.combined
                        val dispatchTools = if (accepted == null) {
                            payload.tools
                        } else {
                            check(accepted.requested.userPrompt == prompt.text && accepted.requested.participantUids == payload.profileUids && accepted.requested.requestedModels == payload.models) {
                                "Queued Combined intent no longer matches its accepted snapshot."
                            }
                            val users = before.filter { it.platformType == null } + MessageV2(chatId = chatId, content = prompt.text, platformType = null)
                            val rows = users.map { user -> before.filter { it.platformType != null && it.linkedMessageId == user.id } }
                            payload.tools.copy(combined = chats.prepareCombinedTurn(accepted.copy(chatId = chatId), users, rows, resolved, payload.tools))
                        }
                        val acceptedRecord = dispatchTools.combined?.let {
                            dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn(context = it, tools = dispatchTools).encode()
                        }
                        val turn = PersistAgentTurnRequest(
                            chatRoom = room,
                            userMessage = MessageV2(chatId = chatId, content = prompt.text, attachments = payload.attachments, platformType = null),
                            runs = resolved.map { AgentRunDraft(UUID.randomUUID().toString(), it.uid, it.compatibleType.name, it.model, synthesisSnapshot = acceptedRecord) },
                            chatPlatformModels = chats.fetchChatPlatformModels(chatId),
                            queuedPromptId = prompt.id,
                            queuedPausedProfiles = pausedProfiles
                        )
                        val result = if (selected == null) chats.persistAgentTurn(turn) else gatewayStore.persist(prompt, before, turn) ?: return@forEach
                        result.runs.forEach { run ->
                            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Send", "QUEUED_SEND_ACCEPTED · requestId=${payload.requestId ?: prompt.id} · queueId=${prompt.id} · runId=${run.runId} · chatId=$chatId · dispatched=false")
                        }
                        val users = before.filter { it.platformType == null } + result.userMessage
                        val assistants = users.map { user ->
                            (before + result.assistantMessages).filter { it.platformType != null && it.linkedMessageId == user.id }
                        }
                        coordinator.start(
                            result.runs.map { run ->
                                AgentRunRequest(
                                    run.runId,
                                    chatId,
                                    result.assistantMessages.first { it.id == run.assistantMessageId },
                                    resolved.first { it.uid == run.profileUid },
                                    users,
                                    assistants,
                                    dispatchTools.copy(
                                        combined = dispatchTools.combined?.forRole(
                                            dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.CONTRIBUTOR,
                                            run.profileUid
                                        )
                                    )
                                )
                            }
                        )
                        if (selected != null) {
                            submittedRuns += result.runs.single().runId
                            states[chatId] = GatewayQueuedChatState(GatewayQueuedState.QUEUED, ((counts[chatId] ?: 1) - 1).coerceAtLeast(0), "Saved for execution. Waiting for app execution to start.", result.runs.single().runId)
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Preserve failed submissions for user inspection and an explicit resume.
                        dao.pause(prompt.id, true)
                        if (runCatching { prompt.details().gateway != null }.getOrDefault(false)) states.putIfAbsent(chatId, GatewayQueuedChatState(GatewayQueuedState.BLOCKED, counts[chatId] ?: 1, "The queued Gateway message could not start. Its input is saved; review the original profile and resume it."))
                    }
                }
                _gatewayStates.value = states
            }
        }
    }

    private fun waitingState(observation: GatewayAvailability?, count: Int = 0, runId: String? = null): GatewayQueuedChatState {
        val unsupported = observation?.isOnline == true && !observation.conversationSyncSupported
        return GatewayQueuedChatState(
            if (unsupported) GatewayQueuedState.BLOCKED else GatewayQueuedState.WAITING_FOR_GATEWAY,
            count,
            if (unsupported) "This Gateway must support saved conversations before queued work can start." else "Saved on this device. Waiting for the original Gateway and model to be ready.",
            runId
        )
    }

    private suspend fun reconcile(lease: GatewaySubmissionRecord, profiles: List<PlatformV2>, ready: Map<String, GatewayAvailability>): GatewayQueuedChatState {
        val runId = requireNotNull(lease.runId)
        if (lease.state == "LOCAL_QUEUE_DELIVERY_PENDING") return reconcileDelivery(lease)
        if (runId in coordinator.activeRuns.value) return GatewayQueuedChatState(GatewayQueuedState.RUNNING, runId = runId)
        return try {
            val run = database.agentRunDao().getById(runId)
            if (lease.receipt == "BACKUP_RESTORED" || run?.terminalError == "BACKUP_RESTORED") return GatewayQueuedChatState(GatewayQueuedState.BLOCKED, message = "This Gateway job was restored from a backup. Review its original saved job before allowing new work.", runId = runId)
            val original = gatewayStore.turn(lease)
            val selected = requireNotNull(original.accepted.gateway)
            val remote = database.gatewayJobDao().submissionsForRun(runId)
            if (run != null && (run.status == "QUEUED" || run.status == "INTERRUPTED" && run.startedAt == null) && remote.isEmpty() && run.gatewayJobId == null) {
                val blocker = gatewayStore.problem(lease)
                if (blocker != null) return GatewayQueuedChatState(GatewayQueuedState.BLOCKED, message = blocker, runId = runId, canCancelUnsubmitted = true)
                val observation = ready[targetKey(selected)]
                if (observation?.isOnline != true || !observation.conversationSyncSupported || observation.freshnessRemainingMillis(android.os.SystemClock.elapsedRealtime()) == 0L) return waitingState(observation, runId = runId).copy(canCancelUnsubmitted = true)
                if (runId !in submittedRuns) {
                    val profile = profiles.singleOrNull { it.uid == selected.profileUid } ?: return GatewayQueuedChatState(GatewayQueuedState.BLOCKED, message = "The original Gateway profile is unavailable.", runId = runId)
                    gatewayStore.resumedRequest(lease, profile)?.let {
                        coordinator.start(listOf(it))
                        submittedRuns += runId
                    }
                }
                return GatewayQueuedChatState(GatewayQueuedState.QUEUED, message = "Saved for execution. Open the app if Android has paused background execution.", runId = runId, canCancelUnsubmitted = true)
            }
            if (run?.status in setOf("RUNNING", "WAITING_PROVIDER_LIMIT")) return GatewayQueuedChatState(GatewayQueuedState.RUNNING, runId = runId)
            if (gatewayStore.remoteSettled(lease)) {
                // Terminal execution proof releases the global fence independently
                // of transcript delivery, which may need a later read-only sync.
                val needsDelivery = gatewayStore.needsDelivery(lease)
                gatewayStore.release(lease, if (remote.isEmpty()) "NO_REMOTE_ADMISSION" else "VERIFIED_REMOTE_TERMINAL", pendingDelivery = needsDelivery)
                submittedRuns -= runId
                if (needsDelivery) {
                    return reconcileDelivery(lease)
                }
                GatewayQueuedChatState(GatewayQueuedState.QUEUED, message = "The previous Gateway job has ended.")
            } else {
                gatewayStore.refresh(lease)
                coordinator.recoverInterruptedGatewayRuns()
                GatewayQueuedChatState(GatewayQueuedState.RECONCILING, message = "Checking the original Gateway job before starting the next message.", runId = runId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GatewayQueuedChatState(GatewayQueuedState.RECONCILING, message = "The previous Gateway outcome is not confirmed. Its saved job will be checked again when the original connection is available.", runId = runId)
        }
    }

    private suspend fun reconcileDelivery(lease: GatewaySubmissionRecord): GatewayQueuedChatState {
        if (lease.receipt == "BACKUP_RESTORED") return GatewayQueuedChatState(GatewayQueuedState.BLOCKED, message = "This saved reply was restored from a backup. Check its original job before continuing this chat.", runId = lease.runId)
        return try {
            if (!gatewayStore.deliveryReady(lease)) {
                gatewayStore.refresh(lease)
                coordinator.recoverInterruptedGatewayRuns()
            }
            if (gatewayStore.deliveryReady(lease)) {
                gatewayStore.finishDelivery(lease)
                GatewayQueuedChatState(GatewayQueuedState.QUEUED, message = "The previous Gateway reply is saved.")
            } else {
                GatewayQueuedChatState(GatewayQueuedState.RECONCILING, message = "The Gateway job ended. Its saved reply must be restored before this chat continues.", runId = lease.runId)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            GatewayQueuedChatState(GatewayQueuedState.RECONCILING, message = "The Gateway job ended. Waiting to sync its saved reply before continuing this chat.", runId = lease.runId)
        }
    }

    suspend fun enqueue(chatId: Int, text: String, payload: PendingPromptPayload, deduplicationKey: String? = null) {
        require(chatId > 0 && (text.isNotBlank() || payload.attachments.isNotEmpty()))
        val room = requireNotNull(database.gatewayJobDao().localChat(chatId)) { "The conversation is unavailable." }
        val acceptedPayload = if (room.conversationMode == dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode.GATEWAY) {
            // Native approved continuations already freeze their profile/model/tools.
            // Attach the same room's Gateway identity without changing that intent.
            val selected = requireNotNull(payload.gateway ?: settings.getFeatureSettings().conversationGateway[chatId]) { "The original Gateway selection must be saved with queued input." }
            require(!room.isTemporary && room.activePlatform == listOf(selected.profileUid) && payload.profileUids == listOf(selected.profileUid) && payload.models[selected.profileUid] == selected.model && payload.tools.combined == null)
            check(settings.getFeatureSettings().conversationGateway[chatId] == selected) { "The Gateway selection changed before the message was saved." }
            payload.copy(gateway = selected)
        } else {
            require(payload.gateway == null) { "Gateway work requires its own saved conversation." }
            payload
        }
        val promptId = deduplicationKey?.let { UUID.nameUUIDFromBytes("$chatId:$it".toByteArray(Charsets.UTF_8)).toString() } ?: UUID.randomUUID().toString()
        val pending = PendingPrompt(promptId, chatId, text, Json.encodeToString(acceptedPayload), 0)
        val inserted = database.withTransaction {
            val accepted = if (deduplicationKey == null) {
                dao.enqueue(pending)
                true
            } else {
                dao.enqueueOnce(pending)
            }
            if (accepted && acceptedPayload.gateway != null) {
                val current = database.gatewayJobDao().localChat(chatId)
                if (current != null && !current.isTitleCustomized && current.title in setOf("", "Untitled Chat", "New Gateway Chat")) {
                    val title = text.replace(Regex("\\s+"), " ").trim().take(50).ifBlank { "Gateway attachment" }
                    chats.updateChatTitle(current, title, isCustomized = true)
                }
            }
            accepted
        }
        if (!inserted) {
            start()
            return
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Send", "SEND_QUEUE_PERSISTED · requestId=${payload.requestId ?: promptId} · queueId=$promptId · chatId=$chatId")
        if (acceptedPayload.gateway != null) scheduleWake()
        start()
    }

    private fun scheduleWake() {
        try {
            GatewayQueueWakeWorker.schedule(context)
        } catch (_: Exception) {
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("GatewayQueue", "BACKGROUND_WAKE_DEFERRED · persisted=true", "W")
        }
    }

    internal fun wake() {
        start()
        wake.value += 1
    }

    internal suspend fun hasGatewayWork(): Boolean = database.gatewayJobDao().queuedDispatches().isNotEmpty() ||
        database.gatewayJobDao().queuedDeliveries().isNotEmpty() ||
        pending.first().any { !it.paused && runCatching { it.details().gateway != null }.getOrDefault(false) }

    /** User-visible read-only retry, including a quarantined restored job with exact remote proof. */
    suspend fun checkGateway(chatId: Int): String? {
        val leases = (database.gatewayJobDao().queuedDispatches() + database.gatewayJobDao().queuedDeliveries(chatId)).filter { it.chatId == chatId }
        for (lease in leases) {
            val restored = lease.receipt == "BACKUP_RESTORED" || lease.runId?.let { database.agentRunDao().getById(it)?.terminalError } == "BACKUP_RESTORED"
            if (restored && !gatewayStore.reviewRestored(lease)) return "This backup has no verified terminal command for its original job. Open Gateway Jobs and reconcile that original job; it will not be submitted again."
        }
        val blocker = if (leases.isEmpty()) gatewayStore.externalWorkBlocker() else null
        wake()
        return blocker
    }

    suspend fun cancelUnsubmittedGateway(chatId: Int): Boolean {
        val canceled = gatewayStore.cancelUnsubmitted(chatId)
        if (canceled) wake()
        return canceled
    }

    fun observe(chatId: Int) = pending.map { entries -> entries.filter { it.chatId == chatId } }
    suspend fun remove(id: String) {
        if (FollowUpProgressStore.canChange(id)) dao.delete(id)
    }
    suspend fun edit(id: String, text: String, hasAttachments: Boolean) {
        require(text.isNotBlank() || hasAttachments) { "Add a message or keep an attachment before saving." }
        if (!FollowUpProgressStore.canChange(id)) return
        val current = dao.get(id) ?: return
        val payload = current.details()
        val combined = payload.tools.combined
        val updated = if (combined == null) {
            payload
        } else {
            val accepted = combined.requested
            val requested = dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver.accept(
                settings = accepted.settings,
                singlePass = accepted.singlePass,
                participantUids = accepted.participantUids,
                requestedModels = accepted.requestedModels,
                userPrompt = text,
                settingsRevision = accepted.settingsRevision,
                preferredOwnerUid = accepted.preferredOwnerUid,
                requestedTemperatures = accepted.requestedTemperatures
            )
            payload.copy(
                tools = payload.tools.copy(
                    combined = combined.copy(
                        requested = requested,
                        plan = null,
                        rootBudgetId = UUID.randomUUID().toString()
                    )
                )
            )
        }
        check(dao.replaceUnconsumed(id, current.text, current.payload, text, Json.encodeToString(updated)) == 1) {
            "The queued message changed or started while editing."
        }
    }
    suspend fun pause(id: String, paused: Boolean) {
        if (FollowUpProgressStore.canChange(id)) dao.pause(id, paused)
    }
    suspend fun move(id: String, otherId: String) = dao.swap(id, otherId)
    fun pausedProfiles(chatId: Int): Set<String> = preferences.getStringSet("paused_$chatId", emptySet()).orEmpty().toSet()
    fun setPausedProfiles(chatId: Int, uids: Set<String>) {
        // SharedPreferences.commit() blocks until disk I/O completes. Profile pause state
        // is changed from the chat UI, so use apply(): it updates the in-memory value
        // immediately and persists asynchronously without blocking Compose's main thread.
        preferences.edit().putStringSet("paused_$chatId", uids.toSet()).apply()
        wake.value += 1
    }
}
