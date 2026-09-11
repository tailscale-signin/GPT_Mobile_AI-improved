package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.catalog.CatalogEntry
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunDraft
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItem
import dev.chungjungsoo.gptmobile.data.database.entity.AssistantTimelineItemType
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoomV2
import dev.chungjungsoo.gptmobile.data.database.entity.LEGACY_ORDER_NOTICE
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentRetryRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PersistAgentTurnRequest
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.database.entity.hasUnavailableAssistantOrder
import dev.chungjungsoo.gptmobile.data.database.entity.rebuildAssistantTimelineForEdit
import dev.chungjungsoo.gptmobile.data.database.entity.resetActiveRevision
import dev.chungjungsoo.gptmobile.data.database.entity.selectRevision
import dev.chungjungsoo.gptmobile.data.database.entity.snapshotLatestAssistantRevision
import dev.chungjungsoo.gptmobile.data.localmodel.LocalModelStatus
import dev.chungjungsoo.gptmobile.data.model.AvailableChatTool
import dev.chungjungsoo.gptmobile.data.model.ChatMcpToolConfig
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    @param:ApplicationContext private val context: Context,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator,
    private val agentRunCoordinator: AgentRunCoordinator,
    private val toolConnectionRepository: ToolConnectionRepository,
    private val localModelRepository: LocalModelRepository,
    private val modelCatalogRepository: ModelCatalogRepository
) : ViewModel() {
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
    val enabledPlatformsInChat = enabledPlatformString.split(',')
    val targetMessageId: Int = savedStateHandle.get<Int>("targetMessageId") ?: -1

    private val currentTimeStamp: Long
        get() = System.currentTimeMillis() / 1000

    private val _chatRoom = MutableStateFlow(ChatRoomV2(id = -1, title = "", enabledPlatform = enabledPlatformsInChat))
    val chatRoom = _chatRoom.asStateFlow()

    // Session-disabled platforms within this chat session
    private val _sessionDisabledPlatformUids = MutableStateFlow<Set<String>>(emptySet())
    val sessionDisabledPlatformUids = _sessionDisabledPlatformUids.asStateFlow()

    fun toggleSessionPlatformDisabled(uid: String) {
        _sessionDisabledPlatformUids.update { current ->
            if (uid in current) current - uid else current + uid
        }
    }

    private val _isChatTitleDialogOpen = MutableStateFlow(false)
    val isChatTitleDialogOpen = _isChatTitleDialogOpen.asStateFlow()

    private val _messageEditSession = MutableStateFlow<MessageEditSession?>(null)
    val messageEditSession = _messageEditSession.asStateFlow()

    private val _isSelectTextSheetOpen = MutableStateFlow(false)
    val isSelectTextSheetOpen = _isSelectTextSheetOpen.asStateFlow()

    private val _isChatModelDialogOpen = MutableStateFlow(false)
    val isChatModelDialogOpen = _isChatModelDialogOpen.asStateFlow()

    private val _isChatToolSheetOpen = MutableStateFlow(false)
    val isChatToolSheetOpen = _isChatToolSheetOpen.asStateFlow()

    private val _chatToolConfig = MutableStateFlow(ChatMcpToolConfig())
    val chatToolConfig = _chatToolConfig.asStateFlow()

    private val _availableChatTools = MutableStateFlow<List<AvailableChatTool>>(emptyList())
    val availableChatTools = _availableChatTools.asStateFlow()

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
                displayName = names[model.catalogEntryId]?.takeIf { it.isNotBlank() } ?: model.catalogEntryId
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    private val _runNoticesById = MutableStateFlow<Map<String, List<ChatRunNotice>>>(emptyMap())
    val runNoticesById = _runNoticesById.asStateFlow()

    private val _needsLocalNetworkAccess = MutableStateFlow(false)
    val needsLocalNetworkAccess = _needsLocalNetworkAccess.asStateFlow()

    // Chat messages currently in the chat room
    private val _groupedMessages = MutableStateFlow(GroupedMessages())
    val groupedMessages = _groupedMessages.asStateFlow()

    private val _toolEventsByRun = MutableStateFlow<Map<String, List<ToolEvent>>>(emptyMap())
    val toolEventsByRun = _toolEventsByRun.asStateFlow()

    private val _agentRunsById = MutableStateFlow<Map<String, AgentRun>>(emptyMap())
    val agentRunsById = _agentRunsById.asStateFlow()

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

    private var pendingQuestionText: String? = null

    init {
        fetchChatRoom()
        viewModelScope.launch { fetchMessages() }
        fetchEnabledPlatformsInApp()
        observePersistedMessages()
        observeAgentRuns()
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
            pendingQuestionText = questionText
            question.clearText()
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Loading } }
            trySendPendingQuestionIfReady()
            return
        }

        sendQuestion(questionText, _selectedAttachments.value)
    }

    fun cancelActiveRuns() {
        _chatRoom.value.id.takeIf { it > 0 }?.let(agentRunCoordinator::cancelChat)
    }

    fun refreshLocalNetworkRequirement() {
        fetchEnabledPlatformsInApp()
    }

    override fun onCleared() {
        AttachmentPayloadCache.clear()
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
    fun openChatModelDialog() = _isChatModelDialogOpen.update { true }

    fun openChatToolSheet() = _isChatToolSheetOpen.update { true }
    fun closeChatToolSheet() = _isChatToolSheetOpen.update { false }

    fun toggleChatTool(toolId: String) {
        _chatToolConfig.update { config ->
            val isEnabled = config.isToolEnabled(toolId)
            if (isEnabled) config.withToolDisabled(toolId) else config.withToolEnabled(toolId)
        }
    }

    fun enableAllChatTools() {
        _chatToolConfig.update { config ->
            config.copy(
                disabledToolIds = emptySet(),
                enabledToolIds = _availableChatTools.value.map { it.id }.toSet(),
                allToolsDisabled = false
            )
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
            val connections = toolConnectionRepository.getAllConnections()
            _availableChatTools.update { ChatToolUtils.buildAvailableChatTools(connections) }
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
        val assistantMessage = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return
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

    fun openSelectTextSheet(content: String) {
        _selectedText.update { content }
        _isSelectTextSheetOpen.update { true }
    }

    fun generateDefaultChatTitle(): String? = chatRepository.generateDefaultChatTitle(_groupedMessages.value.userMessages)

    fun updateChatPlatformModels(models: Map<String, String>) {
        val sanitizedModels = models
            .filterKeys { it in enabledPlatformsInChat }
            .mapValues { (_, model) -> model.trim() }

        _chatPlatformModels.update { it + sanitizedModels }

        if (_chatRoom.value.id > 0) {
            viewModelScope.launch {
                chatRepository.saveChatPlatformModels(_chatRoom.value.id, _chatPlatformModels.value)
            }
        }
    }

    fun retryChat(turnIndex: Int, platformIndex: Int) {
        if (turnIndex !in _groupedMessages.value.assistantMessages.indices) return
        if (platformIndex >= enabledPlatformsInChat.size || platformIndex < 0) return
        val platform = _platformsInApp.value.firstOrNull { it.uid == enabledPlatformsInChat[platformIndex] } ?: return
        val platformWithChatModel = resolvePlatformModel(platform)
        val currentAssistantMessage = _groupedMessages.value.assistantMessages
            .getOrNull(turnIndex)
            ?.getOrNull(platformIndex)
            ?: return
        val userMessage = _groupedMessages.value.userMessages.getOrNull(turnIndex) ?: return
        val runId = UUID.randomUUID().toString()
        _loadingStates.update { it.toMutableList().apply { this[platformIndex] = LoadingState.Loading } }

        viewModelScope.launch {
            persistBeforeProvider(
                persist = {
                    chatRepository.persistAgentRetry(
                        PersistAgentRetryRequest(
                            userMessage = userMessage,
                            assistantMessage = currentAssistantMessage,
                            run = AgentRunDraft(
                                runId = runId,
                                profileUid = platformWithChatModel.uid,
                                providerSnapshot = platformWithChatModel.compatibleType.name,
                                modelSnapshot = platformWithChatModel.model,
                                createdAt = currentTimeStamp
                            )
                        )
                    )
                },
                startProvider = { persisted ->
                    _groupedMessages.update { groupedMessages ->
                        updateAssistantSlot(groupedMessages, turnIndex, platformIndex) { persisted.assistantMessage }
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
                                chatToolConfig = _chatToolConfig.value
                            )
                        )
                    )
                },
                onFailure = { error ->
                    showPersistenceFailure(turnIndex, listOf(platformIndex), error)
                }
            )
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
        if (_chatRoom.value.id > 0) {
            _chatRoom.update { it.copy(title = title) }
            viewModelScope.launch {
                chatRepository.updateChatTitle(_chatRoom.value, title)
            }
        }
    }

    fun updateChatPlatformIndex(assistantIndex: Int, platformIndex: Int) {
        if (assistantIndex >= _indexStates.value.size || assistantIndex < 0) return
        if (platformIndex >= enabledPlatformsInChat.size || platformIndex < 0) return

        _indexStates.update {
            val updatedIndex = it.toMutableList()
            updatedIndex[assistantIndex] = platformIndex
            updatedIndex
        }
    }

    fun addSelectedFile(filePath: String) {
        addDraftFile(
            currentAttachments = { _selectedAttachments.value },
            updateAttachments = { attachments -> _selectedAttachments.update { attachments } },
            filePath = filePath,
            onNotice = { notice -> _attachmentNotice.update { notice } }
        )
    }

    fun removeSelectedFile(filePath: String) {
        removeDraftFile(
            currentAttachments = { _selectedAttachments.value },
            updateAttachments = { attachments -> _selectedAttachments.update { attachments } },
            filePath = filePath
        )
        trySendPendingQuestionIfReady()
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

    fun showPreviousAssistantRevision(turnIndex: Int, platformIndex: Int) {
        _groupedMessages.update {
            updateAssistantSlot(it, turnIndex, platformIndex) { msg ->
                val next = (msg.activeRevisionIndex + 1).coerceAtMost(msg.revisions.lastIndex)
                msg.copy(activeRevisionIndex = next)
            }
        }
    }

    fun showNextAssistantRevision(turnIndex: Int, platformIndex: Int) {
        _groupedMessages.update {
            updateAssistantSlot(it, turnIndex, platformIndex) { msg ->
                val next = msg.activeRevisionIndex - 1
                msg.copy(activeRevisionIndex = if (next < 0) ACTIVE_REVISION_LATEST else next)
            }
        }
    }

    private fun addDraftFile(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        updateAttachments: (List<ChatAttachmentDraft>) -> Unit,
        filePath: String,
        onNotice: (String) -> Unit
    ) {
        val drafts = currentAttachments().toMutableList()
        val newDraft = ChatAttachmentDraft(
            sourceFilePath = filePath,
            status = ChatAttachmentDraft.Status.Ready,
            attachment = dev.chungjungsoo.gptmobile.data.database.entity.Attachment(filePathForDisplay = filePath)
        )
        drafts.add(newDraft)
        updateAttachments(drafts)
    }

    private fun removeDraftFile(
        currentAttachments: () -> List<ChatAttachmentDraft>,
        updateAttachments: (List<ChatAttachmentDraft>) -> Unit,
        filePath: String
    ) {
        val drafts = currentAttachments().filterNot { it.sourceFilePath == filePath }
        updateAttachments(drafts)
    }

    private fun updateMessageEditAttachments(drafts: List<ChatAttachmentDraft>) {
        _messageEditSession.update { current ->
            current?.copy(attachments = drafts)
        }
    }

    private fun trySendPendingQuestionIfReady() {
        val pendingText = pendingQuestionText ?: return
        val currentDrafts = _selectedAttachments.value
        if (currentDrafts.all { it.status == ChatAttachmentDraft.Status.Ready }) {
            pendingQuestionText = null
            sendQuestion(pendingText, currentDrafts)
        }
    }

    private fun sendQuestion(questionText: String, attachments: List<ChatAttachmentDraft>) {
        val readyAttachments = attachments.filter { it.status == ChatAttachmentDraft.Status.Ready }.mapNotNull { it.attachment }
        val userMessage = MessageV2(
            chatId = _chatRoom.value.id,
            content = questionText,
            attachments = readyAttachments,
            createdAt = currentTimeStamp
        )

        val activePlatforms = enabledPlatformsInChat.filter { it !in _sessionDisabledPlatformUids.value }
        val assistantDrafts = enabledPlatformsInChat.map { uid ->
            MessageV2(
                chatId = _chatRoom.value.id,
                content = if (uid in _sessionDisabledPlatformUids.value) "[Platform disabled for session]" else "",
                platformType = uid,
                createdAt = currentTimeStamp
            )
        }

        val turnIndex = _groupedMessages.value.userMessages.size
        _groupedMessages.update {
            it.copy(
                userMessages = it.userMessages + listOf(userMessage),
                assistantMessages = it.assistantMessages + listOf(assistantDrafts)
            )
        }
        _indexStates.update { it + listOf(0) }

        question.clearText()
        clearSelectedFiles()

        val activeIndices = enabledPlatformsInChat.mapIndexedNotNull { index, uid ->
            if (uid !in _sessionDisabledPlatformUids.value) index else null
        }

        _loadingStates.update {
            List(enabledPlatformsInChat.size) { idx ->
                if (idx in activeIndices) LoadingState.Loading else LoadingState.Idle
            }
        }

        viewModelScope.launch {
            if (_chatRoom.value.id <= 0) {
                val newRoom = chatRepository.createChatRoom(
                    ChatRoomV2(
                        title = questionText.take(30).ifBlank { "New Chat" },
                        enabledPlatform = enabledPlatformsInChat
                    )
                )
                _chatRoom.value = newRoom
            }

            val runs = activeIndices.map { index ->
                val platformUid = enabledPlatformsInChat[index]
                val platform = _platformsInApp.value.find { it.uid == platformUid }
                    ?: PlatformV2(name = platformUid, uid = platformUid, compatibleType = dev.chungjungsoo.gptmobile.data.database.entity.PlatformTypeV2.OPENAI)
                val platformWithChatModel = resolvePlatformModel(platform)
                val runId = UUID.randomUUID().toString()
                AgentRunRequest(
                    runId = runId,
                    chatId = _chatRoom.value.id,
                    assistantMessage = assistantDrafts[index].copy(chatId = _chatRoom.value.id),
                    platform = platformWithChatModel,
                    userMessages = _groupedMessages.value.userMessages,
                    assistantMessages = _groupedMessages.value.assistantMessages.map { it.getOrElse(index) { assistantDrafts[index] } },
                    chatToolConfig = _chatToolConfig.value
                )
            }

            runs.forEach { req ->
                agentRunCoordinator.start(listOf(req))
            }
        }
    }

    private fun resolvePlatformModel(platform: PlatformV2): PlatformV2 {
        val configuredModel = _chatPlatformModels.value[platform.uid]
        return if (!configuredModel.isNullOrBlank()) {
            platform.copy(model = configuredModel)
        } else {
            platform
        }
    }

    private fun fetchChatRoom() {
        if (chatRoomId > 0) {
            viewModelScope.launch {
                val room = chatRepository.getChatRoom(chatRoomId)
                if (room != null) {
                    _chatRoom.value = room
                    _chatPlatformModels.value = chatRepository.getChatPlatformModels(chatRoomId)
                }
            }
        }
    }

    private suspend fun fetchMessages() {
        if (chatRoomId > 0) {
            val messages = chatRepository.getMessagesForChat(chatRoomId)
            val users = messages.filter { it.platformType == null }
            val assistants = messages.filter { it.platformType != null }

            val groupedAssistants = mutableListOf<List<MessageV2>>()
            for (i in users.indices) {
                val turnAssistants = enabledPlatformsInChat.map { uid ->
                    assistants.find { it.chatId == chatRoomId && it.platformType == uid }
                        ?: MessageV2(chatId = chatRoomId, content = "", platformType = uid)
                }
                groupedAssistants.add(turnAssistants)
            }

            _groupedMessages.value = GroupedMessages(
                userMessages = users,
                assistantMessages = groupedAssistants
            )
            _indexStates.value = List(users.size) { 0 }
            _isLoaded.value = true
        } else {
            _isLoaded.value = true
        }
    }

    private fun fetchEnabledPlatformsInApp() {
        viewModelScope.launch {
            settingRepository.observePlatforms().collect { list ->
                _platformsInApp.value = list
                val enabled = list.filter { it.enabled }
                _enabledPlatformsInApp.value = enabled
                _needsLocalNetworkAccess.value = enabled.any { it.requiresLocalNetworkAccess() }
            }
        }
    }

    private fun observePersistedMessages() {
        if (chatRoomId > 0) {
            viewModelScope.launch {
                chatRepository.observeMessagesForChat(chatRoomId).collect { messages ->
                    if (messages.isNotEmpty()) {
                        val users = messages.filter { it.platformType == null }
                        val assistants = messages.filter { it.platformType != null }
                        val groupedAssistants = mutableListOf<List<MessageV2>>()
                        for (i in users.indices) {
                            val turnAssistants = enabledPlatformsInChat.map { uid ->
                                assistants.find { it.chatId == chatRoomId && it.platformType == uid }
                                    ?: MessageV2(chatId = chatRoomId, content = "", platformType = uid)
                            }
                            groupedAssistants.add(turnAssistants)
                        }
                        _groupedMessages.value = GroupedMessages(
                            userMessages = users,
                            assistantMessages = groupedAssistants
                        )
                    }
                }
            }
        }
    }

    private fun observeAgentRuns() {
        viewModelScope.launch {
            agentRunCoordinator.observeRuns().collect { runs ->
                _agentRunsById.value = runs.associateBy { it.runId }
                _loadingStates.update { current ->
                    current.mapIndexed { index, _ ->
                        val uid = enabledPlatformsInChat.getOrNull(index)
                        val activeRun = runs.find { it.profileUid == uid && it.status == AgentRunStatus.EXECUTING }
                        if (activeRun != null) LoadingState.Loading else LoadingState.Idle
                    }
                }
            }
        }
    }

    private fun observeToolEvents() {
        viewModelScope.launch {
            agentRunCoordinator.observeToolEvents().collect { events ->
                _toolEventsByRun.value = events.groupBy { it.runId }
            }
        }
    }

    private fun observeAgentNotices() {
        viewModelScope.launch {
            agentRunCoordinator.observeNotices().collect { notices ->
                _runNoticesById.value = notices.groupBy { it.runId }.mapValues { entry ->
                    entry.value.map { ChatRunNotice(runId = it.runId, message = it.message) }
                }
            }
        }
    }

    private fun deleteDraftFiles(attachment: ChatAttachmentDraft) {
        attachment.preparedFilePath?.let { File(it).delete() }
    }

    private fun groupedMessagesThroughTurn(grouped: GroupedMessages, turnIndex: Int): GroupedMessages {
        return GroupedMessages(
            userMessages = grouped.userMessages.take(turnIndex + 1),
            assistantMessages = grouped.assistantMessages.take(turnIndex + 1)
        )
    }

    private suspend fun persistBeforeProvider(
        persist: suspend () -> dev.chungjungsoo.gptmobile.data.database.entity.PersistedAgentRetry,
        startProvider: suspend (dev.chungjungsoo.gptmobile.data.database.entity.PersistedAgentRetry) -> Unit,
        onFailure: (Throwable) -> Unit
    ) {
        try {
            val result = persist()
            startProvider(result)
        } catch (e: Exception) {
            onFailure(e)
        }
    }

    private fun showPersistenceFailure(turnIndex: Int, platformIndices: List<Int>, error: Throwable) {
        _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
        _attachmentNotice.update { "Failed to save message turn: ${error.localizedMessage}" }
    }

    private fun updateAssistantSlot(
        grouped: GroupedMessages,
        turnIndex: Int,
        platformIndex: Int,
        transform: (MessageV2) -> MessageV2
    ): GroupedMessages {
        val updatedTurns = grouped.assistantMessages.toMutableList()
        val turnList = updatedTurns.getOrNull(turnIndex)?.toMutableList() ?: return grouped
        if (platformIndex < turnList.size) {
            turnList[platformIndex] = transform(turnList[platformIndex])
            updatedTurns[turnIndex] = turnList
        }
        return grouped.copy(assistantMessages = updatedTurns)
    }
}
