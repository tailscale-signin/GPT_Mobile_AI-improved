package dev.melo.gptmobile.improved.presentation.ui.chat

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.data.model.AgentRunStatus
import dev.melo.gptmobile.improved.data.model.AgentToolDefinition
import dev.melo.gptmobile.improved.data.model.Attachment
import dev.melo.gptmobile.improved.data.model.ChatRoom
import dev.melo.gptmobile.improved.data.model.Message
import dev.melo.gptmobile.improved.data.model.Sender
import dev.melo.gptmobile.improved.data.model.ToolTrace
import dev.melo.gptmobile.improved.data.repository.ChatRepository
import dev.melo.gptmobile.improved.data.repository.SettingRepository
import dev.melo.gptmobile.improved.domain.usecase.GetChatMessagesUseCase
import dev.melo.gptmobile.improved.domain.usecase.SendMessageUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val getChatMessagesUseCase: GetChatMessagesUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    val chatRoomId: String = checkNotNull(savedStateHandle["chatRoomId"])

    val messages: StateFlow<List<Message>> = getChatMessagesUseCase(chatRoomId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isGenerating = MutableStateFlow(false)
    val isGenerating: StateFlow<Boolean> = _isGenerating.asStateFlow()

    private val _availableTools = MutableStateFlow<List<AgentToolDefinition>>(emptyList())
    val availableTools: StateFlow<List<AgentToolDefinition>> = _availableTools.asStateFlow()

    private val _selectedTools = MutableStateFlow<Set<String>>(emptySet())
    val selectedTools: StateFlow<Set<String>> = _selectedTools.asStateFlow()

    private val _attachmentDrafts = MutableStateFlow<List<AttachmentDraft>>(emptyList())
    val attachmentDrafts: StateFlow<List<AttachmentDraft>> = _attachmentDrafts.asStateFlow()

    private val _currentChatRoom = MutableStateFlow<ChatRoom?>(null)
    val currentChatRoom: StateFlow<ChatRoom?> = _currentChatRoom.asStateFlow()

    private val _agentRunStatus = MutableStateFlow<AgentRunStatus?>(null)
    val agentRunStatus: StateFlow<AgentRunStatus?> = _agentRunStatus.asStateFlow()

    init {
        loadChatRoom()
        loadAvailableTools()
    }

    private fun loadChatRoom() {
        viewModelScope.launch {
            _currentChatRoom.value = chatRepository.getChatRoom(chatRoomId)
        }
    }

    private fun loadAvailableTools() {
        viewModelScope.launch {
            val tools = chatRepository.getAvailableTools()
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
                val mimeType = contentResolver.getType(uri) ?: "application/octet-stream"

                val tempFile = File(context.cacheDir, "${UUID.randomUUID()}_$fileName")
                contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }

                val draft = AttachmentDraft(
                    id = UUID.randomUUID().toString(),
                    name = fileName,
                    path = tempFile.absolutePath,
                    mimeType = mimeType,
                    size = tempFile.length()
                )
                _attachmentDrafts.value = _attachmentDrafts.value + draft
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun removeAttachment(draft: AttachmentDraft) {
        _attachmentDrafts.value = _attachmentDrafts.value - draft
        try {
            File(draft.path).delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun sendMessage(content: String) {
        if (content.isBlank() && _attachmentDrafts.value.isEmpty()) return
        if (_isGenerating.value) return

        val attachments = _attachmentDrafts.value.map { draft ->
            Attachment(
                id = draft.id,
                name = draft.name,
                path = draft.path,
                mimeType = draft.mimeType,
                size = draft.size
            )
        }
        _attachmentDrafts.value = emptyList()

        viewModelScope.launch {
            _isGenerating.value = true
            _agentRunStatus.value = AgentRunStatus.STARTING

            try {
                sendMessageUseCase(
                    chatRoomId = chatRoomId,
                    content = content,
                    attachments = attachments,
                    enabledTools = _selectedTools.value.toList(),
                    onStatusUpdate = { status ->
                        _agentRunStatus.value = status
                    }
                )
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isGenerating.value = false
                _agentRunStatus.value = AgentRunStatus.IDLE
            }
        }
    }

    fun retryLastMessage() {
        val lastUserMessage = messages.value.lastOrNull { it.sender == Sender.USER } ?: return
        sendMessage(lastUserMessage.content)
    }

    fun stopGeneration() {
        viewModelScope.launch {
            chatRepository.stopGeneration(chatRoomId)
            _isGenerating.value = false
            _agentRunStatus.value = AgentRunStatus.IDLE
        }
    }

    fun clearMessages() {
        viewModelScope.launch {
            chatRepository.clearMessages(chatRoomId)
        }
    }

    fun deleteCurrentChatRoom(onDeleted: () -> Unit) {
        viewModelScope.launch {
            chatRepository.deleteChatRoom(chatRoomId)
            onDeleted()
        }
    }
}
