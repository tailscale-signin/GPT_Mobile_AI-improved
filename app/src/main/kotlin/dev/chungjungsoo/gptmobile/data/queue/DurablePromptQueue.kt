package dev.chungjungsoo.gptmobile.data.queue

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.agent.temperatureFor
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
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
    @param:ApplicationContext private val context: Context
) {
    private val dao get() = database.pendingPromptDao()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val started = AtomicBoolean()
    private val preferences = context.getSharedPreferences("prompt_queue", Context.MODE_PRIVATE)
    private val wake = MutableStateFlow(0)
    val pending = dao.observePending()

    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate.await()
            combine(pending, database.agentRunDao().observeActive(), combine(settings.observePlatformV2s(), settings.observeFeatureSettings()) { profiles, _ -> profiles }, coordinator.activeRuns, wake) { prompts, active, profiles, live, _ ->
                Triple(prompts, active.map { it.chatId }.toSet() + live.values.map { it.chatId }, profiles)
            }.collect { (prompts, busyChats, profiles) ->
                prompts.groupBy { it.chatId }.forEach { (chatId, entries) ->
                    if (chatId in busyChats) return@forEach
                    val prompt = entries.first()
                    if (prompt.paused) return@forEach
                    try {
                        val payload = prompt.details()
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
                        val result = chats.persistAgentTurn(
                            PersistAgentTurnRequest(
                                chatRoom = room,
                                userMessage = MessageV2(chatId = chatId, content = prompt.text, attachments = payload.attachments, platformType = null),
                                runs = resolved.map { AgentRunDraft(UUID.randomUUID().toString(), it.uid, it.compatibleType.name, it.model, synthesisSnapshot = acceptedRecord) },
                                chatPlatformModels = chats.fetchChatPlatformModels(chatId),
                                queuedPromptId = prompt.id,
                                queuedPausedProfiles = pausedProfiles
                            )
                        )
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
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        // Preserve failed submissions for user inspection and an explicit resume.
                        dao.pause(prompt.id, true)
                    }
                }
            }
        }
    }

    suspend fun enqueue(chatId: Int, text: String, payload: PendingPromptPayload) {
        require(chatId > 0 && (text.isNotBlank() || payload.attachments.isNotEmpty()))
        dao.enqueue(PendingPrompt(UUID.randomUUID().toString(), chatId, text, Json.encodeToString(payload), 0))
        start()
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
