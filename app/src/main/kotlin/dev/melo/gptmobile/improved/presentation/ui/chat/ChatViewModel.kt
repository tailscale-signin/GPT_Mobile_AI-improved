package dev.melo.gptmobile.improved.presentation.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.agent.AgentRunCoordinator
import dev.melo.gptmobile.improved.data.agent.AgentRunDraft
import dev.melo.gptmobile.improved.data.agent.AgentRunRequest
import dev.melo.gptmobile.improved.data.database.entity.AgentRun
import dev.melo.gptmobile.improved.data.database.entity.AgentRunStatus
import dev.melo.gptmobile.improved.data.database.entity.ChatRoomV2
import dev.melo.gptmobile.improved.data.database.entity.MessageV2
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentRetryRequest
import dev.melo.gptmobile.improved.data.database.entity.PersistAgentTurnRequest
import dev.melo.gptmobile.improved.data.database.entity.PlatformV2
import dev.melo.gptmobile.improved.data.database.entity.ToolEvent
import dev.melo.gptmobile.improved.data.model.AvailableChatTool
import dev.melo.gptmobile.improved.data.model.ChatAttachment
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import dev.melo.gptmobile.improved.util.ChatToolUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val chatRepository: ChatRepository,
    private val settingRepository: SettingRepository,
    private val agentRunCoordinator: AgentRunCoordinator,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val chatRoomId: Int = when (val raw = savedStateHandle.get<Any>("chatRoomId")) {
        is Int -> raw
        is String -> raw.toIntOrNull() ?: 0
        else -> 0
    }

    private val enabledPlatformsParam: String = savedStateHandle.get<String>("enabledPlatforms").orEmpty()

    val messages: StateFlow<List<MessageV2>> = chatRepository.observeMessagesV2(chatRoomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val agentRuns: StateFlow<List<AgentRun>> = chatRepository.observeAgentRuns(chatRoomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val toolEvents: StateFlow<List<ToolEvent>> = chatRepository.observeToolEvents(chatRoomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isGenerating: StateFlow<Boolean> = agentRunCoordinator.activeRuns
        .combine(MutableStateFlow(chatRoomId)) { runs, chatId ->
            runs.values.any { it.chatId == chatId }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _availableTools = MutableStateFlow<List<AvailableChatTool>>(emptyList())
    val availableTools: StateFlow<List<AvailableChatTool>> = _availableTools.asStateFlow()

    private val _selectedTools = MutableStateFlow<Set<String>>(emptySet())
    val selectedTools: StateFlow<Set<String>> = _selectedTools.asStateFlow()

    private val _attachmentDrafts = MutableStateFlow<List<ChatAttachmentDraft>>(emptyList())
    val attachmentDrafts: StateFlow<List<ChatAttachmentDraft>> = _attachmentDrafts.asStateFlow()

    private val _currentChatRoom = MutableStateFlow<ChatRoomV2?>(null)
    val currentChatRoom: StateFlow<ChatRoomV2?> = _currentChatRoom.asStateFlow()

    private val _platforms = MutableStateFlow<List<PlatformV2>>(emptyList())
    val platforms: StateFlow<List<PlatformV2>> = _platforms.asStateFlow()

    private val _chatPlatformModels = MutableStateFlow<Map<String, String>>(emptyMap())
    val chatPlatformModels: StateFlow<Map<String, String>> = _chatPlatformModels.asStateFlow()

    init {
        loadChatRoom()
        loadPlatforms()
        loadAvailableTools()
    }

    private fun loadChatRoom() {
        viewModelScope.launch {
            if (chatRoomId > 0) {
                val chats = chatRepository.fetchChatListV2()
                _currentChatRoom.value = chats.find { it.chatId == chatRoomId }
                _chatPlatformModels.value = chatRepository.fetchChatPlatformModels(chatRoomId)
            }
        }
    }

    private fun loadPlatforms() {
        viewModelScope.launch {
            _platforms.value = settingRepository.fetchPlatformV2s()
        }
    }

    private fun loadAvailableTools() {
        viewModelScope.launch {
            val connections = settingRepository.fetchToolConnections()
            val tools = ChatToolUtils.buildAvailableChatTools(connections)
            _availableTools.value = tools
            _selectedTools.value = tools.map { it.name }.toSet()
        }
    }

    fun toggleTool(toolName: String) {
        val current = _selectedTools.value.toMutableSet()
        if (current.contains(toolName)) {
            current.remove(toolName)
        } else {
            current.add(toolName)
        }
        _selectedTools.value = current
    }

    fun addAttachment(uri: Uri, context: Context) {
        viewModelScope.launch {
            try {
                val contentResolver = context.contentResolver
                val fileName = uri.lastPathSegment ?: "file_${System.currentTimeMillis()}"
                val tempFile = File(context.cacheDir, "${UUID.randomUUID()}_$fileName")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val draft = ChatAttachmentDraft(
                    sourceFilePath = tempFile.absolutePath,
                    preparedFilePath = tempFile.absolutePath,
                    sourceUri = uri
                )
                _attachmentDrafts.value = _attachmentDrafts.value + draft
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeAttachment(draft: ChatAttachmentDraft) {
        _attachmentDrafts.value = _attachmentDrafts.value - draft
        try {
            File(draft.sourceFilePath).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() && _attachmentDrafts.value.isEmpty()) return
        if (isGenerating.value) return

        val attachments = _attachmentDrafts.value.map { draft ->
            ChatAttachment(
                id = UUID.randomUUID().toString(),
                name = File(draft.sourceFilePath).name,
                localFilePath = draft.sourceFilePath,
                preparedFilePath = draft.preparedFilePath.orEmpty()
            )
        }
        _attachmentDrafts.value = emptyList()

        viewModelScope.launch {
            val allPlatforms = if (_platforms.value.isEmpty()) settingRepository.fetchPlatformV2s() else _platforms.value
            val enabledUids = if (enabledPlatformsParam.isNotBlank()) {
                enabledPlatformsParam.split(",").filter { it.isNotBlank() }
            } else {
                listOfNotNull(_currentChatRoom.value?.activePlatformUid).ifEmpty {
                    allPlatforms.filter { it.enabled }.map { it.uid }
                }
            }

            val targetPlatforms = allPlatforms.filter { it.uid in enabledUids && it.enabled }
            if (targetPlatforms.isEmpty()) return@launch

            val primaryPlatform = targetPlatforms.first()
            val room = _currentChatRoom.value ?: ChatRoomV2(
                chatId = chatRoomId,
                title = content.take(50).ifBlank { "New Chat" },
                activePlatformUid = primaryPlatform.uid
            )

            val userMessage = MessageV2(
                chatId = room.chatId,
                sender = 0,
                content = content,
                attachments = attachments
            )

            val drafts = targetPlatforms.map { platform ->
                AgentRunDraft(
                    runId = UUID.randomUUID().toString(),
                    profileUid = platform.uid,
                    providerSnapshot = platform.name,
                    modelSnapshot = _chatPlatformModels.value[platform.uid] ?: platform.model
                )
            }

            val turnResult = chatRepository.persistAgentTurn(
                PersistAgentTurnRequest(
                    chatRoom = room,
                    userMessage = userMessage,
                    runs = drafts,
                    chatPlatformModels = _chatPlatformModels.value
                )
            )

            if (_currentChatRoom.value == null) {
                _currentChatRoom.value = turnResult.chatRoom
            }

            val requests = turnResult.runs.zip(targetPlatforms) { run, platform ->
                val assistantMessage = turnResult.assistantMessages.first { it.currentRunId == run.runId }
                AgentRunRequest(
                    runId = run.runId,
                    chatId = turnResult.chatRoom.chatId,
                    assistantMessage = assistantMessage,
                    platform = platform,
                    userMessages = listOf(turnResult.userMessage),
                    assistantMessages = emptyList()
                )
            }

            agentRunCoordinator.start(requests)
        }
    }

    fun retryLastMessage() {
        val lastUserMessage = messages.value.lastOrNull { it.sender == 0 } ?: return
        val lastAssistantMessage = messages.value.lastOrNull { it.sender == 1 && it.linkedMessageId == lastUserMessage.id } ?: return
        val platform = _platforms.value.find { it.uid == lastAssistantMessage.platformType } ?: return

        viewModelScope.launch {
            val runDraft = AgentRunDraft(
                runId = UUID.randomUUID().toString(),
                profileUid = platform.uid,
                providerSnapshot = platform.name,
                modelSnapshot = _chatPlatformModels.value[platform.uid] ?: platform.model
            )

            val retryResult = chatRepository.persistAgentRetry(
                PersistAgentRetryRequest(
                    userMessage = lastUserMessage,
                    assistantMessage = lastAssistantMessage,
                    run = runDraft
                )
            )

            val request = AgentRunRequest(
                runId = retryResult.run.runId,
                chatId = lastUserMessage.chatId,
                assistantMessage = retryResult.assistantMessage,
                platform = platform,
                userMessages = listOf(lastUserMessage),
                assistantMessages = emptyList()
            )

            agentRunCoordinator.start(listOf(request))
        }
    }

    fun stopGeneration() {
        agentRunCoordinator.cancelChat(chatRoomId)
    }

    fun clearMessages() {
        viewModelScope.launch {
            val room = _currentChatRoom.value ?: return@launch
            agentRunCoordinator.cancelChatAndJoin(room.chatId)
            chatRepository.saveChat(room, emptyList(), _chatPlatformModels.value)
        }
    }

    fun deleteCurrentChatRoom(onDeleted: () -> Unit) {
        viewModelScope.launch {
            val room = _currentChatRoom.value
            if (room != null) {
                agentRunCoordinator.cancelChatAndJoin(room.chatId)
                chatRepository.deleteChatsV2(listOf(room))
            }
            onDeleted()
        }
    }
}
