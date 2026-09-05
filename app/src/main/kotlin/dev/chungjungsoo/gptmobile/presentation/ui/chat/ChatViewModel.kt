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
import dev.chungjungsoo.gptmobile.util.AttachmentPayloadCache
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

    private val currentTimeStamp: Long
        get() = System.currentTimeMillis() / 1000

    private val _chatRoom = MutableStateFlow(ChatRoomV2(id = -1, title = "", enabledPlatform = enabledPlatformsInChat))
    val chatRoom = _chatRoom.asStateFlow()

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
                                assistantMessages = contextMessages.assistantMessages
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
        // Should be only used for changing chat title after the chatroom is created.
        if (_chatRoom.value.id > 0) {
            _chatRoom.update { it.copy(title = title) }
            viewModelScope.launch {
                chatRepository.updateChatTitle(_chatRoom.value, title)
            }
        }
    }

    fun updateChatPlatformIndex(assistantIndex: Int, platformIndex: Int) {
        // Change the message shown in the screen to another platform
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

    fun notifyAttachmentCopyFailed() {
        _attachmentNotice.update { "Failed to copy attachment." }
    }

    fun saveUserMessageEdit(
        editedMessage: MessageV2,
        attachments: List<ChatAttachmentDraft>
    ): Boolean {
        if (attachments.any { it.status != ChatAttachmentDraft.Status.Ready }) {
            _attachmentNotice.update { "Wait for attachments to finish processing before saving." }
            return false
        }

        val userMessages = _groupedMessages.value.userMessages
        val assistantMessages = _groupedMessages.value.assistantMessages

        // Find the index of the message being edited
        val messageIndex = userMessages.indexOfFirst { it.id == editedMessage.id }
        if (messageIndex == -1) return false

        // Update the message content
        val updatedUserMessages = userMessages.toMutableList()
        updatedUserMessages[messageIndex] = editedMessage.copy(
            attachments = attachments.mapNotNull { it.attachment },
            createdAt = currentTimeStamp
        )

        // Remove all messages after the edited question (both user and assistant messages)
        val remainingUserMessages = updatedUserMessages.take(messageIndex + 1)
        val remainingAssistantMessages = assistantMessages.take(messageIndex)

        // Update the grouped messages
        _groupedMessages.update {
            GroupedMessages(
                userMessages = remainingUserMessages,
                assistantMessages = remainingAssistantMessages
            )
        }

        // Add empty assistant message slots for the edited question
        _groupedMessages.update {
            it.copy(
                assistantMessages = it.assistantMessages + listOf(
                    enabledPlatformsInChat.map { p -> MessageV2(chatId = chatRoomId, content = "", platformType = p) }
                )
            )
        }

        // Update index states to match the new message count - trim the end part
        val removedMessagesCount = userMessages.size - remainingUserMessages.size
        _indexStates.update {
            val currentStates = it.toMutableList()
            repeat(removedMessagesCount) { currentStates.removeLastOrNull() }
            currentStates
        }

        // Start new conversation from the edited question
        cancelActiveRuns()
        completeChat(persistSnapshotFirst = true)
        return true
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

    fun exportChat(
        toolTraceLabels: ToolTraceLabels = ToolTraceLabels.Default,
        legacyOrderNotice: String = LEGACY_ORDER_NOTICE
    ): Pair<String, String> {
        val platformNames = _platformsInApp.value.associate { it.uid to it.name }
        // Build the chat history in Markdown format
        val chatHistoryMarkdown = buildString {
            appendLine("# Chat Export: \"${chatRoom.value.title}\"")
            appendLine()
            appendLine("**Exported on:** ${formatCurrentDateTime()}")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("## Chat History")
            appendLine()
            _groupedMessages.value.userMessages.forEachIndexed { i, message ->
                appendLine("**User:**")
                appendLine(message.content)
                appendLine()

                _groupedMessages.value.assistantMessages[i].forEach { message ->
                    val platformName = message.platformType?.let { platformNames[it] } ?: "Unknown"
                    append(formatAssistantExport(platformName, message, _toolEventsByRun.value, toolTraceLabels, legacyOrderNotice))
                }
            }
        }

        // Save the Markdown file
        val fileName = "export_${chatRoom.value.title}_${System.currentTimeMillis()}.md"
        return Pair(fileName, chatHistoryMarkdown)
    }

    private fun completeChat(persistSnapshotFirst: Boolean = false) {
        // Update all the platform loading states to Loading
        _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Loading } }
        val turnIndex = _groupedMessages.value.assistantMessages.lastIndex

        viewModelScope.launch {
            val platforms = resolveSelectedPlatforms(enabledPlatformsInChat, _platformsInApp.value)
                .map { IndexedValue(it.index, resolvePlatformModel(it.value)) }
            val unavailableIndexes = enabledPlatformsInChat.indices - platforms.mapTo(mutableSetOf()) { it.index }
            _loadingStates.update { states ->
                states.toMutableList().apply {
                    unavailableIndexes.forEach { this[it] = LoadingState.Idle }
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
            val runs = platforms.map { (_, platform) ->
                AgentRunDraft(
                    runId = UUID.randomUUID().toString(),
                    profileUid = platform.uid,
                    providerSnapshot = platform.compatibleType.name,
                    modelSnapshot = platform.model,
                    createdAt = timestamp
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
                            messages = persistableMessages(_groupedMessages.value),
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
                    _chatRoom.update { persisted.chatRoom }
                    _groupedMessages.update { groupedMessages ->
                        groupedMessages.copy(
                            userMessages = groupedMessages.userMessages.toMutableList().apply {
                                this[turnIndex] = persisted.userMessage
                            },
                            assistantMessages = groupedMessages.assistantMessages.toMutableList().apply {
                                this[turnIndex] = mergePersistedAssistantRow(
                                    currentRow = this[turnIndex],
                                    selectedProfileUids = enabledPlatformsInChat,
                                    persistedMessages = persisted.assistantMessages,
                                    chatId = persisted.chatRoom.id
                                )
                            }
                        )
                    }
                    val contextMessages = _groupedMessages.value
                    agentRunCoordinator.start(
                        platforms.mapIndexed { runIndex, (_, platform) ->
                            AgentRunRequest(
                                runId = runs[runIndex].runId,
                                chatId = persisted.chatRoom.id,
                                assistantMessage = persisted.assistantMessages[runIndex],
                                platform = platform,
                                userMessages = contextMessages.userMessages,
                                assistantMessages = contextMessages.assistantMessages
                            )
                        }
                    )
                },
                onFailure = { error ->
                    showPersistenceFailure(turnIndex, platforms.map { it.index }, error)
                }
            )
        }
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
                    notice = "Only image attachments are currently supported."
                )
                trySendPendingQuestionIfReady()
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
                trySendPendingQuestionIfReady()
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
                trySendPendingQuestionIfReady()
                return@launch
            }

            val preparationResult = withContext(Dispatchers.IO) {
                attachmentUploadCoordinator.prepareLocalAttachment(context, filePath)
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

            trySendPendingQuestionIfReady()
        }
    }

    private fun trySendPendingQuestionIfReady() {
        val queuedQuestion = pendingQuestionText ?: return
        val attachments = _selectedAttachments.value

        if (attachments.any { it.status == ChatAttachmentDraft.Status.Failed }) {
            restoreQueuedQuestion(queuedQuestion)
            pendingQuestionText = null
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
            return
        }

        if (attachments.any { it.status == ChatAttachmentDraft.Status.Preparing }) {
            return
        }

        if (queuedQuestion.isBlank() && attachments.none { it.status == ChatAttachmentDraft.Status.Ready }) {
            pendingQuestionText = null
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
            return
        }

        pendingQuestionText = null
        sendQuestion(queuedQuestion, attachments)
    }

    private fun sendQuestion(questionText: String, attachments: List<ChatAttachmentDraft>) {
        MessageV2(
            chatId = chatRoomId,
            content = questionText,
            attachments = attachments.mapNotNull { it.attachment },
            platformType = null,
            createdAt = currentTimeStamp
        ).let { addMessage(it) }
        question.clearText()
        clearSelectedFiles()
        completeChat()
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
        updateAttachments(currentAttachments().filter { it.sourceFilePath != filePath })\n        _attachmentNotice.update { notice }\n    }\n\n    private fun restoreQueuedQuestion(questionText: String) {\n        if (questionText.isBlank()) return\n        question.setTextAndPlaceCursorAtEnd(questionText)\n    }\n\n    private fun deleteDraftFiles(attachment: ChatAttachmentDraft) {\n        if (!attachment.cleanupOnDiscard) return\n        java.io.File(attachment.sourceFilePath).delete()\n        attachment.preparedFilePath\n            ?.takeIf { it != attachment.sourceFilePath }\n            ?.let { java.io.File(it).delete() }\n    }\n\n    /**\n     * Assistant revisions are stored newest-first: revisions[0] is the newest\n     * saved answer, and ACTIVE_REVISION_LATEST points at the live content.\n     */\n    private fun updateAssistantRevisionSelection(\n        turnIndex: Int,\n        platformIndex: Int,\n        nextIndex: (MessageV2) -> Int\n    ) {\n        _groupedMessages.update {\n            updateAssistantSlot(\n                groupedMessages = it,\n                turnIndex = turnIndex,\n                platformIndex = platformIndex\n            ) { message ->\n                message.selectRevision(nextIndex(message))\n            }\n        }\n        persistCurrentChatSnapshot()\n    }\n\n    private fun formatCurrentDateTime(): String {\n        val currentDate = java.util.Date()\n        val format = java.text.SimpleDateFormat(\"yyyy-MM-dd hh:mm a\", java.util.Locale.getDefault())\n        return format.format(currentDate)\n    }\n\n    private suspend fun fetchMessages() {\n        // If the room isn't new\n        if (chatRoomId != 0) {\n            _groupedMessages.update { fetchGroupedMessages(chatRoomId) }\n            if (_groupedMessages.value.assistantMessages.size != _indexStates.value.size) {\n                _indexStates.update { List(_groupedMessages.value.assistantMessages.size) { 0 } }\n            }\n            _isLoaded.update { true } // Finish fetching\n            return\n        }\n\n        // When message id should sync after saving chats\n        if (_chatRoom.value.id != 0) {\n            _groupedMessages.update { fetchGroupedMessages(_chatRoom.value.id) }\n            return\n        }\n    }\n\n    private suspend fun fetchGroupedMessages(chatId: Int): GroupedMessages {\n        val messages = chatRepository.fetchMessagesV2(chatId).sortedBy { it.createdAt }\n        return groupPersistedMessages(messages, enabledPlatformsInChat, chatId)\n    }\n\n    private fun fetchChatRoom() {\n        viewModelScope.launch {\n            _chatRoom.update {\n                if (chatRoomId == 0) {\n                    ChatRoomV2(id = 0, title = \"Untitled Chat\", enabledPlatform = enabledPlatformsInChat)\n                } else {\n                    chatRepository.fetchChatListV2().first { it.id == chatRoomId }\n                }\n            }\n        }\n    }\n\n    private fun fetchEnabledPlatformsInApp() {\n        viewModelScope.launch {\n            val allPlatforms = settingRepository.fetchPlatformV2s()\n            _platformsInApp.update { allPlatforms }\n            initializeChatPlatformModels(allPlatforms)\n            updateLocalNetworkRequirement(allPlatforms)\n            _enabledPlatformsInApp.update { allPlatforms.filter { it.enabled } }\n        }\n    }\n\n    private suspend fun updateLocalNetworkRequirement(platforms: List<PlatformV2>) {\n        val selectedProfiles = platforms.filter { it.uid in enabledPlatformsInChat }\n        val providerNeedsAccess = selectedProfiles.any { requiresLocalNetworkAccess(it.apiUrl) }\n        val requiresAccess = determineLocalNetworkAccessRequirement(\n            providerNeedsAccess = providerNeedsAccess,\n            toolNeedsAccess = {\n                enabledPlatformsInChat.any { profileUid ->\n                    toolConnectionRepository.listBindingsWithConnections(profileUid).any { binding ->\n                        binding.connection?.endpointUrl?.let(::requiresLocalNetworkAccess) == true\n                    }\n                }\n            },\n            onLookupFailure = {\n                _attachmentNotice.update { context.getString(R.string.local_network_check_failed) }\n            }\n        )\n        _needsLocalNetworkAccess.update { requiresAccess }\n    }\n\n    private suspend fun initializeChatPlatformModels(platforms: List<PlatformV2>) {\n        val defaultModels = enabledPlatformsInChat.associateWith { uid ->\n            platforms.firstOrNull { it.uid == uid }?.model ?: \"\"\n        }\n        val persistedModels = if (chatRoomId != 0) {\n            chatRepository.fetchChatPlatformModels(chatRoomId)\n        } else {\n            emptyMap()\n        }\n\n        val mergedModels = defaultModels.mapValues { (uid, defaultModel) ->\n            persistedModels[uid]?.takeIf { it.isNotBlank() } ?: defaultModel\n        }\n\n        _chatPlatformModels.update { mergedModels }\n\n        if (chatRoomId != 0 && mergedModels != persistedModels) {\n            chatRepository.saveChatPlatformModels(chatRoomId, mergedModels)\n        }\n    }\n\n    @OptIn(ExperimentalCoroutinesApi::class)\n    private fun observePersistedMessages() {\n        viewModelScope.launch {\n            StartupRecoveryGate.await()\n            _chatRoom\n                .map { it.id }\n                .distinctUntilChanged()\n                .flatMapLatest { chatId ->\n                    if (chatId > 0) {\n                        chatRepository.observeMessagesV2(chatId).map { messages -> chatId to messages }\n                    } else {\n                        flowOf(chatId to emptyList())\n                    }\n                }\n                .collect { (chatId, messages) ->\n                    if (chatId <= 0) return@collect\n                    val groupedMessages = groupPersistedMessages(messages, enabledPlatformsInChat, chatId)\n                    _groupedMessages.update { groupedMessages }\n                    _indexStates.update { current ->\n                        List(groupedMessages.assistantMessages.size) { index -> current.getOrElse(index) { 0 } }\n                    }\n                    syncLoadingStates(_agentRunsById.value)\n                    _isLoaded.update { true }\n                }\n        }\n    }\n\n    @OptIn(ExperimentalCoroutinesApi::class)\n    private fun observeAgentRuns() {\n        viewModelScope.launch {\n            StartupRecoveryGate.await()\n            _chatRoom\n                .map { it.id }\n                .distinctUntilChanged()\n                .flatMapLatest { chatId ->\n                    if (chatId > 0) chatRepository.observeAgentRuns(chatId) else flowOf(emptyList())\n                }\n                .collect { runs ->\n                    val runsById = runs.associateBy(AgentRun::runId)\n                    _agentRunsById.update { runsById }\n                    _runNoticesById.update { current ->\n                        pruneTransientChatRunNotices(\n                            current,\n                            runsById = runsById,\n                            activeRunIds = agentRunCoordinator.activeRuns.value.keys\n                        )\n                    }\n                    syncLoadingStates(runsById)\n                }\n        }\n    }\n\n    private fun observeAgentNotices() {\n        viewModelScope.launch {\n            agentRunCoordinator.notices.collect { notice ->\n                if (notice.chatId == _chatRoom.value.id) {\n                    _runNoticesById.update { current ->\n                        applyChatRunNotice(current, notice.runId, notice.message, notice.persistent)\n                    }\n                }\n            }\n        }\n        viewModelScope.launch {\n            agentRunCoordinator.activeRuns.collect {\n                syncLoadingStates(_agentRunsById.value)\n            }\n        }\n    }\n\n    private fun syncLoadingStates(runs: List<AgentRun>) {\n        syncLoadingStates(runs.associateBy(AgentRun::runId))\n    }\n\n    private fun syncLoadingStates(runsById: Map<String, AgentRun>) {\n        val activeRunIds = agentRunCoordinator.activeRuns.value.keys\n        val latestAssistantRow = _groupedMessages.value.assistantMessages.lastOrNull()\n        _loadingStates.update {\n            loadingStatesForLatestAssistant(\n                platformCount = enabledPlatformsInChat.size,\n                latestAssistantRow = latestAssistantRow,\n                runsById = runsById,\n                activeRunIds = activeRunIds\n            )\n        }\n    }\n\n    @OptIn(ExperimentalCoroutinesApi::class)\n    private fun observeToolEvents() {\n        viewModelScope.launch {\n            StartupRecoveryGate.await()\n            _chatRoom\n                .map { it.id }\n                .distinctUntilChanged()\n                .flatMapLatest { chatId ->\n                    if (chatId > 0) chatRepository.observeToolEvents(chatId) else flowOf(emptyList())\n                }\n                .collect { events ->\n                    _toolEventsByRun.update { events.groupBy(ToolEvent::runId) }\n                }\n        }\n    }\n\n    private fun resolvePlatformModel(platform: PlatformV2): PlatformV2 = resolvePlatformModel(platform, _chatPlatformModels.value)\n\n    private fun persistCurrentChatSnapshot() {\n        viewModelScope.launch {\n            val chatRoom = _chatRoom.value\n            val groupedMessages = _groupedMessages.value\n            if (chatRoom.id <= 0) return@launch\n            if (groupedMessages.userMessages.isEmpty()) return@launch\n            if (groupedMessages.userMessages.size != groupedMessages.assistantMessages.size) return@launch\n\n            withContext(Dispatchers.IO) {\n                chatRepository.saveChat(\n                    chatRoom = chatRoom,\n                    messages = persistableMessages(groupedMessages),\n                    chatPlatformModels = _chatPlatformModels.value\n                )\n            }\n        }\n    }\n}\n\ndata class ChatRunNotice(\n    val message: String,\n    val persistent: Boolean\n)\n\ninternal fun applyChatRunNotice(\n    noticesByRunId: Map<String, List<ChatRunNotice>>,\n    runId: String,\n    message: String,\n    persistent: Boolean\n): Map<String, List<ChatRunNotice>> {\n    if (runId.isBlank() || message.isBlank()) return noticesByRunId\n    val current = noticesByRunId[runId].orEmpty()\n    if (current.any { it.message == message && it.persistent == persistent }) return noticesByRunId\n    return noticesByRunId + (runId to (current + ChatRunNotice(message, persistent)))\n}\n\n@JvmName(\"pruneTransientChatRunNoticesByStatus\")\ninternal fun pruneTransientChatRunNotices(\n    noticesByRunId: Map<String, List<ChatRunNotice>>,\n    runStatuses: Map<String, String>,\n    activeRunIds: Set<String>\n): Map<String, List<ChatRunNotice>> = pruneTransientChatRunNoticesWithStatusLookup(\n    noticesByRunId = noticesByRunId,\n    activeRunIds = activeRunIds,\n    getStatus = { runStatuses[it] }\n)\n\ninternal fun pruneTransientChatRunNotices(\n    noticesByRunId: Map<String, List<ChatRunNotice>>,\n    runsById: Map<String, AgentRun>,\n    activeRunIds: Set<String>\n): Map<String, List<ChatRunNotice>> = pruneTransientChatRunNoticesWithStatusLookup(\n    noticesByRunId = noticesByRunId,\n    activeRunIds = activeRunIds,\n    getStatus = { runsById[it]?.status }\n)\n\nprivate inline fun pruneTransientChatRunNoticesWithStatusLookup(\n    noticesByRunId: Map<String, List<ChatRunNotice>>,\n    activeRunIds: Set<String>,\n    getStatus: (String) -> String?\n): Map<String, List<ChatRunNotice>> = noticesByRunId.mapValues { (runId, notices) ->\n    val status = getStatus(runId)\n    val isActive = runId in activeRunIds || status == AgentRunStatus.QUEUED || status == AgentRunStatus.RUNNING\n    if (isActive) notices else notices.filter { it.persistent }\n}.filterValues { it.isNotEmpty() }\n\ninternal fun visibleChatRunNotices(\n    stored: List<ChatRunNotice>,\n    timelineNotices: List<String>,\n    isRunActive: Boolean\n): List<String> {\n    val fromStore = stored.filter { it.persistent || isRunActive }.map { it.message }\n    return (timelineNotices + fromStore).distinct()\n}\n\ninternal fun timelineNoticeMessages(timeline: List<AssistantTimelineItem>): List<String> = timeline.filter { it.type == AssistantTimelineItemType.NOTICE }.map { it.content }.filter { it.isNotBlank() }\n\ninternal fun loadingStatesForLatestAssistant(\n    platformCount: Int,\n    latestAssistantRow: List<MessageV2>?,\n    runsById: Map<String, AgentRun>,\n    activeRunIds: Set<String>\n): List<ChatViewModel.LoadingState> = List(platformCount) { platformIndex ->\n    val runId = latestAssistantRow?.getOrNull(platformIndex)?.currentRunId\n    val status = runId?.let(runsById::get)?.status\n    if (runId in activeRunIds || status == AgentRunStatus.QUEUED || status == AgentRunStatus.RUNNING) {\n        ChatViewModel.LoadingState.Loading\n    } else {\n        ChatViewModel.LoadingState.Idle\n    }\n}\n\ninternal fun groupPersistedMessages(\n    messages: List<MessageV2>,\n    enabledPlatformsInChat: List<String>,\n    chatId: Int\n): ChatViewModel.GroupedMessages {\n    val userMessages = mutableListOf<MessageV2>()\n    val assistantMessages = mutableListOf<MutableList<MessageV2>>()\n    messages.forEach { message ->\n        if (message.platformType == null) {\n            userMessages += message\n            assistantMessages += mutableListOf<MessageV2>()\n        } else {\n            assistantMessages.lastOrNull()?.add(message)\n        }\n    }\n    return ChatViewModel.GroupedMessages(\n        userMessages = userMessages,\n        assistantMessages = assistantMessages.map { row ->\n            normalizeAssistantRow(row, enabledPlatformsInChat, chatId)\n        }\n    )\n}\n\ninternal fun groupedMessagesThroughTurn(\n    groupedMessages: ChatViewModel.GroupedMessages,\n    turnIndex: Int\n): ChatViewModel.GroupedMessages = groupedMessages.copy(\n    userMessages = groupedMessages.userMessages.take(turnIndex + 1),\n    assistantMessages = groupedMessages.assistantMessages.take(turnIndex + 1)\n)\n\ninternal fun resolvePlatformModel(\n    platform: PlatformV2,\n    chatPlatformModels: Map<String, String>\n): PlatformV2 {\n    val chatModel = chatPlatformModels[platform.uid]?.trim().orEmpty()\n    if (chatModel.isBlank() || chatModel == platform.model) return platform\n\n    return platform.copy(model = chatModel)\n}\n\ninternal fun resolveSelectedPlatforms(\n    selectedProfileUids: List<String>,\n    configuredPlatforms: List<PlatformV2>\n): List<IndexedValue<PlatformV2>> {\n    val platformsByUid = configuredPlatforms.associateBy(PlatformV2::uid)\n    return selectedProfileUids.mapIndexedNotNull { index, uid ->\n        platformsByUid[uid]?.let { IndexedValue(index, it) }\n    }\n}\n\ninternal suspend fun <T> persistBeforeProvider(\n    persist: suspend () -> T,\n    startProvider: suspend (T) -> Unit,\n    onFailure: suspend (Throwable) -> Unit\n) {\n    val persisted = try {\n        persist()\n    } catch (error: CancellationException) {\n        throw error\n    } catch (error: Throwable) {\n        onFailure(error)\n        return\n    }\n    startProvider(persisted)\n}\n\ninternal fun mergePersistedAssistantRow(\n    currentRow: List<MessageV2>,\n    selectedProfileUids: List<String>,\n    persistedMessages: List<MessageV2>,\n    chatId: Int\n): List<MessageV2> {\n    val currentByProfile = currentRow.associateBy { it.platformType }\n    val persistedByProfile = persistedMessages.associateBy { it.platformType }\n    return selectedProfileUids.map { profileUid ->\n        persistedByProfile[profileUid]\n            ?: currentByProfile[profileUid]\n            ?: createEmptyAssistantMessage(chatId, profileUid)\n    }\n}\n\ninternal fun formatAssistantExport(\n    platformName: String,\n    message: MessageV2,\n    toolEventsByRun: Map<String, List<ToolEvent>>,\n    toolTraceLabels: ToolTraceLabels = ToolTraceLabels.Default,\n    legacyOrderNotice: String = LEGACY_ORDER_NOTICE\n): String = buildString {\n    appendLine(\"**Assistant ($platformName):**\")\n    val trace = message.effectiveRunId()\n        ?.let(toolEventsByRun::get)\n        .orEmpty()\n    val timeline = message.effectiveTimeline()\n    val content = message.effectiveContent()\n    val thoughts = message.effectiveThoughts()\n    if (hasUnavailableAssistantOrder(timeline, content, thoughts, trace.isNotEmpty())) {\n        appendLine(\"> $legacyOrderNotice\")\n        appendLine()\n        thoughts.takeIf(String::isNotBlank)?.let {\n            appendLine(\"<details><summary>Thinking (order unavailable)</summary>\")\n            appendLine()\n            appendLine(it)\n            appendLine()\n            appendLine(\"</details>\")\n            appendLine()\n        }\n        content.takeIf(String::isNotBlank)?.let {\n            appendLine(it)\n            appendLine()\n        }\n        formatToolTraceMarkdown(trace, toolTraceLabels).takeIf { it.isNotBlank() }?.let {\n            appendLine(it)\n            appendLine()\n        }\n    } else if (timeline.isEmpty()) {\n        appendLine(content)\n        appendLine()\n        formatToolTraceMarkdown(trace, toolTraceLabels).takeIf { it.isNotBlank() }?.let {\n            appendLine(it)\n            appendLine()\n        }\n    } else {\n        val traceBySequence = trace.associateBy(ToolEvent::sequence)\n        val renderedSequences = timeline.mapNotNull { it.toolSequence }.toSet()\n        timeline.forEach { item ->\n            when (item.type) {\n                AssistantTimelineItemType.TEXT -> appendLine(item.content)\n\n                AssistantTimelineItemType.THINKING -> {\n                    appendLine(\"<details><summary>Thinking</summary>\")\n                    appendLine()\n                    appendLine(item.content)\n                    appendLine()\n                    appendLine(\"</details>\")\n                }\n\n                AssistantTimelineItemType.TOOL ->\n                    item.toolSequence\n                        ?.let(traceBySequence::get)\n                        ?.let { appendLine(formatToolTraceMarkdown(listOf(it), toolTraceLabels)) }\n\n                AssistantTimelineItemType.NOTICE -> appendLine(\"> ${item.content}\")\n\n                AssistantTimelineItemType.LEGACY_ORDER -> Unit\n            }\n            appendLine()\n        }\n        trace.filterNot { it.sequence in renderedSequences }\n            .takeIf { it.isNotEmpty() }\n            ?.let {\n                appendLine(formatToolTraceMarkdown(it, toolTraceLabels))\n                appendLine()\n            }\n    }\n}\n\nprivate fun isPersistableMessage(message: MessageV2): Boolean =\n    message.effectiveContent().isNotBlank() ||\n        message.effectiveThoughts().isNotBlank() ||\n        message.effectiveTimeline().isNotEmpty() ||\n        message.attachments.isNotEmpty() ||\n        message.currentRunId != null\n\ninternal fun persistableMessages(groupedMessages: ChatViewModel.GroupedMessages): List<MessageV2> {\n    val estimatedSize = groupedMessages.userMessages.size + (groupedMessages.assistantMessages.size * 2)\n    val result = ArrayList<MessageV2>(estimatedSize)\n    for (msg in groupedMessages.userMessages) {\n        if (isPersistableMessage(msg)) {\n            result.add(msg)\n        }\n    }\n    for (row in groupedMessages.assistantMessages) {\n        for (msg in row) {\n            if (isPersistableMessage(msg)) {\n                result.add(msg)\n            }\n        }\n    }\n    result.sortBy { it.createdAt }\n    return result\n}\n\ninternal fun createEmptyAssistantMessage(chatId: Int, platformUid: String): MessageV2 = MessageV2(\n    chatId = chatId,\n    content = \"\",\n    platformType = platformUid\n)\n\ninternal fun createRetryAssistantMessage(\n    currentMessage: MessageV2,\n    chatId: Int,\n    platformUid: String\n): MessageV2 = createEmptyAssistantMessage(chatId, platformUid).copy(\n    revisions = currentMessage.revisions\n)\n\ninternal fun normalizeAssistantRow(\n    assistantMessages: List<MessageV2>,\n    enabledPlatformsInChat: List<String>,\n    chatId: Int\n): List<MessageV2> {\n    if (enabledPlatformsInChat.isEmpty()) return assistantMessages\n\n    val consumedIndexes = mutableSetOf<Int>()\n    val normalizedMessages = enabledPlatformsInChat.map { platformUid ->\n        val matchedIndex = assistantMessages.indices.firstOrNull { index ->\n            index !in consumedIndexes && assistantMessages[index].platformType == platformUid\n        }\n\n        if (matchedIndex == null) {\n            createEmptyAssistantMessage(chatId, platformUid)\n        } else {\n            consumedIndexes += matchedIndex\n            assistantMessages[matchedIndex]\n        }\n    }\n    val overflowMessages = assistantMessages.filterIndexed { index, _ -> index !in consumedIndexes }\n\n    return normalizedMessages + overflowMessages\n}\n\ninternal fun updateAssistantSlot(\n    groupedMessages: ChatViewModel.GroupedMessages,\n    turnIndex: Int,\n    platformIndex: Int,\n    transform: (MessageV2) -> MessageV2\n): ChatViewModel.GroupedMessages {\n    if (turnIndex !in groupedMessages.assistantMessages.indices) return groupedMessages\n\n    val currentTurnMessages = groupedMessages.assistantMessages[turnIndex]\n    if (platformIndex !in currentTurnMessages.indices) return groupedMessages\n\n    val updatedTurnMessages = currentTurnMessages.toMutableList()\n    val updatedMessage = transform(updatedTurnMessages[platformIndex])\n    if (updatedMessage == updatedTurnMessages[platformIndex]) return groupedMessages\n\n    updatedTurnMessages[platformIndex] = updatedMessage\n    val assistantMessages = groupedMessages.assistantMessages.toMutableList()\n    assistantMessages[turnIndex] = updatedTurnMessages\n\n    return groupedMessages.copy(assistantMessages = assistantMessages)\n}\n