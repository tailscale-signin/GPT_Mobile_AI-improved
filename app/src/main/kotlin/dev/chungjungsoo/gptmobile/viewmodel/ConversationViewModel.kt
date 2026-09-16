package dev.chungjungsoo.gptmobile.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.model.Conversation
import dev.chungjungsoo.gptmobile.data.model.Message
import dev.chungjungsoo.gptmobile.service.TitleGenerationService
import dev.chungjungsoo.gptmobile.service.OllamaAutoContinueService
import dev.chungjungsoo.gptmobile.util.DebugUtils
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ConversationViewModel : ViewModel() {
    private val TAG = "ConversationViewModel"
    
    private val _conversation = MutableStateFlow<Conversation?>(null)
    val conversation: StateFlow<Conversation?> = _conversation.asStateFlow()

    private val _messages = MutableStateFlow<List<Message>>(emptyList())
    val messages: StateFlow<List<Message>> = _messages.asStateFlow()

    private val titleGenerationService = TitleGenerationService()
    private val autoContinueService = OllamaAutoContinueService()

    fun initializeConversation(conversation: Conversation) {
        viewModelScope.launch {
            DebugUtils.logDebug("Initializing conversation ${conversation.id}")
            _conversation.value = conversation
        }
    }

    fun addMessage(message: Message) {
        viewModelScope.launch {
            DebugUtils.logDebug("Adding message to conversation ${conversation.value?.id}")
            val currentMessages = _messages.value
            _messages.value = currentMessages + message
        }
    }

    fun generateTitleForConversation() {
        viewModelScope.launch {
            val conv = conversation.value
            val msgList = messages.value
            
            if (conv != null && msgList.isNotEmpty()) {
                DebugUtils.logDebug("Generating title for conversation ${conv.id}")
                // In a real implementation, this would call the service and update the conversation
            }
        }
    }

    fun checkAutoContinue() {
        viewModelScope.launch {
            val conv = conversation.value
            val msgList = messages.value
            
            if (conv != null) {
                DebugUtils.logDebug("Checking auto-continue for conversation ${conv.id}")
                // In a real implementation, this would call the service and handle continuation logic
            }
        }
    }
}