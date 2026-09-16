package dev.chungjungsoo.gptmobile.ui.conversation.ollama

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.conversation.Conversation
import dev.chungjungsoo.gptmobile.data.conversation.Message
import dev.chungjungsoo.gptmobile.data.conversation.MessageDao
import dev.chungjungsoo.gptmobile.data.conversation.ConversationDao
import dev.chungjungsoo.gptmobile.data.conversation.ollama.OllamaAutoContinueService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing Ollama auto-continue functionality.
 *
 * This ViewModel handles:
 * - Auto-continue settings
 * - Conversation continuation
 * - Debug mode integration
 * - UI state management
 */
class OllamaAutoContinueViewModel(
    private val autoContinueService: OllamaAutoContinueService,
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : ViewModel() {

    private val _autoContinueEnabled = MutableStateFlow<Boolean>(false)
    val autoContinueEnabled: StateFlow<Boolean> = _autoContinueEnabled

    private val _autoContinueState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val autoContinueState: StateFlow<Map<String, Boolean>> = _autoContinueState

    private val _debugInfo = MutableStateFlow<String>("")
    val debugInfo: StateFlow<String> = _debugInfo

    fun enableAutoContinue(conversationId: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                autoContinueService.enableAutoContinue(conversationId, enabled)
                _autoContinueEnabled.value = enabled
                
                if (autoContinueService.isDebugModeEnabled()) {
                    val info = "Auto-continue enabled for conversation $conversationId: $enabled"
                    _debugInfo.value = info
                    println("[DEBUG] Ollama auto-continue ViewModel: $info")
                }
            } catch (e: Exception) {
                if (autoContinueService.isDebugModeEnabled()) {
                    val error = "Error enabling auto-continue: ${e.message}"
                    _debugInfo.value = error
                    println("[DEBUG] Ollama auto-continue ViewModel error: $error")
                }
            }
        }
    }

    fun checkAutoContinue(conversationId: String, messages: List<Message>) {
        viewModelScope.launch {
            try {
                val conversation = conversationDao.getConversationById(conversationId)
                if (conversation != null) {
                    val shouldContinue = autoContinueService.shouldAutoContinue(conversation, messages)
                    
                    if (shouldContinue) {
                        val success = autoContinueService.autoContinueConversation(conversationId, messages)
                        if (autoContinueService.isDebugModeEnabled()) {
                            val info = "Auto-continue triggered for conversation $conversationId: $success"
                            _debugInfo.value = info
                            println("[DEBUG] Ollama auto-continue ViewModel: $info")
                        }
                    }
                }
            } catch (e: Exception) {
                if (autoContinueService.isDebugModeEnabled()) {
                    val error = "Error checking auto-continue: ${e.message}"
                    _debugInfo.value = error
                    println("[DEBUG] Ollama auto-continue ViewModel error: $error")
                }
            }
        }
    }

    fun setDebugMode(enabled: Boolean) {
        autoContinueService.setDebugMode(enabled)
        if (autoContinueService.isDebugModeEnabled()) {
            println("[DEBUG] Ollama auto-continue ViewModel debug mode enabled")
        }
    }

    fun getDebugInfo(): String {
        return autoContinueService.getDebugInfo()
    }

    fun isAutoContinueEnabled(): Boolean {
        return autoContinueService.isDebugModeEnabled()
    }

    fun isDebugModeEnabled(): Boolean {
        return autoContinueService.isDebugModeEnabled()
    }
}
