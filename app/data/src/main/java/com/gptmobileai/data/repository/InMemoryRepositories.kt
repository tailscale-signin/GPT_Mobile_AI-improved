package com.gptmobileai.data.repository

import com.gptmobileai.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * In-memory implementation for testing and development
 */
class InMemoryMessageQueueRepository : MessageQueueRepository {
    private val _queue = MutableStateFlow<List<QueuedMessage>>(emptyList())
    override val queue: Flow<List<QueuedMessage>> = _queue.asStateFlow()

    override suspend fun enqueue(message: QueuedMessage): String {
        val updated = message.copy(
            id = UUID.randomUUID().toString(),
            status = MessageQueueStatus.Pending,
            createdAt = System.currentTimeMillis()
        )
        _queue.update { it + updated }
        return updated.id
    }

    override suspend fun dequeue(): QueuedMessage? {
        val pending = _queue.value.filter { it.status == MessageQueueStatus.Pending }
            .sortedByDescending { it.priority }
        if (pending.isEmpty()) return null
        
        val message = pending.first()
        _queue.update { it - message }
        return message.copy(status = MessageQueueStatus.Processing)
    }

    override suspend fun processNext(): Boolean {
        val processing = _queue.value.find { it.status == MessageQueueStatus.Processing }
        if (processing != null) {
            _queue.update { it.map { m -> 
                if (m.id == processing.id) m.copy(status = MessageQueueStatus.Completed(success = true)) else m 
            }}
            return true
        }
        return false
    }

    override suspend fun removeMessage(id: String): Boolean {
        _queue.update { it.filterNot { m -> m.id == id } }
        return true
    }

    override suspend fun getQueueStatus(): List<QueuedMessage> = _queue.value.toList()

    override suspend fun clearCompleted(): Int {
        val count = _queue.value.count { it.status is MessageQueueStatus.Completed }
        _queue.update { it.filterNot { m -> m.status is MessageQueueStatus.Completed } }
        return count
    }

    override suspend fun setPriority(id: String, priority: Int): Boolean {
        _queue.update { it.map { m -> if (m.id == id) m.copy(priority = priority.coerceIn(1, 5)) else m } }
        return true
    }
}

class InMemoryModelRepository : ModelRepository {
    private val _models = MutableStateFlow<List<UnifiedModel>>(emptyList())
    override val models: Flow<List<UnifiedModel>> = _models.asStateFlow()

    init {
        // Seed with sample models
        _models.value = listOf(
            UnifiedModel(
                id = "ollama-llama3",
                name = "Llama 3",
                provider = ModelProvider.Ollama,
                description = "Meta's latest LLM",
                contextWindow = 8192,
                temperature = 0.7f,
                isDefault = true,
                isActive = true
            ),
            UnifiedModel(
                id = "ollama-mistral",
                name = "Mistral",
                provider = ModelProvider.Ollama,
                description = "Lightweight and efficient",
                contextWindow = 32000,
                temperature = 0.5f,
                isActive = true
            ),
            UnifiedModel(
                id = "openrouter-gpt4o",
                name = "GPT-4o",
                provider = ModelProvider.OpenRouter,
                description = "OpenAI's multimodal model",
                contextWindow = 128000,
                temperature = 0.7f,
                isActive = true
            ),
            UnifiedModel(
                id = "openrouter-claude3",
                name = "Claude 3",
                provider = ModelProvider.OpenRouter,
                description = "Anthropic's reasoning model",
                contextWindow = 200000,
                temperature = 0.6f,
                isActive = true
            )
        )
    }

    override suspend fun getAllModels(): List<UnifiedModel> = _models.value.toList()

    override suspend fun getModelById(id: String): UnifiedModel? {
        return _models.value.firstOrNull { it.id == id }
    }

    override suspend fun setActiveModel(modelId: String): Boolean {
        val updated = _models.value.map { m -> 
            if (m.id == modelId) m.copy(isActive = true) else m.copy(isActive = false)
        }.firstOrNull { it.id == modelId }?.copy(isActive = true) ?: return false
        
        _models.update { it.map { m -> if (m.id == modelId) updated else m } }
        return true
    }

    override suspend fun updateModel(model: UnifiedModel): Boolean {
        val updated = model.copy(createdAt = System.currentTimeMillis())
        _models.update { it.map { m -> if (m.id == model.id) updated else m } }
        return true
    }

    override suspend fun deleteModel(id: String): Boolean {
        _models.update { it.filterNot { m -> m.id == id } }
        return true
    }
}

class InMemoryModelPickerRepository : ModelPickerRepository {
    private val _activeModel = MutableStateFlow<UnifiedModel?>(null)
    override val activeModel: Flow<UnifiedModel?> = _activeModel.asStateFlow()

    init {
        // Set default model
        if (_models.value.isNotEmpty()) {
            _activeModel.value = _models.value.first { it.isDefault }
        }
    }

    override suspend fun getActiveModel(): UnifiedModel? = _activeModel.value

    override suspend fun setDefaultModel(modelId: String): Boolean {
        val model = getModelById(modelId) ?: return false
        _activeModel.update { if (it?.id == modelId) it else model }
        return true
    }

    override suspend fun getModelsByProvider(provider: ModelProvider): List<UnifiedModel> {
        return getAllModels().filter { it.provider == provider && it.isActive }
    }

    override suspend fun filterModels(query: String): List<UnifiedModel> {
        val lowerQuery = query.lowercase()
        return getAllModels().filter { 
            it.name.lowercase().contains(lowerQuery) || 
            it.description?.lowercase()?.contains(lowerQuery) == true
        }
    }

    private suspend fun getModelById(id: String): UnifiedModel? {
        return InMemoryModelRepository().getModelById(id)
    }
}
