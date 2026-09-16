package dev.chungjungsoo.gptmobile.ui.conversation.title

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.chungjungsoo.gptmobile.data.conversation.Conversation
import dev.chungjungsoo.gptmobile.data.conversation.ConversationTitle
import dev.chungjungsoo.gptmobile.data.conversation.ConversationTitleDao
import dev.chungjungsoo.gptmobile.data.conversation.ConversationDao
import dev.chungjungsoo.gptmobile.data.conversation.Message
import dev.chungjungsoo.gptmobile.data.conversation.MessageDao
import dev.chungjungsoo.gptmobile.data.conversation.TitleGenerationService
import dev.chungjungsoo.gptmobile.data.conversation.TitleGenerationServiceFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing conversation title generation and summarization.
 *
 * This ViewModel handles:
 * - Auto-generating titles for new conversations
 * - AI-generated title summarization
 * - Title customization tracking
 * - Debug mode integration
 */

class ConversationTitleViewModel(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao,
    private val titleDao: ConversationTitleDao,
    private val titleGenerationService: TitleGenerationService
) : ViewModel() {

    private val _conversationTitle = MutableStateFlow<String?>(null)
    val conversationTitle: StateFlow<String?> = _conversationTitle

    private val _isTitleCustomized = MutableStateFlow<Boolean>(false)
    val isTitleCustomized: StateFlow<Boolean> = _isTitleCustomized

    private val _isTitleGenerationInProgress = MutableStateFlow<Boolean>(false)
    val isTitleGenerationInProgress: StateFlow<Boolean> = _isTitleGenerationInProgress

    private val _debugInfo = MutableStateFlow<String>("")
    val debugInfo: StateFlow<String> = _debugInfo

    private var currentConversationId: String? = null

    fun initializeConversation(conversationId: String) {
        viewModelScope.launch {
            currentConversationId = conversationId
            val conversation = conversationDao.getConversationById(conversationId)
            if (conversation != null) {
                _conversationTitle.value = conversation.title
                _isTitleCustomized.value = conversation.isTitleCustomized
                Log.d("ConversationTitleViewModel", "Initialized conversation: ${conversation.title}")
            }
        }
    }

    fun updateTitle(conversationId: String, newTitle: String) {
        viewModelScope.launch {
            try {
                val conversation = conversationDao.getConversationById(conversationId)
                if (conversation != null) {
                    conversation.title = newTitle
                    conversation.isTitleCustomized = true
                    conversation.updatedAt = java.util.Date()
                    conversationDao.updateConversation(conversation)
                    _conversationTitle.value = newTitle
                    _isTitleCustomized.value = true
                    Log.d("ConversationTitleViewModel", "Title updated to: $newTitle")
                }
            } catch (e: Exception) {
                Log.e("ConversationTitleViewModel", "Error updating title: ${e.message}", e)
                _debugInfo.value = "Error updating title: ${e.message}"
            }
        }
    }

    fun generateTitleForConversation(conversationId: String) {
        viewModelScope.launch {
            try {
                _isTitleGenerationInProgress.value = true
                Log.d("ConversationTitleViewModel", "Starting title generation for conversation: $conversationId")

                val conversation = conversationDao.getConversationById(conversationId)
                if (conversation != null && !conversation.isTitleCustomized) {
                    val messages = messageDao.getMessagesByConversationId(conversationId)
                    val title = titleGenerationService.generateTitle(messages)
                    
                    conversation.title = title
                    conversation.updatedAt = java.util.Date()
                    conversationDao.updateConversation(conversation)
                    
                    // Save title history
                    val titleHistory = ConversationTitle.createFromConversation(conversation)
                    titleDao.insertTitle(titleHistory)
                    
                    _conversationTitle.value = title
                    Log.d("ConversationTitleViewModel", "Generated title: $title")
                }
            } catch (e: Exception) {
                Log.e("ConversationTitleViewModel", "Error generating title: ${e.message}", e)
                _debugInfo.value = "Error generating title: ${e.message}"
            } finally {
                _isTitleGenerationInProgress.value = false
            }
        }
    }

    fun resetTitleGeneration() {
        viewModelScope.launch {
            _conversationTitle.value = null
            _isTitleCustomized.value = false
            _isTitleGenerationInProgress.value = false
            _debugInfo.value = ""
        }
    }

    fun setDebugInfo(info: String) {
        _debugInfo.value = info
        Log.d("ConversationTitleViewModel", "Debug info set: $info")
    }

    fun isTitleGenerationInProgress(): Boolean {
        return _isTitleGenerationInProgress.value
    }

    fun isTitleCustomized(): Boolean {
        return _isTitleCustomized.value
    }

    fun getConversationTitle(): String? {
        return _conversationTitle.value
    }

    fun getDebugInfo(): String {
        return _debugInfo.value
    }
}
