package com.example.gpt_mobile_ai

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Service for generating conversation titles based on message content
 */
class TitleGenerationService {
    private val TAG = "TitleGenerationService"
    private val isInitialized = AtomicBoolean(false)

    /**
     * Initializes the title generation service
     */
    fun initialize(): Boolean {
        if (isInitialized.get()) {
            Log.d(TAG, "Title generation service already initialized")
            return true
        }

        try {
            // In a real implementation, this would initialize any required components
            Log.d(TAG, "Title generation service initialized")
            isInitialized.set(true)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize title generation service", e)
            return false
        }
    }

    /**
     * Generates a title for a conversation based on its messages
     */
    suspend fun generateTitle(conversation: Conversation): TitleGenerationResult {
        return withContext(Dispatchers.Default) {
            try {
                Log.d(TAG, "Generating title for conversation: ${conversation.id}")

                // If title is already set and not empty, return it
                if (conversation.title.isNotBlank()) {
                    Log.d(TAG, "Using existing title: ${conversation.title}")
                    return@withContext TitleGenerationResult(
                        title = conversation.title,
                        isGenerated = false,
                        confidence = 1.0
                    )
                }

                // If conversation has no messages, return a default title
                if (conversation.messages.isEmpty()) {
                    Log.d(TAG, "Empty conversation, using default title")
                    return@withContext TitleGenerationResult(
                        title = "New Conversation",
                        isGenerated = true,
                        confidence = 0.8
                    )
                }

                // Get the most recent user message to base title on
                val recentUserMessage = conversation.messages
                    .filter { it.role == "user" }
                    .lastOrNull()
                    ?: conversation.messages.lastOrNull()

                if (recentUserMessage == null) {
                    Log.d(TAG, "No user message found, using default title")
                    return@withContext TitleGenerationResult(
                        title = "New Conversation",
                        isGenerated = true,
                        confidence = 0.8
                    )
                }

                // Generate title based on message content
                val title = generateTitleFromContent(recentUserMessage.content)
                Log.d(TAG, "Generated title: $title")

                TitleGenerationResult(
                    title = title,
                    isGenerated = true,
                    confidence = 0.9
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed to generate title", e)
                TitleGenerationResult(
                    title = "Conversation",
                    isGenerated = false,
                    confidence = 0.0
                )
            }
        }
    }

    /**
     * Generates a title from message content
     */
    private fun generateTitleFromContent(content: String): String {
        // Simple implementation - in a real app this would be more sophisticated
        val trimmedContent = content.trim()
        
        // If content is empty or very short, return a default title
        if (trimmedContent.isEmpty() || trimmedContent.length < 5) {
            return "New Conversation"
        }

        // Extract first few words (up to 6 words) for title
        val words = trimmedContent.split("\\s+".toRegex())
        val titleWords = words.take(6)
        val title = titleWords.joinToString(" ")

        // If title is too short, add a default phrase
        return if (title.length < 5) {
            "New Conversation"
        } else {
            title
        }
    }

    /**
     * Updates conversation title with generated title
     */
    suspend fun updateConversationTitle(conversation: Conversation): Boolean {
        return try {
            val result = generateTitle(conversation)
            if (result.isGenerated) {
                conversation.updateTitle(result.title)
                Log.d(TAG, "Updated conversation title to: ${result.title}")
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update conversation title", e)
            false
        }
    }

    /**
     * Cleans up resources
     */
    fun cleanup() {
        if (isInitialized.get()) {
            Log.d(TAG, "Cleaning up title generation service")
            isInitialized.set(false)
        }
    }
}