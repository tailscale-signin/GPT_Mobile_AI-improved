package dev.chungjungsoo.gptmobile.presentation.ui.setting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.database.ChatDatabaseV2
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ToolPluginId
import dev.chungjungsoo.gptmobile.data.permissions.ToolApproval
import dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.queue.PendingPrompt
import dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

data class GitHubProgrammerState(
    val profiles: List<PlatformV2> = emptyList(),
    val profileUid: String? = null,
    val profileOverrideUid: String? = null,
    val canLoadEarlier: Boolean = false,
    val context: GitHubConversationContext? = null,
    val chatId: Int? = null,
    val messages: List<MessageV2> = emptyList(),
    val runs: List<AgentRun> = emptyList(),
    val events: List<ToolEvent> = emptyList(),
    val pending: List<PendingPrompt> = emptyList(),
    val approvals: List<ToolApproval> = emptyList(),
    val submitting: Boolean = false,
    val prepareIndex: Boolean = false,
    val draft: String = "",
    val draftLoaded: Boolean = false,
    val error: String? = null
)

/** One main profile, one scoped conversation, and the same durable runner as Chat. */
@HiltViewModel
class GitHubProgrammerViewModel @Inject constructor(
    private val settings: SettingRepository,
    private val chats: ChatRepository,
    private val database: ChatDatabaseV2,
    private val queue: DurablePromptQueue,
    private val coordinator: AgentRunCoordinator,
    private val approvals: ToolApprovalManager,
    private val connections: ToolConnectionRepository,
    private val drafts: dev.chungjungsoo.gptmobile.data.github.GitHubWorkspaceDraftStore
) : ViewModel() {
    private val mutable = MutableStateFlow(GitHubProgrammerState())
    val state = mutable.asStateFlow()
    private val destination = MutableStateFlow<GitHubWorkspaceState?>(null)
    private var conversationJob: Job? = null
    private var observedChat: Int? = null
    private var historyTurns = 10
    private data class CommandDraft(val key: String, val text: String, val revision: Long)
    private val draftRevision = java.util.concurrent.atomic.AtomicLong()
    private val latestDrafts = java.util.concurrent.ConcurrentHashMap<String, CommandDraft>()
    private val draftJobs = java.util.concurrent.ConcurrentHashMap<String, Job>()
    private val draftMutex = kotlinx.coroutines.sync.Mutex()
    private fun key(context: GitHubConversationContext) = "programmer:${context.connectionUid}:${context.owner.lowercase()}/${context.repo.lowercase()}:${context.ref}:${context.mainProfileUid}"

    init {
        viewModelScope.launch(Dispatchers.IO) {
            combine(settings.observePlatformV2s(), settings.observeFeatureSettings(), destination) { profiles, features, workspace -> Triple(profiles, features, workspace) }.collect { (profiles, features, workspace) ->
                val connection = workspace?.connection
                val selected = workspace?.selection
                val storedUid = connection?.connectionUid?.let(features.githubMainProfiles::get)
                val enabled = profiles.filter { it.enabled }
                val uid = storedUid ?: features.githubDefaultProfileUid ?: enabled.sortedWith(compareByDescending<PlatformV2> { it.isFavorite }.thenBy { it.name }).firstOrNull()?.uid
                val prepare = connection?.connectionUid?.let { it in features.githubPrepareIndex } == true
                val context = if (connection != null && selected != null && workspace.headSha.isNotBlank() && uid != null) {
                    GitHubConversationContext(connection.connectionUid, selected.owner, selected.repo, selected.ref, workspace.headSha, uid, prepare)
                } else {
                    null
                }
                val previousKey = mutable.value.context?.let(::key)
                val nextKey = context?.let(::key)
                if (previousKey != nextKey) observeConversation(null)
                mutable.update { it.copy(profiles = enabled, profileUid = uid, profileOverrideUid = storedUid, context = context, prepareIndex = prepare, draftLoaded = it.draftLoaded && previousKey == nextKey) }
                if (previousKey != nextKey || !mutable.value.draftLoaded) {
                    try {
                        val restored = nextKey?.let { latestDrafts[it]?.text ?: drafts.read(it) }.orEmpty()
                        mutable.update { it.copy(draft = restored, draftLoaded = context != null) }
                    } catch (_: Exception) {
                        mutable.update { it.copy(draftLoaded = false, error = "Your encrypted command draft could not be restored. Reopen the workspace to retry.") }
                    }
                }
                val candidates = if (context == null) emptyList() else features.conversationGitHub.filterValues { it.sameWorkspace(context) }.keys
                val room = candidates.sortedDescending().firstNotNullOfOrNull { id ->
                    database.agentPersistenceDao().getChatRoom(id)?.takeIf { !it.isArchived && !it.isTemporary && it.activePlatform == listOf(uid) }
                }
                observeConversation(room?.id)
            }
        }
    }

    fun updateDraft(context: GitHubConversationContext?, text: String) {
        if (context == null || !mutable.value.draftLoaded) return
        val key = key(context)
        val value = CommandDraft(key, text, draftRevision.incrementAndGet())
        latestDrafts[key] = value
        mutable.update { if (it.context?.let(::key) == key) it.copy(draft = text) else it }
        draftJobs.remove(key)?.cancel()
        draftJobs[key] = viewModelScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(250)
            try {
                persistDraft(value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                mutable.update { it.copy(error = "The command draft could not be saved. Keep this workspace open and retry.") }
            }
        }
    }

    private suspend fun persistDraft(value: CommandDraft) = draftMutex.withLock {
        if (latestDrafts[value.key] == value) drafts.write(value.key, value.text)
    }

    suspend fun flushDraft(context: GitHubConversationContext?, text: String): Boolean {
        if (context == null) return true
        return try {
            val value = CommandDraft(key(context), text, draftRevision.incrementAndGet())
            latestDrafts[value.key] = value
            draftJobs.remove(value.key)?.cancel()
            persistDraft(value)
            true
        } catch (_: Exception) {
            mutable.update { it.copy(error = "The command draft could not be saved. Keep this workspace open and retry.") }
            false
        }
    }

    fun bind(workspace: GitHubWorkspaceState) {
        val current = destination.value
        if (current?.connection?.connectionUid != workspace.connection?.connectionUid || current?.selection != workspace.selection || current?.headSha != workspace.headSha) destination.value = workspace
    }

    fun selectProfile(uid: String?) {
        val connectionUid = destination.value?.connection?.connectionUid ?: return
        if (uid != null && mutable.value.profiles.none { it.uid == uid }) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settings.mutateFeatureSettings { it.copy(githubMainProfiles = if (uid == null) it.githubMainProfiles - connectionUid else it.githubMainProfiles + (connectionUid to uid)) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not save the GitHub main profile.") }
            }
        }
    }

    fun setPrepareIndex(value: Boolean) {
        val uid = destination.value?.connection?.connectionUid ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                settings.mutateFeatureSettings { it.copy(githubPrepareIndex = if (value) it.githubPrepareIndex + uid else it.githubPrepareIndex - uid) }
            } catch (_: Exception) {
                mutable.update { it.copy(error = "Could not save the repository indexing option.") }
            }
        }
    }

    private fun observeConversation(chatId: Int?, reload: Boolean = false) {
        if (observedChat == chatId && !reload) return
        if (observedChat != chatId) historyTurns = 10
        observedChat = chatId
        conversationJob?.cancel()
        if (!reload) mutable.update { it.copy(chatId = chatId, messages = emptyList(), runs = emptyList(), events = emptyList(), pending = emptyList(), approvals = emptyList()) }
        if (chatId == null) return
        conversationJob = viewModelScope.launch(Dispatchers.IO) {
            combine(chats.observeMessageWindow(chatId, historyTurns), chats.observeAgentRuns(chatId), chats.observeToolEvents(chatId), queue.observe(chatId), approvals.pending) { messages, runs, events, pending, prompts ->
                val ids = runs.mapTo(HashSet()) { it.runId }
                GitHubProgrammerState(messages = messages, canLoadEarlier = messages.count { it.platformType == null } >= historyTurns, runs = runs, events = events.takeLast(100), pending = pending, approvals = prompts.filter { it.runId in ids })
            }.collect { value ->
                if (observedChat == chatId) mutable.update { it.copy(messages = value.messages, canLoadEarlier = value.canLoadEarlier, runs = value.runs, events = value.events, pending = value.pending, approvals = value.approvals) }
            }
        }
    }

    fun loadEarlierMessages() {
        historyTurns += 10
        observeConversation(observedChat, reload = true)
    }

    fun send(text: String, accepted: () -> Unit) {
        val context = mutable.value.context ?: return
        if (text.isBlank() || mutable.value.submitting) return
        mutable.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val profile = settings.fetchPlatformV2s().singleOrNull { it.uid == context.mainProfileUid && it.enabled } ?: error("Select an available main AI profile.")
                val features = settings.getFeatureSettings()
                require(!profile.disableAllTools && !profile.disableRemoteTools && features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.GITHUB) && features.isToolPluginEnabledForProfile(profile.uid, ToolPluginId.connection(context.connectionUid))) { "Enable GitHub tools for ${profile.name} in AI profile settings before sending. Your command is kept." }
                require(!settings.providerActivationRequired(profile)) { "Review this AI profile's provider privacy settings before sending." }
                dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile)?.let { error(it.message) }
                val connection = connections.getConnection(context.connectionUid) ?: error("The GitHub connection was removed.")
                require(connection.type == "GITHUB" && connection.secretRef != null) { "Connect GitHub before starting an engineering task." }
                var room = mutable.value.chatId?.let { database.agentPersistenceDao().getChatRoom(it) }?.takeIf { !it.isArchived && it.activePlatform == listOf(profile.uid) && features.conversationGitHub[it.id]?.sameWorkspace(context) == true }
                if (room == null) room = chats.createDraftChat(listOf(profile.uid), "STANDARD", "${context.owner}/${context.repo}", profile.uid)
                val chatId = room.id
                chats.saveChatPlatformModels(chatId, mapOf(profile.uid to profile.model))
                settings.mutateFeatureSettings { it.copy(conversationGitHub = it.conversationGitHub + (chatId to context)) }
                val tools = ChatMcpToolConfig(allowAllByDefault = false, enabledToolIds = setOf(context.connectionUid), github = context)
                val prompt = "GitHub workspace: ${context.owner}/${context.repo}\nBranch: ${context.ref}\n\n" + text
                queue.enqueue(chatId, prompt, PendingPromptPayload(profileUids = listOf(profile.uid), models = mapOf(profile.uid to profile.model), tools = tools))
                if (mutable.value.context?.sameWorkspace(context) == true) observeConversation(chatId)
                withContextMain(accepted)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(error = failure.message ?: "Could not save this command. Your draft is kept.") }
            } finally {
                mutable.update { it.copy(submitting = false) }
            }
        }
    }

    private suspend fun withContextMain(action: () -> Unit) = kotlinx.coroutines.withContext(Dispatchers.Main) { action() }

    fun stop() {
        val id = mutable.value.chatId ?: return
        coordinator.cancelChat(id)
        viewModelScope.launch(Dispatchers.IO) { mutable.value.pending.forEach { queue.remove(it.id) } }
    }

    fun approve(id: String, allowed: Boolean) {
        viewModelScope.launch { approvals.decide(id, allowed) }
    }
    fun alwaysAllowTool(id: String) {
        viewModelScope.launch { approvals.alwaysAllow(id) }
    }
    fun alwaysAllowProvider(id: String) {
        viewModelScope.launch { approvals.alwaysAllowProvider(id) }
    }
    fun allowInConversation(id: String) {
        viewModelScope.launch { approvals.allowInConversation(id) }
    }
    fun dismissError() {
        mutable.update { it.copy(error = null) }
    }
}
