package dev.melo.gptmobile.improved.presentation.ui.chat

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.melo.gptmobile.improved.domain.model.AttachmentDraft
import dev.melo.gptmobile.improved.domain.model.ChatSession
import dev.melo.gptmobile.improved.domain.model.Message
import dev.melo.gptmobile.improved.domain.model.MessageRole
import dev.melo.gptmobile.improved.domain.model.ToolDefinition
import dev.melo.gptmobile.improved.domain.usecase.AddAttachmentUseCase
import dev.melo.gptmobile.improved.domain.usecase.ChatUseCase
import dev.melo.gptmobile.improved.domain.usecase.DeleteMessageUseCase
import dev.melo.gptmobile.improved.domain.usecase.DeleteSessionUseCase
import dev.melo.gptmobile.improved.domain.usecase.GetAvailableToolsUseCase
import dev.melo.gptmobile.improved.domain.usecase.GetChatSessionUseCase
import dev.melo.gptmobile.improved.domain.usecase.RenameSessionUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class ChatUiState(
    val sessionId: String = "",
    val sessionTitle: String = "",
    val messages: List<Message> = emptyList(),
    val inputMessage: String = "",
    val isLoading: Boolean = false,
    val attachmentDrafts: List<AttachmentDraft> = emptyList(),
    val availableTools: List<ToolDefinition> = emptyList(),
    val selectedToolIds: Set<String> = emptySet(),
    val error: String? = null
)

@HiltViewModel
class ChatViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val getChatSessionUseCase: GetChatSessionUseCase,
    private val chatUseCase: ChatUseCase,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val deleteMessageUseCase: DeleteMessageUseCase,
    private val renameSessionUseCase: RenameSessionUseCase,
    private val addAttachmentUseCase: AddAttachmentUseCase,
    private val getAvailableToolsUseCase: GetAvailableToolsUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    init {
        val sessionId: String? = savedStateHandle["sessionId"]
        sessionId?.let { loadSession(it) }
        loadAvailableTools()
    }

    fun onInputChange(newInput: String) {
        _uiState.update { it.copy(inputMessage = newInput) }
    }

    private fun loadSession(sessionId: String) {
        viewModelScope.launch {
            getChatSessionUseCase(sessionId).collect { session ->
                if (session != null) {
                    _uiState.update {
                        it.copy(
                            sessionId = session.id,
                            sessionTitle = session.title,
                            messages = session.messages
                        )
                    }
                }
            }
        }
    }

    private fun loadAvailableTools() {
        viewModelScope.launch {
            getAvailableToolsUseCase().collect { tools ->
                _uiState.update { it.copy(availableTools = tools) }
            }
        }
    }

    fun toggleToolSelection(toolId: String) {
        _uiState.update { state ->
            val current = state.selectedToolIds
            val updated = if (current.contains(toolId)) current - toolId else current + toolId
            state.copy(selectedToolIds = updated)
        }
    }

    fun addAttachment(uri: Uri) {
        viewModelScope.launch {
            val draft = addAttachmentUseCase(uri)
            if (draft != null) {
                _uiState.update { it.copy(attachmentDrafts = it.attachmentDrafts + draft) }
            }
        }
    }

    fun removeAttachmentDraft(draft: AttachmentDraft) {
        _uiState.update { it.copy(attachmentDrafts = it.attachmentDrafts - draft) }
    }

    fun sendMessage() {
        val currentInput = _uiState.value.inputMessage.trim()
        val drafts = _uiState.value.attachmentDrafts
        if (currentInput.isBlank() && drafts.isEmpty()) return

        val userMessage = Message(
            id = UUID.randomUUID().toString(),
            role = MessageRole.USER,
            content = currentInput,
            timestamp = System.currentTimeMillis()
        )

        _uiState.update {
            it.copy(
                inputMessage = "",
                attachmentDrafts = emptyList(),
                isLoading = true,
                messages = it.messages + userMessage
            )
        }

        viewModelScope.launch {
            try {
                chatUseCase(
                    sessionId = _uiState.value.sessionId,
                    message = userMessage,
                    selectedToolIds = _uiState.value.selectedToolIds
                ).collect { chunk ->
                    // Process streaming chunks or final response
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun regenerateLastResponse() {
        val lastUserMessage = _uiState.value.messages.lastOrNull { it.role == MessageRole.USER } ?: return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                chatUseCase(
                    sessionId = _uiState.value.sessionId,
                    message = lastUserMessage,
                    selectedToolIds = _uiState.value.selectedToolIds
                ).collect {
                    // Update
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.localizedMessage) }
            } finally {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun editMessage(message: Message, newContent: String) {
        viewModelScope.launch {
            val updated = message.copy(content = newContent)
            _uiState.update { state ->
                state.copy(
                    messages = state.messages.map { if (it.id == message.id) updated else it }
                )
            }
        }
    }

    fun deleteMessage(message: Message) {
        viewModelScope.launch {
            deleteMessageUseCase(_uiState.value.sessionId, message.id)
            _uiState.update { state ->
                state.copy(messages = state.messages.filter { it.id != message.id })
            }
        }
    }

    fun renameSession(newTitle: String) {
        viewModelScope.launch {
            renameSessionUseCase(_uiState.value.sessionId, newTitle)
            _uiState.update { it.copy(sessionTitle = newTitle) }
        }
    }

    fun deleteCurrentSession() {
        viewModelScope.launch {
            deleteSessionUseCase(_uiState.value.sessionId)
        }
    }

    private fun updateAssistantSlot(
        session: ChatSession,
        placeholderId: String,
        updated: Message
    ): List<Message> {
        return session.messages.map { msg ->
            if (msg.id == placeholderId) updated else msg
        }
    }
}
