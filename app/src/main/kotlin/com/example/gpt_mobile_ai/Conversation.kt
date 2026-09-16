package com.example.gpt_mobile_ai

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.util.UUID

/**
 * Data model for a conversation
 */
data class Conversation(
    val id: String = UUID.randomUUID().toString(),
    val title: String = "",
    val messages: MutableList<Message> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isAutoContinueEnabled: Boolean = false,
    val autoContinueSettings: AutoContinueSettings = AutoContinueSettings()
) {
    fun addMessage(message: Message) {
        messages.add(message)
        updatedAt = System.currentTimeMillis()
    }

    fun updateTitle(title: String) {
        this.title = title
        updatedAt = System.currentTimeMillis()
    }
}

/**
 * Data model for a message in a conversation
 */
data class Message(
    val id: String = UUID.randomUUID().toString(),
    val role: String, // "user", "assistant", "system"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isAutoContinued: Boolean = false
)

/**
 * Auto-continue settings for a conversation
 */
data class AutoContinueSettings(
    val isEnabled: Boolean = false,
    val maxTokens: Int = 2048,
    val maxToolCalls: Int = 10,
    val maxConsecutiveContinues: Int = 5
)

/**
 * Conversation title generation result
 */
data class TitleGenerationResult(
    val title: String,
    val isGenerated: Boolean,
    val confidence: Double = 1.0
)