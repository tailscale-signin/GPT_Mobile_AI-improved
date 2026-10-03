package dev.chungjungsoo.gptmobile.presentation.ui.workspace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.tool.AgentToolResolver
import dev.chungjungsoo.gptmobile.data.agent.tool.McpClientManager
import dev.chungjungsoo.gptmobile.data.agent.tool.McpInteractions
import dev.chungjungsoo.gptmobile.data.benchmark.BenchmarkStore
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.data.workspace.RecipeScheduler
import dev.chungjungsoo.gptmobile.data.workspace.RemoteTaskHandle
import dev.chungjungsoo.gptmobile.data.workspace.ResearchPin
import dev.chungjungsoo.gptmobile.data.workspace.TaskRecipe
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRecord
import dev.chungjungsoo.gptmobile.data.workspace.WorkspaceRepository
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.single
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString

@HiltViewModel
class WorkspaceViewModel @Inject constructor(
    val workspace: WorkspaceRepository,
    val queue: DurablePromptQueue,
    val coordinator: AgentRunCoordinator,
    val settings: SettingRepository,
    val benchmarks: BenchmarkStore,
    val interactions: McpInteractions,
    private val resolver: AgentToolResolver,
    private val mcp: McpClientManager,
    private val connections: ToolConnectionRepository,
    private val scheduler: RecipeScheduler
) : ViewModel() {
    val inspectedRun = MutableStateFlow("")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val requestReceipts = inspectedRun.flatMapLatest { workspace.dao.observeRun(it) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val records = workspace.records.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val runs = workspace.database.agentRunDao().observeRecent(150).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val approvals = workspace.database.toolApprovalDao().attention().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val spending = workspace.database.invocationDao().statistics().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val pending = workspace.database.pendingPromptDao().observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val features = settings.observeFeatureSettings().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings())
    val notice = MutableStateFlow<String?>(null)
    val profiles = MutableStateFlow<List<PlatformV2>>(emptyList())
    val chats = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2>>(emptyList())
    val comparison = MutableStateFlow<Pair<List<dev.chungjungsoo.gptmobile.data.database.entity.MessageV2>, List<dev.chungjungsoo.gptmobile.data.database.entity.MessageV2>>?>(null)
    fun compare(other: Int) = action {
        val selected = requireNotNull(chatId.value)
        comparison.value = workspace.database.messageDao().comparisonHistory(selected).asReversed() to workspace.database.messageDao().comparisonHistory(other).asReversed()
    }
    val chatId = MutableStateFlow<Int?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val events = chatId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else workspace.database.agentPersistenceDao().observeToolEventsForChat(id) }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    init {
        action {
            profiles.value = settings.fetchPlatformV2s()
            chats.value = workspace.database.chatRoomDao().getChatRoomsWithFavorites()
            chatId.value = chats.value.firstOrNull()?.id
            benchmarks.load()
        }
    }
    fun action(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                notice.value = error.message ?: "This action could not finish."
            }
        }
    }
    fun remote(entry: WorkspaceRecord, cancel: Boolean = false, answer: Boolean = false) = action {
        val handle = workspace.json.decodeFromString<RemoteTaskHandle>(entry.payload)
        val connection = requireNotNull(connections.getConnection(handle.connectionUid)) { "The original connection is unavailable." }
        mcp.refreshTask(resolver.mcpConfig(connection), entry, cancel, answer)
        notice.value = if (cancel) "Cancellation requested. The server may already have completed the task." else "Task status refreshed."
    }
    fun exclude(entry: WorkspaceRecord, fact: String? = null, attachment: String? = null, documents: Boolean = false) = action {
        val id = requireNotNull(entry.chatId)
        val old = workspace.exclusions(id)
        workspace.exclude(id, old.copy(facts = old.facts + listOfNotNull(fact), attachments = old.attachments + listOfNotNull(attachment), documents = old.documents || documents))
        notice.value = "Excluded from future requests in this conversation. The recorded request is unchanged."
    }
    fun pin(event: ToolEvent, url: String, excerpt: String, claim: String) = action {
        val id = requireNotNull(chatId.value)
        workspace.pin(id, ResearchPin(url, excerpt.take(16000), claim.take(2000), event.eventId, event.completedAt ?: event.startedAt ?: 0, if (event.toolName.contains("search", true)) "Search snippet" else "Tool evidence"))
    }
    fun draft(text: String, onReady: (Int) -> Unit) = action {
        val id = requireNotNull(chatId.value) { "Choose a conversation first." }
        val room = workspace.database.chatRoomDao().getChatRoomsByIds(listOf(id)).single()
        workspace.database.chatRoomDao().saveComposerDraft(id, listOfNotNull(room.draftText?.takeIf(String::isNotBlank), text).joinToString("\n\n"), room.draftAttachments, System.currentTimeMillis() / 1000)
        onReady(id)
    }
    fun reviewRecipe(entry: WorkspaceRecord) = action {
        val recipe = workspace.json.decodeFromString<TaskRecipe>(entry.payload)
        val profile = settings.fetchPlatformV2s().firstOrNull { it.uid == recipe.profileUid && it.enabled } ?: error("The saved profile is unavailable.")
        require(!recipe.localOnly || profile.compatibleType == ClientType.LITERT_LM) { "This local-only recipe no longer has an on-device profile." }
        val id = requireNotNull(entry.chatId)
        require(workspace.database.chatRoomDao().getChatRoomsByIds(listOf(id)).single().isTemporary.not())
        val payload = dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload(profileUids = listOf(profile.uid), models = mapOf(profile.uid to profile.model), tools = dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig(allToolsDisabled = true, delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false)), localOnly = recipe.localOnly)
        workspace.database.pendingPromptDao().enqueue(dev.chungjungsoo.gptmobile.data.queue.PendingPrompt("recipe:${entry.id}:${UUID.randomUUID()}", id, "${recipe.prompt}\n\nOutput: ${recipe.outputFormat}", workspace.json.encodeToString(payload), 0, paused = true))
        queue.start()
        notice.value = "Paused task prepared with ${profile.name}. Review it in Tasks and press Resume to send. Tools and delegation are disabled."
    }
    fun saveRecipe(id: String?, title: String, recipe: TaskRecipe) = action {
        require(title.isNotBlank() && recipe.prompt.isNotBlank() && recipe.prompt.length <= 16000)
        val profile = profiles.value.firstOrNull { it.uid == recipe.profileUid && it.enabled } ?: error("Choose an enabled profile.")
        require(!recipe.localOnly || profile.compatibleType == ClientType.LITERT_LM) { "Choose an on-device profile or disable local-only." }
        val chat = requireNotNull(chatId.value) { "Choose the destination conversation." }
        require(chats.value.first { it.id == chat }.isTemporary.not()) { "Recipes cannot target temporary conversations." }
        val entry = WorkspaceRecord(id ?: UUID.randomUUID().toString(), "recipe", title.take(120), workspace.json.encodeToString(recipe), chat)
        workspace.dao.save(entry)
        scheduler.update(entry.id, recipe)
        notice.value = "Recipe saved. Scheduled runs are deferrable, with tools and delegation disabled."
    }
    val evaluating = MutableStateFlow(false)
    fun evaluateMemory() {
        if (evaluating.value) return
        evaluating.value = true
        action {
            try {
                notice.value = "Running local memory fixtures…"
                val result = dev.chungjungsoo.gptmobile.data.rag.MemoryQualitySuite.run()
                workspace.dao.save(WorkspaceRecord(UUID.randomUUID().toString(), "memory_benchmark", "Memory corpus v${result.corpusVersion}", workspace.json.encodeToString(result)))
                notice.value = "Memory fixture evaluation completed. Native encoder/device integration is a separate instrumented gate."
            } finally {
                evaluating.value = false
            }
        }
    }
    fun delete(entry: WorkspaceRecord) = action {
        if (entry.kind == "recipe") {
            scheduler.update(entry.id, workspace.json.decodeFromString<TaskRecipe>(entry.payload).copy(scheduled = false))
            workspace.database.pendingPromptDao().deleteRecipePrompts("recipe:${entry.id}:%")
        }
        workspace.dao.delete(entry.id)
    }
}
