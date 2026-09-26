package dev.chungjungsoo.gptmobile.data.queue

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
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
            combine(pending, database.agentRunDao().observeActive(), settings.observePlatformV2s(), wake) { prompts, active, profiles, _ ->
                Triple(prompts, active.map { it.chatId }.toSet(), profiles)
            }.collect { (prompts, busyChats, profiles) ->
                prompts.groupBy { it.chatId }.forEach { (chatId, entries) ->
                    if (chatId in busyChats) return@forEach
                    val prompt = entries.first()
                    if (prompt.paused) return@forEach
                    try {
                        val payload = prompt.details()
                        val pausedProfiles = preferences.getStringSet("paused_$chatId", emptySet()).orEmpty()
                        if (payload.profileUids.any { it in pausedProfiles }) return@forEach
                        val targets = payload.profileUids.mapNotNull { uid -> profiles.firstOrNull { it.uid == uid && it.enabled } }
                        if (targets.isEmpty() || targets.size != payload.profileUids.size) {
                            dao.pause(prompt.id, true)
                            return@forEach
                        }
                        val room = database.agentPersistenceDao().getChatRoom(chatId) ?: return@forEach
                        val before = chats.fetchMessagesV2(chatId)
                        val resolved = targets.map { it.copy(model = payload.models[it.uid] ?: it.model) }
                        val result = chats.persistAgentTurn(
                            PersistAgentTurnRequest(
                                chatRoom = room,
                                userMessage = MessageV2(chatId = chatId, content = prompt.text, attachments = payload.attachments, platformType = null),
                                runs = resolved.map { AgentRunDraft(UUID.randomUUID().toString(), it.uid, it.compatibleType.name, it.model) },
                                chatPlatformModels = payload.models,
                                queuedPromptId = prompt.id
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
                                    payload.tools
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
    suspend fun remove(id: String) = dao.delete(id)
    suspend fun edit(id: String, text: String) {
        require(text.isNotBlank())
        dao.edit(id, text)
    }
    suspend fun pause(id: String, paused: Boolean) = dao.pause(id, paused)
    suspend fun move(id: String, otherId: String) = dao.swap(id, otherId)
    fun pausedProfiles(chatId: Int): Set<String> = preferences.getStringSet("paused_$chatId", emptySet()).orEmpty().toSet()
    fun setPausedProfiles(chatId: Int, uids: Set<String>) {
        check(preferences.edit().putStringSet("paused_$chatId", uids).commit())
        wake.value += 1
    }
}
