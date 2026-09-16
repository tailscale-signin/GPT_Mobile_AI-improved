package com.example.gpt_mobile_ai.ViewModels

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.gpt_mobile_ai.Conversation
import com.example.gpt_mobile_ai.Message
import com.example.gpt_mobile_ai.TitleGenerationService
import com.example.gpt_mobile_ai.OllamaAutoContinueService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing conversation data and UI state
 */
class ConversationViewModel : ViewModel() {
    private val TAG = "ConversationViewModel"
    
    private val titleGenerationService = TitleGenerationService()
    private val autoContinueService = OllamaAutoContinueService()
    
    private val _conversations = MutableStateFlow<List<Conversation>>(emptyList())
    val conversations: StateFlow<List<Conversation>> = _conversations
    
    private val _currentConversation = MutableStateFlow<Conversation?>(null)
    val currentConversation: StateFlow<Conversation?> = _currentConversation
    
    init {
        // Initialize services
        titleGenerationService.initialize()
        autoContinueService.initialize()
    }

    /**
     * Creates a new conversation
     */
    fun createNewConversation(): Conversation {
        val conversation = Conversation()
        viewModelScope.launch {
            // Generate title for the new conversation
            val titleResult = titleGenerationService.generateTitle(conversation)
            conversation.updateTitle(titleResult.title)
            
            // Add to conversations list
            val currentConversations = _conversations.value.toMutableList()
            currentConversations.add(conversation)
            _conversations.value = currentConversations
            
            // Set as current conversation
            _currentConversation.value = conversation
        }
        return conversation
    }

    /**
     * Adds a message to the current conversation
     */
    fun addMessageToCurrentConversation(message: Message) {
        viewModelScope.launch {
            val current = _currentConversation.value
            if (current != null) {
                current.addMessage(message)
                
                // Update the conversation in the list
                val currentConversations = _conversations.value.toMutableList()
                val index = currentConversations.indexOfFirst { it.id == current.id }
                if (index != -1) {
                    currentConversations[index] = current
                    _conversations.value = currentConversations
                }
            }
        }
    }

    /**
     * Updates the current conversation's title
     */
    fun updateCurrentConversationTitle(title: String) {
        viewModelScope.launch {
            val current = _currentConversation.value
            if (current != null) {
                current.updateTitle(title)
                
                // Update the conversation in the list
                val currentConversations = _conversations.value.toMutableList()
                val index = currentConversations.indexOfFirst { it.id == current.id }
                if (index != -1) {
                    currentConversations[index] = current
                    _conversations.value = currentConversations
                }
            }
        }
    }

    /**
     * Sets the current conversation
     */
    fun setCurrentConversation(conversation: Conversation) {
        _currentConversation.value = conversation
    }

    /**
     * Gets the current conversation
     */
    fun getCurrentConversation(): Conversation? {
        return _currentConversation.value
    }

    /**
     * Gets all conversations
     */
    fun getConversations(): List<Conversation> {
        return _conversations.value
    }

    /**
     * Cleans up resources
     */
    override fun onCleared() {
        super.onCleared()
        Log.d(TAG, "Cleaning up ConversationViewModel")
        titleGenerationService.cleanup()
        autoContinueService.cleanup()
    }
}