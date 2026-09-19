package dev.chungjungsoo.gptmobile.domain.unified

import java.util.UUID
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Unified model picker and message queue system for GPT Mobile AI.
 */
sealed class UnifiedModelProvider(val key: String, val displayName: String) {
    data object OpenRouter : UnifiedModelProvider("openrouter", "OpenRouter")
    data object Ollama : UnifiedModelProvider("ollama", "Ollama")
    data object OpenAI : UnifiedModelProvider("openai", "OpenAI")
    data object Anthropic : UnifiedModelProvider("anthropic", "Anthropic")
    data object Google : UnifiedModelProvider("google", "Google Gemini")
    data object Groq : UnifiedModelProvider("groq", "Groq")
    data object LiteRtLm : UnifiedModelProvider("litert_lm", "On-Device (LiteRT)")
    data object Llama : UnifiedModelProvider("llama", "Llama")
    data class Custom(val customName: String) : UnifiedModelProvider("custom", customName)

    companion object {
        fun fromKey(key: String): UnifiedModelProvider = when (key.lowercase()) {
            "openrouter" -> OpenRouter
            "ollama" -> Ollama
            "openai" -> OpenAI
            "anthropic" -> Anthropic
            "google" -> Google
            "groq" -> Groq
            "litert_lm", "local" -> LiteRtLm
            "llama" -> Llama
            else -> Custom(key)
        }
    }
}

data class UnifiedModel(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val modelId: String,
    val provider: UnifiedModelProvider,
    val platformUid: String? = null,
    val description: String? = null,
    val contextWindow: Int = 0,
    val temperature: Float = 0.7f,
    val isDefault: Boolean = false,
    val isActive: Boolean = true,
    val isLocal: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

sealed class MessageQueueStatus {
    data object Pending : MessageQueueStatus()
    data object Processing : MessageQueueStatus()
    data class Completed(val responseText: String) : MessageQueueStatus()
    data class Error(val errorMessage: String, val retryCount: Int = 0) : MessageQueueStatus()
    data object Cancelled : MessageQueueStatus()
}

data class QueuedMessage(
    val id: String = UUID.randomUUID().toString(),
    val chatId: Int? = null,
    val content: String,
    val modelId: String,
    val platformUid: String? = null,
    val priority: Int = 3, // 1 (lowest) to 5 (highest)
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

/**
 * Service to execute AI inference for queued messages or direct invocations.
 */
interface AIService {
    suspend fun executeMessage(message: QueuedMessage): Result<String>
    fun executeMessageStream(message: QueuedMessage): Flow<String>
}

interface MessageQueueRepository {
    val queueState: StateFlow<List<QueuedMessage>>
    suspend fun enqueue(message: QueuedMessage): String
    suspend fun dequeue(): QueuedMessage?
    suspend fun processNext(): Boolean
    suspend fun removeMessage(id: String): Boolean
    suspend fun clearCompleted(): Int
    suspend fun setPriority(id: String, priority: Int): Boolean
    suspend fun retryMessage(id: String): Boolean
    suspend fun cancelMessage(id: String): Boolean
}

interface UnifiedModelRepository {
    suspend fun getAllModels(): List<UnifiedModel>
    suspend fun getModelById(id: String): UnifiedModel?
    suspend fun getActiveModel(): UnifiedModel?
    suspend fun setActiveModel(modelId: String): Boolean
    suspend fun setDefaultModel(modelId: String): Boolean
    suspend fun getModelsByProvider(provider: UnifiedModelProvider): List<UnifiedModel>
    suspend fun filterModels(query: String): List<UnifiedModel>
}
