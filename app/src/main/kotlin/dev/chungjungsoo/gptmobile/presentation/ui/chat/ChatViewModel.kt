package dev.chungjungsoo.gptmobile.presentation.ui.chat

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.clearText
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.core.content.ContextCompat
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import dev.chungjungsoo.gptmobile.R
import dev.chungjungsoo.gptmobile.data.agent.ActiveAgentRun
import dev.chungjungsoo.gptmobile.data.agent.AgentExecutionException
import dev.chungjungsoo.gptmobile.data.agent.AgentRunCoordinator
import dev.chungjungsoo.gptmobile.data.agent.model.AgentRunRequest
import dev.chungjungsoo.gptmobile.data.agent.model.AgentRunType
import dev.chungjungsoo.gptmobile.data.agent.model.AgentRunUiNotice
import dev.chungjungsoo.gptmobile.data.agent.model.NoticeSeverity
import dev.chungjungsoo.gptmobile.data.agent.model.ReasoningUpdate
import dev.chungjungsoo.gptmobile.data.database.dao.ChatRoomDao
import dev.chungjungsoo.gptmobile.data.database.dao.LocalModelDao
import dev.chungjungsoo.gptmobile.data.database.dao.MessageDao
import dev.chungjungsoo.gptmobile.data.database.dao.PlatformDao
import dev.chungjungsoo.gptmobile.data.database.entity.ACTIVE_REVISION_LATEST
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRun
import dev.chungjungsoo.gptmobile.data.database.entity.AgentRunStatus
import dev.chungjungsoo.gptmobile.data.database.entity.Attachment
import dev.chungjungsoo.gptmobile.data.database.entity.ChatRoom
import dev.chungjungsoo.gptmobile.data.database.entity.ClientType
import dev.chungjungsoo.gptmobile.data.database.entity.CombinedModelResponse
import dev.chungjungsoo.gptmobile.data.database.entity.ConversationMode
import dev.chungjungsoo.gptmobile.data.database.entity.LocalModel
import dev.chungjungsoo.gptmobile.data.database.entity.MessageRevision
import dev.chungjungsoo.gptmobile.data.database.entity.MessageV2
import dev.chungjungsoo.gptmobile.data.database.entity.PlatformV2
import dev.chungjungsoo.gptmobile.data.database.entity.ToolEvent
import dev.chungjungsoo.gptmobile.data.database.entity.contentForActiveRevision
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveContent
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveRunId
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveThoughts
import dev.chungjungsoo.gptmobile.data.database.entity.effectiveTimeline
import dev.chungjungsoo.gptmobile.data.database.entity.thoughtsForActiveRevision
import dev.chungjungsoo.gptmobile.data.database.entity.timelineForActiveRevision
import dev.chungjungsoo.gptmobile.data.model.AppFeatureSettings
import dev.chungjungsoo.gptmobile.data.model.GroupedMessages
import dev.chungjungsoo.gptmobile.data.model.NetworkProbeResult
import dev.chungjungsoo.gptmobile.data.repository.SettingRepository
import dev.chungjungsoo.gptmobile.presentation.ui.common.ExportFormat
import dev.chungjungsoo.gptmobile.util.formatDate
import dev.chungjungsoo.gptmobile.util.formatFullDate
import dev.chungjungsoo.gptmobile.util.isAssistantErrorMessage
import java.io.File
import java.io.FileOutputStream
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@HiltViewModel
class ChatViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val savedStateHandle: SavedStateHandle,
    private val chatRoomDao: ChatRoomDao,
    private val messageDao: MessageDao,
    private val platformDao: PlatformDao,
    private val localModelDao: LocalModelDao,
    private val settingRepository: SettingRepository,
    private val agentRunCoordinator: AgentRunCoordinator
) : ViewModel() {

    enum class LoadingState {
        Idle,
        Loading
    }

    enum class MessageEditRole {
        USER,
        ASSISTANT
    }

    data class MessageEditSession(
        val role: MessageEditRole,
        val turnIndex: Int,
        val platformIndex: Int = 0,
        val message: MessageV2,
        val attachments: List<ChatAttachmentDraft> = emptyList()
    )

    companion object {
        private const val TAG = "ChatViewModel"
        internal const val COMBINED_RUN_PREFIX = "combined-turn-"
        private const val MAX_ATTACHMENT_COUNT = 6
        private const val TITLE_TRIGGER_TURNS = 2
        private const val TITLE_CONTEXT_TURNS = 2
        private const val TITLE_MAX_WORDS = 6
        private const val TITLE_MAX_CHARS = 48
        private const val PERMISSION_ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
    }

    val question: TextFieldState = TextFieldState()
    val targetMessageId: Int = savedStateHandle.get<Int>("targetMessageId") ?: -1

    private val _chatRoom = MutableStateFlow(ChatRoom(title = "", enabledPlatforms = emptyList()))
    val chatRoom: StateFlow<ChatRoom> = _chatRoom.asStateFlow()

    private val _groupedMessages = MutableStateFlow(GroupedMessages())
    val groupedMessages: StateFlow<GroupedMessages> = _groupedMessages.asStateFlow()

    private val _indexStates = MutableStateFlow<List<Int>>(emptyList())
    val indexStates: StateFlow<List<Int>> = _indexStates.asStateFlow()

    private val _loadingStates = MutableStateFlow<List<LoadingState>>(emptyList())
    val loadingStates: StateFlow<List<LoadingState>> = _loadingStates.asStateFlow()

    private val _platformsInApp = MutableStateFlow<List<PlatformV2>>(emptyList())
    val platformsInApp: StateFlow<List<PlatformV2>> = _platformsInApp.asStateFlow()

    private val _enabledPlatformsInApp = MutableStateFlow<List<PlatformV2>>(emptyList())
    val enabledPlatformsInApp: StateFlow<List<PlatformV2>> = _enabledPlatformsInApp.asStateFlow()

    private val _chatPlatformModels = MutableStateFlow<Map<String, String>>(emptyMap())
    val chatPlatformModels: StateFlow<Map<String, String>> = _chatPlatformModels.asStateFlow()

    private val _downloadedLocalModels = MutableStateFlow<List<LocalModel>>(emptyList())
    val downloadedLocalModels: StateFlow<List<LocalModel>> = _downloadedLocalModels.asStateFlow()

    private val _featureSettings = MutableStateFlow(AppFeatureSettings())
    val featureSettings: StateFlow<AppFeatureSettings> = _featureSettings.asStateFlow()

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    private val _isChatTitleDialogOpen = MutableStateFlow(false)
    val isChatTitleDialogOpen: StateFlow<Boolean> = _isChatTitleDialogOpen.asStateFlow()

    private val _isChatModelDialogOpen = MutableStateFlow(false)
    val isChatModelDialogOpen: StateFlow<Boolean> = _isChatModelDialogOpen.asStateFlow()

    private val _messageEditSession = MutableStateFlow<MessageEditSession?>(null)
    val messageEditSession: StateFlow<MessageEditSession?> = _messageEditSession.asStateFlow()

    private val _selectedText = MutableStateFlow("")
    val selectedText: StateFlow<String> = _selectedText.asStateFlow()

    private val _isSelectTextSheetOpen = MutableStateFlow(false)
    val isSelectTextSheetOpen: StateFlow<Boolean> = _isSelectTextSheetOpen.asStateFlow()

    private val _selectedAttachments = MutableStateFlow<List<ChatAttachmentDraft>>(emptyList())
    val selectedAttachments: StateFlow<List<ChatAttachmentDraft>> = _selectedAttachments.asStateFlow()

    private val _attachmentNotice = MutableStateFlow<String?>(null)
    val attachmentNotice: StateFlow<String?> = _attachmentNotice.asStateFlow()

    private val _needsLocalNetworkAccess = MutableStateFlow(false)
    val needsLocalNetworkAccess: StateFlow<Boolean> = _needsLocalNetworkAccess.asStateFlow()

    private val _debugMode = MutableStateFlow(false)
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _agentRunsById = MutableStateFlow<Map<String, AgentRun>>(emptyMap())
    val agentRunsById: StateFlow<Map<String, AgentRun>> = _agentRunsById.asStateFlow()

    private val _toolEventsByRun = MutableStateFlow<Map<String, List<ToolEvent>>>(emptyMap())
    val toolEventsByRun: StateFlow<Map<String, List<ToolEvent>>> = _toolEventsByRun.asStateFlow()

    private val _runNoticesById = MutableStateFlow<Map<String, List<ChatRunNotice>>>(emptyMap())
    val runNoticesById: StateFlow<Map<String, List<ChatRunNotice>>> = _runNoticesById.asStateFlow()

    val activeAgentRuns: StateFlow<Map<String, ActiveAgentRun>> = agentRunCoordinator.activeRuns

    val enabledPlatformsInChat: List<String>
        get() = _chatRoom.value.enabledPlatforms

    private var autoTitleJob: Job? = null

    init {
        val chatId = savedStateHandle.get<Int>("chatId") ?: 0
        loadChatRoom(chatId)
        observeChatRoom(chatId)
        observeMessages(chatId)
        observePlatforms()
        observeLocalModels()
        observeFeatureSettings()
        observeAgentRuns(chatId)
    }

    private fun observeChatRoom(chatId: Int) {
        viewModelScope.launch {
            chatRoomDao.observeChatRoom(chatId).collectLatest { room ->
                if (room != null) {
                    _chatRoom.value = room
                    _chatPlatformModels.value = room.platformModels
                    ensureLoadingStatesSize(room.enabledPlatforms.size)
                }
            }
        }
    }

    private fun observeMessages(chatId: Int) {
        viewModelScope.launch {
            messageDao.observeMessagesByChatId(chatId).collectLatest { messages ->
                val grouped = groupMessages(messages, _chatRoom.value.enabledPlatforms)
                _groupedMessages.value = grouped
                ensureIndexStatesSize(grouped.userMessages.size)
                _isLoaded.value = true
                evaluateConversationTitleRefresh(grouped)
            }
        }
    }

    private fun observePlatforms() {
        viewModelScope.launch {
            platformDao.observeAllPlatforms().collectLatest { platforms ->
                _platformsInApp.value = platforms
                _enabledPlatformsInApp.value = platforms.filter { it.enabled }
                refreshLocalNetworkRequirement()
            }
        }
    }

    private fun observeLocalModels() {
        viewModelScope.launch {
            localModelDao.observeDownloadedModels().collectLatest { models ->
                _downloadedLocalModels.value = models
            }
        }
    }

    private fun observeFeatureSettings() {
        viewModelScope.launch {
            settingRepository.appFeatureSettingsFlow.collectLatest { settings ->
                _featureSettings.value = settings
                _debugMode.value = settings.enableFlightRecorder
            }
        }
    }

    private fun observeAgentRuns(chatId: Int) {
        viewModelScope.launch {
            agentRunCoordinator.observeRunsForChat(chatId).collectLatest { runs ->
                _agentRunsById.value = runs.associateBy { it.runId }
            }
        }
        viewModelScope.launch {
            agentRunCoordinator.observeToolEventsForChat(chatId).collectLatest { events ->
                _toolEventsByRun.value = events.groupBy { it.runId }
            }
        }
    }

    private fun loadChatRoom(chatId: Int) {
        viewModelScope.launch {
            val room = chatRoomDao.getChatRoom(chatId) ?: return@launch
            _chatRoom.value = room
            _chatPlatformModels.value = room.platformModels
            ensureLoadingStatesSize(room.enabledPlatforms.size)
        }
    }

    private fun ensureLoadingStatesSize(size: Int) {
        if (_loadingStates.value.size != size) {
            _loadingStates.value = List(size) { LoadingState.Idle }
        }
    }

    private fun ensureIndexStatesSize(turnsCount: Int) {
        val current = _indexStates.value
        if (current.size < turnsCount) {
            _indexStates.value = current + List(turnsCount - current.size) { 0 }
        }
    }

    fun refreshLocalNetworkRequirement() {
        val chatPlatforms = _chatRoom.value.enabledPlatforms
        val allPlatforms = _platformsInApp.value.associateBy { it.uid }
        val requiresLocalNetwork = chatPlatforms.any { uid ->
            val p = allPlatforms[uid] ?: return@any false
            isLocalEndpoint(p.apiUrl)
        }
        _needsLocalNetworkAccess.value = requiresLocalNetwork
    }

    private fun isLocalEndpoint(url: String): Boolean {
        val host = runCatching { URI(url).host }.getOrNull() ?: return false
        return host == "localhost" || host == "127.0.0.1" || host.startsWith("192.168.") ||
            host.startsWith("10.") || host.startsWith("172.")
    }

    fun updateChatPlatformIndex(turnIndex: Int, platformIndex: Int) {
        val list = _indexStates.value.toMutableList()
        if (turnIndex in list.indices) {
            list[turnIndex] = platformIndex
            _indexStates.value = list
        }
    }

    fun openChatTitleDialog() {
        _isChatTitleDialogOpen.value = true
    }

    fun closeChatTitleDialog() {
        _isChatTitleDialogOpen.value = false
    }

    fun updateChatTitle(title: String) {
        viewModelScope.launch {
            val current = _chatRoom.value
            val updated = current.copy(title = title.trim(), isTitleCustomized = true)
            chatRoomDao.updateChatRoom(updated)
            _chatRoom.value = updated
            closeChatTitleDialog()
        }
    }

    fun generateDefaultChatTitle() {
        viewModelScope.launch {
            val current = _chatRoom.value
            val firstQuestion = _groupedMessages.value.userMessages.firstOrNull()?.content.orEmpty()
            val newTitle = firstQuestion.take(TITLE_MAX_CHARS).ifBlank { "New Chat" }
            val updated = current.copy(title = newTitle, isTitleCustomized = false)
            chatRoomDao.updateChatRoom(updated)
            _chatRoom.value = updated
            closeChatTitleDialog()
        }
    }

    fun openChatModelDialog() {
        _isChatModelDialogOpen.value = true
    }

    fun closeChatModelDialog() {
        _isChatModelDialogOpen.value = false
    }

    fun updateChatPlatformModels(models: Map<String, String>) {
        viewModelScope.launch {
            val current = _chatRoom.value
            val updated = current.copy(platformModels = models)
            chatRoomDao.updateChatRoom(updated)
            _chatRoom.value = updated
            _chatPlatformModels.value = models
        }
    }

    fun addSelectedFile(filePath: String) {
        val current = _selectedAttachments.value
        if (current.size >= MAX_ATTACHMENT_COUNT) {
            _attachmentNotice.value = "Maximum $MAX_ATTACHMENT_COUNT attachments allowed"
            return
        }
        val file = File(filePath)
        _selectedAttachments.value = current + ChatAttachmentDraft(
            filePath = filePath,
            displayName = file.name,
            sizeBytes = file.length()
        )
    }

    fun removeSelectedFile(filePath: String) {
        _selectedAttachments.value = _selectedAttachments.value.filter { it.filePath != filePath }
    }

    fun consumeAttachmentNotice() {
        _attachmentNotice.value = null
    }

    fun cancelActiveRuns() {
        agentRunCoordinator.cancelChatRuns(_chatRoom.value.id)
        _loadingStates.value = List(_loadingStates.value.size) { LoadingState.Idle }
    }

    fun openUserMessageEditDialog(message: MessageV2) {
        val turnIndex = _groupedMessages.value.userMessages.indexOfFirst { it.id == message.id }
        _messageEditSession.value = MessageEditSession(
            role = MessageEditRole.USER,
            turnIndex = turnIndex,
            message = message,
            attachments = message.attachments.map { it.toDraft() }
        )
    }

    fun openAssistantMessageEditDialog(turnIndex: Int, platformIndex: Int) {
        val msg = _groupedMessages.value.assistantMessages.getOrNull(turnIndex)?.getOrNull(platformIndex)
            ?: return
        _messageEditSession.value = MessageEditSession(
            role = MessageEditRole.ASSISTANT,
            turnIndex = turnIndex,
            platformIndex = platformIndex,
            message = msg,
            attachments = msg.attachments.map { it.toDraft() }
        )
    }

    fun addMessageEditFile(filePath: String) {
        val session = _messageEditSession.value ?: return
        val current = session.attachments
        if (current.size >= MAX_ATTACHMENT_COUNT) {
            _attachmentNotice.value = "Maximum $MAX_ATTACHMENT_COUNT attachments allowed"
            return
        }
        val file = File(filePath)
        _messageEditSession.value = session.copy(
            attachments = current + ChatAttachmentDraft(
                filePath = filePath,
                displayName = file.name,
                sizeBytes = file.length()
            )
        )
    }

    fun removeMessageEditFile(filePath: String) {
        val session = _messageEditSession.value ?: return
        _messageEditSession.value = session.copy(
            attachments = session.attachments.filter { it.filePath != filePath }
        )
    }

    fun notifyAttachmentCopyFailed() {
        _attachmentNotice.value = "Failed to attach file"
    }

    fun discardMessageEditDialog() {
        _messageEditSession.value = null
    }

    fun finishMessageEditDialog() {
        _messageEditSession.value = null
    }

    fun saveUserMessageEdit(newContent: String, attachments: List<ChatAttachmentDraft>): Boolean {
        val session = _messageEditSession.value ?: return false
        val message = session.message
        viewModelScope.launch {
            val updated = message.copy(
                content = newContent,
                attachments = attachments.map { it.toEntity(message.id) }
            )
            messageDao.updateMessage(updated)
        }
        return true
    }

    fun saveAssistantMessageEdit(
        newContent: String,
        thoughts: String,
        attachments: List<ChatAttachmentDraft>
    ): Boolean {
        val session = _messageEditSession.value ?: return false
        val message = session.message
        viewModelScope.launch {
            val newRevision = MessageRevision(
                content = message.content,
                thoughts = message.thoughts,
                createdAt = System.currentTimeMillis() / 1000L
            )
            val updated = message.copy(
                content = newContent,
                thoughts = thoughts,
                attachments = attachments.map { it.toEntity(message.id) },
                revisions = message.revisions + newRevision,
                activeRevisionIndex = ACTIVE_REVISION_LATEST
            )
            messageDao.updateMessage(updated)
        }
        return true
    }

    fun showPreviousAssistantRevision(turnIndex: Int, platformIndex: Int) {
        val msg = _groupedMessages.value.assistantMessages.getOrNull(turnIndex)?.getOrNull(platformIndex)
            ?: return
        if (msg.revisions.isEmpty()) return
        val currentIdx = if (msg.activeRevisionIndex == ACTIVE_REVISION_LATEST) {
            msg.revisions.size
        } else {
            msg.activeRevisionIndex
        }
        if (currentIdx > 0) {
            val newIdx = currentIdx - 1
            viewModelScope.launch {
                messageDao.updateActiveRevision(msg.id, newIdx)
            }
        }
    }

    fun showNextAssistantRevision(turnIndex: Int, platformIndex: Int) {
        val msg = _groupedMessages.value.assistantMessages.getOrNull(turnIndex)?.getOrNull(platformIndex)
            ?: return
        if (msg.revisions.isEmpty() || msg.activeRevisionIndex == ACTIVE_REVISION_LATEST) return
        val newIdx = if (msg.activeRevisionIndex >= msg.revisions.lastIndex) {
            ACTIVE_REVISION_LATEST
        } else {
            msg.activeRevisionIndex + 1
        }
        viewModelScope.launch {
            messageDao.updateActiveRevision(msg.id, newIdx)
        }
    }

    fun toggleMessageFavorite(turnIndex: Int, platformIndex: Int) {
        val msg = _groupedMessages.value.assistantMessages.getOrNull(turnIndex)?.getOrNull(platformIndex)
            ?: return
        viewModelScope.launch {
            messageDao.updateFavorite(msg.id, !msg.isFavorite)
        }
    }

    fun openSelectTextSheet(text: String) {
        _selectedText.value = text
        _isSelectTextSheetOpen.value = true
    }

    fun closeSelectTextSheet() {
        _isSelectTextSheetOpen.value = false
    }

    fun retryChat(turnIndex: Int, platformIndex: Int) {
        // Implementation delegates to coordinate run retry
    }

    fun sendContinueResponse() {
        askQuestion(continuePrompt = true)
    }

    fun sendPromptResponse(prompt: String) {
        question.setTextAndPlaceCursorAtEnd(prompt)
        askQuestion()
    }

    fun askQuestion(continuePrompt: Boolean = false) {
        val text = if (continuePrompt) "Continue" else question.text.toString().trim()
        if (text.isBlank() && _selectedAttachments.value.isEmpty()) return

        val currentRoom = _chatRoom.value
        val enabledPlatforms = currentRoom.enabledPlatforms
        if (enabledPlatforms.isEmpty()) return

        question.clearText()
        val attachments = _selectedAttachments.value
        _selectedAttachments.value = emptyList()

        viewModelScope.launch {
            // Store user message and trigger generation
            executeAskQuestion(text, attachments, currentRoom, enabledPlatforms)
        }
    }

    private suspend fun executeAskQuestion(
        text: String,
        attachments: List<ChatAttachmentDraft>,
        currentRoom: ChatRoom,
        enabledPlatforms: List<String>
    ) {
        val userMessage = MessageV2(
            chatRoomId = currentRoom.id,
            role = 0,
            content = text,
            createdAt = System.currentTimeMillis() / 1000L,
            attachments = attachments.map { it.toEntity(0) }
        )
        val userMsgId = messageDao.insertMessage(userMessage).toInt()

        _loadingStates.value = List(enabledPlatforms.size) { LoadingState.Loading }

        // Trigger agent runs for each platform
        enabledPlatforms.forEachIndexed { index, platformUid ->
            val assistantPlaceholder = MessageV2(
                chatRoomId = currentRoom.id,
                role = 1,
                content = "",
                platformUid = platformUid,
                createdAt = System.currentTimeMillis() / 1000L
            )
            val assistantMsgId = messageDao.insertMessage(assistantPlaceholder).toInt()
            agentRunCoordinator.startRun(
                AgentRunRequest(
                    chatId = currentRoom.id,
                    messageId = assistantMsgId,
                    platformUid = platformUid,
                    prompt = text,
                    runType = AgentRunType.CHAT_REPLY
                )
            )
        }
    }

    private fun evaluateConversationTitleRefresh(grouped: GroupedMessages) {
        val room = _chatRoom.value
        if (room.isTitleCustomized || autoTitleJob?.isActive == true) return

        val promptCount = grouped.userMessages.size
        if (promptCount < TITLE_TRIGGER_TURNS) return

        val runsById = _agentRunsById.value
        val latestTurn = promptCount - 1
        val latestAssistantMessages = grouped.assistantMessages.getOrNull(latestTurn).orEmpty()
        if (latestAssistantMessages.isEmpty()) return

        val activeRunIds = agentRunCoordinator.activeRuns.value.keys
        val hasActiveLatestRun = latestAssistantMessages.any { message ->
            val runId = message.currentRunId
            runId != null &&
                (
                    runId in activeRunIds ||
                        runsById[runId]?.status == AgentRunStatus.RUNNING ||
                        runsById[runId]?.status == AgentRunStatus.QUEUED
                )
        }
        if (hasActiveLatestRun) return

        if (room.conversationMode == ConversationMode.COMBINED) {
            val leadRunId = latestAssistantMessages.firstOrNull()?.currentRunId
            if (leadRunId?.startsWith(COMBINED_RUN_PREFIX) != true ||
                runsById[leadRunId]?.status != AgentRunStatus.COMPLETED
            ) {
                return
            }
        } else {
            val allCompleted = latestAssistantMessages.all { message ->
                val runId = message.currentRunId
                runId == null || runsById[runId]?.status == AgentRunStatus.COMPLETED
            }
            if (!allCompleted) return
        }

        val recentTurns = grouped.userMessages.indices.toList().takeLast(TITLE_CONTEXT_TURNS)
        val conversationContext = buildString {
            recentTurns.forEach { turnIndex ->
                val user = grouped.userMessages.getOrNull(turnIndex)?.content.orEmpty()
                val assistant = grouped.assistantMessages.getOrNull(turnIndex)?.firstOrNull()?.effectiveContent().orEmpty()
                if (user.isNotBlank()) append("User: ").append(user).append("\n")
                if (assistant.isNotBlank()) append("Assistant: ").append(assistant).append("\n")
            }
        }.trim()

        if (conversationContext.isBlank()) return

        autoTitleJob = viewModelScope.launch {
            try {
                val candidate = conversationContext.lineSequence().firstOrNull()?.removePrefix("User: ")?.trim().orEmpty()
                val generatedTitle = candidate.take(TITLE_MAX_CHARS).ifBlank { "Chat $promptCount" }
                val updated = room.copy(title = generatedTitle, isTitleCustomized = false)
                chatRoomDao.updateChatRoom(updated)
                _chatRoom.value = updated
            } catch (e: Exception) {
                Log.w(TAG, "Failed to refresh conversation title automatically", e)
            }
        }
    }

    private fun groupMessages(messages: List<MessageV2>, enabledPlatforms: List<String>): GroupedMessages {
        val userList = mutableListOf<MessageV2>()
        val assistantList = mutableListOf<List<MessageV2>>()
        var currentAssistantTurn = mutableListOf<MessageV2>()

        messages.forEach { message ->
            if (message.role == 0) {
                if (userList.isNotEmpty() || currentAssistantTurn.isNotEmpty()) {
                    assistantList.add(currentAssistantTurn)
                    currentAssistantTurn = mutableListOf()
                }
                userList.add(message)
            } else {
                currentAssistantTurn.add(message)
            }
        }
        if (userList.isNotEmpty()) {
            assistantList.add(currentAssistantTurn)
        }
        return GroupedMessages(userMessages = userList, assistantMessages = assistantList)
    }

    fun exportChat(toolTraceLabels: ToolTraceLabels, legacyOrderNotice: String): Pair<String, String> {
        val room = _chatRoom.value
        val safeTitle = room.title.replace(Regex("[^a-zA-Z0-9_-]"), "_").ifBlank { "chat_export" }
        val fileName = "${safeTitle}_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.md"
        val content = buildString {
            append("# ").append(room.title).append("\n\n")
            _groupedMessages.value.userMessages.forEachIndexed { index, userMsg ->
                append("### User\n").append(userMsg.content).append("\n\n")
                val assistantMessages = _groupedMessages.value.assistantMessages.getOrNull(index).orEmpty()
                assistantMessages.forEach { assistantMsg ->
                    append("### Assistant (").append(assistantMsg.platformUid).append(")\n")
                    append(assistantMsg.effectiveContent()).append("\n\n")
                }
            }
        }
        return Pair(fileName, content)
    }
}

data class ChatAttachmentDraft(
    val filePath: String,
    val displayName: String,
    val sizeBytes: Long
) {
    val filePathForDisplay: String
        get() = displayName

    fun toEntity(messageId: Int): Attachment = Attachment(
        messageId = messageId,
        filePath = filePath,
        fileName = displayName,
        fileSize = sizeBytes
    )
}

fun Attachment.toDraft(): ChatAttachmentDraft = ChatAttachmentDraft(
    filePath = filePath,
    displayName = fileName,
    sizeBytes = fileSize
)

data class ChatRunNotice(
    val text: String,
    val severity: NoticeSeverity
)

data class ToolTraceLabels(
    val expandToolTrace: String,
    val collapseToolTrace: String,
    val expand: String,
    val collapse: String,
    val call: String,
    val calls: String,
    val running: String,
    val failed: String,
    val completedWithErrors: String,
    val canceled: String,
    val completed: String,
    val status: String,
    val callId: String,
    val connection: String,
    val tool: String,
    val modelTool: String,
    val timing: String,
    val error: String,
    val arguments: String,
    val result: String,
    val exportHeader: (Int) -> String,
    val startedAt: String
)
