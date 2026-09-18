package com.gptmobileai.domain.model

import java.util.UUID

/**
 * Unified model picker and message queue system
 * 
 * This module provides:
 * 1. A unified interface for selecting models across all providers (Ollama, OpenRouter, etc.)
 * 2. A message queue system for handling AI requests when APIs are busy
 * 3. Priority-based request handling with retry logic
 */

sealed class ModelProvider {
    data object Ollama : ModelProvider()
    data object OpenRouter : ModelProvider()
    data object Local : ModelProvider()
}

data class UnifiedModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val provider: ModelProvider,
    val description: String? = null,
    val contextWindow: Int = 0,
    val temperature: Float = 0.7f,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
) {
    fun getProviderName(): String = when (provider) {
        ModelProvider.Ollama -> "Ollama"
        ModelProvider.OpenRouter -> "OpenRouter"
        ModelProvider.Local -> "Local"
    }
}

sealed class MessageQueueStatus {
    data object Pending : MessageQueueStatus()
    data object Processing : MessageQueueStatus()
    data class Completed(val success: Boolean) : MessageQueueStatus()
    data class Error(val errorMessage: String, val retryCount: Int = 0) : MessageQueueStatus()
}

data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val modelId: String,
    val priority: Int = 1, // 1-5, 5 being highest
    val status: MessageQueueStatus = MessageQueueStatus.Pending,
    val createdAt: Long = System.currentTimeMillis(),
    val retryCount: Int = 0,
    val maxRetries: Int = 3,
    val response: String? = null,
    val error: String? = null
) {
    fun isRetryable(): Boolean = status is MessageQueueStatus.Error && retryCount < maxRetries
}

data class QueueConfig(
    val maxQueueSize: Int = 100,
    val defaultPriority: Int = 3,
    val maxRetries: Int = 3,
    val retryDelayMs: Long = 1000L,
    val exponentialBackoffMultiplier: Double = 2.0
)

interface MessageQueueRepository {
    suspend fun enqueue(message: QueuedMessage): String
    suspend fun dequeue(): QueuedMessage?
    suspend fun processNext(): Boolean
    suspend fun removeMessage(id: String): Boolean
    suspend fun getQueueStatus(): List<QueuedMessage>
    suspend fun clearCompleted(): Int
    suspend fun setPriority(id: String, priority: Int): Boolean
}

interface ModelRepository {
    suspend fun getAllModels(): List<UnifiedModel>
    suspend fun getModelById(id: String): UnifiedModel?
    suspend fun setActiveModel(modelId: String): Boolean
    suspend fun updateModel(model: UnifiedModel): Boolean
    suspend fun deleteModel(id: String): Boolean
}

interface ModelPickerRepository {
    suspend fun getActiveModel(): UnifiedModel?
    suspend fun setDefaultModel(modelId: String): Boolean
    suspend fun getModelsByProvider(provider: ModelProvider): List<UnifiedModel>
    suspend fun filterModels(query: String): List<UnifiedModel>
}
