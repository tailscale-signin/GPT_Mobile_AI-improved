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
import dev.chungjungsoo.gptmobile.util.ExportHelper
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GroupedMessages(
    val userMessages: List<MessageV2> = emptyList(),
    val assistantMessages: List<List<MessageV2>> = emptyList()
)

data class MessageEditSession(
    val message: MessageV2,
    val isAssistant: Boolean = false,
    val turnIndex: Int = -1,
    val platformIndex: Int = -1,
    val attachments: List<ChatAttachmentDraft> = emptyList()
)

data class ChatAttachmentDraft(
    val sourceFilePath: String,
    val preparedFilePath: String? = null,
    val status: Status = Status.Preparing,
    val attachment: dev.chungjungsoo.gptmobile.data.database.entity.Attachment? = null,
    val errorMessage: String? = null,
    val notice: String? = null
) {
    enum class Status {
        Preparing,
        Ready,
        Failed
    }
}

data class ChatRunNotice(
    val id: String = UUID.randomUUID().toString(),
    val runId: String,
    val message: String
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val localModelRepository: LocalModelRepository,
    private val modelCatalogRepository: ModelCatalogRepository,
    private val toolConnectionRepository: ToolConnectionRepository,
    private val agentRunCoordinator: AgentRunCoordinator,
    private val attachmentUploadCoordinator: AttachmentUploadCoordinator,
    private val startupRecoveryGate: StartupRecoveryGate
) : ViewModel() {

    enum class LoadingState {
        Idle,
        Loading,
        Cancelling
    }

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

    private val _selectedAttachments = MutableStateFlow<List<ChatAttachmentDraft>>(emptyList())
    val selectedAttachments = _selectedAttachments.asStateFlow()

    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice = _attachmentNotice.asStateFlow()

    private val _groupedMessages = MutableStateFlow(GroupedMessages())
    val groupedMessages = _groupedMessages.asStateFlow()

    private val _agentRunsById = MutableStateFlow<Map<String, AgentRun>>(emptyMap())
    val agentRunsById = _agentRunsById.asStateFlow()

    private val _runNoticesById = MutableStateFlow<Map<String, List<ChatRunNotice>>>(emptyMap())
    val runNoticesById = _runNoticesById.asStateFlow()

    private val _toolEventsByRun = MutableStateFlow<Map<String, List<ToolEvent>>>(emptyMap())
    val toolEventsByRun = _toolEventsByRun.asStateFlow()

    private val _loadingStates = MutableStateFlow<List<LoadingState>>(
        List(enabledPlatformsInChat.size) { LoadingState.Idle }
    )
    val loadingStates = _loadingStates.asStateFlow()

    private val _indexStates = MutableStateFlow<List<Int>>(emptyList())
    val indexStates = _indexStates.asStateFlow()

    private val _selectedText = MutableStateFlow("")
    val selectedText = _selectedText.asStateFlow()

    private var pendingQuestionText: String? = null
    private var pendingQuestionJob: Job? = null

    init {
        loadChatRoom()
        loadPlatforms()
        observeAgentRuns()
        observeAgentNotices()
        loadAvailableChatTools()
        viewModelScope.launch {
            _catalogEntries.value = modelCatalogRepository.getCachedVisibleEntries()
        }
    }

    fun toggleSessionPlatformDisabled(uid: String) {
        _sessionDisabledPlatformUids.update { current ->
            if (uid in current) current - uid else current + uid
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
        viewModelScope.launch {
            agentRunCoordinator.cancelActiveRuns()
            _loadingStates.update { List(enabledPlatformsInChat.size) { LoadingState.Idle } }
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

        val messageIndex = userMessages.indexOfFirst { it.id == editedMessage.id }
        if (messageIndex == -1) return false

        val updatedUserMessages = userMessages.toMutableList()
        updatedUserMessages[messageIndex] = editedMessage.copy(
            attachments = attachments.mapNotNull { it.attachment },
            createdAt = currentTimeStamp
        )

        val remainingUserMessages = updatedUserMessages.take(messageIndex + 1)
        val remainingAssistantMessages = assistantMessages.take(messageIndex)

        _groupedMessages.update {
            GroupedMessages(
                userMessages = remainingUserMessages,
                assistantMessages = remainingAssistantMessages
            )
        }

        closeUserMessageEditDialog()
        resendFromTurn(messageIndex, editedMessage.content, attachments)
        return true
    }

    fun saveAssistantMessageEdit(
        editedContent: String,
        attachments: List<ChatAttachmentDraft>
    ): Boolean {
        val session = _messageEditSession.value ?: return false
        if (!session.isAssistant || session.turnIndex < 0 || session.platformIndex < 0) return false

        val turnIndex = session.turnIndex
        val platformIndex = session.platformIndex
        val targetMessage = _groupedMessages.value.assistantMessages.getOrNull(turnIndex)?.getOrNull(platformIndex) ?: return false

        val updatedTimeline = rebuildAssistantTimelineForEdit(
            timeline = targetMessage.effectiveTimeline(),
            newContent = editedContent
        )

        val updatedMessage = targetMessage.copy(
            content = editedContent,
            timeline = updatedTimeline,
            attachments = attachments.mapNotNull { it.attachment }
        )

        _groupedMessages.update {
            updateAssistantSlot(it, turnIndex, platformIndex) { updatedMessage }
        }

        if (updatedMessage.id > 0) {
            viewModelScope.launch {
                chatRepository.updateMessage(updatedMessage)
            }
        }

        closeAssistantMessageEditDialog()
        return true
    }

    fun openUserMessageEditDialog(message: MessageV2) {
        val drafts = message.attachments.map { attachment ->
            ChatAttachmentDraft(
                sourceFilePath = attachment.filePathForDisplay,
                preparedFilePath = attachment.filePathForDisplay,
                status = ChatAttachmentDraft.Status.Ready,
                attachment = attachment
            )
        }
        _messageEditSession.value = MessageEditSession(
            message = message,
            isAssistant = false,
            attachments = drafts
        )
    }

    fun openAssistantMessageEditDialog(turnIndex: Int, platformIndex: Int) {
        val message = _groupedMessages.value.assistantMessages.getOrNull(turnIndex)?.getOrNull(platformIndex) ?: return
        val drafts = message.attachments.map { attachment ->
            ChatAttachmentDraft(
                sourceFilePath = attachment.filePathForDisplay,
                preparedFilePath = attachment.filePathForDisplay,
                status = ChatAttachmentDraft.Status.Ready,
                attachment = attachment
            )
        }
        _messageEditSession.value = MessageEditSession(
            message = message,
            isAssistant = true,
            turnIndex = turnIndex,
            platformIndex = platformIndex,
            attachments = drafts
        )
    }

    fun closeUserMessageEditDialog() {
        if (_messageEditSession.value?.isAssistant == false) {
            _messageEditSession.value = null
        }
    }

    fun closeAssistantMessageEditDialog() {
        if (_messageEditSession.value?.isAssistant == true) {
            _messageEditSession.value = null
        }
    }

    fun openChatTitleDialog() {
        _isChatTitleDialogOpen.value = true
    }

    fun closeChatTitleDialog() {
        _isChatTitleDialogOpen.value = false
    }

    fun openChatModelDialog() {
        _isChatModelDialogOpen.value = true
    }

    fun closeChatModelDialog() {
        _isChatModelDialogOpen.value = false
    }

    fun updateChatPlatformModels(models: Map<String, String>) {
        _chatPlatformModels.value = models
        if (_chatRoom.value.id > 0) {
            viewModelScope.launch {
                chatRepository.updateChatPlatformModels(_chatRoom.value.id, models)
            }
        }
    }

    fun openSelectTextSheet(text: String) {
        _selectedText.value = text
        _isSelectTextSheetOpen.value = true
    }

    fun closeSelectTextSheet() {
        _isSelectTextSheetOpen.value = false
        _selectedText.value = ""
    }

    fun showPreviousAssistantRevision(turnIndex: Int, platformIndex: Int) {
        _groupedMessages.update {
            updateAssistantSlot(it, turnIndex, platformIndex) { msg ->
                msg.copy(activeRevisionIndex = (msg.activeRevisionIndex + 1).coerceAtMost(msg.revisions.lastIndex))
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

    fun retryChat(turnIndex: Int, platformIndex: Int) {
        val userMessages = _groupedMessages.value.userMessages
        val assistantMessages = _groupedMessages.value.assistantMessages
        val userMessage = userMessages.getOrNull(turnIndex) ?: return
        val currentAssistantMessage = assistantMessages.getOrNull(turnIndex)?.getOrNull(platformIndex) ?: return
        val platformUid = enabledPlatformsInChat.getOrNull(platformIndex) ?: return
        val platform = _platformsInApp.value.find { it.uid == platformUid } ?: return

        val platformWithChatModel = platform.copy(
            model = _chatPlatformModels.value[platformUid] ?: platform.model
        )

        val runId = UUID.randomUUID().toString()
        _loadingStates.update { current ->
            current.mapIndexed { idx, state -> if (idx == platformIndex) LoadingState.Loading else state }
        }

        viewModelScope.launch {
            safePersistTurn(
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

    fun generateDefaultChatTitle(currentTitle: String) {
        val firstMessage = _groupedMessages.value.userMessages.firstOrNull()?.content?.take(30)
        val defaultTitle = if (!firstMessage.isNullOrBlank()) firstMessage else "Chat"
        updateChatTitle(defaultTitle)
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
                val platformWithChatModel = platform.copy(
                    model = _chatPlatformModels.value[platformUid] ?: platform.model
                )
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

    private fun resendFromTurn(turnIndex: Int, questionText: String, attachments: List<ChatAttachmentDraft>) {
        val userMessage = _groupedMessages.value.userMessages[turnIndex]
        val activePlatforms = enabledPlatformsInChat.filter { it !in _sessionDisabledPlatformUids.value }
        val assistantDrafts = enabledPlatformsInChat.map { uid ->
            MessageV2(
                chatId = _chatRoom.value.id,
                content = if (uid in _sessionDisabledPlatformUids.value) "[Platform disabled for session]" else "",
                platformType = uid,
                createdAt = currentTimeStamp
            )
        }

        _groupedMessages.update {
            it.copy(
                assistantMessages = it.assistantMessages + listOf(assistantDrafts)
            )
        }

        val activeIndices = enabledPlatformsInChat.mapIndexedNotNull { index, uid ->
            if (uid !in _sessionDisabledPlatformUids.value) index else null
        }

        _loadingStates.update {
            List(enabledPlatformsInChat.size) { idx ->
                if (idx in activeIndices) LoadingState.Loading else LoadingState.Idle
            }
        }

        viewModelScope.launch {
            val runs = activeIndices.map { index ->
                val platformUid = enabledPlatformsInChat[index]
                val platform = _platformsInApp.value.find { it.uid == platformUid }
                    ?: PlatformV2(name = platformUid, uid = platformUid, compatibleType = dev.chungjungsoo.gptmobile.data.database.entity.PlatformTypeV2.OPENAI)
                val platformWithChatModel = platform.copy(
                    model = _chatPlatformModels.value[platformUid] ?: platform.model
                )
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

    private fun loadChatRoom() {
        if (chatRoomId > 0) {
            viewModelScope.launch {
                val room = chatRepository.getChatRoom(chatRoomId)
                if (room != null) {
                    _chatRoom.value = room
                    loadChatMessages(chatRoomId)
                }
            }
        }
    }

    private fun loadChatMessages(roomId: Int) {
        viewModelScope.launch {
            val messages = chatRepository.getMessagesForChat(roomId)
            val users = messages.filter { it.platformType == null }
            val assistants = messages.filter { it.platformType != null }

            val groupedAssistants = mutableListOf<List<MessageV2>>()
            for (i in users.indices) {
                val turnAssistants = enabledPlatformsInChat.map { uid ->
                    assistants.find { it.chatId == roomId && it.platformType == uid }
                        ?: MessageV2(chatId = roomId, content = "", platformType = uid)
                }
                groupedAssistants.add(turnAssistants)
            }

            _groupedMessages.value = GroupedMessages(
                userMessages = users,
                assistantMessages = groupedAssistants
            )
            _indexStates.value = List(users.size) { 0 }
        }
    }

    private fun loadPlatforms() {
        viewModelScope.launch {
            settingRepository.observePlatforms().collect { list ->
                _platformsInApp.value = list
                _enabledPlatformsInApp.value = list.filter { it.enabled }
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

    private fun observeAgentNotices() {
        viewModelScope.launch {
            agentRunCoordinator.observeNotices().collect { notices ->
                _runNoticesById.value = notices.groupBy { it.runId }.mapValues { entry ->
                    entry.value.map { ChatRunNotice(runId = it.runId, message = it.message) }
                }
            }
        }
    }

    private fun loadAvailableChatTools() {
        viewModelScope.launch {
            toolConnectionRepository.observeAllTools().collect { tools ->
                _availableChatTools.value = tools
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

    private fun groupedMessagesThroughTurn(grouped: GroupedMessages, turnIndex: Int): GroupedMessages {
        return GroupedMessages(
            userMessages = grouped.userMessages.take(turnIndex + 1),
            assistantMessages = grouped.assistantMessages.take(turnIndex + 1)
        )
    }

    private suspend fun safePersistTurn(
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
}
