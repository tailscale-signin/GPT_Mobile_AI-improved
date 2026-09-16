package dev.chungjungsoo.gptmobile.data.conversation.ollama

import dev.chungjungsoo.gptmobile.data.conversation.Conversation
import dev.chungjungsoo.gptmobile.data.conversation.Message
import dev.chungjungsoo.gptmobile.data.conversation.MessageDao
import dev.chungjungsoo.gptmobile.data.conversation.ConversationDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Service for managing Ollama auto-continue functionality.
 *
 * This service handles:
 * - Auto-continue detection (token limits, tool calls)
 * - Seamless continuation of conversations
 * - Debug mode integration
 * - Conversation state management
 */
interface OllamaAutoContinueService {
    fun isAutoContinueEnabled(conversationId: String): Boolean
    fun enableAutoContinue(conversationId: String, enabled: Boolean)
    fun shouldAutoContinue(conversation: Conversation, messages: List<Message>): Boolean
    fun autoContinueConversation(conversationId: String, messages: List<Message>): Boolean
    fun getDebugInfo(): String
    fun isDebugModeEnabled(): Boolean
    fun setDebugMode(enabled: Boolean)
}

/**
 * Implementation of OllamaAutoContinueService.
 *
 * This implementation:
 * - Detects when auto-continue is needed
 * - Handles conversation continuation
 * - Provides debug information
 * - Manages conversation state
 */
class DefaultOllamaAutoContinueService(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : OllamaAutoContinueService {

    private var isDebugMode = false
    private var debugInfo = ""
    private val _autoContinueState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val autoContinueState: StateFlow<Map<String, Boolean>> = _autoContinueState.asStateFlow()

    override fun isAutoContinueEnabled(conversationId: String): Boolean {
        val conversation = conversationDao.getConversationById(conversationId)
        return conversation?.isAutoContinueEnabled ?: false
    }

    override fun enableAutoContinue(conversationId: String, enabled: Boolean) {
        try {
            val conversation = conversationDao.getConversationById(conversationId)
            if (conversation != null) {
                conversation.isAutoContinueEnabled = enabled
                conversationDao.updateConversation(conversation)
                
                if (isDebugMode) {
                    debugInfo = "Auto-continue enabled for conversation $conversationId: $enabled"
                    println("[DEBUG] Ollama auto-continue: $debugInfo")
                }
            }
        } catch (e: Exception) {
            if (isDebugMode) {
                debugInfo = "Error enabling auto-continue: ${e.message}"
                println("[DEBUG] Ollama auto-continue error: $debugInfo")
            }
        }
    }

    override fun shouldAutoContinue(conversation: Conversation, messages: List<Message>): Boolean {
        try {
            if (!conversation.isAutoContinueEnabled) {
                if (isDebugMode) {
                    debugInfo = "Auto-continue disabled for conversation ${conversation.id}"
                    println("[DEBUG] Ollama auto-continue: $debugInfo")
                }
                return false
            }

            // Check if we've reached max tool calls
            val toolCallCount = messages.count { it.isToolCall }
            if (toolCallCount >= conversation.maxToolCalls) {
                if (isDebugMode) {
                    debugInfo = "Max tool calls reached: $toolCallCount >= ${conversation.maxToolCalls}"
                    println("[DEBUG] Ollama auto-continue: $debugInfo")
                }
                return true
            }

            // Check if we've reached max context length
            val totalTokens = messages.sumOf { it.content.length }
            if (totalTokens >= conversation.maxContextLength) {
                if (isDebugMode) {
                    debugInfo = "Max context length reached: $totalTokens >= ${conversation.maxContextLength}"
                    println("[DEBUG] Ollama auto-continue: $debugInfo")
                }
                return true
            }

            // Check if last message indicates continuation needed
            val lastMessage = messages.lastOrNull()
            if (lastMessage != null && lastMessage.content.contains("continue", ignoreCase = true)) {
                if (isDebugMode) {
                    debugInfo = "Last message indicates continuation needed"
                    println("[DEBUG] Ollama auto-continue: $debugInfo")
                }
                return true
            }

            if (isDebugMode) {
                debugInfo = "No auto-continue conditions met"
                println("[DEBUG] Ollama auto-continue: $debugInfo")
            }
            return false
        } catch (e: Exception) {
            if (isDebugMode) {
                debugInfo = "Error checking auto-continue: ${e.message}"
                println("[DEBUG] Ollama auto-continue error: $debugInfo")
            }
            return false
        }
    }

    override fun autoContinueConversation(conversationId: String, messages: List<Message>): Boolean {
        try {
            if (isDebugMode) {
                debugInfo = "Starting auto-continue for conversation $conversationId"
                println("[DEBUG] Ollama auto-continue: $debugInfo")
            }

            val conversation = conversationDao.getConversationById(conversationId)
            if (conversation == null) {
                if (isDebugMode) {
                    debugInfo = "Conversation not found: $conversationId"
                    println("[DEBUG] Ollama auto-continue error: $debugInfo")
                }
                return false
            }

            // Create a new continuation message
            val continuationMessage = Message.createAssistantMessage(
                "Continuing conversation based on auto-continue settings"
            )
            continuationMessage.conversationId = conversationId
            continuationMessage.isAutoContinue = true
            continuationMessage.autoContinueReason = "Auto-continue triggered by Ollama service"

            // Save the continuation message
            messageDao.insertMessage(continuationMessage)
            
            if (isDebugMode) {
                debugInfo = "Auto-continue completed for conversation $conversationId"
                println("[DEBUG] Ollama auto-continue: $debugInfo")
            }
            
            return true
        } catch (e: Exception) {
            if (isDebugMode) {
                debugInfo = "Error during auto-continue: ${e.message}"
                println("[DEBUG] Ollama auto-continue error: $debugInfo")
            }
            return false
        }
    }

    override fun getDebugInfo(): String = debugInfo

    override fun isDebugModeEnabled(): Boolean = isDebugMode

    override fun setDebugMode(enabled: Boolean) {
        isDebugMode = enabled
        if (isDebugMode) {
            println("[DEBUG] Ollama auto-continue debug mode enabled")
        }
    }
}
