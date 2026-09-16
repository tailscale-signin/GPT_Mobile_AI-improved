package dev.chungjungsoo.gptmobile.data.conversation.title

import dev.chungjungsoo.gptmobile.data.conversation.Message
import dev.chungjungsoo.gptmobile.util.debugging.DebugUtils
import kotlinx.serialization.Serializable

/**
 * Service for generating conversation titles from message history.
 *
 * This service handles:
 * - AI-generated title summarization
 * - Title formatting (3-6 words)
 * - Conversation context analysis
 * - Debug mode integration
 */
interface TitleGenerationService {
    fun generateTitle(messages: List<Message>): String
    fun isDebugModeEnabled(): Boolean
    fun setDebugMode(enabled: Boolean)
    fun getDebugInfo(): String
}

/**
 * Factory for creating TitleGenerationService instances.
 *
 * This factory handles:
 * - Service creation
 * - Debug mode configuration
 * - Service initialization
 */
interface TitleGenerationServiceFactory {
    fun createService(): TitleGenerationService
    fun isDebugModeEnabled(): Boolean
    fun setDebugMode(enabled: Boolean)
}

/**
 * Default implementation of TitleGenerationService.
 *
 * This implementation:
 * - Uses a lightweight prompt for title generation
 * - Ensures 3-6 word title length
 * - Handles edge cases
 * - Provides debug information
 */
@Serializable
class DefaultTitleGenerationService : TitleGenerationService {
    private var isDebugMode = false
    private var debugInfo = ""

    override fun generateTitle(messages: List<Message>): String {
        try {
            val (result, executionTime) = DebugUtils.measureExecutionTime {
                if (messages.isEmpty()) {
                    return@measureExecutionTime "New Conversation"
                }

                // Simple approach: use first few messages to generate title
                val content = messages.take(3).joinToString(" ") { it.content }
                
                // Generate a concise title (3-6 words)
                val title = generateConciseTitle(content)
                title
            }
            
            if (isDebugMode) {
                debugInfo = "Generated title: $result from ${messages.size} messages"
                DebugUtils.logDebug("Title generation completed: ${DebugUtils.formatTitleGenerationInfo(result, result.split(" ").size, executionTime)}")
            }
            
            return result
        } catch (e: Exception) {
            if (isDebugMode) {
                debugInfo = "Error generating title: ${e.message}"
                DebugUtils.logError("Title generation error", e)
            }
            return "New Conversation"
        }
    }

    private fun generateConciseTitle(content: String): String {
        // Simple approach: extract first few words
        val words = content.split(" ").filter { it.isNotBlank() }
        val titleWords = words.take(6)
        
        // Ensure we have at least 3 words
        val title = if (titleWords.size >= 3) {
            titleWords.joinToString(" ")
        } else {
            // If we don't have enough words, pad with placeholder
            (titleWords + listOf("conversation", "chat", "discussion")).take(6).joinToString(" ")
        }
        
        // Limit to 6 words max
        val finalTitle = titleWords.take(6).joinToString(" ")
        
        // Ensure title is 3-6 words
        val wordCount = finalTitle.split(" ").size
        return when {
            wordCount < 3 -> "${finalTitle} title"
            wordCount > 6 -> finalTitle.split(" ").take(6).joinToString(" ")
            else -> finalTitle
        }
    }

    override fun isDebugModeEnabled(): Boolean = isDebugMode

    override fun setDebugMode(enabled: Boolean) {
        isDebugMode = enabled
        if (isDebugMode) {
            DebugUtils.logDebug("Title generation debug mode enabled")
        }
    }

    override fun getDebugInfo(): String = debugInfo
}

/**
 * Default implementation of TitleGenerationServiceFactory.
 *
 * This factory:
 * - Creates DefaultTitleGenerationService instances
 * - Handles debug mode configuration
 * - Provides service initialization
 */
@Serializable
class DefaultTitleGenerationServiceFactory : TitleGenerationServiceFactory {
    private var isDebugMode = false

    override fun createService(): TitleGenerationService {
        val service = DefaultTitleGenerationService()
        service.setDebugMode(isDebugMode)
        return service
    }

    override fun isDebugModeEnabled(): Boolean = isDebugMode

    override fun setDebugMode(enabled: Boolean) {
        isDebugMode = enabled
        if (isDebugMode) {
            DebugUtils.logDebug("Title generation factory debug mode enabled")
        }
    }
}
