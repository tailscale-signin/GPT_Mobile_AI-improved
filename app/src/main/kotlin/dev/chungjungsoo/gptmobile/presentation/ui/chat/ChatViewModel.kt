package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.agent.ProviderWaitDeadline
import dev.chungjungsoo.gptmobile.data.agent.bindAcceptedStage
import dev.chungjungsoo.gptmobile.data.agent.temperatureFor
import dev.chungjungsoo.gptmobile.data.agent.tool.DelegationRecoveryInteractions
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.LEGACY_ORDER_NOTICE
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolConnectionType
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveCombinedSources
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.database.entity.rebuildAssistantTimelineForEdit
import dev.chungjungsoo.gptmobile.data.database.entity.resetActiveRevision
import dev.chungjungsoo.gptmobile.data.database.entity.selectRevision
import dev.chungjungsoo.gptmobile.data.database.entity.snapshotLatestAssistantRevision
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelStatus
import dev.chungjungsoo.gptmobile.data.model.AvailableChatTool
import dev.chungjungsoo.gptmobile.data.model.ChatAttachment
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
import dev.chungjungsoo.gptmobile.data.model.ClientType
import dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings
import dev.chungjungsoo.gptmobile.data.model.delegationFor
import dev.chungjungsoo.gptmobile.data.model.excludesMemory
import dev.chungjungsoo.gptmobile.data.model.isPrivateDestination
import dev.chungjungsoo.gptmobile.data.repository.AttachmentUploadCoordinator
import dev.chungjungsoo.gptmobile.data.repository.ChatRepository
import dev.chungjungsoo.gptmobile.data.repository.LocalModelRepository
import dev.chungjungsoo.gptmobile.data.repository.ModelCatalogRepository
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.data.repository.ToolConnectionRepository
import dev.chungjungsoo.gptmobile.presentation.StartupRecoveryGate
import dev.chungjungsoo.gptmobile.presentation.ui.setup.DownloadedLocalModelOption
import dev.chungjungsoo.gptmobile.presentation.ui.thinking.ThinkingParser
import dev.chungjungsoo.gptmobile.util.AttachmentPayloadCache
import dev.chungjungsoo.gptmobile.util.ChatToolUtils
import dev.chungjungsoo.gptmobile.util.FileUtils
import dev.chungjungsoo.gptmobile.util.buildAssistantErrorContent
import dev.chungjungsoo.gptmobile.util.determineLocalNetworkAccessRequirement
import dev.chungjungsoo.gptmobile.util.requiresLocalNetworkAccess
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val delegationRecoveryInteractions: DelegationRecoveryInteractions,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator,
    private val agentRunCoordinator: AgentRunCoordinator,
    private val toolConnectionRepository: ToolConnectionRepository,
    private val localModelRepository: LocalModelRepository,
    private val modelCatalogRepository: ModelCatalogRepository,
    private val freeModelToolConsentStore: dev.chungjungsoo.gptmobile.data.permissions.FreeModelToolConsentStore,
    private val conversationReadStateStore: dev.chungjungsoo.gptmobile.data.chat.ConversationReadStateStore,
    private val generationCompletionStore: dev.chungjungsoo.gptmobile.data.chat.GenerationCompletionStore,
    private val durablePromptQueue: dev.chungjungsoo.gptmobile.data.queue.DurablePromptQueue? = null,
    private val toolApprovals: dev.chungjungsoo.gptmobile.data.permissions.ToolApprovalManager? = null,
    private val mcpInteractions: dev.chungjungsoo.gptmobile.data.agent.tool.McpInteractions? = null,
    private val invocationLedger: dev.chungjungsoo.gptmobile.data.accounting.InvocationLedger? = null,
    private val shareInbox: dev.chungjungsoo.gptmobile.data.sharing.ShareInbox? = null,
    private val factVault: dev.chungjungsoo.gptmobile.data.rag.FactVaultRepository? = null,
    private val researchSessionStore: dev.chungjungsoo.gptmobile.data.research.ResearchSessionStore? = null,
    private val conversationExitWork: dev.chungjungsoo.gptmobile.data.chat.ConversationExitWork,
    @dev.chungjungsoo.gptmobile.di.DefaultDispatcher private val messageGroupingDispatcher: kotlinx.coroutines.CoroutineDispatcher,
    private val memoryPreview: dev.chungjungsoo.gptmobile.data.readiness.MemoryPreviewCoordinator? = null,
    private val researchDatabase: dev.chungjungsoo.gptmobile.data.research.feature.ResearchDatabase? = null,
    private val combinedCollectionDecisions: dev.chungjungsoo.gptmobile.data.agent.CombinedCollectionDecisionStore? = null,
    private val gitHubDecisions: dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions? = null,
    private val gitHubDecisionNotifications: dev.chungjungsoo.gptmobile.data.github.GitHubDecisionNotifications? = null
) : ViewModel() {
    private val diagnosticPrivacyOwner = java.util.UUID.randomUUID().toString()
    private val visibleHistoryTurns = MutableStateFlow(if (savedStateHandle.get<Int>("targetMessageId") != null) Int.MAX_VALUE else 40)
    private var windowStartId = 0
    val olderHistoryAvailable = MutableStateFlow(false)
    fun loadOlderMessages() {
        visibleHistoryTurns.update { (it.toLong() + 40).coerceAtMost(Int.MAX_VALUE.toLong()).toInt() }
    }
    suspend fun resolveCombinedMessage(message: MessageV2): MessageV2 = chatRepository.resolveCombinedMessage(message)

    suspend fun combinedDocumentBlocks(reference: dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference, first: Int, count: Int): List<dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock> =
        chatRepository.combinedDocumentBlocks(reference, first, count)

    suspend fun combinedSourceBlocks(reference: dev.chungjungsoo.gptmobile.data.database.entity.CombinedManifestReference, source: dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse, first: Int, count: Int): List<dev.chungjungsoo.gptmobile.data.agent.CombinedDocumentBlock> =
        chatRepository.combinedSourceBlocks(reference, source, first, count)

    private suspend fun completeWindowMessages(grouped: GroupedMessages): List<MessageV2> {
        val older = if (windowStartId > 0) chatRepository.fetchMessagesV2(_chatRoom.value.id).filter { it.id < windowStartId } else emptyList()
        return (older + persistableMessages(grouped)).map { chatRepository.resolveCombinedMessage(it) }
    }

    val pendingDelegationRecovery get() = delegationRecoveryInteractions.pending
    fun respondDelegationRecovery(id: String, profileUid: String?) {
        delegationRecoveryInteractions.respond(id, profileUid)
    }

    val pendingMcpInput get() = mcpInteractions?.pending ?: MutableStateFlow(emptyList())
    val githubDecisions get() = gitHubDecisions?.decisions ?: MutableStateFlow(emptyList())
    val waitingGitHubDecisions get() = gitHubDecisions?.waiting ?: MutableStateFlow(emptySet())
    suspend fun loadGitHubDecisions() {
        try {
            gitHubDecisions?.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _attachmentNotice.value = "Saved GitHub decisions could not be loaded. Retry after reopening this conversation."
        }
    }
    fun answerGitHubDecision(id: String, option: Int?, answer: String?) {
        viewModelScope.launch {
            try {
                val workspace = _chatToolConfig.value.github ?: return@launch
                val result = gitHubDecisions?.respond(id, _chatRoom.value.id, dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions.workspaceKey(workspace), option, answer) ?: return@launch
                if (result.accepted) {
                    gitHubDecisionNotifications?.dismiss(id)
                    if (!result.waiting) resumeGitHubDecision(id)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "Your choice could not be saved. Please try again."
            }
        }
    }
    fun resumeGitHubDecision(id: String) {
        val decision = gitHubDecisions?.decisions?.value?.firstOrNull { it.id == id && it.chatId == _chatRoom.value.id && !it.delivered && !it.cancelled } ?: return
        val workspace = _chatToolConfig.value.github ?: return
        if (decision.workspace != dev.chungjungsoo.gptmobile.data.github.GitHubChatDecisions.workspaceKey(workspace) || isGenerationBusy()) return
        val turn = _groupedMessages.value.userMessages.indexOfFirst { it.id == decision.sourceMessageId }
        val profile = enabledPlatformsInChat.indexOf(decision.profileUid)
        if (turn >= 0 && profile >= 0) retryChat(turn, profile) else _attachmentNotice.value = "Your choice is saved. Open the original task and retry it to continue."
    }
    fun stopGitHubDecision(id: String) {
        viewModelScope.launch {
            try {
                gitHubDecisions?.dismiss(id, _chatRoom.value.id)
                gitHubDecisionNotifications?.dismiss(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "The stop decision could not be saved. Please try again."
            }
        }
    }
    fun respondMcpInput(id: String, value: kotlinx.serialization.json.JsonObject?) {
        mcpInteractions?.respond(id, value)
    }
    val pendingToolApprovals get() = toolApprovals?.pending ?: flowOf(emptyList())
    data class FreeToolConsentRequest(
        val profileUid: String,
        val profileName: String,
        val toolId: String,
        val toolName: String,
        val profileSnapshot: dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2? = null,
        val chatId: Int? = null,
        val dataCategory: dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory = dev.chungjungsoo.gptmobile.data.permissions.personalDataCategory(toolId)
    )
    private val _pendingFreeToolConsent = MutableStateFlow<FreeToolConsentRequest?>(null)
    val pendingFreeToolConsent = _pendingFreeToolConsent.asStateFlow()
    fun decideToolApproval(id: String, allow: Boolean) {
        viewModelScope.launch { toolApprovals?.decide(id, allow) }
    }
    fun allowToolInConversation(id: String) {
        viewModelScope.launch {
            try {
                toolApprovals?.allowInConversation(id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "Could not save the scoped permission. Allow once is still available."
            }
        }
    }
    fun alwaysAllowTool(id: String) {
        viewModelScope.launch {
            try {
                toolApprovals?.alwaysAllow(id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "Could not save permission. You can still allow this action once."
            }
        }
    }

    fun alwaysAllowToolProvider(id: String) {
        viewModelScope.launch {
            try {
                toolApprovals?.alwaysAllowProvider(id)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "Could not save provider permission. You can still allow this action once."
            }
        }
    }

    sealed class LoadingState {
        data object Idle : LoadingState()
        data object Loading : LoadingState()
    }

    data class GroupedMessages(
        val userMessages: List<MessageV2> = listOf(),
        val assistantMessages: List<List<MessageV2>> = listOf()
    )

    enum class MessageEditRole {
        USER,
        ASSISTANT
    }

    data class MessageEditSession(
        val message: MessageV2,
        val role: MessageEditRole,
        val turnIndex: Int? = null,
        val platformIndex: Int? = null,
        val attachments: List<ChatAttachmentDraft> = emptyList()
    )

    private val chatRoomId: Int = checkNotNull(savedStateHandle["chatRoomId"])
    private val enabledPlatformString: String = checkNotNull(savedStateHandle["enabledPlatforms"])
    private val requestedConversationMode = ConversationMode.normalize(savedStateHandle["conversationMode"])
    private val initialPlatformUids = enabledPlatformString.split(',').filter(String::isNotBlank)
    private val platformSlotUids = MutableStateFlow(initialPlatformUids)
    val enabledPlatformsInChat: List<String>
        get() = platformSlotUids.value
    private val _activePlatformUids = MutableStateFlow(initialPlatformUids)
    val activePlatformUids = _activePlatformUids.asStateFlow()
    val targetMessageId: Int = savedStateHandle.get<Int>("targetMessageId") ?: -1

    private val currentTimeStamp: Long
        get() = System.currentTimeMillis() / 1000

    private val _chatRoom = MutableStateFlow(
        ChatRoomV2(
            id = -1,
            title = "",
            enabledPlatform = enabledPlatformsInChat,
            conversationMode = requestedConversationMode
        )
    )
    val chatRoom = _chatRoom.asStateFlow()
    val researchSessions = researchSessionStore?.sessions ?: MutableStateFlow<Map<String, dev.chungjungsoo.gptmobile.data.research.ResearchSession>>(emptyMap()).asStateFlow()
    val researchExecutionState = researchSessionStore?.executionState ?: MutableStateFlow<Map<String, dev.chungjungsoo.gptmobile.data.research.ResearchExecutionRecord>>(emptyMap()).asStateFlow()
    private val _interruptedResearchReview = MutableStateFlow<ResearchInterruptedReview?>(null)
    internal val interruptedResearchReview = _interruptedResearchReview.asStateFlow()
    fun stopResearch(runId: String) {
        researchSessionStore?.stop(runId)
    }
    fun loadResearchHistory(chatId: Int) {
        viewModelScope.launch { researchSessionStore?.loadChat(chatId) }
    }

    // Fact text remains in the encrypted vault; persisted timelines contain IDs only.
    val debugMemorySources by lazy {
        combine(
            factVault?.state ?: flowOf(dev.chungjungsoo.gptmobile.data.rag.FactVaultSnapshot()),
            debugMode,
            chatRoom
        ) { _, debug, room ->
            if (!debug || room.id <= 0) {
                emptyMap()
            } else {
                withContext(Dispatchers.IO) {
                    factVault?.visibleFacts(room.id, isLocal = true).orEmpty()
                        .filter { it.supersededBy == null }
                        .associate {
                            it.id to DebugMemorySource(
                                "${it.fact.entity.name} ${it.fact.relation.relationType.lowercase().replace('_', ' ')} ${it.fact.target.name}",
                                it.fact.target.name
                            )
                        }
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())
    }

    private val _isChatTitleDialogOpen = MutableStateFlow(false)
    val isChatTitleDialogOpen = _isChatTitleDialogOpen.asStateFlow()

    private val _messageEditSession = MutableStateFlow<MessageEditSession?>(null)
    val messageEditSession = _messageEditSession.asStateFlow()

    private val _isSelectTextSheetOpen = MutableStateFlow(false)
    val isSelectTextSheetOpen = _isSelectTextSheetOpen.asStateFlow()

    private val _isChatModelDialogOpen = MutableStateFlow(false)
    val isChatModelDialogOpen = _isChatModelDialogOpen.asStateFlow()
    private val _combinedConversationIdentity = MutableStateFlow(
        savedStateHandle.get<String>("combinedConversationIdentity") ?: UUID.randomUUID().toString().also {
            savedStateHandle["combinedConversationIdentity"] = it
        }
    )
    val combinedConversationIdentity = _combinedConversationIdentity.asStateFlow()
    private val _combinedSettingsSaveState = MutableStateFlow(dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState())
    val combinedSettingsSaveState = _combinedSettingsSaveState.asStateFlow()
    private val combinedSettingsJournal by lazy { dev.chungjungsoo.gptmobile.data.repository.CombinedSettingsSaveJournal(settingRepository) }

    private val _isChatToolSheetOpen = MutableStateFlow(false)
    val isChatToolSheetOpen = _isChatToolSheetOpen.asStateFlow()

    private val _chatToolConfig = MutableStateFlow(ChatMcpToolConfig())
    val chatToolConfig = _chatToolConfig.asStateFlow()

    private val _availableChatTools = MutableStateFlow<List<AvailableChatTool>>(emptyList())
    val availableChatTools = _availableChatTools.asStateFlow()

    // Initialized before the init block launches immediate coroutines. The tool loader writes this state.
    private val _academicGateways = MutableStateFlow<Map<String, String>>(emptyMap())
    val academicGateways = _academicGateways.asStateFlow()

    private val _chatPlatformModels = MutableStateFlow<Map<String, String>>(emptyMap())
    val chatPlatformModels = _chatPlatformModels.asStateFlow()

    private val _catalogEntries = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val catalogEntries = _catalogEntries.asStateFlow()
    val downloadedLocalModels: StateFlow<List<DownloadedLocalModelOption>> = combine(
        localModelRepository.observeAll(),
        _catalogEntries
    ) { models, catalog ->
        val names = catalog.associate { it.id to it.displayName }
        models.filter { it.status == LocalModelStatus.READY }.map { model ->
            DownloadedLocalModelOption(
                catalogEntryId = model.catalogEntryId,
                displayName = names[model.catalogEntryId]?.takeIf { it.isNotBlank() } ?: model.catalogEntryId,
                compatibilityIssue = dev.chungjungsoo.gptmobile.data.localmodel.LocalModelCompatibility.unsupportedReason("", model.fileName)
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val researchProfileStatistics = combine(
        researchSessions,
        researchExecutionState,
        (invocationLedger?.usageForChat(chatRoomId) ?: flowOf(emptyList())).sample(250),
        researchDatabase?.research()?.jobs()?.map { rows -> rows.filter { it.chatId == chatRoomId } }?.distinctUntilChanged() ?: flowOf(emptyList())
    ) { sessions, executions, requests, jobs ->
        val byRoot = sessions.values.filter { it.chatId == chatRoomId }.groupBy { it.turnId }
        val linkedIds = byRoot.values.flatten().flatMap { row -> row.snapshot.modelEvents.flatMap { event -> listOfNotNull(event.invocationId) + event.metrics?.invocationIds.orEmpty() } }.toSet() +
            jobs.flatMap { job -> runCatching { kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.research.feature.ExaminationCheckpoint>(job.checkpointJson).invocationIds }.getOrDefault(emptyList()) }
        val correlatedRequests = requests + invocationLedger?.linkedStatistics(linkedIds - requests.map { it.id }.toSet()).orEmpty()
        val acceptedRoots = executions.values.filter { it.accepted.chatId == chatRoomId }.associateBy { it.accepted.rootWorkId }
        (byRoot.keys + acceptedRoots.keys).associateWith { root ->
            val rows = byRoot[root].orEmpty()
            val accepted = acceptedRoots[root]?.accepted ?: researchSessionStore?.acceptedTask(root)
            val participants = accepted?.participantUids?.toSet() ?: requests.filter { it.turnKey == root }.mapNotNull { it.profileUid }.toSet()
            dev.chungjungsoo.gptmobile.data.research.researchProfileStatistics(root, participants, rows, correlatedRequests, jobs)
        }
    }.flowOn(Dispatchers.Default).distinctUntilChanged().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyMap())

    val invocationDiagnostics = (invocationLedger?.diagnosticsForChat(chatRoomId) ?: flowOf(emptyList()))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val debugMode: StateFlow<Boolean> = settingRepository.observeDebugMode()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    // All platforms configured in app (including disabled)
    private val _platformsInApp = MutableStateFlow(listOf<PlatformV2>())
    val platformsInApp = _platformsInApp.asStateFlow()

    // Enabled platforms list in app
    private val _enabledPlatformsInApp = MutableStateFlow(listOf<PlatformV2>())
    val enabledPlatformsInApp = _enabledPlatformsInApp.asStateFlow()

    // User input used for the chat composer
    val question = TextFieldState()

    // Selected attachment drafts for current message
    private val _selectedAttachments = MutableStateFlow(listOf<ChatAttachmentDraft>())
    val selectedAttachments = _selectedAttachments.asStateFlow()

    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice = _attachmentNotice.asStateFlow()
    private val _removedCombinedProfile = MutableStateFlow<Pair<String, String>?>(null)
    val removedCombinedProfile = _removedCombinedProfile.asStateFlow()
    private val _freeProviderNotice = MutableStateFlow<String?>(null)
    val freeProviderNotice = _freeProviderNotice.asStateFlow()
    fun dismissFreeProviderNotice() {
        _freeProviderNotice.value = null
    }

    private val _runNoticesById = MutableStateFlow<Map<String, List<ChatRunNotice>>>(emptyMap())
    val runNoticesById = _runNoticesById.asStateFlow()
    val activeAgentRuns = agentRunCoordinator.activeRuns

    private val _needsLocalNetworkAccess = MutableStateFlow(false)
    val needsLocalNetworkAccess = _needsLocalNetworkAccess.asStateFlow()

    // Chat messages currently in the chat room
    private val _groupedMessages = MutableStateFlow(GroupedMessages())
    val groupedMessages = _groupedMessages.asStateFlow()

    private val _toolEventsByRun = MutableStateFlow<Map<String, List<ToolEvent>>>(emptyMap())
    val toolEventsByRun = _toolEventsByRun.asStateFlow()

    private val _agentRunsById = MutableStateFlow<Map<String, AgentRun>>(emptyMap())
    val agentRunsById = _agentRunsById.asStateFlow()
    private val providerWaitDeadlinesByRun = MutableStateFlow<Map<String, ProviderWaitDeadline>>(emptyMap())

    // Each chat states for assistant chat messages
    // Index of the currently shown message's platform - default is 0 (first platform)
    private val _indexStates = MutableStateFlow(listOf<Int>())
    val indexStates = _indexStates.asStateFlow()

    // Loading states for each platform
    private val _loadingStates = MutableStateFlow(List<LoadingState>(enabledPlatformsInChat.size) { LoadingState.Idle })
    val loadingStates = _loadingStates.asStateFlow()

    // Used for text data to show in SelectText Bottom Sheet
    private val _selectedText = MutableStateFlow("")
    val selectedText = _selectedText.asStateFlow()

    // State for the message loading state (From the database)
    private val _isLoaded = MutableStateFlow(false)
    val isLoaded = _isLoaded.asStateFlow()

    val featureSettings: StateFlow<dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings> =
        settingRepository.observeFeatureSettings()
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings()
            )

    val nextMessageProfileUid = savedStateHandle.getStateFlow<String?>("nextMessageProfileUid", null)

    fun selectNextMessageProfile(uid: String?) {
        if (uid != null && (_chatRoom.value.conversationMode == ConversationMode.COMBINED || uid !in _activePlatformUids.value || uid in _disabledPlatformUids.value || _platformsInApp.value.none { it.uid == uid && it.enabled })) return
        savedStateHandle["nextMessageProfileUid"] = uid
    }

    private data class QueuedPrompt(
        val text: String,
        val attachments: List<ChatAttachmentDraft>,
        val profileUid: String? = null,
        val tools: ChatMcpToolConfig
    )
    private val queuedPrompts = ArrayDeque<QueuedPrompt>()
    private val _pendingPrompts = MutableStateFlow<List<dev.chungjungsoo.gptmobile.data.queue.PendingPrompt>>(emptyList())
    val followUpProgress = dev.chungjungsoo.gptmobile.data.queue.FollowUpProgressStore.state
    val pendingPrompts = kotlinx.coroutines.flow.combine(_pendingPrompts, followUpProgress) { prompts, progress ->
        val merging = progress.values.filter { it.prompt.chatId == _chatRoom.value.id && it.phase == dev.chungjungsoo.gptmobile.data.queue.FollowUpPhase.MERGING }.map { it.prompt }
        (prompts + merging).distinctBy { it.id }.sortedBy { it.position }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())
    private var queueSubmissionPending = false
    private val _queuedPromptCount = MutableStateFlow(0)
    val queuedPromptCount = _queuedPromptCount.asStateFlow()
    private val _disabledPlatformUids = MutableStateFlow<Set<String>>(emptySet())
    val disabledPlatformUids = _disabledPlatformUids.asStateFlow()
    private var lastAutoTitleUserTurnCount = 0
    private var autoTitleGenerationInFlight = false
    private val combinedSynthesisMessages = mutableSetOf<Int>()
    private val combinedSynthesisAttempts = CombinedSynthesisGate()
    private var pendingRunDispatches = 0
    private var pendingMessageGrouping = 0
    private val platformMembershipMutex = Mutex()

    // Keep instance state above startup: Main.immediate can run collectors and
    // restoration inline, before the constructor returns. Later initializers can
    // otherwise expose null backing fields or reset flags already set by recovery.
    private var unlockingAllTools = false

    val pendingReplies = generationCompletionStore.items

    private val conversationDelegationMutex = Mutex()

    private val _combinedFailures = MutableStateFlow<List<CombinedProfileFailure>>(emptyList())
    private val combinedFailureCache = CombinedProfileFailureCache()
    val combinedFailures = _combinedFailures.asStateFlow()
    private val pendingCombinedRetries = mutableSetOf<String>()
    private val pendingResearchRecoveries = mutableSetOf<String>()
    private var reviewingCombinedPrimaryTurn: Int? = null
    private var reviewingCombinedFailureTurn: Int? = null

    private val _queuedJobInspection = MutableStateFlow<String?>(null)
    val queuedJobInspection = _queuedJobInspection.asStateFlow()

    private val _branchNavigation = MutableStateFlow<Int?>(null)
    val branchNavigation = _branchNavigation.asStateFlow()
    private var branching = false
    val regenerateBranch = savedStateHandle.get<Boolean>("regenerate") ?: false
    private var branchResumed = false

    private var leavingConversation = false
    private var exitOwnsDiagnosticPrivacy = false

    private var composerRestored = false
    private var modelsRestored = false
    private val draftMutex = Mutex()

    init {
        viewModelScope.launch {
            combinedCollectionDecisions?.load()
            combinedCollectionDecisions?.decisions?.collect { refreshCombinedFailures() }
        }
        fetchChatRoom()
        viewModelScope.launch {
            combine(
                _chatRoom.distinctUntilChangedBy { listOf(it.id, it.isTemporary, it.parentChatId, it.branchMessageId, it.activePlatform, it.conversationMode) },
                _enabledPlatformsInApp,
                nextMessageProfileUid
            ) { room, profiles, nextUid -> Triple(room, profiles, nextUid) }.collectLatest { (room, profiles, nextUid) ->
                val available = profiles.filter { it.uid in room.activePlatform && it.enabled }.map { it.uid }
                if (!leavingConversation) {
                    dev.chungjungsoo.gptmobile.data.readiness.ReadinessHints.selectProfiles(
                        (listOfNotNull(nextUid).filter { it in available } + available).distinct()
                    )
                }
                memoryPreview?.clear()
            }
        }
        durablePromptQueue?.let { queue ->
            viewModelScope.launch {
                _chatRoom.collectLatest { room ->
                    if (room.id > 0) {
                        _disabledPlatformUids.value = queue.pausedProfiles(room.id)
                        queue.observe(room.id).collect { entries ->
                            _pendingPrompts.value = entries
                            _queuedPromptCount.value = entries.size
                        }
                    }
                }
            }
            queue.start()
        }
        viewModelScope.launch { fetchMessages() }
        fetchEnabledPlatformsInApp()
        observePersistedMessages()
        observeAgentRuns()
        observeProviderWaitDeadlines()
        observeToolEvents()
        observeAgentNotices()
        loadAvailableChatTools()
        viewModelScope.launch {
            _catalogEntries.value = modelCatalogRepository.getCachedVisibleEntries()
        }
    }

    fun addMessage(userMessage: MessageV2) {
        _groupedMessages.update {
            it.copy(
                userMessages = it.userMessages + listOf(userMessage),
                assistantMessages = it.assistantMessages + listOf(
                    enabledPlatformsInChat.map { p -> MessageV2(chatId = chatRoomId, content = "", platformType = p) }
                )
            )
        }
        _indexStates.update { it + listOf(0) }
    }

    fun askQuestion() {
        val questionText = question.text.toString()
        val hasReadyAttachments = _selectedAttachments.value.any { it.status == ChatAttachmentDraft.Status.Ready }
        val hasPreparingAttachments = _selectedAttachments.value.any { it.status == ChatAttachmentDraft.Status.Preparing }
        if (questionText.isBlank() && !hasReadyAttachments && !hasPreparingAttachments) return
        if (_selectedAttachments.value.any { it.status == ChatAttachmentDraft.Status.Failed }) {
            _attachmentNotice.update { "Remove failed attachments before sending." }
            return
        }

        if (hasPreparingAttachments) {
            // Do not accept a half-prepared submission or clear a newer composer draft.
            _attachmentNotice.value = "Preparing attachments. Your draft is kept until they are ready."
            return
        }

        submitOrQueueQuestion(questionText, _selectedAttachments.value)
    }

    private fun rejectUnavailableFreeProfile(profile: PlatformV2): Boolean {
        val outcome = dev.chungjungsoo.gptmobile.data.network.FreeProviderPreflight.check(profile) ?: return false
        dev.chungjungsoo.gptmobile.data.free.FreeProviderDiagnostics.recordPreflight(profile, outcome, _chatRoom.value.id, !_chatRoom.value.isTemporary)
        _freeProviderNotice.value = "${profile.name}: ${outcome.message} Your draft and conversation are kept."
        return true
    }

    private fun submitOrQueueQuestion(questionText: String, attachments: List<ChatAttachmentDraft>) {
        if (_combinedSettingsSaveState.value.isCommitting) {
            _attachmentNotice.value = "Saving the Combined setup. Your message draft is kept."
            return
        }
        val oneMessageUid = nextMessageProfileUid.value
        if (oneMessageUid != null && (oneMessageUid !in _activePlatformUids.value || oneMessageUid in _disabledPlatformUids.value || _platformsInApp.value.none { it.uid == oneMessageUid && it.enabled })) {
            _attachmentNotice.value = "The one-message profile is unavailable. Choose another profile; your draft is kept."
            return
        }
        val acceptedTools = try {
            captureCombinedTools(questionText)
        } catch (error: IllegalArgumentException) {
            _attachmentNotice.value = error.message ?: "Could not accept the Combined setup. Your draft is kept."
            return
        }
        val acceptedProfiles = acceptedTools.combined?.requested?.participantUids ?: oneMessageUid?.let(::listOf) ?: _activePlatformUids.value
        val acceptedModels = acceptedTools.combined?.requested?.requestedModels ?: _chatPlatformModels.value
        val unavailable = if (acceptedTools.combined != null) emptyList() else _platformsInApp.value.filter { it.uid in acceptedProfiles && (acceptedTools.combined != null || it.uid !in _disabledPlatformUids.value) }.map { it.copy(model = acceptedModels[it.uid] ?: it.model) }.filter { rejectUnavailableFreeProfile(it) }
        if (unavailable.isNotEmpty()) return
        if (isGenerationBusy() || !hasUnpausedProfile() || _pendingPrompts.value.isNotEmpty()) {
            if (durablePromptQueue != null) {
                if (queueSubmissionPending) return
                val roomId = _chatRoom.value.id
                if (roomId == 0) {
                    _attachmentNotice.value = "The conversation is starting. Your draft is kept; send it again when ready."
                    return
                }
                queueSubmissionPending = true
                val snapshot = attachments.toList()
                viewModelScope.launch {
                    try {
                        durablePromptQueue.enqueue(
                            roomId,
                            questionText,
                            dev.chungjungsoo.gptmobile.data.queue.PendingPromptPayload(
                                snapshot.mapNotNull { it.attachment },
                                acceptedProfiles,
                                acceptedModels,
                                acceptedTools
                            )
                        )
                        if (nextMessageProfileUid.value == oneMessageUid) savedStateHandle["nextMessageProfileUid"] = null
                        if (question.text.toString() == questionText) question.clearText()
                        _selectedAttachments.update { current -> current.filterNot { it in snapshot } }
                        _attachmentNotice.value = "Queued. Use the buttons beside the message to edit or stop it."
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        _attachmentNotice.value = "Could not save the queued prompt. Your draft is kept."
                    } finally {
                        queueSubmissionPending = false
                    }
                }
                return
            }
            queuedPrompts.addLast(QueuedPrompt(questionText, attachments.toList(), oneMessageUid, acceptedTools))
            savedStateHandle["nextMessageProfileUid"] = null
            _queuedPromptCount.value = queuedPrompts.size
            question.clearText()
            _selectedAttachments.value = emptyList()

            return
        }
        savedStateHandle["nextMessageProfileUid"] = null
        sendQuestion(questionText, attachments, profileUid = oneMessageUid, acceptedTools = acceptedTools)
    }

    private fun captureCombinedTools(text: String): ChatMcpToolConfig {
        val tools = _chatToolConfig.value.let { current ->
            if (dev.chungjungsoo.gptmobile.data.research.isResearchConversationOnly(text)) current.copy(research = current.research?.copy(researchToggle = false)) else current
        }
        val room = _chatRoom.value
        if (room.conversationMode != ConversationMode.COMBINED) return tools.copy(combined = null)
        val recovery = dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery(_groupedMessages.value.userMessages + MessageV2(chatId = room.id, content = text, platformType = null))
        val available = _activePlatformUids.value.filterNot { it in _disabledPlatformUids.value }
        val selected = if (recovery?.primaryOnly == true) {
            val primary = room.combinedPrimaryUid ?: room.activePlatform.firstOrNull()
            require(primary != null && primary in available) { "The designated primary is unavailable. Enable it or select an approved primary before continuing." }
            listOf(primary)
        } else {
            available.ifEmpty { _activePlatformUids.value }
        }
        val profiles = _platformsInApp.value.filter { it.enabled }.associateBy { it.uid }
        require(selected.isNotEmpty() && selected.all { it in profiles }) { "Select an available profile before sending." }
        selected.forEach { uid ->
            val profile = resolvePlatformModel(profiles.getValue(uid))
            val reason = dev.chungjungsoo.gptmobile.data.agent.CombinedProviderEligibility.unavailableReason(profile, recovery?.originalRequest ?: text)
            require(reason == null) { "${profile.name} cannot contribute to this Combined request: $reason Review the selected profiles; your draft is kept." }
        }
        val preferences = featureSettings.value.conversationCombined[room.id]
            ?: dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings()
        room.combinedPrimaryUid?.takeIf { it in selected }?.let { uid ->
            val primary = resolvePlatformModel(profiles.getValue(uid))
            val reason = dev.chungjungsoo.gptmobile.data.agent.CombinedProviderEligibility.unavailableReason(
                primary,
                recovery?.originalRequest ?: text,
                writer = true,
                appendContinuations = dev.chungjungsoo.gptmobile.BuildConfig.COMBINED_APPEND_CONTINUATIONS && preferences.continueLongAnswers
            )
            require(reason == null) { "${primary.name} cannot write this Combined answer: $reason Review the primary selection; your draft is kept." }
        }
        val requested = dev.chungjungsoo.gptmobile.data.agent.CombinedPolicyResolver.accept(
            settings = preferences,
            singlePass = true,
            participantUids = selected,
            requestedModels = selected.associateWith { _chatPlatformModels.value[it] ?: profiles.getValue(it).model },
            userPrompt = recovery?.originalRequest ?: text,
            preferredOwnerUid = room.combinedPrimaryUid,
            requestedTemperatures = selected.associateWith { preferences.creativity ?: resolvePlatformModel(profiles.getValue(it)).temperature },
            acceptedResearch = (tools.research ?: featureSettings.value.conversationResearch[room.id])?.normalized()
                ?.takeIf { it.researchToggle && it.depth > 0 && !dev.chungjungsoo.gptmobile.data.research.isResearchConversationOnly(recovery?.originalRequest ?: text) }?.let { research ->
                    dev.chungjungsoo.gptmobile.data.agent.CombinedResearchRequirement(
                        depth = research.depth,
                        sourceGoal = selected.maxOf { uid ->
                            dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(featureSettings.value, uid, research).sourceGoal.toLong()
                        }.coerceAtLeast(1),
                        contentScope = research.scope.name,
                        gatewayConnectionUid = research.gatewayConnectionUid,
                        allowNativeFallback = research.allowNativeFallback,
                        examinationRequested = featureSettings.value.researchFeature.enabled && featureSettings.value.researchFeature.autoExamination != dev.chungjungsoo.gptmobile.data.research.feature.AutoExamination.OFF && research.academicEligibleByUi,
                        independentReviewRequested = selected.any { featureSettings.value.delegationFor(it).reviewerEnabled }
                    )
                }
        )
        return tools.copy(
            combined = dev.chungjungsoo.gptmobile.data.agent.CombinedExecutionContext(
                requested = requested,
                rootBudgetId = UUID.randomUUID().toString(),
                chatId = room.id,
                persistBudget = !room.isTemporary
            )
        )
    }

    private fun isGenerationBusy(): Boolean =
        pendingRunDispatches > 0 ||
            pendingMessageGrouping > 0 ||
            _loadingStates.value.any { it != LoadingState.Idle } ||
            agentRunCoordinator.activeRuns.value.any { (runId, run) -> run.chatId == _chatRoom.value.id && _agentRunsById.value[runId]?.status != AgentRunStatus.WAITING_REMOTE_JOB }

    private fun drainPromptQueueIfIdle() {
        if (durablePromptQueue != null) return
        if (isGenerationBusy() || queuedPrompts.isEmpty() || !hasUnpausedProfile()) return
        val selected = queuedPrompts.first().profileUid
        if (selected != null && (selected !in _activePlatformUids.value || selected in _disabledPlatformUids.value)) return
        val next = queuedPrompts.removeFirst()
        _queuedPromptCount.value = queuedPrompts.size
        sendQuestion(next.text, next.attachments, clearComposer = false, profileUid = next.profileUid, acceptedTools = next.tools)
    }

    fun removeQueuedPrompt(id: String) {
        viewModelScope.launch { durablePromptQueue?.remove(id) }
    }
    fun editQueuedPrompt(id: String, text: String, hasAttachments: Boolean) {
        viewModelScope.launch {
            try {
                durablePromptQueue?.edit(id, text, hasAttachments)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _attachmentNotice.value = error.message ?: "Could not update the queued message."
            }
        }
    }
    fun pauseQueuedPrompt(id: String, paused: Boolean) {
        viewModelScope.launch { durablePromptQueue?.pause(id, paused) }
    }
    fun moveQueuedPrompt(id: String, otherId: String) {
        viewModelScope.launch { durablePromptQueue?.move(id, otherId) }
    }

    private fun hasUnpausedProfile(): Boolean = _activePlatformUids.value.any { it !in _disabledPlatformUids.value }

    fun togglePlatformDisabled(platformUid: String) {
        if (platformUid !in _activePlatformUids.value) return
        _disabledPlatformUids.update { disabled ->
            if (platformUid in disabled) {
                disabled - platformUid
            } else {
                disabled + platformUid
            }
        }
        durablePromptQueue?.setPausedProfiles(_chatRoom.value.id, _disabledPlatformUids.value)
        advancePendingGeneration()
    }

    fun removeCombinedFailureProfile(platformUid: String) = updatePlatformMembership(platformUid, false, offerUndo = true)

    fun finishRemovedProfileNotice(undo: Boolean) {
        val removed = _removedCombinedProfile.value ?: return
        _removedCombinedProfile.value = null
        if (undo) setPlatformMembership(removed.first, true)
    }

    fun setPlatformMembership(platformUid: String, active: Boolean) = updatePlatformMembership(platformUid, active, offerUndo = false)

    private fun updatePlatformMembership(platformUid: String, active: Boolean, offerUndo: Boolean) {
        // Reserve the change before launching: a queued turn must not dispatch with
        // membership that has not yet been persisted or given a stable response slot.
        pendingRunDispatches += 1
        viewModelScope.launch {
            try {
                platformMembershipMutex.withLock {
                    val current = _activePlatformUids.value
                    val platform = _platformsInApp.value.firstOrNull { it.uid == platformUid }
                    if (active && platform?.enabled != true) return@withLock
                    val updated = if (active) (current + platformUid).distinct() else current - platformUid
                    if (updated.isEmpty()) {
                        _attachmentNotice.value = "At least one AI profile must remain active."
                        return@withLock
                    }
                    if (updated == current) return@withLock

                    val updatedRoom = agentRunCoordinator.withChatGate(_chatRoom.value.id) {
                        chatRepository.updateChatPlatforms(_chatRoom.value, updated)
                    }
                    _chatRoom.value = updatedRoom
                    applyChatPlatformState(updatedRoom)
                    if (!active && offerUndo) _removedCombinedProfile.value = platformUid to (platform?.name ?: "Profile")
                    if (!active && updatedRoom.conversationMode == ConversationMode.COMBINED) {
                        agentRunCoordinator.removeCombinedProfile(updatedRoom.id, platformUid)
                        _groupedMessages.value.userMessages.forEach { user ->
                            acceptedCombinedTurn(user.id)?.let { accepted -> agentRunCoordinator.resumeCombinedCollection(updatedRoom.id, user.id, accepted.context.rootBudgetId) }
                        }
                    }
                    if (active && platform != null && platformUid !in _chatPlatformModels.value) {
                        _chatPlatformModels.update { it + (platformUid to platform.model) }
                        if (updatedRoom.id > 0) {
                            chatRepository.saveChatPlatformModels(updatedRoom.id, _chatPlatformModels.value)
                        }
                    }
                    updateLocalNetworkRequirement(_platformsInApp.value)
                    loadAvailableChatTools()
                }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                _attachmentNotice.value = "Could not update AI profiles for this conversation."
            } finally {
                pendingRunDispatches -= 1
                advancePendingGeneration()
            }
        }
    }

    fun sendContinueResponse() {
        submitOrQueueQuestion("continue", emptyList())
    }

    fun sendPromptResponse(promptText: String) {
        ChatResponseActionParser.answerRequest(promptText)?.let { request ->
            submitOrQueueQuestion(request, emptyList())
        }
    }

    fun cancelActiveRuns() {
        _chatRoom.value.id.takeIf { it > 0 }?.let(agentRunCoordinator::cancelChat)
    }

    fun stopResearchAttempt(rootWorkId: String) {
        val record = researchExecutionState.value[rootWorkId]?.takeIf { it.accepted.chatId == _chatRoom.value.id && !it.attempt.state.terminal } ?: return
        viewModelScope.launch {
            researchSessionStore?.updateExecution(rootWorkId, record.attempt, dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.CANCELLED, !_chatRoom.value.isTemporary, reason = "USER_STOP")
            agentRunCoordinator.cancelChat(record.accepted.chatId)
        }
    }

    fun retryResearch(rootWorkId: String) {
        val record = researchExecutionState.value[rootWorkId]?.takeIf { it.accepted.chatId == _chatRoom.value.id } ?: return
        if (record.attempt.nextAction == dev.chungjungsoo.gptmobile.data.research.ResearchNextAction.RECONCILE_REQUESTS) return
        resumeResearchAttempt(record)
    }

    fun reviewInterruptedResearch(rootWorkId: String) {
        val record = researchExecutionState.value[rootWorkId]?.takeIf { it.accepted.chatId == _chatRoom.value.id } ?: return
        if (!record.attempt.state.terminal || record.attempt.nextAction != dev.chungjungsoo.gptmobile.data.research.ResearchNextAction.RECONCILE_REQUESTS) return
        if (record.attempt.attemptId in pendingResearchRecoveries) return
        _interruptedResearchReview.value = ResearchInterruptedReview.capture(
            record,
            "reviewed-research-attempt:${record.attempt.attemptId}:${java.util.UUID.randomUUID()}",
            _agentRunsById.value,
            _platformsInApp.value.associate { it.uid to it.name },
            _groupedMessages.value.assistantMessages.flatten()
        )
    }

    fun dismissInterruptedResearchReview() {
        _interruptedResearchReview.value = null
    }

    fun authorizeReviewedResearchAttempt(actionId: String) {
        val review = _interruptedResearchReview.value?.takeIf { it.actionId == actionId && it.execution.accepted.chatId == _chatRoom.value.id } ?: return
        val current = researchExecutionState.value[review.execution.accepted.rootWorkId]
        if (current?.attempt != review.execution.attempt) {
            _interruptedResearchReview.compareAndSet(review, null)
            _attachmentNotice.value = "This research attempt changed. Review its current requests before starting another attempt."
            return
        }
        if (!_interruptedResearchReview.compareAndSet(review, null)) return
        resumeResearchAttempt(review.execution, review)
    }

    private fun resumeResearchAttempt(record: dev.chungjungsoo.gptmobile.data.research.ResearchExecutionRecord, reviewed: ResearchInterruptedReview? = null) {
        val needsReview = record.attempt.nextAction == dev.chungjungsoo.gptmobile.data.research.ResearchNextAction.RECONCILE_REQUESTS
        if (needsReview && reviewed?.execution != record) return
        if (!record.attempt.state.terminal && record.attempt.state != dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.BLOCKED_CONFIGURATION) return
        val turn = _groupedMessages.value.userMessages.indexOfFirst { it.id == record.accepted.originatingUserMessageId }
        if (turn < 0 || !pendingResearchRecoveries.add(record.attempt.attemptId)) return
        viewModelScope.launch {
            try {
                val responses = _groupedMessages.value.assistantMessages.getOrNull(turn).orEmpty()
                val deliveryRetry = record.attempt.nextAction == dev.chungjungsoo.gptmobile.data.research.ResearchNextAction.RETRY_DELIVERY || (needsReview && responses.any { it.isCombinedSynthesis() })
                val targets = responses.indices.filter { index ->
                    val response = responses[index]
                    response.platformType in record.accepted.participantUids && !response.isCombinedSynthesis() && response.currentRunId?.let { _agentRunsById.value[it]?.status } in setOf(AgentRunStatus.FAILED, AgentRunStatus.INTERRUPTED, AgentRunStatus.CANCELED)
                }
                if (!deliveryRetry && targets.isEmpty() && record.attempt.state == dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.BLOCKED_CONFIGURATION) {
                    _attachmentNotice.value = "Review the selected profiles, then regenerate this research request. Saved evidence is retained."
                    openChatModelDialog()
                    return@launch
                }
                val resumed = researchSessionStore?.resumeExecution(
                    record.accepted.rootWorkId,
                    reviewed?.actionId ?: "user-research-retry:${record.attempt.attemptId}",
                    !_chatRoom.value.isTemporary,
                    reconciled = needsReview && reviewed != null,
                    expectedAttempt = record.attempt
                ) ?: return@launch
                if (resumed.attempt.generation != record.attempt.generation + 1 || resumed.attempt.state.terminal) return@launch
                when {
                    deliveryRetry && responses.any { it.isCombinedSynthesis() } -> retryCombinedMerge(turn)
                    deliveryRetry -> combineAvailableResults(record.accepted.originatingUserMessageId)
                    targets.isEmpty() || record.attempt.state == dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.CANCELLED -> regenerateWithSelectedPrimary(turn)
                    else -> targets.forEach { retryChat(turn, it, dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_FAILED_STAGE) }
                }
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _attachmentNotice.value = failure.message ?: "Research could not resume. Saved evidence is retained."
            } finally {
                pendingResearchRecoveries.remove(record.attempt.attemptId)
            }
        }
    }

    fun refreshLocalNetworkRequirement() {
        fetchEnabledPlatformsInApp()
    }

    override fun onCleared() {
        AttachmentPayloadCache.clear()
        if (!exitOwnsDiagnosticPrivacy) dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.setPrivateSession(diagnosticPrivacyOwner, false)
        super.onCleared()
    }

    fun closeChatTitleDialog() = _isChatTitleDialogOpen.update { false }

    fun discardMessageEditDialog() {
        _messageEditSession.value?.attachments?.forEach { attachment ->
            if (attachment.cleanupOnDiscard) {
                attachment.preparedFilePath?.let { AttachmentPayloadCache.remove(it) }
                deleteDraftFiles(attachment)
            }
        }
        _messageEditSession.update { null }
    }

    fun finishMessageEditDialog() {
        _messageEditSession.update { null }
    }

    fun closeSelectTextSheet() {
        _isSelectTextSheetOpen.update { false }
        _selectedText.update { "" }
    }

    fun closeChatModelDialog() = _isChatModelDialogOpen.update { false }

    fun openChatTitleDialog() = _isChatTitleDialogOpen.update { true }
    fun openChatModelDialog() {
        if (!_combinedSettingsSaveState.value.isCommitting) {
            _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState()
        }
        loadAvailableChatTools()
        _isChatModelDialogOpen.update { true }
    }

    suspend fun loadProfileModels(uid: String): List<dev.chungjungsoo.gptmobile.data.repository.ProfileModelOption> {
        val profile = settingRepository.fetchPlatformV2s().first { it.uid == uid }
        return dev.chungjungsoo.gptmobile.data.repository.ProfileModelCatalog().load(profile)
    }

    fun setDeviceLocationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            if (enabled) {
                val features = settingRepository.getFeatureSettings()
                val profiles = settingRepository.fetchPlatformV2s().filter { it.uid in _activePlatformUids.value }
                if (!features.deviceLocationTool || profiles.all { it.disableAllTools || it.disableLocalTools }) {
                    _attachmentNotice.value = "Enable device location in Advanced Settings and allow local tools for an AI profile."
                    return@launch
                }
                profiles.filterNot { it.disableAllTools || it.disableLocalTools }.forEach {
                    toolConnectionRepository.setBuiltInToolBinding(it.uid, dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool.DEVICE_LOCATION, true)
                }
            }
            setChatToolsEnabled(listOf(dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool.DEVICE_LOCATION), enabled)
            loadAvailableChatTools()
        }
    }

    fun openChatToolSheet() = _isChatToolSheetOpen.update { true }
    fun closeChatToolSheet() = _isChatToolSheetOpen.update { false }

    fun toggleChatTool(toolId: String) {
        val config = _chatToolConfig.value
        if (config.isToolEnabled(toolId)) {
            _chatToolConfig.update { it.withToolDisabled(toolId) }
            return
        }

        val available = _availableChatTools.value.firstOrNull { it.id == toolId }
        if (!dev.chungjungsoo.gptmobile.data.permissions.FreeToolDataPolicy.requiresPersonalPermission(toolId)) {
            _chatToolConfig.update { it.withToolEnabled(toolId) }
            return
        }

        viewModelScope.launch {
            val freeProfile = settingRepository.fetchPlatformV2s()
                .map(::resolvePlatformModel)
                .firstOrNull {
                    it.uid in _activePlatformUids.value &&
                        (it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.FREE || dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.forProfile(it) != null) &&
                        !it.disableAllTools &&
                        !freeModelToolConsentStore.isGranted(it, toolId, _chatRoom.value.id)
                }
            if (freeProfile == null) {
                _chatToolConfig.update { it.withToolEnabled(toolId) }
            } else if (dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.forProfile(freeProfile)?.personalDataAllowed != true) {
                _attachmentNotice.value = "Personal tools are unavailable for this provider route. Public web tools remain available."
            } else {
                _pendingFreeToolConsent.value = FreeToolConsentRequest(
                    profileUid = freeProfile.uid,
                    profileName = freeProfile.name,
                    toolId = toolId,
                    toolName = available?.name ?: toolId,
                    profileSnapshot = freeProfile,
                    chatId = _chatRoom.value.id
                )
            }
        }
    }

    fun confirmFreeToolConsent(rememberForProfile: Boolean = false) = confirmFreeToolConsent(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataGrantOptions(rememberForProfile = rememberForProfile))

    fun confirmFreeToolConsent(options: dev.chungjungsoo.gptmobile.data.permissions.PersonalDataGrantOptions) {
        val request = _pendingFreeToolConsent.value ?: return
        viewModelScope.launch {
            val captured = request.profileSnapshot ?: return@launch
            val current = settingRepository.fetchPlatformV2s().singleOrNull { it.uid == request.profileUid }?.let(::resolvePlatformModel)
            if (_pendingFreeToolConsent.value != request ||
                current == null ||
                !current.enabled ||
                current.disableAllTools ||
                current.uid !in _activePlatformUids.value ||
                request.chatId != _chatRoom.value.id ||
                !dev.chungjungsoo.gptmobile.data.agent.sameRecoveryDestination(captured, current) ||
                dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.scope(current) != dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.scope(captured)
            ) {
                _pendingFreeToolConsent.value = null
                _attachmentNotice.value = "The profile or conversation changed. Review the tool permission again."
                return@launch
            }
            runCatching {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    check(_pendingFreeToolConsent.value == request) { "The sharing request was dismissed." }
                    val category = dev.chungjungsoo.gptmobile.data.permissions.personalDataCategory(request.toolId)
                    freeModelToolConsentStore.grant(
                        current,
                        request.toolId,
                        chatId = request.chatId.takeUnless { options.rememberForProfile },
                        expiresAt = System.currentTimeMillis() + (if (options.rememberForProfile) 30L else 1L) * 24 * 60 * 60_000L,
                        categories = if (category == dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory.PRECISE_LOCATION && !options.preciseLocation) setOf(dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory.COARSE_LOCATION) else dev.chungjungsoo.gptmobile.data.permissions.personalDataCategories(request.toolId),
                        healthRangeDays = options.healthRangeDays.takeIf { category == dev.chungjungsoo.gptmobile.data.permissions.PersonalDataCategory.HEALTH }
                    )
                }
            }.onSuccess {
                if (_pendingFreeToolConsent.value != request) return@onSuccess
                _pendingFreeToolConsent.value = null
                if (unlockingAllTools) enableAllChatTools() else toggleChatTool(request.toolId)
            }.onFailure {
                _attachmentNotice.value = "Could not save this tool permission."
            }
        }
    }

    fun dismissFreeToolConsent() {
        unlockingAllTools = false
        _pendingFreeToolConsent.value = null
    }

    fun acknowledgeDisplayedReplies(displayed: List<MessageV2>, captured: List<dev.chungjungsoo.gptmobile.data.chat.CompletedGeneration>) {
        val byId = displayed.associateBy { it.id }
        viewModelScope.launch {
            captured.filter { it.chatId == chatRoom.value.id }.forEach { item ->
                byId[item.assistantMessageId]?.let { generationCompletionStore.acknowledge(item, chatRepository.resolveCombinedMessage(it).content) }
            }
        }
    }

    fun setConversationResearch(value: dev.chungjungsoo.gptmobile.data.model.MultiSearchResearchSelection) {
        _chatToolConfig.update { it.copy(research = value.normalized()) }
        val id = _chatRoom.value.id
        if (!value.researchToggle || value.depth <= 0) {
            researchSessionStore?.sessions?.value?.values?.filter { it.chatId == id && !it.snapshot.complete }?.forEach { researchSessionStore?.stop(it.runId) }
        }
        if (id <= 0) return
        viewModelScope.launch {
            conversationDelegationMutex.withLock {
                settingRepository.mutateFeatureSettings { latest -> latest.copy(conversationResearch = latest.conversationResearch + (id to value.normalized())) }
            }
        }
    }

    fun grantAcademicResearch(profileUid: String, includePapers: Boolean = true) {
        viewModelScope.launch {
            conversationDelegationMutex.withLock {
                settingRepository.mutateFeatureSettings { latest ->
                    val granted = latest.withToolPluginEnabled(dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH, true)
                        .withProfileToolPluginEnabled(profileUid, dev.chungjungsoo.gptmobile.data.model.ToolPluginId.DEEP_RESEARCH, true)
                    val behavior = granted.profileBehavior[profileUid] ?: dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings()
                    granted.copy(profileBehavior = granted.profileBehavior + (profileUid to behavior.copy(researchEnabledOverride = true, academicSourcesAllowed = if (includePapers) true else behavior.academicSourcesAllowed)))
                }
            }
        }
    }

    fun saveResearchDepthDefault(profileUid: String, depth: Int) {
        viewModelScope.launch {
            conversationDelegationMutex.withLock {
                settingRepository.mutateFeatureSettings { latest ->
                    val behavior = latest.profileBehavior[profileUid] ?: dev.chungjungsoo.gptmobile.data.model.ProfileBehaviorSettings()
                    latest.copy(profileBehavior = latest.profileBehavior + (profileUid to behavior.copy(researchDepthOverride = depth.coerceIn(0, 100))))
                }
            }
        }
    }

    fun setConversationReasoning(enabled: Boolean) {
        _chatToolConfig.update { it.copy(reasoning = enabled) }
        val chatId = _chatRoom.value.id
        if (chatId > 0) {
            viewModelScope.launch {
                try {
                    conversationDelegationMutex.withLock {
                        settingRepository.mutateFeatureSettings { latest -> latest.copy(conversationReasoning = latest.conversationReasoning + (chatId to enabled)) }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    _attachmentNotice.value = "Could not save reasoning for this conversation. Please try again."
                }
            }
        }
    }

    fun setLocalAssist(enabled: Boolean) {
        viewModelScope.launch {
            try {
                conversationDelegationMutex.withLock {
                    settingRepository.mutateFeatureSettings { latest -> latest.copy(localAssist = enabled) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "Could not save Local Assist. Please try again."
            }
        }
    }

    fun setConversationDelegation(value: ConversationDelegationSettings?) {
        _chatToolConfig.update { it.copy(delegation = value) }
        val chatId = _chatRoom.value.id
        if (chatId <= 0) return
        viewModelScope.launch {
            try {
                persistConversationDelegation(chatId, value)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "Could not save delegation for this conversation. Please try again."
            }
        }
    }

    private suspend fun persistConversationDelegation(chatId: Int, value: ConversationDelegationSettings?) {
        conversationDelegationMutex.withLock {
            settingRepository.mutateFeatureSettings { latest ->
                val overrides = if (value == null) latest.conversationDelegation - chatId else latest.conversationDelegation + (chatId to value)
                latest.copy(conversationDelegation = overrides)
            }
        }
    }

    fun setChatToolsEnabled(toolIds: Collection<String>, enabled: Boolean) {
        if (toolIds.isEmpty()) return
        val ids = toolIds.toSet()
        _chatToolConfig.update { config ->
            if (enabled) {
                config.copy(
                    disabledToolIds = config.disabledToolIds - ids,
                    enabledToolIds = config.enabledToolIds + ids,
                    allToolsDisabled = false
                )
            } else {
                val disabled = config.disabledToolIds + ids
                config.copy(
                    disabledToolIds = disabled,
                    enabledToolIds = config.enabledToolIds - ids
                )
            }
        }
    }

    fun enableAllChatTools() {
        viewModelScope.launch {
            val profiles = settingRepository.fetchPlatformV2s().map(::resolvePlatformModel).filter {
                it.uid in _activePlatformUids.value && (it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.FREE || dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.forProfile(it) != null) && !it.disableAllTools
            }
            for (tool in _availableChatTools.value.filter { dev.chungjungsoo.gptmobile.data.permissions.FreeToolDataPolicy.requiresPersonalPermission(it.id) }) {
                val profile = profiles.firstOrNull { dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.forProfile(it)?.personalDataAllowed == true && !freeModelToolConsentStore.isGranted(it, tool.id, _chatRoom.value.id) }
                if (profile != null) {
                    unlockingAllTools = true
                    _pendingFreeToolConsent.value = FreeToolConsentRequest(profile.uid, profile.name, tool.id, tool.name, profile, _chatRoom.value.id)
                    return@launch
                }
            }
            unlockingAllTools = false
            _chatToolConfig.update { config ->
                val allowed = _availableChatTools.value.filter { tool ->
                    !dev.chungjungsoo.gptmobile.data.permissions.FreeToolDataPolicy.requiresPersonalPermission(tool.id) ||
                        profiles.all { freeModelToolConsentStore.isGranted(it, tool.id, _chatRoom.value.id) }
                }.mapTo(hashSetOf()) { it.id }
                config.copy(disabledToolIds = _availableChatTools.value.map { it.id }.toSet() - allowed, enabledToolIds = allowed, allToolsDisabled = false)
            }
        }
    }

    fun disableAllChatTools() {
        _chatToolConfig.update { config ->
            config.copy(
                disabledToolIds = _availableChatTools.value.map { it.id }.toSet(),
                enabledToolIds = emptySet(),
                allToolsDisabled = true
            )
        }
    }

    private fun loadAvailableChatTools() {
        viewModelScope.launch {
            try {
                val connections = withContext(Dispatchers.IO) { toolConnectionRepository.getAllConnections() }
                _academicGateways.value = connections.filter { it.alias == "deep_research" }.associate { it.connectionUid to it.name }
                val profiles = settingRepository.fetchPlatformV2s().filter { it.uid in _activePlatformUids.value && !it.disableAllTools }
                val features = settingRepository.getFeatureSettings()
                val bindings = profiles.flatMap { toolConnectionRepository.listBindingsByProfile(it.uid) }
                val remoteProfiles = profiles.filterNot { it.disableRemoteTools }.mapTo(mutableSetOf()) { it.uid }
                val localProfiles = profiles.filterNot { it.disableLocalTools }.mapTo(mutableSetOf()) { it.uid }
                val boundConnectionIds = bindings.filter { it.profileUid in remoteProfiles }.mapNotNull { it.connectionUid }.toSet()
                val locationEnabled = features.deviceLocationTool &&
                    bindings.any {
                        it.profileUid in localProfiles && it.toolName == dev.chungjungsoo.gptmobile.data.database.entity.BuiltInAgentTool.DEVICE_LOCATION
                    }
                val available = ChatToolUtils.buildAvailableChatTools(
                    connections.filter {
                        (it.connectionUid in boundConnectionIds || (it.type in setOf(ToolConnectionType.GITHUB, ToolConnectionType.AMAZON_SERPAPI) && remoteProfiles.isNotEmpty())) && it.type != ToolConnectionType.MCP
                    }
                ) + if (features.remoteMcpConnections) {
                    bindings.filter { it.profileUid in remoteProfiles }
                        .mapNotNull { binding ->
                            val connection = connections.firstOrNull { it.connectionUid == binding.connectionUid && it.type == ToolConnectionType.MCP }
                                ?: return@mapNotNull null
                            AvailableChatTool("${connection.connectionUid}:${binding.toolName}", "${connection.name} · ${binding.toolName}", connection.alias, "MCP")
                        }.distinctBy { it.id }
                } else {
                    emptyList()
                }
                val builtIn = listOf(
                    AvailableChatTool("web_search", "Web search", "All enabled web search engines", "Multi-engine", remoteProfiles.isNotEmpty()),
                    AvailableChatTool("device_location", "Device location", "Phone GPS location", "Built-in", locationEnabled)
                )
                _availableChatTools.value = available + builtIn

                // Personal tools require a recipient-scoped grant and a route that permits them.
                val freeProfiles = profiles.map(::resolvePlatformModel).filter { it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.FREE || dev.chungjungsoo.gptmobile.data.permissions.ProviderPrivacyPolicy.forProfile(it) != null }
                if (freeProfiles.isNotEmpty()) {
                    val lockedMcpIds = (available + builtIn)
                        .filter { tool ->
                            dev.chungjungsoo.gptmobile.data.permissions.FreeToolDataPolicy.requiresPersonalPermission(tool.id) &&
                                freeProfiles.any { profile ->
                                    !freeModelToolConsentStore.isGranted(profile, tool.id, _chatRoom.value.id)
                                }
                        }
                        .mapTo(mutableSetOf()) { it.id }
                    if (lockedMcpIds.isNotEmpty()) {
                        _chatToolConfig.update { config ->
                            config.copy(
                                disabledToolIds = config.disabledToolIds + lockedMcpIds,
                                enabledToolIds = config.enabledToolIds - lockedMcpIds
                            )
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // A damaged database entry, revoked credential or inaccessible MCP service must not
                // cancel viewModelScope or crash a newly opened chat.
                android.util.Log.w("ChatViewModel", "Tool catalog unavailable; continuing without remote tools", error)
                _academicGateways.value = emptyMap()
                _availableChatTools.value = listOf(
                    AvailableChatTool("web_search", "Web search", "All enabled web search engines", "Multi-engine", false),
                    AvailableChatTool("device_location", "Device location", "Phone GPS location", "Built-in", false)
                )
                _attachmentNotice.value = "Some tools are temporarily unavailable. Chat is still ready."
            }
        }
    }

    fun openUserMessageEditDialog(question: MessageV2) {
        _messageEditSession.update {
            MessageEditSession(
                message = question,
                role = MessageEditRole.USER,
                attachments = question.attachments.map(ChatAttachmentDraft::fromAttachment)
            )
        }
    }

    fun openAssistantMessageEditDialog(turnIndex: Int, platformIndex: Int) {
        val selected = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return
        viewModelScope.launch {
            val assistantMessage = chatRepository.resolveCombinedMessage(selected)
            _messageEditSession.update {
                MessageEditSession(
                    message = assistantMessage,
                    role = MessageEditRole.ASSISTANT,
                    turnIndex = turnIndex,
                    platformIndex = platformIndex,
                    attachments = assistantMessage.attachments.map(ChatAttachmentDraft::fromAttachment)
                )
            }
        }
    }

    fun openSelectTextSheet(content: String) {
        _selectedText.update { content }
        _isSelectTextSheetOpen.update { true }
    }

    fun generateDefaultChatTitle(): String? = chatRepository.generateDefaultChatTitle(_groupedMessages.value.userMessages)

    fun updateChatCreativity(temperature: Float) {
        val clamped = temperature.coerceIn(0f, 2f)
        val targetUids = _activePlatformUids.value.toSet()
        _platformsInApp.update { platforms ->
            platforms.map { platform ->
                if (platform.uid in targetUids) platform.copy(temperature = clamped) else platform
            }
        }
        viewModelScope.launch {
            _platformsInApp.value
                .filter { it.uid in targetUids }
                .forEach { settingRepository.updatePlatformV2(it) }
        }
    }

    fun updateChatPlatformModels(models: Map<String, String>) {
        val sanitizedModels = models
            .filterKeys { it in _activePlatformUids.value }
            .mapValues { (_, model) -> model.trim() }

        _chatPlatformModels.update { it + sanitizedModels }

        if (_chatRoom.value.id > 0) {
            viewModelScope.launch {
                chatRepository.saveChatPlatformModels(_chatRoom.value.id, _chatPlatformModels.value)
            }
        }
    }

    /** The first tab commits only its owned settings; immediate Tools/Privacy edits have separate revisions. */
    fun reviewCombinedPrimary(userMessageId: Int) {
        reviewingCombinedPrimaryTurn = userMessageId
        openChatModelDialog()
    }

    private suspend fun resumeResearchForUserTurn(userMessageId: Int, action: String) {
        val index = _groupedMessages.value.userMessages.indexOfFirst { it.id == userMessageId }
        val source = dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery(_groupedMessages.value.userMessages.take(index + 1))?.sourceMessageId ?: userMessageId
        val root = "${_chatRoom.value.id}:$source"
        val execution = researchSessionStore?.execution(root, !_chatRoom.value.isTemporary) ?: return
        if (execution.accepted.targetSources > 0 && (execution.attempt.state.terminal || execution.attempt.state == dev.chungjungsoo.gptmobile.data.research.ResearchAttemptState.BLOCKED_CONFIGURATION)) {
            researchSessionStore.resumeExecution(root, "$action:${execution.attempt.attemptId}", !_chatRoom.value.isTemporary)
        }
    }

    fun saveCombinedChatSettings(draft: dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft) {
        if (_combinedSettingsSaveState.value.isCommitting || _chatRoom.value.conversationMode != ConversationMode.COMBINED) return
        _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState(dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.VALIDATING)
        viewModelScope.launch {
            try {
                val validated = draft.validated()
                check(validated.conversationIdentity == _combinedConversationIdentity.value) { "The dialog belongs to another conversation." }
                val profiles = settingRepository.fetchPlatformV2s().associateBy { it.uid }
                check(validated.participantUids.all { profiles[it]?.enabled == true }) { "A selected AI profile is unavailable." }
                var room = _chatRoom.value
                if (room.id <= 0) {
                    room = chatRepository.createDraftChat(room.activePlatform, ConversationMode.COMBINED, room.title, room.combinedPrimaryUid)
                    _chatRoom.value = room
                    settingRepository.ensureConversationIdentity(room.id, _combinedConversationIdentity.value)
                }
                val features = settingRepository.getFeatureSettings()
                val previousSettings = features.conversationCombined[room.id] ?: dev.chungjungsoo.gptmobile.data.model.ConversationCombinedSettings()
                val previous = dev.chungjungsoo.gptmobile.data.model.CombinedChatSettingsDraft(
                    conversationIdentity = _combinedConversationIdentity.value,
                    chatId = room.id,
                    baseRevision = previousSettings.revision,
                    settings = previousSettings,
                    participantUids = room.activePlatform,
                    models = room.activePlatform.associateWith { _chatPlatformModels.value[it] ?: profiles[it]?.model.orEmpty() },
                    creativity = previousSettings.creativity ?: profiles[room.activePlatform.firstOrNull()]?.temperature ?: 0.7f,
                    primaryProfileUid = room.combinedPrimaryUid
                )
                val next = validated.copy(chatId = room.id)
                savedStateHandle["combinedSettingsOperationId"] = next.operationId
                _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState(dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.COMMITTING)
                combinedSettingsJournal.save(next, previous) { before, after -> chatRepository.applyCombinedChatSettings(before, after) }
                val committedRoom = (chatRepository.fetchChatListV2() + chatRepository.fetchArchivedChatListV2()).first { it.id == room.id }
                _chatRoom.value = committedRoom
                _chatPlatformModels.value = chatRepository.fetchChatPlatformModels(room.id)
                applyChatPlatformState(committedRoom)
                _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState(dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.COMMITTED)
                _isChatModelDialogOpen.value = false
                loadAvailableChatTools()
                val selectedTurn = reviewingCombinedPrimaryTurn
                reviewingCombinedPrimaryTurn = null
                if (selectedTurn != null && next.primaryProfileUid != null) {
                    val accepted = acceptedCombinedTurn(selectedTurn)
                    if (accepted != null) {
                        try {
                            require(next.primaryProfileUid in accepted.context.requested.participantUids) { "For this saved turn, choose one of its accepted profiles or regenerate with the new selection." }
                            agentRunCoordinator.withChatGate(committedRoom.id) {
                                val latest = chatRepository.fetchMessageReferencesV2(committedRoom.id)
                                require(latest.none { it.linkedMessageId == selectedTurn && it.isCombinedSynthesis() }) { "Final writing has already started. Use a new answer revision to change its writer." }
                                combinedCollectionDecisions?.chooseWriter(accepted.context.rootBudgetId, next.primaryProfileUid, accepted.context.persistBudget)
                                resumeResearchForUserTurn(selectedTurn, "user-selected-writer")
                            }
                            agentRunCoordinator.resumeCombinedCollection(committedRoom.id, selectedTurn, accepted.context.rootBudgetId, "USER_SELECTED_WRITER")
                        } catch (failure: Exception) {
                            if (failure is CancellationException) throw failure
                            _attachmentNotice.value = failure.message ?: "Primary saved. The retained turn could not resume yet."
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState(
                    dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.FAILED,
                    error.message ?: "The Combined setup could not be saved. Your edits are kept."
                )
            }
        }
    }

    fun abortCombinedChatSettingsSave() {
        if (_combinedSettingsSaveState.value.isCommitting) return
        reviewingCombinedPrimaryTurn = null
        viewModelScope.launch {
            try {
                savedStateHandle.get<String>("combinedSettingsOperationId")?.let { operation ->
                    if (_chatRoom.value.id > 0) {
                        combinedSettingsJournal.abort(_chatRoom.value.id, operation) { before, after ->
                            chatRepository.applyCombinedChatSettings(before, after)
                        }
                    }
                }
                savedStateHandle.remove<String>("combinedSettingsOperationId")
                _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState(dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.ABORTED)
                _isChatModelDialogOpen.value = false
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState(dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.FAILED, error.message ?: "The discarded save could not be settled. Retry Cancel.")
            }
        }
    }

    /** Explicit fresh scope: never copy the blocked answer, receipts, attachments, or recovery root. */
    fun retryWithoutSavedEvidence(turnIndex: Int, platformIndex: Int) {
        if (branching || _loadingStates.value.any { it == LoadingState.Loading }) return
        val uid = enabledPlatformsInChat.getOrNull(platformIndex) ?: return
        val profile = _platformsInApp.value.firstOrNull { it.uid == uid && it.enabled } ?: return
        if (turnIndex !in _groupedMessages.value.userMessages.indices) return
        val users = _groupedMessages.value.userMessages.take(turnIndex + 1)
        val original = dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery(users)?.originalRequest ?: users.lastOrNull()?.content ?: return
        if (original.isBlank()) return
        branching = true
        viewModelScope.launch {
            try {
                val saved = chatRepository.createEvidenceFreeRetry(_chatRoom.value.id, original, resolvePlatformModel(profile))
                _branchNavigation.value = saved.id
            } catch (error: kotlinx.coroutines.CancellationException) {
                throw error
            } catch (_: Exception) {
                _attachmentNotice.value = "Could not create a fresh conversation. The original response is retained."
            } finally {
                branching = false
            }
        }
    }

    /** Recovery controls are bound to the card's persisted original turn, never latest chat text. */
    fun recoverResearch(sourceMessageId: Int, command: String) {
        val action = dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.fromText(command) ?: return
        val turnIndex = _groupedMessages.value.userMessages.indexOfFirst { it.id == sourceMessageId }
        if (turnIndex < 0) return
        val row = _groupedMessages.value.assistantMessages.getOrNull(turnIndex).orEmpty()
        val finalIndex = row.indexOfFirst { it.currentRunId?.let { id -> _agentRunsById.value[id]?.synthesisKey != null } == true }
        if (action == dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_WRITING && finalIndex >= 0) {
            retryChat(turnIndex, finalIndex, action)
            return
        }
        row.indices.filter { row[it].platformType in _activePlatformUids.value && row[it].platformType !in _disabledPlatformUids.value }.forEach { index -> retryChat(turnIndex, index, action) }
    }

    private fun refreshCombinedFailures() {
        if (_chatRoom.value.conversationMode != ConversationMode.COMBINED) {
            _combinedFailures.value = emptyList()
            combinedFailureCache.retainTurns(emptySet())
            return
        }
        val grouped = _groupedMessages.value
        combinedFailureCache.retainTurns(grouped.userMessages.mapTo(hashSetOf()) { it.id })
        _combinedFailures.value = grouped.userMessages.indices.flatMap { index ->
            val user = grouped.userMessages[index]
            val row = grouped.assistantMessages.getOrNull(index).orEmpty()
            if (row.any { it.isCombinedSynthesis() }) return@flatMap emptyList()
            val accepted = acceptedCombinedTurn(user.id) ?: return@flatMap emptyList()
            if (!accepted.context.requested.singlePass) return@flatMap emptyList()
            val excluded = combinedCollectionDecisions?.decisions?.value?.get(accepted.context.rootBudgetId)?.excludedProfileUids.orEmpty()
            val activeRows = row.filter { it.platformType in _activePlatformUids.value && it.platformType in accepted.context.requested.participantUids && it.platformType !in excluded }
            if (activeRows.any { _agentRunsById.value[it.currentRunId]?.status == AgentRunStatus.CANCELED }) return@flatMap emptyList()
            combinedFailureCache.failures(user.id, user.content, activeRows, _agentRunsById.value, _platformsInApp.value.associate { it.uid to it.name }, accepted.context.requested.requestedModels, providerWaitDeadlinesByRun.value)
                .filterNot { reviewingCombinedFailureTurn != user.id && combinedCollectionDecisions?.acknowledged(accepted.context.rootBudgetId, it.attemptId) == true }
                .map { it.copy(retrying = "${it.userMessageId}:${it.profileUid}:${it.attemptId}" in pendingCombinedRetries) }
        }
    }

    fun reviewCombinedFailures(userMessageId: Int) {
        reviewingCombinedFailureTurn = userMessageId
        refreshCombinedFailures()
    }

    /** Closing an alert must leave paused contributors and their scheduled resumes intact. */
    fun dismissCombinedFailures(userId: Int) {
        val accepted = acceptedCombinedTurn(userId) ?: return
        val decisions = combinedCollectionDecisions ?: return
        val attempts = _combinedFailures.value.filter { it.userMessageId == userId }.mapNotNull { it.attemptId }.toSet()
        val chatId = _chatRoom.value.id
        viewModelScope.launch {
            try {
                agentRunCoordinator.withChatGate(chatId) {
                    decisions.acknowledge(accepted.context.rootBudgetId, attempts, accepted.context.persistBudget)
                }
                if (reviewingCombinedFailureTurn == userId) reviewingCombinedFailureTurn = null
                refreshCombinedFailures()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _attachmentNotice.value = error.message ?: "Could not save your choice. Your replies are retained."
            }
        }
    }

    fun retryCombinedProfile(userId: Int, profileUid: String) {
        val failure = _combinedFailures.value.firstOrNull { it.userMessageId == userId && it.profileUid == profileUid } ?: return
        if (!failure.retryAllowed || failure.retrying || (failure.retryAfterMillis ?: 0) > System.currentTimeMillis() || profileUid !in _activePlatformUids.value) return
        val turn = _groupedMessages.value.userMessages.indexOfFirst { it.id == userId }
        val index = _groupedMessages.value.assistantMessages.getOrNull(turn).orEmpty().indexOfFirst { it.platformType == profileUid }
        if (turn < 0 || index < 0) return
        val row = _groupedMessages.value.assistantMessages[turn]
        if (row[index].currentRunId != failure.attemptId || row.any { it.isCombinedSynthesis() }) return
        val actionKey = "$userId:$profileUid:${failure.attemptId}"
        if (!pendingCombinedRetries.add(actionKey)) return
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("CombinedRetry", "RETRY_ACCEPTED · initiator=USER · userMessageId=$userId · profileUid=$profileUid · priorAttempt=${failure.attemptId} · category=${failure.category}")
        refreshCombinedFailures()
        retryChat(turn, index, dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_FAILED_STAGE)
        if (_loadingStates.value.getOrNull(enabledPlatformsInChat.indexOf(profileUid)) != LoadingState.Loading) {
            pendingCombinedRetries.remove(actionKey)
            refreshCombinedFailures()
        }
    }

    fun canCombineAvailable(userId: Int): Boolean {
        val grouped = _groupedMessages.value
        val index = grouped.userMessages.indexOfFirst { it.id == userId }
        val user = grouped.userMessages.getOrNull(index) ?: return false
        val root = acceptedCombinedTurn(userId)?.context?.rootBudgetId
        val excluded = combinedCollectionDecisions?.decisions?.value?.get(root)?.excludedProfileUids.orEmpty()
        val row = grouped.assistantMessages.getOrNull(index).orEmpty().filter { it.platformType in _activePlatformUids.value && it.platformType !in excluded }
        return combinedSynthesisInputs(availableCombinedResponses(row, _agentRunsById.value), _agentRunsById.value, _platformsInApp.value.associate { it.uid to it.name }, _chatPlatformModels.value, user.content) != null
    }

    fun excludeCombinedFailureForTurn(userId: Int, profileUid: String) {
        val failure = _combinedFailures.value.firstOrNull { it.userMessageId == userId && it.profileUid == profileUid } ?: return
        if (failure.retrying || failure.applicationFailure) return
        val accepted = acceptedCombinedTurn(userId) ?: return
        val decisions = combinedCollectionDecisions ?: return
        viewModelScope.launch {
            try {
                val excluded = agentRunCoordinator.withChatGate(_chatRoom.value.id) {
                    val rows = chatRepository.fetchMessagesV2(_chatRoom.value.id).filter { it.linkedMessageId == userId && it.platformType != null }
                    if (rows.any { it.isCombinedSynthesis() } || rows.singleOrNull { it.platformType == profileUid }?.currentRunId != failure.attemptId) return@withChatGate false
                    val excludedUids = decisions.excludedProfiles(accepted.context.rootBudgetId)
                    val remaining = accepted.context.requested.participantUids.filter { it in _activePlatformUids.value && it !in excludedUids && it != profileUid }
                    check(remaining.isNotEmpty()) { "At least one profile must remain included in this turn." }
                    decisions.excludeProfile(accepted.context.rootBudgetId, profileUid, accepted.context.persistBudget)
                    val run = chatRepository.observeAgentRuns(_chatRoom.value.id).first().firstOrNull { it.runId == failure.attemptId }
                    cancelExcludedProviderWait(run, accepted.context.rootBudgetId)
                    true
                }
                if (excluded) {
                    refreshCombinedFailures()
                    agentRunCoordinator.resumeCombinedCollection(_chatRoom.value.id, userId, accepted.context.rootBudgetId, "USER_EXCLUDE_THIS_TURN")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _attachmentNotice.value = error.message ?: "Could not save your choice. Your replies are retained."
            }
        }
    }

    fun combineAvailableResults(userId: Int) {
        val accepted = acceptedCombinedTurn(userId) ?: return
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("CombinedSynthesis", "COMBINE_AVAILABLE · initiator=USER · userMessageId=$userId")
        viewModelScope.launch {
            try {
                agentRunCoordinator.withChatGate(_chatRoom.value.id) {
                    val rows = chatRepository.fetchMessagesV2(_chatRoom.value.id).filter { it.linkedMessageId == userId && it.platformType != null }
                    check(rows.none { it.isCombinedSynthesis() }) { "The final response has already started." }
                    val runs = chatRepository.observeAgentRuns(_chatRoom.value.id).first().associateBy { it.runId }
                    val decisions = combinedCollectionDecisions
                    val excluded = decisions?.excludedProfiles(accepted.context.rootBudgetId).orEmpty()
                    val activeRows = rows.filter { it.platformType in _activePlatformUids.value && it.platformType in accepted.context.requested.participantUids && it.platformType !in excluded }
                    val pendingRows = activeRows.filter { runs[it.currentRunId]?.status in setOf(AgentRunStatus.WAITING_REMOTE_JOB, AgentRunStatus.WAITING_PROVIDER_LIMIT) }
                    val pendingProfiles = pendingRows.mapNotNull { it.platformType }
                    if (pendingProfiles.isNotEmpty()) {
                        val savedDecisions = requireNotNull(decisions)
                        check(accepted.context.requested.participantUids.any { it in _activePlatformUids.value && it !in excluded && it !in pendingProfiles }) {
                            "All remaining profiles are waiting for a provider. Check their saved status or select another profile before combining."
                        }
                        pendingProfiles.forEach { savedDecisions.excludeProfile(accepted.context.rootBudgetId, it, accepted.context.persistBudget) }
                        pendingRows.forEach { cancelExcludedProviderWait(runs[it.currentRunId], accepted.context.rootBudgetId) }
                    }
                    val attempts = combinedProfileFailures(userId, accepted.context.requested.userPrompt, activeRows, runs, _platformsInApp.value.associate { it.uid to it.name }, accepted.context.requested.requestedModels, providerWaitDeadlinesByRun.value).mapNotNull { it.attemptId }.toSet()
                    decisions?.acknowledge(accepted.context.rootBudgetId, attempts, accepted.context.persistBudget)
                    resumeResearchForUserTurn(userId, "user-combine-available")
                }
                if (reviewingCombinedFailureTurn == userId) reviewingCombinedFailureTurn = null
                refreshCombinedFailures()
                agentRunCoordinator.resumeCombinedCollection(_chatRoom.value.id, userId, accepted.context.rootBudgetId, "USER_COMBINE_AVAILABLE")
            } catch (failure: Exception) {
                if (failure is CancellationException) throw failure
                _attachmentNotice.value = failure.message ?: "Could not save your choice. Your replies are retained."
            }
        }
    }

    /** Excluding one paused contributor must not cancel the accepted Combined turn. */
    private suspend fun cancelExcludedProviderWait(run: AgentRun?, rootBudgetId: String) {
        if (run != null) agentRunCoordinator.cancelExcludedProviderWait(run, rootBudgetId)
    }

    fun retryCombinedMerge(turnIndex: Int) {
        val index = _groupedMessages.value.assistantMessages.getOrNull(turnIndex).orEmpty().indexOfFirst { it.currentRunId?.let { id -> _agentRunsById.value[id]?.synthesisKey != null } == true }
        if (index >= 0) retryChat(turnIndex, index, dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_WRITING)
    }

    fun reviseCombinedAnswer(turnIndex: Int, target: dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerRevisionTarget) {
        try {
            val user = requireNotNull(_groupedMessages.value.userMessages.getOrNull(turnIndex)) { "The original question is unavailable." }
            val owner = requireNotNull(_groupedMessages.value.assistantMessages.getOrNull(turnIndex)?.singleOrNull { it.id == target.messageId }) { "The selected answer is unavailable." }
            val selectedRun = requireNotNull(_agentRunsById.value[target.runId]) { "The selected answer run is unavailable." }
            require(user.id == target.userMessageId && user.chatId == target.chatId && _chatRoom.value.conversationMode == ConversationMode.COMBINED) { "The selected answer belongs to a different turn." }
            target.validate(owner, selectedRun)
            regenerateCombinedTurn(turnIndex, owner, user, selectedRun, target)
        } catch (failure: IllegalArgumentException) {
            _attachmentNotice.value = failure.message ?: "The selected answer changed. Review it before creating a revision."
        }
    }

    fun regenerateWithSelectedPrimary(turnIndex: Int) {
        val user = _groupedMessages.value.userMessages.getOrNull(turnIndex) ?: return
        val owner = _groupedMessages.value.assistantMessages.getOrNull(turnIndex).orEmpty().firstOrNull { it.currentRunId != null } ?: return
        val previous = owner.currentRunId?.let { _agentRunsById.value[it] } ?: return
        regenerateCombinedTurn(turnIndex, owner, user, previous)
    }

    fun dismissQueuedJobInspection() {
        _queuedJobInspection.value = null
    }
    fun checkCombinedQueuedJob(turnIndex: Int) {
        val userId = _groupedMessages.value.userMessages.getOrNull(turnIndex)?.id ?: return
        val run = _agentRunsById.value.values.filter { it.userMessageId == userId && it.terminalError?.contains("QUEUE_JOB_PENDING") == true }.maxByOrNull { it.createdAt } ?: return
        checkQueuedJob(run.runId)
    }
    fun checkQueuedJob(runId: String) {
        if (_agentRunsById.value[runId]?.chatId != _chatRoom.value.id) return
        viewModelScope.launch {
            try {
                _queuedJobInspection.value = chatRepository.checkCombinedQueuedJob(runId)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _queuedJobInspection.value = failure.message ?: "The saved job could not be checked."
            }
        }
    }

    fun tryAnotherCombinedWriter(turnIndex: Int) {
        if (isGenerationBusy()) return
        val owner = _groupedMessages.value.assistantMessages.getOrNull(turnIndex).orEmpty().firstOrNull { it.currentRunId?.let { id -> _agentRunsById.value[id]?.synthesisKey != null } == true } ?: return
        viewModelScope.launch {
            if (!agentRunCoordinator.tryAnotherCombinedWriter(owner.chatId, requireNotNull(owner.currentRunId))) {
                _attachmentNotice.value = "No untried selected writer currently meets the capacity, availability and saved-input requirements. Retained replies remain available."
            }
        }
    }

    fun cancelResearchWriting(sourceMessageId: Int) {
        val active = agentRunCoordinator.activeRuns.value.keys
        agentRunCoordinator.cancelRuns(_agentRunsById.value.values.filter { it.userMessageId == sourceMessageId && it.chatId == _chatRoom.value.id && it.runId in active }.mapTo(mutableSetOf()) { it.runId })
    }

    fun useCombinedReply(turnIndex: Int, sourceProfileUid: String) {
        if (isGenerationBusy()) return
        val owner = _groupedMessages.value.assistantMessages.getOrNull(turnIndex).orEmpty().firstOrNull { it.currentRunId?.let { id -> _agentRunsById.value[id]?.synthesisKey != null } == true } ?: return
        viewModelScope.launch {
            agentRunCoordinator.withChatGate(owner.chatId) {
                val published = chatRepository.useCombinedReply(requireNotNull(owner.currentRunId), owner.ownershipFence, sourceProfileUid)
                if (published == null) {
                    _attachmentNotice.value = "The saved reply changed or is no longer available. Refresh the response before selecting it."
                } else {
                    _groupedMessages.value = fetchGroupedMessages(owner.chatId)
                }
            }
        }
    }

    fun retryChat(turnIndex: Int, platformIndex: Int) = retryChat(turnIndex, platformIndex, null)

    private fun retryChat(turnIndex: Int, platformIndex: Int, requestedRecovery: dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction?) {
        val currentAssistantMessage = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return
        val ownerProfileUid = currentAssistantMessage.platformType ?: return
        val ownerLoadingIndex = enabledPlatformsInChat.indexOf(ownerProfileUid)
        if (_loadingStates.value.getOrNull(ownerLoadingIndex) == LoadingState.Loading) return
        val userMessage = _groupedMessages.value.userMessages.getOrNull(turnIndex) ?: return
        val previousRun = currentAssistantMessage.currentRunId?.let { _agentRunsById.value[it] }
        val singlePassAttempt = previousRun?.synthesisSnapshot?.let { raw ->
            dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.decode(raw)?.context?.requested?.singlePass
                ?: runCatching { kotlinx.serialization.json.Json { ignoreUnknownKeys = true }.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(raw).combined?.requested?.singlePass }.getOrNull()
        } == true
        val needsFreshCombinedTurn = previousRun?.status in setOf(AgentRunStatus.COMPLETED, AgentRunStatus.CANCELED, "CANCELLED") || currentAssistantMessage.timeline.any { it.combinedTurn?.deliveryStatus == "MERGE_UNAVAILABLE" }
        if (_chatRoom.value.conversationMode == ConversationMode.COMBINED && previousRun != null && !singlePassAttempt && needsFreshCombinedTurn && requestedRecovery != dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_WRITING) {
            regenerateCombinedTurn(turnIndex, currentAssistantMessage, userMessage, previousRun)
            return
        }
        val writerUid = previousRun?.profileUid?.takeIf { previousRun.synthesisKey != null } ?: ownerProfileUid
        if (writerUid in _disabledPlatformUids.value) return
        val platform = _platformsInApp.value.firstOrNull { it.uid == writerUid && it.enabled } ?: return
        val accepted = dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.decode(previousRun?.synthesisSnapshot)
        val platformWithChatModel = resolvePlatformModel(platform).let { current ->
            current.copy(
                model = accepted?.context?.requested?.requestedModels?.get(current.uid) ?: current.model,
                temperature = if (accepted != null) accepted.context.requested.temperatureFor(current.uid, current.temperature) else current.temperature
            )
        }
        if (_chatRoom.value.conversationMode != ConversationMode.COMBINED && rejectUnavailableFreeProfile(platformWithChatModel)) return
        var reconciledAnswer: String? = null
        val retryTools = accepted?.tools?.copy(combined = accepted.context.forRole(dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.CONTRIBUTOR, platform.uid)) ?: _chatToolConfig.value
        if (currentAssistantMessage.currentRunId?.startsWith(COMBINED_RUN_PREFIX) == true) {
            retryFrozenCombined(turnIndex, platformIndex, currentAssistantMessage, platformWithChatModel)
            return
        }
        combinedSynthesisAttempts.reset(userMessage.id)
        val runId = UUID.randomUUID().toString()
        val prior = dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery(_groupedMessages.value.userMessages.take(turnIndex + 1))
        val sourceMessageId = prior?.sourceMessageId ?: userMessage.id
        val recoveryAction = requestedRecovery ?: if (prior?.primaryOnly == true) dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.CONTINUE_PRIMARY_ONLY else dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_FAILED_STAGE
        val recovery = dev.chungjungsoo.gptmobile.data.agent.TaskResumeDescriptor(
            "${userMessage.chatId}:$sourceMessageId",
            sourceMessageId,
            runId,
            recoveryAction,
            allowUndispatchedBudgetRetry = recoveryAction == dev.chungjungsoo.gptmobile.data.agent.TaskRecoveryAction.RETRY_FAILED_STAGE
        )
        _loadingStates.update { states -> states.mapIndexed { index, state -> if (enabledPlatformsInChat.getOrNull(index) == ownerProfileUid) LoadingState.Loading else state } }

        viewModelScope.launch {
            agentRunCoordinator.withChatGate(userMessage.chatId) {
                persistBeforeProvider(
                    persist = {
                        researchSessionStore?.execution(recovery.rootWorkId, !_chatRoom.value.isTemporary)?.takeIf { it.accepted.targetSources > 0 && it.attempt.state.terminal }?.let {
                            researchSessionStore.resumeExecution(recovery.rootWorkId, "profile-retry:${it.attempt.attemptId}", !_chatRoom.value.isTemporary)
                        }
                        if (accepted?.context?.requested?.singlePass == true && previousRun != null) {
                            val durable = chatRepository.fetchMessageReferencesV2(userMessage.chatId)
                            require(durable.singleOrNull { it.id == currentAssistantMessage.id }?.currentRunId == previousRun.runId && durable.none { it.linkedMessageId == userMessage.id && it.isCombinedSynthesis() }) { "This contribution changed or the final answer already started. Use a new answer revision." }
                            reconciledAnswer = chatRepository.reconcileCombinedQueuedContributor(previousRun.runId)
                            require(chatRepository.reopenCombinedContributor(previousRun.runId)) { "This turn was cancelled or already combined. Start a new turn; saved replies are retained." }
                        }
                        chatRepository.persistAgentRetry(
                            PersistAgentRetryRequest(
                                userMessage = userMessage,
                                assistantMessage = currentAssistantMessage.copy(combinedSources = emptyList()),
                                recovery = recovery,
                                run = AgentRunDraft(
                                    runId = runId,
                                    profileUid = platformWithChatModel.uid,
                                    providerSnapshot = platformWithChatModel.compatibleType.name,
                                    modelSnapshot = platformWithChatModel.model,
                                    synthesisSnapshot = accepted?.copy(recoveredProviderAnswer = reconciledAnswer, sourceQueueRunId = if (reconciledAnswer != null) accepted.sourceQueueRunId ?: previousRun?.runId else null)?.encode(),
                                    createdAt = currentTimeStamp
                                )
                            )
                        )
                    },
                    startProvider = { persisted ->
                        _agentRunsById.update { it + (persisted.run.runId to persisted.run) }
                        pendingCombinedRetries.remove("${userMessage.id}:$ownerProfileUid:${previousRun?.runId}")
                        _groupedMessages.update { groupedMessages ->
                            updateAssistantSlot(groupedMessages, turnIndex, platformIndex) { current ->
                                if (current.id == currentAssistantMessage.id && current.platformType == ownerProfileUid) persisted.assistantMessage else current
                            }
                        }
                        val contextMessages = groupedMessagesThroughTurn(_groupedMessages.value, turnIndex)
                        agentRunCoordinator.start(
                            listOf(
                                AgentRunRequest(
                                    runId = runId,
                                    chatId = persisted.assistantMessage.chatId,
                                    assistantMessage = persisted.assistantMessage,
                                    platform = platformWithChatModel,
                                    userMessages = contextMessages.userMessages,
                                    assistantMessages = contextMessages.assistantMessages,
                                    chatToolConfig = retryTools
                                )
                            )
                        )
                    },
                    onFailure = { error ->
                        pendingCombinedRetries.remove("${userMessage.id}:$ownerProfileUid:${previousRun?.runId}")
                        refreshCombinedFailures()
                        _attachmentNotice.value = error.message ?: "The retry could not be saved. The previous response is retained."
                        _loadingStates.update { states -> states.mapIndexed { index, state -> if (enabledPlatformsInChat.getOrNull(index) == ownerProfileUid) LoadingState.Idle else state } }
                    }
                )
            }
        }
    }

    /** Explicit regeneration accepts a new turn policy and all participants before any provider dispatch. */
    private fun regenerateCombinedTurn(turnIndex: Int, owner: MessageV2, user: MessageV2, previousRun: AgentRun, revisionOf: dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerRevisionTarget? = null) {
        if (isGenerationBusy() || _combinedSettingsSaveState.value.isCommitting) {
            _attachmentNotice.value = "Wait for current work and settings changes to finish before regenerating."
            return
        }
        val captured = try {
            captureCombinedTools(user.content)
        } catch (failure: IllegalArgumentException) {
            _attachmentNotice.value = failure.message ?: "The current Combined setup could not be accepted."
            return
        }
        val requested = captured.combined ?: return
        val profiles = requested.requested.participantUids.map { uid ->
            val profile = _platformsInApp.value.firstOrNull { it.uid == uid && it.enabled } ?: return
            resolvePlatformModel(profile).copy(model = requested.requested.requestedModels.getValue(uid), temperature = requested.requested.temperatureFor(uid, profile.temperature))
        }
        val originalContext = groupedMessagesThroughTurn(_groupedMessages.value, turnIndex)
        val context = originalContext.copy(assistantMessages = originalContext.assistantMessages.dropLast(1) + listOf(emptyList()))
        val parentRoot = dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.context(previousRun.synthesisSnapshot)?.rootBudgetId
        combinedSynthesisMessages += user.id
        pendingRunDispatches += 1
        _loadingStates.value = enabledPlatformsInChat.map { if (it in requested.requested.participantUids) LoadingState.Loading else LoadingState.Idle }
        viewModelScope.launch {
            try {
                val planned = chatRepository.prepareCombinedTurn(requested, context.userMessages, context.assistantMessages, profiles, captured)
                val dispatchTools = captured.copy(combined = planned)
                val accepted = dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn(context = planned, tools = dispatchTools, parentRootBudgetId = parentRoot, revisionOf = revisionOf)
                val runs = profiles.map { profile ->
                    AgentRunDraft(UUID.randomUUID().toString(), profile.uid, profile.compatibleType.name, profile.model, currentTimeStamp, synthesisSnapshot = accepted.encode())
                }
                persistBeforeProvider(
                    persist = { chatRepository.regenerateCombinedTurn(user, owner, runs, accepted) },
                    startProvider = { persisted ->
                        _agentRunsById.update { it + persisted.runs.associateBy(AgentRun::runId) }
                        val refreshed = chatRepository.fetchMessageReferencesV2(user.chatId).filter { it.linkedMessageId == user.id && it.platformType != null }.associateBy { it.platformType }
                        _groupedMessages.update { grouped ->
                            val index = grouped.userMessages.indexOfFirst { it.id == user.id }
                            if (index < 0) {
                                grouped
                            } else {
                                grouped.copy(
                                    assistantMessages = grouped.assistantMessages.toMutableList().apply {
                                        this[index] = enabledPlatformsInChat.map { uid -> refreshed[uid] ?: MessageV2(chatId = user.chatId, linkedMessageId = user.id, platformType = uid, content = "") }
                                    }
                                )
                            }
                        }
                        combinedSynthesisAttempts.reset(user.id)
                        agentRunCoordinator.start(
                            profiles.mapIndexed { index, profile ->
                                AgentRunRequest(
                                    runId = persisted.runs[index].runId,
                                    chatId = user.chatId,
                                    assistantMessage = persisted.assistantMessages[index],
                                    platform = profile,
                                    userMessages = context.userMessages,
                                    assistantMessages = context.assistantMessages,
                                    chatToolConfig = dispatchTools.copy(combined = planned.forRole(dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.CONTRIBUTOR, profile.uid))
                                )
                            }
                        )
                    },
                    onFailure = { failure -> _attachmentNotice.value = failure.message ?: "Combined regeneration could not be saved. The previous revisions are retained." }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _attachmentNotice.value = failure.message ?: "Combined regeneration could not start. The previous revisions are retained."
            } finally {
                combinedSynthesisMessages -= user.id
                pendingRunDispatches -= 1
                syncLoadingStates(_agentRunsById.value)
            }
        }
    }

    private fun retryFrozenCombined(turnIndex: Int, platformIndex: Int, message: MessageV2, platform: PlatformV2) {
        _loadingStates.update { states -> states.mapIndexed { index, state -> if (enabledPlatformsInChat.getOrNull(index) == platform.uid) LoadingState.Loading else state } }
        viewModelScope.launch {
            try {
                val snapshot = requireNotNull(chatRepository.prepareCombinedRetry(requireNotNull(message.currentRunId), platform)) { "The original Combined input is unavailable; no replacement was sent." }
                val acceptedPlatform = platform.copy(
                    model = snapshot.model,
                    temperature = if (snapshot.version >= 5 && snapshot.combined != null) snapshot.temperature else platform.temperature,
                    systemPrompt = dev.chungjungsoo.gptmobile.data.agent.combinedWriterSystemPrompt(snapshot.systemPrompt, snapshot.deliveryProtocol, snapshot.userMessage.content, (snapshot.writerRequestPlan?.version ?: 1) >= 2 && snapshot.combined?.requested?.appendContinuations == true),
                    disableAllTools = true,
                    disableLocalTools = true
                )
                val acceptedTools = (snapshot.acceptedTools ?: _chatToolConfig.value).copy(
                    allToolsDisabled = true,
                    delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false),
                    combined = snapshot.combined?.forRole(dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.SYNTHESIS, snapshot.profileUid)
                )
                val raw = snapshot.encode()
                val nextRunId = COMBINED_RUN_PREFIX + UUID.randomUUID().toString()
                persistBeforeProvider(
                    persist = {
                        chatRepository.persistAgentRetry(
                            PersistAgentRetryRequest(
                                userMessage = snapshot.userMessage,
                                assistantMessage = message.copy(combinedSources = snapshot.sources),
                                run = AgentRunDraft(
                                    runId = nextRunId,
                                    profileUid = snapshot.profileUid,
                                    providerSnapshot = platform.compatibleType.name,
                                    modelSnapshot = snapshot.model,
                                    synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(raw),
                                    synthesisSnapshot = raw,
                                    createdAt = currentTimeStamp
                                ),
                                resumeCombinedFrom = snapshot.resumeFromRunId,
                                replaceCombinedCorpus = snapshot.combined?.requested?.singlePass == true
                            )
                        )
                    },
                    startProvider = { persisted ->
                        _agentRunsById.update { it + (persisted.run.runId to persisted.run) }
                        _groupedMessages.update { current ->
                            updateAssistantSlot(current, turnIndex, platformIndex) { selected ->
                                if (selected.id == message.id && selected.platformType == message.platformType) persisted.assistantMessage else selected
                            }
                        }
                        agentRunCoordinator.start(
                            listOf(
                                AgentRunRequest(
                                    runId = persisted.run.runId,
                                    chatId = message.chatId,
                                    assistantMessage = persisted.assistantMessage,
                                    platform = acceptedPlatform,
                                    userMessages = snapshot.history + snapshot.userMessage,
                                    assistantMessages = snapshot.replies + listOf(emptyList()),
                                    chatToolConfig = acceptedTools,
                                    stage = snapshot.bindAcceptedStage()
                                )
                            )
                        )
                    },
                    onFailure = { error ->
                        _attachmentNotice.value = error.message ?: "Combined recovery could not be saved. The previous response is retained."
                        _loadingStates.update { states -> states.mapIndexed { index, state -> if (enabledPlatformsInChat.getOrNull(index) == platform.uid) LoadingState.Idle else state } }
                    }
                )
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _loadingStates.update { states -> states.mapIndexed { index, state -> if (enabledPlatformsInChat.getOrNull(index) == platform.uid) LoadingState.Idle else state } }
                _attachmentNotice.update { failure.message ?: "Combined recovery could not validate its saved input." }
            }
        }
    }

    fun toggleMessageFavorite(turnIndex: Int, platformIndex: Int) {
        val message = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return

        val newFavorite = !message.isFavorite
        _groupedMessages.update {
            updateAssistantSlot(it, turnIndex, platformIndex) { assistantMessage ->
                assistantMessage.copy(isFavorite = newFavorite)
            }
        }
        if (message.id > 0) {
            viewModelScope.launch {
                chatRepository.setMessageFavorite(message.id, newFavorite)
            }
        }
    }

    fun updateChatTitle(title: String) {
        // Should be only used for changing chat title after the chatroom is created.
        if (_chatRoom.value.id > 0) {
            _chatRoom.update { it.copy(title = title, isTitleCustomized = true) }
            viewModelScope.launch {
                chatRepository.updateChatTitle(_chatRoom.value, title, isCustomized = true)
            }
        }
    }

    fun updateChatPlatformIndex(assistantIndex: Int, platformIndex: Int) {
        // Change the message shown in the screen to another platform
        if (assistantIndex >= _indexStates.value.size || assistantIndex < 0) return
        if (platformIndex >= enabledPlatformsInChat.size || platformIndex < 0) return

        _indexStates.update { states -> states.mapIndexed { index, value -> if (index == assistantIndex) platformIndex else value } }
    }

    fun addSelectedFile(filePath: String) {
        addDraftFile(
            currentAttachments = { _selectedAttachments.value },
            updateAttachments = { attachments -> _selectedAttachments.update { attachments } },
            filePath = filePath,
            onNotice = { notice -> _attachmentNotice.update { notice } }
        )
    }

    fun retrySelectedFile(filePath: String) {
        if (_selectedAttachments.value.none { it.sourceFilePath == filePath && it.status == ChatAttachmentDraft.Status.Failed }) return
        _selectedAttachments.update { list -> list.map { if (it.sourceFilePath == filePath) it.copy(status = ChatAttachmentDraft.Status.Preparing, errorMessage = null) else it } }
        preprocessDraftAttachment(
            currentAttachments = { _selectedAttachments.value },
            updateAttachments = { _selectedAttachments.value = it },
            filePath = filePath,
            onNotice = { _attachmentNotice.value = it }
        )
    }

    fun removeSelectedFile(filePath: String) {
        removeDraftFile(
            currentAttachments = { _selectedAttachments.value },
            updateAttachments = { attachments -> _selectedAttachments.update { attachments } },
            filePath = filePath
        )
    }

    fun addMessageEditFile(filePath: String) {
        addDraftFile(
            currentAttachments = { _messageEditSession.value?.attachments.orEmpty() },
            updateAttachments = ::updateMessageEditAttachments,
            filePath = filePath,
            onNotice = { notice -> _attachmentNotice.update { notice } }
        )
    }

    fun removeMessageEditFile(filePath: String) {
        removeDraftFile(
            currentAttachments = { _messageEditSession.value?.attachments.orEmpty() },
            updateAttachments = ::updateMessageEditAttachments,
            filePath = filePath
        )
    }

    fun clearSelectedFiles() {
        _selectedAttachments.value.forEach { attachment ->
            attachment.preparedFilePath?.let { AttachmentPayloadCache.remove(it) }
        }
        _selectedAttachments.update { emptyList() }
    }

    fun consumeAttachmentNotice() {
        _attachmentNotice.update { null }
    }

    fun notifyAttachmentCopyFailed() {
        _attachmentNotice.update { "Failed to copy attachment." }
    }

    fun consumeBranchNavigation() {
        _branchNavigation.value = null
    }

    fun resumeBranchIfReady() {
        if (!modelsRestored || !regenerateBranch || branchResumed || _chatRoom.value.id <= 0 || _platformsInApp.value.isEmpty() || _groupedMessages.value.userMessages.isEmpty()) return
        val last = _groupedMessages.value.assistantMessages.lastOrNull() ?: return
        branchResumed = true
        if (last.any { it.currentRunId != null || it.content.isNotBlank() }) return
        enabledPlatformsInChat.forEachIndexed { index, uid -> if (uid in _activePlatformUids.value) retryChat(_groupedMessages.value.userMessages.lastIndex, index) }
    }

    fun saveUserMessageEdit(editedMessage: MessageV2, attachments: List<ChatAttachmentDraft>): Boolean {
        if (branching || attachments.any { it.status != ChatAttachmentDraft.Status.Ready }) return false
        if (isGenerationBusy()) {
            _attachmentNotice.value = "Stop the active response before branching."
            return false
        }
        branching = true
        viewModelScope.launch {
            try {
                val branch = chatRepository.branchChat(_chatRoom.value, editedMessage.copy(attachments = attachments.mapNotNull { it.attachment }))
                _branchNavigation.value = branch.id
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _attachmentNotice.value = "The branch could not be saved. Your original conversation is unchanged."
            } finally {
                branching = false
            }
        }
        return true
    }

    fun setTemporary(enabled: Boolean) {
        if (isGenerationBusy()) {
            _attachmentNotice.value = "Stop generation before changing privacy."
            return
        }
        viewModelScope.launch {
            _chatRoom.value = chatRepository.updateTemporary(_chatRoom.value, enabled)
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.setPrivateSession(diagnosticPrivacyOwner, enabled)
        }
    }

    fun forgetConversationMemories() {
        viewModelScope.launch {
            chatRepository.forgetChatMemories(_chatRoom.value.id)
            _attachmentNotice.value = "Memories learned from this conversation were removed."
        }
    }

    /** Refresh request context without destroying the controller that owns the unsaved composer. */
    suspend fun bindGitHubContext(context: dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext) = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        val roomId = _chatRoom.value.id
        if (roomId <= 0) return@withContext
        val features = settingRepository.getFeatureSettings()
        val saved = features.conversationGitHub[roomId] ?: return@withContext
        if (!saved.sameWorkspace(context) || _chatRoom.value.id != roomId) return@withContext
        _chatToolConfig.update {
            it.copy(github = saved.copy(commitSha = context.commitSha, prepareIndex = saved.connectionUid in features.githubPrepareIndex))
        }
    }

    /** Save before switching an embedded GitHub tab without marking this conversation as left. */
    fun checkpointComposer() {
        val room = _chatRoom.value
        if (!composerRestored || room.id <= 0 || room.isTemporary) return
        val text = question.text.toString()
        val attachments = _selectedAttachments.value.mapNotNull { it.attachment }
        conversationExitWork.enqueue(room.id) {
            draftMutex.withLock {
                val encoded = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatAttachment.serializer()), attachments)
                chatRepository.saveComposerDraft(room.id, text, encoded, System.currentTimeMillis() / 1000)
            }
        }
    }

    fun leaveConversation(done: () -> Unit) {
        if (leavingConversation) return
        leavingConversation = true
        memoryPreview?.clear()
        val room = _chatRoom.value
        val text = question.text.toString()
        val attachments = _selectedAttachments.value.mapNotNull { it.attachment }
        val saveDraft = composerRestored
        exitOwnsDiagnosticPrivacy = room.isTemporary
        conversationExitWork.enqueue(room.id) {
            if (room.isTemporary) {
                try {
                    agentRunCoordinator.cancelChatAndJoin(room.id, dev.chungjungsoo.gptmobile.data.delegation.CancellationOrigin.CHAT_DELETED)
                    chatRepository.deleteChatsV2(listOf(room))
                } finally {
                    dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.setPrivateSession(diagnosticPrivacyOwner, false)
                }
            } else if (room.id > 0 && saveDraft) {
                draftMutex.withLock {
                    val encoded = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatAttachment.serializer()), attachments)
                    chatRepository.saveComposerDraft(room.id, text, encoded, System.currentTimeMillis() / 1000)
                }
            }
            if (room.isArchived && !room.isTemporary) chatRepository.setChatArchived(room.id, true)
        }
        done()
    }

    fun saveAssistantMessageEdit(
        editedMessage: MessageV2,
        thoughts: String,
        attachments: List<ChatAttachmentDraft>
    ): Boolean {
        if (attachments.any { it.status != ChatAttachmentDraft.Status.Ready }) {
            _attachmentNotice.update { "Wait for attachments to finish processing before saving." }
            return false
        }

        val session = _messageEditSession.value ?: return false
        val turnIndex = session.turnIndex ?: return false
        val platformIndex = session.platformIndex ?: return false
        val currentMessage = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return false

        val updatedContent = editedMessage.content
        val updatedThoughts = thoughts
        val updatedAttachments = attachments.mapNotNull { it.attachment }

        val textChanged = currentMessage.content != updatedContent || currentMessage.thoughts != updatedThoughts
        val updatedTimeline = if (textChanged) {
            rebuildAssistantTimelineForEdit(
                currentTimeline = currentMessage.timeline,
                updatedContent = updatedContent,
                updatedThoughts = updatedThoughts,
                hasToolTrace = currentMessage.currentRunId
                    ?.let(_toolEventsByRun.value::get)
                    .orEmpty()
                    .isNotEmpty()
            )
        } else {
            currentMessage.timeline
        }
        val updatedRevisions = if (textChanged) {
            currentMessage.snapshotLatestAssistantRevision(currentTimeStamp)
                ?.let { listOf(it) + currentMessage.revisions }
                ?: currentMessage.revisions
        } else {
            currentMessage.revisions
        }

        _groupedMessages.update {
            updateAssistantSlot(
                groupedMessages = it,
                turnIndex = turnIndex,
                platformIndex = platformIndex
            ) { assistantMessage ->
                assistantMessage.copy(
                    content = updatedContent,
                    thoughts = updatedThoughts,
                    timeline = updatedTimeline,
                    attachments = updatedAttachments,
                    revisions = updatedRevisions,
                    createdAt = assistantMessage.createdAt
                ).resetActiveRevision()
            }
        }
        persistCurrentChatSnapshot()
        return true
    }

    fun showPreviousAssistantRevision(turnIndex: Int, platformIndex: Int) {
        updateAssistantRevisionSelection(turnIndex, platformIndex) { message ->
            when {
                message.revisions.isEmpty() -> message.activeRevisionIndex
                message.activeRevisionIndex == ACTIVE_REVISION_LATEST -> 0
                message.activeRevisionIndex < message.revisions.lastIndex -> message.activeRevisionIndex + 1
                else -> message.activeRevisionIndex
            }
        }
    }

    fun showNextAssistantRevision(turnIndex: Int, platformIndex: Int) {
        updateAssistantRevisionSelection(turnIndex, platformIndex) { message ->
            when {
                message.activeRevisionIndex == ACTIVE_REVISION_LATEST -> ACTIVE_REVISION_LATEST
                message.activeRevisionIndex == 0 -> ACTIVE_REVISION_LATEST
                else -> message.activeRevisionIndex - 1
            }
        }
    }

    suspend fun exportDebugFile(directory: java.io.File): java.io.File = dev.chungjungsoo.gptmobile.data.diagnostics.JavaHeapWorkGate.run("debug-export") {
        check(debugMode.value) { "Enable Debug mode to export diagnostic conversation data." }
        val room = _chatRoom.value
        val visible = persistableMessages(_groupedMessages.value)
        val allProfiles = _platformsInApp.value.toList()
        val tools = _chatToolConfig.value
        val features = featureSettings.value
        val draft = question.text.toString()
        val live = agentRunCoordinator.streamMessages.value.values.filter { it.chatId == room.id }.toList()
        val baseRecords = chatRepository.fetchDebugRecords(room.id)
        val records = kotlinx.serialization.json.JsonObject(
            baseRecords + mapOf(
                "researchLifecycle" to (researchSessionStore?.featureDiagnostics(room.id) ?: kotlinx.serialization.json.JsonObject(emptyMap())),
                "researchProfileStatistics" to kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToJsonElement(kotlinx.serialization.serializer<Map<String, List<dev.chungjungsoo.gptmobile.data.research.ResearchProfileStatistics>>>(), researchProfileStatistics.value),
                "researchStatisticsScope" to kotlinx.serialization.json.JsonPrimitive("Selected chat; retained stage telemetry. Missing legacy correlation stays unknown, and serialized request preparation alone is not transmission.")
            )
        )
        val messages = withContext(Dispatchers.IO) {
            chatRepository.fetchMessagesV2(room.id).associateBy { it.id }.toMutableMap().apply {
                visible.filter { it.id > 0 }.forEach { put(it.id, chatRepository.resolveCombinedMessage(it)) }
                live.filter { it.id > 0 }.forEach { put(it.id, chatRepository.resolveCombinedMessage(it)) }
            }.values.toList().plus(visible.filter { it.id <= 0 }).sortedWith(compareBy<MessageV2> { it.createdAt }.thenBy { it.id })
        }
        val dependencyIds = buildSet {
            addAll(enabledPlatformsInChat)
            messages.forEach { message ->
                message.platformType?.let(::add)
                (message.combinedSources + message.revisions.flatMap { it.combinedSources }).forEach { add(it.platformUid) }
                (message.timeline + message.revisions.flatMap { it.timeline }).mapNotNull { it.assistDiagnostic?.profileUid }.forEach(::add)
            }
            (records["invocations"] as? kotlinx.serialization.json.JsonArray).orEmpty().forEach { row ->
                ((row as? kotlinx.serialization.json.JsonObject)?.get("profileUid") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(::add)
            }
            features.localAssistPolicy.profileUid?.let(::add)
        }
        val profiles = allProfiles.filter { it.uid in dependencyIds }
        withContext(Dispatchers.IO) {
            check(directory.mkdirs() || directory.isDirectory)
            val json = kotlinx.serialization.json.Json { encodeDefaults = true }
            val safeTitle = room.title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80)
            val destination = java.io.File(directory, "export_${safeTitle}_${System.currentTimeMillis()}.zip")
            val conversation = java.io.File.createTempFile("debug-conversation-", ".json", directory)
            val staging = java.io.File.createTempFile("debug-bundle-", ".partial", directory)
            try {
                conversation.bufferedWriter().use { writer ->
                    writeChatDebugExport(
                        room, messages, profiles, tools, features, draft, records, emptyList(), json,
                        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.entries.value, writer,
                        researchStream = { consume -> researchSessionStore?.streamChat(room.id, consume) }
                    )
                }
                StreamingDebugBundle.write(conversation, staging)
                StreamingDebugBundle.verify(staging)
                check(staging.renameTo(destination)) { "Unable to publish the completed export" }
                destination
            } finally {
                conversation.delete()
                staging.delete()
            }
        }
    }

    suspend fun exportChat(format: ChatExportFormat = ChatExportFormat.MARKDOWN): Pair<String, String> {
        require(format != ChatExportFormat.DEBUG) { "Diagnostic exports use exportDebugFile so large histories stay off the heap." }
        val exported = groupPersistedMessages(completeWindowMessages(_groupedMessages.value), enabledPlatformsInChat, _chatRoom.value.id)
        val content = exported.assistantMessages.flatten()
            .map { assistantExportText(it, format, debugMode = debugMode.value) }
            .filter(String::isNotBlank)
            .joinToString("\n\n")
        val safeTitle = chatRoom.value.title.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(80)
        return "export_${safeTitle}_${System.currentTimeMillis()}.${format.extension}" to content
    }

    private fun completeChat(
        persistSnapshotFirst: Boolean = false,
        oneMessageUid: String? = null,
        acceptedTools: ChatMcpToolConfig = _chatToolConfig.value
    ) {
        // Update all the platform loading states to Loading
        _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Loading } }
        val turnIndex = _groupedMessages.value.assistantMessages.lastIndex

        pendingRunDispatches += 1
        val readinessLease = dev.chungjungsoo.gptmobile.data.readiness.ReadinessWorkloadSignals.enter()
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record("Readiness", "SEND_TAPPED")
        viewModelScope.launch {
            try {
                val disabled = _disabledPlatformUids.value
                val activeUids = acceptedTools.combined?.requested?.participantUids?.toSet()
                    ?: _activePlatformUids.value.filterNot { it in disabled }.toSet()
                val platforms = resolveSelectedPlatforms(enabledPlatformsInChat, _platformsInApp.value)
                    .filter { it.value.uid in activeUids && (oneMessageUid == null || it.value.uid == oneMessageUid) }
                    .map {
                        val resolved = resolvePlatformModel(it.value)
                        IndexedValue(
                            it.index,
                            resolved.copy(
                                model = acceptedTools.combined?.requested?.requestedModels?.get(resolved.uid) ?: resolved.model,
                                temperature = if (acceptedTools.combined == null) resolved.temperature else acceptedTools.combined.requested.temperatureFor(resolved.uid, resolved.temperature)
                            )
                        )
                    }
                if (acceptedTools.combined == null && platforms.any { rejectUnavailableFreeProfile(it.value) }) {
                    _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
                    return@launch
                }
                val unavailableIndexes = enabledPlatformsInChat.indices - platforms.mapTo(mutableSetOf()) { it.index }
                _loadingStates.update { states ->
                    states.toMutableList().apply {
                        unavailableIndexes.filter { it in indices }.forEach { this[it] = LoadingState.Idle }
                    }
                }
                if (platforms.isEmpty()) {
                    _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
                    return@launch
                }
                val timestamp = currentTimeStamp
                val userMessage = _groupedMessages.value.userMessages.getOrNull(turnIndex)
                if (userMessage == null) {
                    _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
                    return@launch
                }
                var dispatchTools = acceptedTools
                acceptedTools.combined?.let { accepted ->
                    dispatchTools = acceptedTools.copy(
                        combined = chatRepository.prepareCombinedTurn(
                            accepted,
                            _groupedMessages.value.userMessages,
                            _groupedMessages.value.assistantMessages,
                            platforms.map { it.value },
                            acceptedTools
                        )
                    )
                }
                val acceptedRecord = dispatchTools.combined?.let {
                    dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn(context = it, tools = dispatchTools).encode()
                }
                val runs = platforms.map { (_, platform) ->
                    AgentRunDraft(
                        runId = UUID.randomUUID().toString(),
                        profileUid = platform.uid,
                        providerSnapshot = platform.compatibleType.name,
                        modelSnapshot = platform.model,
                        createdAt = timestamp,
                        synthesisSnapshot = acceptedRecord
                    )
                }
                val chatRoom = _chatRoom.value.copy(
                    title = if (_chatRoom.value.id == 0) {
                        userMessage.content.replace('\n', ' ').take(50)
                    } else {
                        _chatRoom.value.title
                    },
                    updatedAt = timestamp
                )
                persistBeforeProvider(
                    persist = {
                        if (persistSnapshotFirst && _chatRoom.value.id > 0) {
                            chatRepository.saveChat(
                                chatRoom = _chatRoom.value,
                                messages = completeWindowMessages(_groupedMessages.value),
                                chatPlatformModels = _chatPlatformModels.value
                            )
                        }
                        chatRepository.persistAgentTurn(
                            PersistAgentTurnRequest(
                                chatRoom = chatRoom,
                                userMessage = userMessage,
                                runs = runs,
                                chatPlatformModels = _chatPlatformModels.value.filterKeys { it in chatRoom.enabledPlatform }
                            )
                        )
                    },
                    startProvider = { persisted ->
                        _agentRunsById.update { it + persisted.runs.associateBy(AgentRun::runId) }
                        val wasNewConversation = _chatRoom.value.id <= 0
                        _chatRoom.update { persisted.chatRoom }
                        if (wasNewConversation) {
                            _combinedConversationIdentity.value = settingRepository.ensureConversationIdentity(
                                persisted.chatRoom.id,
                                _combinedConversationIdentity.value
                            )
                        }
                        dispatchTools = dispatchTools.copy(combined = dispatchTools.combined?.copy(chatId = persisted.chatRoom.id))
                        dispatchTools.combined?.let {
                            chatRepository.prepareCombinedTurn(
                                it,
                                _groupedMessages.value.userMessages,
                                _groupedMessages.value.assistantMessages,
                                platforms.map { entry -> entry.value },
                                dispatchTools
                            )
                        }
                        if (wasNewConversation) _chatToolConfig.value.research?.let(::setConversationResearch)
                        if (wasNewConversation) _chatToolConfig.value.reasoning?.let(::setConversationReasoning)
                        if (wasNewConversation && _chatToolConfig.value.delegation != null) {
                            setConversationDelegation(_chatToolConfig.value.delegation)
                        }
                        _groupedMessages.update { groupedMessages ->
                            // Room can replace the optimistic row while persistence suspends.
                            val persistedIndex = persistedTurnIndex(groupedMessages.userMessages, userMessage, persisted.userMessage)
                                ?: return@update groupedMessages
                            if (persistedIndex !in groupedMessages.assistantMessages.indices) return@update groupedMessages
                            groupedMessages.copy(
                                userMessages = groupedMessages.userMessages.toMutableList().apply {
                                    this[persistedIndex] = persisted.userMessage
                                },
                                assistantMessages = groupedMessages.assistantMessages.toMutableList().apply {
                                    this[persistedIndex] = mergePersistedAssistantRow(
                                        currentRow = this[persistedIndex],
                                        selectedProfileUids = enabledPlatformsInChat,
                                        persistedMessages = persisted.assistantMessages,
                                        chatId = persisted.chatRoom.id
                                    )
                                }
                            )
                        }
                        val contextMessages = withContext(messageGroupingDispatcher) {
                            groupPersistedMessages(chatRepository.observeMessageWindow(persisted.chatRoom.id, visibleHistoryTurns.value).first(), enabledPlatformsInChat, persisted.chatRoom.id)
                        }
                        val recoveryTask = dev.chungjungsoo.gptmobile.data.agent.resolveTaskRecovery(contextMessages.userMessages)
                        val originalMessageId = recoveryTask?.sourceMessageId ?: persisted.userMessage.id
                        val taskText = recoveryTask?.originalRequest ?: persisted.userMessage.content
                        if (taskText.isNotBlank()) {
                            val acceptedResearch = dispatchTools.combined?.requested?.acceptedResearch
                            val researchSettings = (dispatchTools.research ?: featureSettings.value.conversationResearch[persisted.chatRoom.id])?.normalized()
                            researchSessionStore?.acceptTask(
                                persisted.chatRoom.id, "${persisted.chatRoom.id}:$originalMessageId", originalMessageId, taskText,
                                platforms.map { it.value.uid }, dispatchTools.combined?.requested?.preferredOwnerUid,
                                dev.chungjungsoo.gptmobile.data.research.researchHash(kotlinx.serialization.json.Json.encodeToString(featureSettings.value) + kotlinx.serialization.json.Json.encodeToString(dispatchTools)),
                                dev.chungjungsoo.gptmobile.data.research.researchHash(platforms.joinToString { kotlinx.serialization.json.Json.encodeToString(it.value) }),
                                !persisted.chatRoom.isTemporary,
                                acceptedPolicyJson = kotlinx.serialization.json.buildJsonObject {
                                    put("features", kotlinx.serialization.json.Json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings.serializer(), featureSettings.value))
                                    put("chat", kotlinx.serialization.json.Json.encodeToJsonElement(dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig.serializer(), dispatchTools))
                                }.toString(),
                                sourceScope = acceptedResearch?.contentScope ?: researchSettings?.scope?.name.orEmpty(),
                                targetSources = acceptedResearch?.sourceGoal?.coerceAtMost(Int.MAX_VALUE.toLong())?.toInt() ?: if (researchSettings?.researchToggle == true && researchSettings.depth > 0) platforms.maxOfOrNull { dev.chungjungsoo.gptmobile.data.model.effectiveResearchPlan(featureSettings.value, it.value.uid, researchSettings).sourceGoal } ?: 0 else 0,
                                requestedModels = platforms.associate { it.value.uid to it.value.model }
                            )
                        }
                        agentRunCoordinator.start(
                            platforms.mapIndexed { runIndex, (_, platform) ->
                                AgentRunRequest(
                                    runId = runs[runIndex].runId,
                                    chatId = persisted.chatRoom.id,
                                    assistantMessage = persisted.assistantMessages[runIndex],
                                    platform = platform,
                                    userMessages = contextMessages.userMessages,
                                    assistantMessages = contextMessages.assistantMessages,
                                    chatToolConfig = dispatchTools.copy(
                                        combined = dispatchTools.combined?.forRole(
                                            dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.CONTRIBUTOR,
                                            platform.uid
                                        )
                                    )
                                )
                            }
                        )
                    },
                    onFailure = { error ->
                        showPersistenceFailure(turnIndex, platforms.map { it.index }, error)
                    }
                )
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val user = _groupedMessages.value.userMessages.getOrNull(turnIndex)
                if (user != null && user.id <= 0 && question.text.isEmpty()) question.edit { append(user.content) }
                _attachmentNotice.value = error.message ?: "The accepted request could not start. Your question is kept."
                showPersistenceFailure(turnIndex, enabledPlatformsInChat.indices.toList(), error)
            } finally {
                pendingRunDispatches -= 1
                syncLoadingStates(_agentRunsById.value)
                drainPromptQueueIfIdle()
            }
        }.invokeOnCompletion { readinessLease.close() }
    }

    private fun showPersistenceFailure(turnIndex: Int, platformIndexes: List<Int>, error: Throwable) {
        val message = error.message ?: "Failed to save this turn."
        _groupedMessages.update { groupedMessages ->
            platformIndexes.fold(groupedMessages) { current, platformIndex ->
                updateAssistantSlot(current, turnIndex, platformIndex) { assistantMessage ->
                    assistantMessage.copy(
                        content = buildAssistantErrorContent(assistantMessage.content, message),
                        createdAt = currentTimeStamp
                    )
                }
            }
        }
        _loadingStates.update { states ->
            states.toMutableList().apply {
                platformIndexes.forEach { index ->
                    if (index in indices) this[index] = LoadingState.Idle
                }
            }
        }
    }

    private fun updateMessageEditAttachments(attachments: List<ChatAttachmentDraft>) {
        _messageEditSession.update { session ->
            session?.copy(attachments = attachments)
        }
    }

    private fun addDraftFile(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        updateAttachments: (List<ChatAttachmentDraft>) -> Unit,
        filePath: String,
        onNotice: (String?) -> Unit = {}
    ) {
        if (currentAttachments().any { it.sourceFilePath == filePath }) return

        updateAttachments(currentAttachments() + ChatAttachmentDraft(sourceFilePath = filePath))
        preprocessDraftAttachment(
            currentAttachments = currentAttachments,
            updateAttachments = updateAttachments,
            filePath = filePath,
            onNotice = onNotice
        )
    }

    private fun removeDraftFile(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        updateAttachments: (List<ChatAttachmentDraft>) -> Unit,
        filePath: String
    ) {
        val removedAttachment = currentAttachments().firstOrNull { it.sourceFilePath == filePath }
        removedAttachment?.preparedFilePath?.let { AttachmentPayloadCache.remove(it) }
        if (removedAttachment?.cleanupOnDiscard == true) {
            removedAttachment.let(::deleteDraftFiles)
        }
        updateAttachments(currentAttachments().filter { it.sourceFilePath != filePath })
    }

    private fun preprocessDraftAttachment(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        updateAttachments: (List<ChatAttachmentDraft>) -> Unit,
        filePath: String,
        onNotice: (String?) -> Unit = {}
    ) {
        viewModelScope.launch {
            val mimeType = withContext(Dispatchers.IO) {
                FileUtils.getMimeType(context, filePath)
            }

            if (!FileUtils.isSupportedUploadMimeType(mimeType)) {
                rejectDraftAttachment(
                    currentAttachments = currentAttachments,
                    updateAttachments = updateAttachments,
                    filePath = filePath,
                    notice = "Unsupported attachment type. Use images, PDF, Word, Excel, PowerPoint, text, CSV, JSON, XML or Markdown files."
                )
                return@launch
            }

            val fileSize = withContext(Dispatchers.IO) {
                FileUtils.getFileSize(context, filePath)
            }

            if (fileSize > FileUtils.MAX_UPLOAD_SIZE_BYTES) {
                rejectDraftAttachment(
                    currentAttachments = currentAttachments,
                    updateAttachments = updateAttachments,
                    filePath = filePath,
                    notice = "Files larger than 50 MB cannot be attached."
                )
                return@launch
            }

            val currentDraftBytes = withContext(Dispatchers.IO) {
                currentAttachments()
                    .filter { it.sourceFilePath != filePath }
                    .sumOf { FileUtils.getFileSize(context, it.sourceFilePath).coerceAtLeast(0L) }
            }

            if (FileUtils.wouldExceedTotalUploadLimit(currentDraftBytes, fileSize)) {
                rejectDraftAttachment(
                    currentAttachments = currentAttachments,
                    updateAttachments = updateAttachments,
                    filePath = filePath,
                    notice = "Total attachments cannot exceed 50 MB."
                )
                return@launch
            }

            val preparationResult = try {
                withContext(Dispatchers.IO) { attachmentUploadCoordinator.prepareLocalAttachment(context, filePath) }
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                rejectDraftAttachment(currentAttachments, updateAttachments, filePath, error.message ?: "Could not read this document.")
                return@launch
            }

            if (currentAttachments().none { it.sourceFilePath == filePath }) {
                if (preparationResult != null && preparationResult.preparedFilePath != filePath) {
                    java.io.File(preparationResult.preparedFilePath).delete()
                }
                return@launch
            }

            updateAttachments(
                currentAttachments().map { attachment ->
                    if (attachment.sourceFilePath != filePath) {
                        attachment
                    } else if (preparationResult == null) {
                        attachment.copy(
                            status = ChatAttachmentDraft.Status.Failed,
                            errorMessage = "Failed to prepare attachment."
                        )
                    } else {
                        attachment.copy(
                            attachment = preparationResult,
                            preparedFilePath = preparationResult.preparedFilePath,
                            mimeType = preparationResult.mimeType,
                            status = ChatAttachmentDraft.Status.Ready,
                            cleanupOnDiscard = true,
                            notice = if (preparationResult.wasResized) {
                                "Large images are resized before upload."
                            } else {
                                null
                            },
                            errorMessage = null
                        )
                    }
                }
            )

            if (preparationResult?.wasResized == true) {
                onNotice("Large images are resized before upload.")
            } else if (preparationResult == null) {
                onNotice("Failed to prepare attachment.")
            }
        }
    }

    private fun sendQuestion(
        questionText: String,
        attachments: List<ChatAttachmentDraft>,
        clearComposer: Boolean = true,
        profileUid: String? = null,
        acceptedTools: ChatMcpToolConfig = captureCombinedTools(questionText)
    ) {
        MessageV2(
            chatId = chatRoomId,
            content = questionText,
            attachments = attachments.mapNotNull { it.attachment },
            platformType = null,
            createdAt = currentTimeStamp
        ).let { addMessage(it) }
        if (clearComposer) {
            question.clearText()
            clearSelectedFiles()
        }
        profileUid?.let { uid ->
            val index = enabledPlatformsInChat.indexOf(uid)
            if (index >= 0) _indexStates.update { states -> states.dropLast(1) + index }
        }
        completeChat(oneMessageUid = profileUid, acceptedTools = acceptedTools)
    }

    private fun rejectDraftAttachment(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        updateAttachments: (List<ChatAttachmentDraft>) -> Unit,
        filePath: String,
        notice: String
    ) {
        val rejectedAttachment = currentAttachments().firstOrNull { it.sourceFilePath == filePath }
        rejectedAttachment?.preparedFilePath?.let { AttachmentPayloadCache.remove(it) }
        if (rejectedAttachment?.cleanupOnDiscard == true) {
            rejectedAttachment.let(::deleteDraftFiles)
        }
        updateAttachments(currentAttachments().filter { it.sourceFilePath != filePath })
        _attachmentNotice.update { notice }
    }

    private fun deleteDraftFiles(attachment: ChatAttachmentDraft) {
        if (!attachment.cleanupOnDiscard) return
        java.io.File(attachment.sourceFilePath).delete()
        attachment.preparedFilePath
            ?.takeIf { it != attachment.sourceFilePath }
            ?.let { java.io.File(it).delete() }
    }

    /**
     * Assistant revisions are stored newest-first: revisions[0] is the newest
     * saved answer, and ACTIVE_REVISION_LATEST points at the live content.
     */
    private fun updateAssistantRevisionSelection(
        turnIndex: Int,
        platformIndex: Int,
        nextIndex: (MessageV2) -> Int
    ) {
        _groupedMessages.update {
            updateAssistantSlot(
                groupedMessages = it,
                turnIndex = turnIndex,
                platformIndex = platformIndex
            ) { message ->
                message.selectRevision(nextIndex(message))
            }
        }
        persistCurrentChatSnapshot()
    }

    private fun formatCurrentDateTime(): String {
        val currentDate = java.util.Date()
        val format = java.text.SimpleDateFormat("yyyy-MM-dd hh:mm a", java.util.Locale.getDefault())
        return format.format(currentDate)
    }

    private suspend fun fetchMessages() {
        // If the room isn't new
        if (chatRoomId != 0) {
            _groupedMessages.update { fetchGroupedMessages(chatRoomId) }
            if (_groupedMessages.value.assistantMessages.size != _indexStates.value.size) {
                _indexStates.update { List(_groupedMessages.value.assistantMessages.size) { 0 } }
            }
            _isLoaded.update { true } // Finish fetching
            return
        }

        // When message id should sync after saving chats
        if (_chatRoom.value.id != 0) {
            _groupedMessages.update { fetchGroupedMessages(_chatRoom.value.id) }
            return
        }
    }

    private suspend fun fetchGroupedMessages(chatId: Int): GroupedMessages {
        val messages = chatRepository.fetchMessageReferencesV2(chatId).sortedBy { it.createdAt }
        return groupPersistedMessages(messages, enabledPlatformsInChat, chatId)
    }

    private data class ReadinessDraftInput(
        val text: String,
        val composing: Boolean,
        val hasAttachments: Boolean,
        val room: ChatRoomV2,
        val profiles: List<PlatformV2>,
        val features: dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
    )

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeComposerDraft() {
        viewModelScope.launch {
            combine(
                androidx.compose.runtime.snapshotFlow { question.text.toString() to (question.composition != null) },
                _selectedAttachments,
                _chatRoom.distinctUntilChangedBy { listOf(it.id, it.isTemporary, it.parentChatId, it.branchMessageId, it.activePlatform) },
                _enabledPlatformsInApp,
                settingRepository.observeFeatureSettings()
            ) { draft, attachments, room, profiles, features ->
                ReadinessDraftInput(draft.first, draft.second, attachments.isNotEmpty(), room, profiles, features)
            }.collectLatest { input ->
                memoryPreview?.clear()
                val room = input.room
                val features = input.features
                if (leavingConversation || !composerRestored || room.id <= 0 || room.isTemporary || input.composing || input.hasAttachments || !dev.chungjungsoo.gptmobile.presentation.AppForegroundTracker.visibility.value || !features.readiness.enabled || !features.readiness.predictMemory) return@collectLatest
                val profile = input.profiles.firstOrNull { it.uid in room.activePlatform && it.enabled } ?: return@collectLatest
                if (profile.excludesMemory()) return@collectLatest
                memoryPreview?.draft(room.id, profile.uid, input.text, profile.isPrivateDestination(), profile.compatibleType == ClientType.LITERT_LM, _groupedMessages.value.userMessages.takeLast(2).joinToString("\n") { it.content.takeLast(1000) })
            }
        }
        viewModelScope.launch {
            combine(
                androidx.compose.runtime.snapshotFlow { question.text.toString() },
                _selectedAttachments
            ) { text, attachments -> text to attachments }
                .debounce(250)
                .collect { (text, attachments) ->
                    val id = _chatRoom.value.id
                    if (!leavingConversation && composerRestored && id > 0 && attachments.none { it.status == ChatAttachmentDraft.Status.Preparing }) {
                        val encoded = kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ChatAttachment.serializer()), attachments.mapNotNull { it.attachment })
                        draftMutex.withLock {
                            chatRepository.saveComposerDraft(id, text.takeIf(String::isNotEmpty), encoded, System.currentTimeMillis() / 1000)
                            _chatRoom.update { it.copy(draftText = text, draftAttachments = encoded) }
                        }
                    }
                }
        }
    }

    private fun fetchChatRoom() {
        viewModelScope.launch {
            conversationExitWork.awaitPending(chatRoomId)
            if (chatRoomId > 0) {
                try {
                    combinedSettingsJournal.recover(chatRoomId) { before, after -> chatRepository.applyCombinedChatSettings(before, after) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    _combinedSettingsSaveState.value = dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSaveUiState(dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.FAILED, error.message)
                }
            }
            val room = if (chatRoomId == 0) {
                ChatRoomV2(
                    id = 0,
                    title = "Untitled Chat",
                    enabledPlatform = initialPlatformUids,
                    activePlatform = initialPlatformUids,
                    conversationMode = requestedConversationMode
                )
            } else {
                (chatRepository.fetchChatListV2() + chatRepository.fetchArchivedChatListV2()).first { it.id == chatRoomId }
            }
            if (room.id > 0) {
                _combinedConversationIdentity.value = settingRepository.ensureConversationIdentity(room.id)
                savedStateHandle["combinedConversationIdentity"] = _combinedConversationIdentity.value
            }
            val features = settingRepository.getFeatureSettings()
            _chatToolConfig.update {
                val workspace = features.conversationGitHub[room.id]
                it.copy(
                    delegation = features.conversationDelegation[room.id],
                    reasoning = features.conversationReasoning[room.id],
                    research = features.conversationResearch[room.id],
                    github = workspace,
                    allowAllByDefault = if (workspace != null) false else it.allowAllByDefault,
                    enabledToolIds = workspace?.let { scope -> setOf(scope.connectionUid) } ?: it.enabledToolIds
                )
            }
            _chatRoom.value = room
            dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.setPrivateSession(diagnosticPrivacyOwner, room.isTemporary)
            applyChatPlatformState(room)
            if (!composerRestored) {
                if (question.text.isEmpty()) question.edit { append(room.draftText.orEmpty()) }
                val restored = runCatching { kotlinx.serialization.json.Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ChatAttachment.serializer()), room.draftAttachments) }.getOrDefault(emptyList())
                _selectedAttachments.value = restored.map(ChatAttachmentDraft::fromAttachment)
                composerRestored = true
                observeComposerDraft()
            }
            if (_platformsInApp.value.isNotEmpty()) initializeChatPlatformModels(_platformsInApp.value)
        }
    }

    private fun applyChatPlatformState(room: ChatRoomV2) {
        val slots = room.enabledPlatform.filter(String::isNotBlank).distinct()
        val active = room.activePlatform
            .filter(String::isNotBlank)
            .distinct()
            .ifEmpty { slots }
            .filter { it in slots }

        platformSlotUids.value = slots
        _activePlatformUids.value = active
        _disabledPlatformUids.update { disabled -> disabled.intersect(active.toSet()) }
        _loadingStates.update { current ->
            List(slots.size) { index -> current.getOrElse(index) { LoadingState.Idle } }
        }
        _groupedMessages.update { grouped ->
            grouped.copy(
                assistantMessages = grouped.assistantMessages.map { row ->
                    normalizeAssistantRow(row, slots, room.id.coerceAtLeast(chatRoomId))
                }
            )
        }
    }

    private fun fetchEnabledPlatformsInApp() {
        viewModelScope.launch {
            val allPlatforms = settingRepository.fetchPlatformV2s()

            _platformsInApp.update { allPlatforms }
            initializeChatPlatformModels(allPlatforms)
            updateLocalNetworkRequirement(allPlatforms)
            _enabledPlatformsInApp.update { allPlatforms.filter { it.enabled } }
            viewModelScope.launch(Dispatchers.Default) {
                try {
                    val palette = settingRepository.getFeatureSettings().profileAttributionColors
                    if (allPlatforms.any { it.uid !in palette }) {
                        settingRepository.mutateFeatureSettings { current ->
                            current.copy(profileAttributionColors = dev.chungjungsoo.gptmobile.data.model.ProfileColorRegistry.ensure(current.profileAttributionColors, allPlatforms.map { it.uid }))
                        }
                    }
                } catch (cancelled: kotlinx.coroutines.CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    // Optional display identity persistence must not block profile loading or generation.
                }
            }
        }
    }

    private suspend fun updateLocalNetworkRequirement(platforms: List<PlatformV2>) {
        val selectedProfiles = platforms.filter { it.uid in _activePlatformUids.value }
        val providerNeedsAccess = selectedProfiles.any { requiresLocalNetworkAccess(it.apiUrl) }
        val requiresAccess = determineLocalNetworkAccessRequirement(
            providerNeedsAccess = providerNeedsAccess,
            toolNeedsAccess = {
                _activePlatformUids.value.any { profileUid ->
                    toolConnectionRepository.listBindingsWithConnections(profileUid).any { binding ->
                        binding.connection?.endpointUrl?.let(::requiresLocalNetworkAccess) == true
                    }
                }
            },
            onLookupFailure = {
                _attachmentNotice.update { context.getString(R.string.local_network_check_failed) }
            }
        )
        _needsLocalNetworkAccess.update { requiresAccess }
    }

    private suspend fun initializeChatPlatformModels(platforms: List<PlatformV2>) {
        val defaultModels = enabledPlatformsInChat.associateWith { uid ->
            platforms.firstOrNull { it.uid == uid }?.model ?: ""
        }
        val persistedModels = if (chatRoomId != 0) {
            chatRepository.fetchChatPlatformModels(chatRoomId)
        } else {
            emptyMap()
        }

        val mergedModels = defaultModels.mapValues { (uid, defaultModel) ->
            persistedModels[uid]?.takeIf { it.isNotBlank() } ?: defaultModel
        }

        _chatPlatformModels.update { mergedModels }
        modelsRestored = _chatRoom.value.id > 0
        resumeBranchIfReady()

        val operation = featureSettings.value.combinedSettingsJournal[chatRoomId]
        if (chatRoomId != 0 && mergedModels != persistedModels && (operation == null || operation.roomSettled || operation.phase == dev.chungjungsoo.gptmobile.data.model.CombinedSettingsSavePhase.COMMITTED)) {
            chatRepository.saveChatPlatformModels(chatRoomId, mergedModels)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observePersistedMessages() {
        viewModelScope.launch {
            StartupRecoveryGate.await()
            _chatRoom
                .map { it.id }
                .distinctUntilChanged()
                .flatMapLatest { chatId ->
                    if (chatId > 0) {
                        visibleHistoryTurns.flatMapLatest { turns ->
                            combine(chatRepository.observeMessageWindow(chatId, if (targetMessageId > 0) Int.MAX_VALUE else turns), agentRunCoordinator.streamMessages, chatRepository.observeTurnCount(chatId)) { messages, live, total ->
                                windowStartId = messages.minOfOrNull { it.id } ?: 0
                                olderHistoryAvailable.value = total > messages.count { it.platformType == null }
                                chatId to messages.map { message -> live[message.id]?.takeIf { it.currentRunId == message.currentRunId && it.ownershipFence == message.ownershipFence } ?: message }
                            }
                        }
                    } else {
                        flowOf(chatId to emptyList())
                    }
                }
                .collect { (chatId, messages) ->
                    if (chatId <= 0) return@collect
                    val slots = enabledPlatformsInChat.toList()
                    pendingMessageGrouping += 1
                    val groupedMessages = try {
                        withContext(messageGroupingDispatcher) { groupPersistedMessages(messages, slots, chatId) }
                    } finally {
                        pendingMessageGrouping -= 1
                    }
                    _groupedMessages.update { groupedMessages }
                    _indexStates.update { current ->
                        List(groupedMessages.assistantMessages.size) { index -> current.getOrElse(index) { 0 } }
                    }
                    advancePendingGeneration()
                    _isLoaded.update { true }
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeAgentRuns() {
        viewModelScope.launch {
            StartupRecoveryGate.await()
            _chatRoom
                .map { it.id }
                .distinctUntilChanged()
                .flatMapLatest { chatId ->
                    if (chatId > 0) chatRepository.observeAgentRuns(chatId) else flowOf(emptyList())
                }
                .collect { runs ->
                    val runsById = runs.associateBy(AgentRun::runId)
                    _agentRunsById.update { runsById }
                    _runNoticesById.update { current ->
                        pruneTransientChatRunNotices(
                            current,
                            runsById = runsById,
                            activeRunIds = agentRunCoordinator.activeRuns.value.keys
                        )
                    }
                    advancePendingGeneration()
                }
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeProviderWaitDeadlines() {
        viewModelScope.launch {
            StartupRecoveryGate.await()
            _chatRoom
                .map { it.id }
                .distinctUntilChanged()
                .flatMapLatest { chatId ->
                    if (chatId > 0) chatRepository.observeProviderWaitDeadlines(chatId) else flowOf(emptyList())
                }
                .collect { deadlines ->
                    providerWaitDeadlinesByRun.value = deadlines.associateBy(ProviderWaitDeadline::runId)
                    refreshCombinedFailures()
                }
        }
    }

    private fun maybeStartCombinedSynthesis(runsById: Map<String, AgentRun>) {
        val room = _chatRoom.value
        val slotUids = enabledPlatformsInChat
        if (room.conversationMode != ConversationMode.COMBINED || slotUids.isEmpty()) return

        val grouped = _groupedMessages.value
        grouped.userMessages.indices.forEach { turnIndex ->
            val userId = grouped.userMessages[turnIndex].id
            // Optimistic, unsaved turns share ID 0. They have no durable owner
            // and must never consume the one-shot synthesis reservation.
            if (userId <= 0 || userId in combinedSynthesisMessages) return@forEach
            val accepted = acceptedCombinedTurn(userId, runsById)
            if (accepted != null) {
                if (accepted.context.requested.singlePass) {
                    val responses = grouped.assistantMessages.getOrNull(turnIndex).orEmpty()
                    val excluded = combinedCollectionDecisions?.decisions?.value?.get(accepted.context.rootBudgetId)?.excludedProfileUids.orEmpty()
                    if (responses.any { it.isCombinedSynthesis() || it.platformType in _activePlatformUids.value && it.platformType !in excluded && runsById[it.currentRunId]?.status == AgentRunStatus.CANCELED }) return@forEach
                    combinedSynthesisMessages += userId
                    viewModelScope.launch {
                        try {
                            agentRunCoordinator.resumeCombinedCollection(room.id, userId, accepted.context.rootBudgetId)
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (failure: Exception) {
                            _attachmentNotice.value = failure.message ?: "Combined input could not be prepared. Saved replies are retained."
                        } finally {
                            combinedSynthesisMessages -= userId
                        }
                    }
                }
                return@forEach
            }
            val activeCombinedUids = accepted?.context?.requested?.participantUids?.toSet()
                ?: _activePlatformUids.value.filterNot { it in _disabledPlatformUids.value }.toSet()
            val activeSlotIndexes = slotUids.mapIndexedNotNull { index, uid -> index.takeIf { uid in activeCombinedUids } }
            if (activeSlotIndexes.size != activeCombinedUids.size || activeSlotIndexes.isEmpty()) return@forEach
            val row = grouped.assistantMessages.getOrNull(turnIndex).orEmpty()
            if (row.any { it.timeline.any { item -> item.combinedTurn?.deliveryStatus == "MERGE_UNAVAILABLE" } }) return@forEach
            val activeRow = activeSlotIndexes.mapNotNull { row.getOrNull(it) }
            if (activeRow.size != activeSlotIndexes.size) return@forEach
            val sources = combinedSynthesisInputs(activeRow, runsById, _platformsInApp.value.associate { it.uid to it.name }, accepted?.context?.requested?.requestedModels ?: _chatPlatformModels.value, grouped.userMessages[turnIndex].content)
                ?: return@forEach
            if (!combinedSynthesisAttempts.reserve(userId, sources)) return@forEach
            combinedSynthesisMessages += userId
            pendingRunDispatches += 1
            viewModelScope.launch {
                var committed = false
                var scheduleRetry = false
                try {
                    val dispatched = startCombinedSynthesis(turnIndex, sources, onPersisted = { committed = true })
                    if (!dispatched && !committed) scheduleRetry = combinedSynthesisAttempts.abandonUncommitted(userId, sources)
                } catch (error: CancellationException) {
                    // Parent/user cancellation is not an invitation to automatically
                    // restart generation; an explicit retry or later state change can recover.
                    if (!committed) combinedSynthesisAttempts.abandonUncommitted(userId, sources)
                    throw error
                } catch (error: Throwable) {
                    // A durable run can never be automatically replayed after UI/provider failure.
                    if (!committed) scheduleRetry = combinedSynthesisAttempts.abandonUncommitted(userId, sources)
                    _attachmentNotice.update {
                        error.message?.takeIf(String::isNotBlank)
                            ?: "Could not combine the model responses."
                    }
                } finally {
                    combinedSynthesisMessages -= userId
                    pendingRunDispatches -= 1
                    syncLoadingStates(_agentRunsById.value)
                    drainPromptQueueIfIdle()
                    if (scheduleRetry) {
                        val chatId = room.id
                        // One delayed state recheck, at most three per unchanged source
                        // revision. No retry is permitted after a persisted run.
                        viewModelScope.launch {
                            delay(5_000L)
                            if (_chatRoom.value.id == chatId && _chatRoom.value.conversationMode == ConversationMode.COMBINED) {
                                advancePendingGeneration()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun acceptedCombinedTurn(
        userId: Int,
        runs: Map<String, AgentRun> = _agentRunsById.value
    ): dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn? {
        val turnIndex = _groupedMessages.value.userMessages.indexOfFirst { it.id == userId }
        val currentRunIds = _groupedMessages.value.assistantMessages.getOrNull(turnIndex).orEmpty().mapNotNull { it.currentRunId }.toSet()
        val records = runs.values.filter { it.userMessageId == userId && it.runId in currentRunIds && it.synthesisKey == null }
            .mapNotNull { dev.chungjungsoo.gptmobile.data.agent.CombinedAcceptedTurn.decode(it.synthesisSnapshot) }
        if (records.map { it.context.requested.requestedPolicyDigest }.distinct().size > 1) return null
        return records.firstOrNull()
    }

    private suspend fun startCombinedSynthesis(
        turnIndex: Int,
        sources: List<CombinedModelResponse>,
        onPersisted: () -> Unit
    ): Boolean {
        val grouped = _groupedMessages.value
        val userMessage = grouped.userMessages.getOrNull(turnIndex) ?: return false
        val accepted = acceptedCombinedTurn(userMessage.id)
        if (accepted != null && chatRepository.isCombinedTurnCancelled(accepted.context.rootBudgetId)) return false
        val combined = accepted?.context?.copy(chatId = userMessage.chatId)
        val pinnedPrimary = combined?.requested?.preferredOwnerUid ?: _chatRoom.value.combinedPrimaryUid ?: _chatRoom.value.activePlatform.firstOrNull()
        val acceptedUids = combined?.requested?.participantUids ?: sources.map { it.platformUid }.distinct()
        val candidates = CombinedOwnerResolver.eligibleOrder(
            sources,
            acceptedUids,
            pinnedPrimary,
            _platformsInApp.value.filter { it.enabled }.mapTo(hashSetOf()) { it.uid }
        ).mapNotNull { uid ->
            _platformsInApp.value.firstOrNull { it.uid == uid && it.enabled }?.let { profile ->
                val capturedModel = sources.firstOrNull { it.platformUid == uid }?.modelName?.takeIf(String::isNotBlank)
                resolvePlatformModel(profile).let {
                    it.copy(
                        model = capturedModel ?: it.model,
                        temperature = if (combined == null) it.temperature else combined.requested.temperatureFor(it.uid, it.temperature)
                    )
                }
            }
        }
        val leadPlatform = dev.chungjungsoo.gptmobile.data.agent.CombinedWriterPolicy.rank(candidates.filter { sources.none { it.localOnly } || it.compatibleType == dev.chungjungsoo.gptmobile.data.model.ClientType.LITERT_LM }, userMessage.content, pinnedPrimary, dev.chungjungsoo.gptmobile.data.context.ContextBudgetService.estimate(sources.joinToString("\n") { it.content })).firstOrNull()
        if (leadPlatform == null) {
            combined?.let { chatRepository.markCombinedMergeUnavailable(userMessage.chatId, userMessage.id, it.rootBudgetId) }
            if (sources.any { it.localOnly }) {
                _attachmentNotice.update { "These responses contain local-only memory. Choose a local AI profile to combine them; the individual responses remain available." }
                return false
            }
            val coolingProvider = candidates.firstNotNullOfOrNull { platform ->
                if (platform.compatibleType != dev.chungjungsoo.gptmobile.data.model.ClientType.FREE) return@firstNotNullOfOrNull null
                val provider = dev.chungjungsoo.gptmobile.data.model.FreeAiProvider.fromApiUrl(platform.apiUrl) ?: return@firstNotNullOfOrNull null
                val retryAt = dev.chungjungsoo.gptmobile.data.network.FreeAiRequestLimiter.shared.cooldownUntilMillis(provider, platform.model)
                    ?: return@firstNotNullOfOrNull null
                provider to retryAt
            }
            _attachmentNotice.update {
                coolingProvider?.let { (provider, retryAt) ->
                    "Combined synthesis is paused because ${provider.displayName} is cooling down. Try again in ${((retryAt - System.currentTimeMillis()).coerceAtLeast(0) + 999) / 1000} seconds; no synthesis request was sent."
                } ?: "Combined synthesis could not start because no selected, usable contributor is currently available to finish it. The individual responses remain available."
            }
            return false
        }
        val leadUid = leadPlatform.uid
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "OWNER_SELECTED · preferred=$pinnedPrimary · selected=$leadUid · reason=${if (leadUid == pinnedPrimary) "HEALTHY_PRIMARY" else "HEALTHY_SELECTED_FALLBACK"} · usable=${sources.size}"
        )
        val leadIndex = enabledPlatformsInChat.indexOf(leadUid).takeIf { it >= 0 } ?: return false
        val leadMessage = grouped.assistantMessages.getOrNull(turnIndex)?.getOrNull(leadIndex) ?: return false
        if (leadMessage.id <= 0) return false

        val synthesisRunId = COMBINED_RUN_PREFIX + UUID.randomUUID().toString()

        val normalizedResearchTask = dev.chungjungsoo.gptmobile.data.research.researchHash(userMessage.content.trim())
        val researchTurnId = "${leadMessage.chatId}:${userMessage.id}"
        val turnSessions = researchSessionStore?.exportTurn(leadMessage.chatId, setOf(researchTurnId)).orEmpty().filter { session ->
            session.turnId == researchTurnId &&
                dev.chungjungsoo.gptmobile.data.research.researchHash(session.snapshot.task.trim()) == normalizedResearchTask
        }
        // Never widen a source partition just because a contributor finished later.
        val leadAuthority = turnSessions.lastOrNull { it.runId == leadMessage.currentRunId }?.authority
        val sharedEvidence = dev.chungjungsoo.gptmobile.data.research.ResearchCorpus.freezeAll(
            dev.chungjungsoo.gptmobile.data.research.authorizedResearchTransfer(turnSessions, leadAuthority)
        )
        val turnRuns = _agentRunsById.value.values.filter { it.chatId == userMessage.chatId && it.userMessageId == userMessage.id && !it.runId.startsWith(COMBINED_RUN_PREFIX) }
        val turnRunIds = turnRuns.mapTo(hashSetOf()) { it.runId }
        // Read the committed event rows rather than racing the UI's asynchronous tool-event snapshot.
        val turnEvents = chatRepository.observeToolEvents(userMessage.chatId).first().filter { it.runId in turnRunIds }
        val evidenceLedger = withContext(Dispatchers.Default) {
            dev.chungjungsoo.gptmobile.data.agent.CombinedEvidenceLedger.freeze(sharedEvidence, turnEvents, turnRuns.associate { it.runId to it.profileUid })
        }
        val turnContext = dev.chungjungsoo.gptmobile.data.agent.CombinedTurnContext.capture(userMessage.createdAt)
        var attributedSources = combinedSourcesWithEvidence(sources, sharedEvidence).map { source ->
            val owned = evidenceLedger.records.filter { source.platformUid in it.profileUids || source.content.contains(it.url) }
            source.copy(sourceIds = (source.sourceIds + owned.map { it.id }).distinct(), evidenceRefs = (source.evidenceRefs + owned.map { it.url }).distinct())
        }
        val fullHistory = chatRepository.fetchMessagesV2(userMessage.chatId)
        require(fullHistory.any { it.id == userMessage.id && it.platformType == null }) { "The Combined user turn is no longer available." }
        val priorUsers = fullHistory.filter { it.platformType == null }.takeWhile { it.id != userMessage.id }
        val historicalUsers = dev.chungjungsoo.gptmobile.data.agent.CombinedTaskContract.relevantHistoryIndices(userMessage.content, priorUsers.map { it.content }).map { priorUsers[it] }
        val historicalReplies = historicalUsers.map { prior -> fullHistory.filter { it.linkedMessageId == prior.id && it.platformType != null } }
        val selectedUids = acceptedUids
        val answerContract = dev.chungjungsoo.gptmobile.data.agent.CombinedAnswerPolicy.from(userMessage.content, historicalUsers.takeIf { it.isNotEmpty() }?.joinToString("\n") { it.content })
        val editorialContext = combined?.forRole(dev.chungjungsoo.gptmobile.data.agent.CombinedRunRole.SYNTHESIS, leadUid)
        val (synthesisPrompt, synthesisSnapshot) = withContext(Dispatchers.Default) {
            val prompt = combinedSynthesisPrompt(userMessage.content, attributedSources, sharedEvidence, evidenceLedger, turnContext, combined = editorialContext, answerContract = answerContract)
            val lineage = dev.chungjungsoo.gptmobile.data.agent.ContributionLineage.freeze(selectedUids, attributedSources)
            val inventory = dev.chungjungsoo.gptmobile.data.agent.CombinedClaimInventory.freeze(attributedSources)
            prompt to kotlinx.serialization.json.Json.encodeToString(
                dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot(
                    version = if (combined == null) 4 else 5,
                    deliveryProtocol = "PASSAGES_V2",
                    evidence = evidenceLedger,
                    turnContext = turnContext,
                    userMessage = userMessage,
                    history = historicalUsers,
                    replies = historicalReplies,
                    sources = attributedSources,
                    prompt = prompt,
                    profileUid = leadPlatform.uid,
                    model = leadPlatform.model,
                    temperature = leadPlatform.temperature,
                    systemPrompt = leadPlatform.systemPrompt.orEmpty(),
                    configKey = dev.chungjungsoo.gptmobile.data.benchmark.benchmarkConfigKey(leadPlatform),
                    lineage = lineage,
                    claimInventory = inventory,
                    combined = editorialContext,
                    answerContract = answerContract,
                    revisionId = UUID.randomUUID().toString(),
                    acceptedTools = accepted?.tools
                )
            )
        }
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_INPUT_FROZEN · trigger=LEGACY_STATE_HANDOFF · runId=$synthesisRunId · sourceCount=${attributedSources.size} · profileUids=${attributedSources.map { it.platformUid }.distinct().joinToString(",")} · candidateUnits=${dev.chungjungsoo.gptmobile.data.agent.CombinedClaimSemantics.records(attributedSources).size} · promptChars=${synthesisPrompt.length} · protocol=PASSAGES_V2"
        )
        val run = AgentRunDraft(
            runId = synthesisRunId,
            profileUid = leadPlatform.uid,
            providerSnapshot = leadPlatform.compatibleType.name,
            modelSnapshot = leadPlatform.model,
            synthesisKey = dev.chungjungsoo.gptmobile.data.research.researchHash(synthesisSnapshot),
            synthesisSnapshot = synthesisSnapshot,
            createdAt = currentTimeStamp
        )

        val persisted = chatRepository.persistAgentRetry(
            PersistAgentRetryRequest(
                userMessage = userMessage,
                assistantMessage = leadMessage.copy(combinedSources = attributedSources),
                run = run,
                freezeCombinedSources = true
            )
        )
        // The durable run now owns this answer slot. No automatic retry may
        // replace it, even if UI publication or provider dispatch subsequently fails.
        onPersisted()
        dev.chungjungsoo.gptmobile.data.diagnostics.AppLogRecorder.record(
            "CombinedSynthesis",
            "SYNTHESIS_RUN_PERSISTED · runId=$synthesisRunId · leadProfileUid=$leadUid · sourceCount=${attributedSources.size} · evidenceRecords=${evidenceLedger.records.size}"
        )

        _agentRunsById.update { it + (persisted.run.runId to persisted.run) }
        _groupedMessages.update { current ->
            updateAssistantSlot(current, turnIndex, leadIndex) { persisted.assistantMessage }
        }
        _loadingStates.update {
            List(enabledPlatformsInChat.size) { index ->
                if (index == leadIndex) LoadingState.Loading else LoadingState.Idle
            }
        }

        // The primary edits all original contributions in one pass. The normal run
        // coordinator owns streaming, cancellation, usage, persistence and continuation.
        agentRunCoordinator.start(
            listOf(
                AgentRunRequest(
                    runId = persisted.run.runId,
                    chatId = persisted.assistantMessage.chatId,
                    assistantMessage = persisted.assistantMessage,
                    platform = leadPlatform.copy(
                        systemPrompt = listOfNotNull(leadPlatform.systemPrompt, COMBINED_SYNTHESIS_INSTRUCTION).joinToString("\n\n"),
                        disableAllTools = true,
                        disableLocalTools = true
                    ),
                    userMessages = historicalUsers + userMessage,
                    assistantMessages = historicalReplies + listOf(emptyList()),
                    chatToolConfig = (accepted?.tools ?: _chatToolConfig.value).copy(
                        allToolsDisabled = true,
                        delegation = dev.chungjungsoo.gptmobile.data.model.ConversationDelegationSettings(enabled = false),
                        combined = editorialContext
                    ),
                    stage = kotlinx.serialization.json.Json.decodeFromString<dev.chungjungsoo.gptmobile.data.agent.CombinedInputSnapshot>(synthesisSnapshot).bindAcceptedStage()
                )
            )
        )
        return true
    }

    private fun checkAndGenerateAiTitle(runsById: Map<String, AgentRun>) {
        val room = _chatRoom.value
        if (!featureSettings.value.automaticConversationTitles) return
        if (room.id <= 0 || room.isTitleCustomized) return

        val grouped = _groupedMessages.value
        val userTurnCount = grouped.userMessages.size
        if (userTurnCount <= 0) return

        // Automatic subjects are a one-shot operation. Existing generated/manual subjects
        // must not be refreshed when the conversation is reopened.
        if (lastAutoTitleUserTurnCount != 0 || autoTitleGenerationInFlight || userTurnCount < 1) return
        val initialUserTitle = grouped.userMessages.firstOrNull()?.content
            ?.replace('\n', ' ')
            ?.take(50)
            ?.trim()
            .orEmpty()
        val currentTitle = room.title.trim()
        if (currentTitle.isNotBlank() &&
            currentTitle != "Untitled Chat" &&
            initialUserTitle.isNotBlank() &&
            currentTitle != initialUserTitle
        ) {
            lastAutoTitleUserTurnCount = userTurnCount
            return
        }

        val latestAssistantMessages = grouped.assistantMessages.firstOrNull().orEmpty()
        if (latestAssistantMessages.isEmpty()) return

        val activeRunIds = agentRunCoordinator.activeRuns.value.keys
        val anyActive = latestAssistantMessages.any { msg ->
            val runId = msg.currentRunId
            if (runId == null) {
                false
            } else {
                runId in activeRunIds ||
                    runsById[runId]?.status == AgentRunStatus.RUNNING ||
                    runsById[runId]?.status == AgentRunStatus.QUEUED
            }
        }
        if (anyActive) return

        if (room.conversationMode == ConversationMode.COMBINED) {
            val leadRunId = latestAssistantMessages
                .firstOrNull { it.currentRunId?.startsWith(COMBINED_RUN_PREFIX) == true }
                ?.currentRunId
            if (leadRunId?.startsWith(COMBINED_RUN_PREFIX) != true ||
                runsById[leadRunId]?.status != AgentRunStatus.COMPLETED
            ) {
                return
            }
        }

        val userContext = grouped.userMessages.firstOrNull()?.content?.trim()?.takeIf(String::isNotBlank) ?: return
        val assistantContext = latestAssistantMessages
            .map { it.effectiveContent().trim() }
            .filter(String::isNotBlank)
            .joinToString("\n")
            .takeIf(String::isNotBlank)
            ?: return
        val proposedTitle = latestAssistantMessages.asSequence()
            .flatMap { message -> sequenceOf(message.effectiveContent()) + message.effectiveCombinedSources().asSequence().map { it.content } }
            .mapNotNull(dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject::extract)
            .firstOrNull()

        autoTitleGenerationInFlight = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val activeUids = _activePlatformUids.value.toSet()
                val aiTitle = proposedTitle ?: userContext.take(60).takeIf { room.conversationMode == ConversationMode.COMBINED } ?: run {
                    val platform = _platformsInApp.value.firstOrNull { it.uid in activeUids && it.enabled }
                        ?: _platformsInApp.value.firstOrNull()
                        ?: return@launch
                    chatRepository.generateAiTitle(userContext, assistantContext, platform)
                }
                val cleaned = dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.clean(aiTitle.orEmpty())
                    ?: dev.chungjungsoo.gptmobile.data.conversation.ConversationSubject.clean(userContext)
                if (cleaned != null && !_chatRoom.value.isTitleCustomized) {
                    _chatRoom.update { it.copy(title = cleaned, isTitleCustomized = true) }
                    chatRepository.updateChatTitle(_chatRoom.value, cleaned, isCustomized = true)
                    lastAutoTitleUserTurnCount = 1
                }
            } finally {
                autoTitleGenerationInFlight = false
            }
        }
    }

    private fun observeAgentNotices() {
        viewModelScope.launch {
            agentRunCoordinator.notices.collect { notice ->
                if (notice.chatId == _chatRoom.value.id) {
                    _runNoticesById.update { current ->
                        applyChatRunNotice(current, notice.runId, notice.message, notice.persistent)
                    }
                }
            }
        }
        viewModelScope.launch {
            agentRunCoordinator.activeRuns.collect {
                advancePendingGeneration()
            }
        }
    }

    private fun advancePendingGeneration() {
        refreshCombinedFailures()
        if (pendingRunDispatches > 0 || pendingMessageGrouping > 0) return
        val runs = _agentRunsById.value
        syncLoadingStates(runs)
        if (_combinedFailures.value.isNotEmpty() || isGenerationBusy()) return
        maybeStartCombinedSynthesis(runs)
        checkAndGenerateAiTitle(runs)
        drainPromptQueueIfIdle()
    }

    private fun syncLoadingStates(runs: List<AgentRun>) {
        syncLoadingStates(runs.associateBy(AgentRun::runId))
    }

    private fun syncLoadingStates(runsById: Map<String, AgentRun>) {
        if (pendingRunDispatches > 0) return
        val activeRunIds = agentRunCoordinator.activeRuns.value.keys
        val latestAssistantRow = _groupedMessages.value.assistantMessages.lastOrNull()
        _loadingStates.update {
            loadingStatesForLatestAssistant(
                platformCount = enabledPlatformsInChat.size,
                latestAssistantRow = latestAssistantRow,
                runsById = runsById,
                activeRunIds = activeRunIds
            )
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun observeToolEvents() {
        viewModelScope.launch {
            StartupRecoveryGate.await()
            _chatRoom
                .map { it.id }
                .distinctUntilChanged()
                .flatMapLatest { chatId ->
                    if (chatId > 0) chatRepository.observeToolEvents(chatId) else flowOf(emptyList())
                }
                .collect { events ->
                    _toolEventsByRun.update { events.groupBy(ToolEvent::runId) }
                }
        }
    }

    private fun resolvePlatformModel(platform: PlatformV2): PlatformV2 = resolvePlatformModel(platform, _chatPlatformModels.value).let { it.copy(reasoning = _chatToolConfig.value.reasoning ?: it.reasoning) }

    private fun persistCurrentChatSnapshot() {
        viewModelScope.launch {
            val chatRoom = _chatRoom.value
            val groupedMessages = _groupedMessages.value
            if (chatRoom.id <= 0) return@launch
            if (groupedMessages.userMessages.isEmpty()) return@launch
            if (groupedMessages.userMessages.size != groupedMessages.assistantMessages.size) return@launch

            withContext(Dispatchers.IO) {
                chatRepository.saveChat(
                    chatRoom = chatRoom,
                    messages = completeWindowMessages(groupedMessages),
                    chatPlatformModels = _chatPlatformModels.value
                )
            }
        }
    }
    companion object {
        internal const val COMBINED_RUN_PREFIX = "combined-synthesis:"
    }
}

data class ChatRunNotice(
    val message: String,
    val persistent: Boolean
)

internal fun applyChatRunNotice(
    noticesByRunId: Map<String, List<ChatRunNotice>>,
    runId: String,
    message: String,
    persistent: Boolean
): Map<String, List<ChatRunNotice>> {
    if (runId.isBlank() || message.isBlank()) return noticesByRunId
    val current = noticesByRunId[runId].orEmpty()
    if (current.any { it.message == message && it.persistent == persistent }) return noticesByRunId
    return noticesByRunId + (runId to (current + ChatRunNotice(message, persistent)))
}

@JvmName("pruneTransientChatRunNoticesByStatus")
internal fun pruneTransientChatRunNotices(
    noticesByRunId: Map<String, List<ChatRunNotice>>,
    runStatuses: Map<String, String>,
    activeRunIds: Set<String>
): Map<String, List<ChatRunNotice>> = pruneTransientChatRunNoticesWithStatusLookup(
    noticesByRunId = noticesByRunId,
    activeRunIds = activeRunIds,
    getStatus = { runStatuses[it] }
)

internal fun pruneTransientChatRunNotices(
    noticesByRunId: Map<String, List<ChatRunNotice>>,
    runsById: Map<String, AgentRun>,
    activeRunIds: Set<String>
): Map<String, List<ChatRunNotice>> = pruneTransientChatRunNoticesWithStatusLookup(
    noticesByRunId = noticesByRunId,
    activeRunIds = activeRunIds,
    getStatus = { runsById[it]?.status }
)

private inline fun pruneTransientChatRunNoticesWithStatusLookup(
    noticesByRunId: Map<String, List<ChatRunNotice>>,
    activeRunIds: Set<String>,
    getStatus: (String) -> String?
): Map<String, List<ChatRunNotice>> = noticesByRunId.mapValues { (runId, notices) ->
    val status = getStatus(runId)
    val isActive = runId in activeRunIds || status == AgentRunStatus.QUEUED || status == AgentRunStatus.RUNNING || status == AgentRunStatus.WAITING_PROVIDER_LIMIT
    if (isActive) notices else notices.filter { it.persistent }
}.filterValues { it.isNotEmpty() }

internal fun visibleChatRunNotices(
    stored: List<ChatRunNotice>,
    timelineNotices: List<String>,
    isRunActive: Boolean
): List<String> {
    val fromStore = stored.filter { it.persistent || isRunActive }.map { it.message }
    return (timelineNotices + fromStore).distinct()
}

internal fun timelineNoticeMessages(timeline: List<AssistantTimelineItem>): List<String> = timeline.filter { it.type == AssistantTimelineItemType.NOTICE && it.delegationInvocationId == null && !it.statusSummary && !it.progressCheckpoint }.map { it.content }.filter { it.isNotBlank() }

internal fun loadingStatesForLatestAssistant(
    platformCount: Int,
    latestAssistantRow: List<MessageV2>?,
    runsById: Map<String, AgentRun>,
    activeRunIds: Set<String>
): List<ChatViewModel.LoadingState> = List(platformCount) { platformIndex ->
    val runId = latestAssistantRow?.getOrNull(platformIndex)?.currentRunId
    val status = runId?.let(runsById::get)?.status
    if (status != AgentRunStatus.WAITING_REMOTE_JOB && (runId in activeRunIds || status == AgentRunStatus.QUEUED || status == AgentRunStatus.RUNNING || status == AgentRunStatus.WAITING_PROVIDER_LIMIT)) {
        ChatViewModel.LoadingState.Loading
    } else {
        ChatViewModel.LoadingState.Idle
    }
}

internal fun groupPersistedMessages(
    messages: List<MessageV2>,
    enabledPlatformsInChat: List<String>,
    chatId: Int
): ChatViewModel.GroupedMessages {
    val userMessages = messages.filter { it.platformType == null }
    // Explicit links keep a late dedicated final with its original turn. Old imports
    // without links retain the chronological grouping used before dedicated finals.
    val byTurn = mutableMapOf<Int, MutableList<MessageV2>>()
    var latestUserId: Int? = null
    messages.forEach { message ->
        if (message.platformType == null) {
            latestUserId = message.id
        } else {
            val userId = message.linkedMessageId.takeIf { it > 0 } ?: latestUserId
            if (userId != null) byTurn.getOrPut(userId) { mutableListOf() } += message
        }
    }
    return ChatViewModel.GroupedMessages(
        userMessages = userMessages,
        assistantMessages = userMessages.map { user ->
            normalizeAssistantRow(byTurn[user.id].orEmpty(), enabledPlatformsInChat, chatId)
        }
    )
}

internal fun groupedMessagesThroughTurn(
    groupedMessages: ChatViewModel.GroupedMessages,
    turnIndex: Int
): ChatViewModel.GroupedMessages = groupedMessages.copy(
    userMessages = groupedMessages.userMessages.take(turnIndex + 1),
    assistantMessages = groupedMessages.assistantMessages.take(turnIndex + 1)
)

internal fun resolvePlatformModel(
    platform: PlatformV2,
    chatPlatformModels: Map<String, String>
): PlatformV2 {
    val chatModel = chatPlatformModels[platform.uid]?.trim().orEmpty()
    if (chatModel.isBlank() || chatModel == platform.model) return platform

    return platform.copy(model = chatModel)
}

internal fun resolveSelectedPlatforms(
    selectedProfileUids: List<String>,
    configuredPlatforms: List<PlatformV2>
): List<IndexedValue<PlatformV2>> {
    val platformsByUid = configuredPlatforms.associateBy(PlatformV2::uid)
    return selectedProfileUids.mapIndexedNotNull { index, uid ->
        platformsByUid[uid]?.let { IndexedValue(index, it) }
    }
}

internal suspend fun <T> persistBeforeProvider(
    persist: suspend () -> T,
    startProvider: suspend (T) -> Unit,
    onFailure: suspend (Throwable) -> Unit
) {
    val persisted = try {
        persist()
    } catch (error: CancellationException) {
        throw error
    } catch (error: Throwable) {
        onFailure(error)
        return
    }
    startProvider(persisted)
}

internal fun mergePersistedAssistantRow(
    currentRow: List<MessageV2>,
    selectedProfileUids: List<String>,
    persistedMessages: List<MessageV2>,
    chatId: Int
): List<MessageV2> {
    val currentByProfile = currentRow.associateBy { it.platformType }
    val persistedByProfile = persistedMessages.associateBy { it.platformType }
    return selectedProfileUids.map { profileUid ->
        persistedByProfile[profileUid]
            ?: currentByProfile[profileUid]
            ?: createEmptyAssistantMessage(chatId, profileUid)
    }
}

@Suppress("UNUSED_PARAMETER")
internal fun formatAssistantExport(
    platformName: String,
    message: MessageV2,
    toolEventsByRun: Map<String, List<ToolEvent>>,
    toolTraceLabels: ToolTraceLabels = ToolTraceLabels.Default,
    legacyOrderNotice: String = LEGACY_ORDER_NOTICE
): String = assistantExportText(message, ChatExportFormat.MARKDOWN)

private fun isPersistableMessage(message: MessageV2): Boolean =
    message.effectiveContent().isNotBlank() ||
        message.effectiveThoughts().isNotBlank() ||
        message.effectiveTimeline().isNotEmpty() ||
        message.attachments.isNotEmpty() ||
        message.currentRunId != null

internal fun persistableMessages(groupedMessages: ChatViewModel.GroupedMessages): List<MessageV2> {
    val estimatedSize = groupedMessages.userMessages.size + (groupedMessages.assistantMessages.size * 2)
    val result = ArrayList<MessageV2>(estimatedSize)
    for (msg in groupedMessages.userMessages) {
        if (isPersistableMessage(msg)) {
            result.add(msg)
        }
    }
    for (row in groupedMessages.assistantMessages) {
        for (msg in row) {
            if (isPersistableMessage(msg)) {
                result.add(msg)
            }
        }
    }
    result.sortBy { it.createdAt }
    return result
}

internal fun createEmptyAssistantMessage(chatId: Int, platformUid: String): MessageV2 = MessageV2(
    chatId = chatId,
    content = "",
    platformType = platformUid
)

internal fun createRetryAssistantMessage(
    currentMessage: MessageV2,
    chatId: Int,
    platformUid: String
): MessageV2 = createEmptyAssistantMessage(chatId, platformUid).copy(
    revisions = currentMessage.revisions
)

internal fun normalizeAssistantRow(
    assistantMessages: List<MessageV2>,
    enabledPlatformsInChat: List<String>,
    chatId: Int
): List<MessageV2> {
    if (enabledPlatformsInChat.isEmpty()) return assistantMessages

    val consumedIndexes = mutableSetOf<Int>()
    val normalizedMessages = enabledPlatformsInChat.map { platformUid ->
        val matchedIndex = assistantMessages.indices.sortedByDescending { assistantMessages[it].isCombinedSynthesis() }.firstOrNull { index ->
            index !in consumedIndexes && assistantMessages[index].platformType == platformUid
        }

        if (matchedIndex == null) {
            createEmptyAssistantMessage(chatId, platformUid)
        } else {
            consumedIndexes += matchedIndex
            assistantMessages[matchedIndex]
        }
    }
    val overflowMessages = assistantMessages.filterIndexed { index, _ -> index !in consumedIndexes }

    return normalizedMessages + overflowMessages
}

internal fun updateAssistantSlot(
    groupedMessages: ChatViewModel.GroupedMessages,
    turnIndex: Int,
    platformIndex: Int,
    transform: (MessageV2) -> MessageV2
): ChatViewModel.GroupedMessages {
    if (turnIndex !in groupedMessages.assistantMessages.indices) return groupedMessages

    val currentTurnMessages = groupedMessages.assistantMessages[turnIndex].toMutableList()
    if (platformIndex !in currentTurnMessages.indices) return groupedMessages

    currentTurnMessages[platformIndex] = transform(currentTurnMessages[platformIndex])

    val updatedAssistantMessages = groupedMessages.assistantMessages.toMutableList()
    updatedAssistantMessages[turnIndex] = currentTurnMessages

    return groupedMessages.copy(assistantMessages = updatedAssistantMessages)
}

internal fun shouldShowReplyLoadingIndicator(
    isActiveMessage: Boolean,
    loadingStates: List<ChatViewModel.LoadingState>
): Boolean = isActiveMessage && loadingStates.any { it == ChatViewModel.LoadingState.Loading }

internal fun hasAssistantProcessDetails(
    timeline: List<AssistantTimelineItem>,
    fallbackThoughts: String,
    hasToolEvents: Boolean
): Boolean {
    val hasTimelineDetails = timeline.any { item ->
        when (item.type) {
            AssistantTimelineItemType.THINKING -> !item.content.isNullOrBlank()
            AssistantTimelineItemType.TOOL -> true
            AssistantTimelineItemType.TEXT -> {
                val parsed = ThinkingParser.extractThinking(item.content.orEmpty())
                !parsed.thinking.isNullOrBlank()
            }
            AssistantTimelineItemType.NOTICE,
            AssistantTimelineItemType.LEGACY_ORDER -> false
        }
    }
    return hasTimelineDetails || fallbackThoughts.isNotBlank() || hasToolEvents
}
