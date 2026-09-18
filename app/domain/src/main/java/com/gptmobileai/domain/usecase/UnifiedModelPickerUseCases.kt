package com.gptmobileai.domain.usecase

import com.gptmobileai.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Message Queue Use Cases
 */

class EnqueueMessageUseCase(
    private val repository: MessageQueueRepository,
    private val modelRepository: ModelRepository
) {
    suspend operator fun invoke(content: String, modelId: String, priority: Int = 3): QueuedMessage {
        val model = modelRepository.getModelById(modelId) ?: throw IllegalArgumentException("Model not found")
        return repository.enqueue(QueuedMessage(
            content = content,
            modelId = modelId,
            priority = priority.coerceIn(1, 5)
        ))
    }
}

class ProcessQueueUseCase(
    private val repository: MessageQueueRepository
) {
    suspend operator fun invoke(): Boolean {
        return repository.processNext()
    }
}

class GetQueueStatusUseCase(
    private val repository: MessageQueueRepository
) {
    operator fun invoke(): List<QueuedMessage> {
        return repository.getQueueStatus()
    }
}

class ClearCompletedMessagesUseCase(
    private val repository: MessageQueueRepository
) {
    suspend operator fun invoke(): Int {
        return repository.clearCompleted()
    }
}

/**
 * Model Picker Use Cases
 */

class GetActiveModelUseCase(
    private val repository: ModelPickerRepository
) {
    operator fun invoke(): UnifiedModel? {
        return repository.getActiveModel()
    }
}

class SetDefaultModelUseCase(
    private val repository: ModelPickerRepository
) {
    suspend operator fun invoke(modelId: String): Boolean {
        return repository.setDefaultModel(modelId)
    }
}

class GetModelsByProviderUseCase(
    private val repository: ModelPickerRepository,
    private val modelRepository: ModelRepository
) {
    operator fun invoke(provider: ModelProvider): List<UnifiedModel> {
        return repository.getModelsByProvider(provider)
    }
}

class FilterModelsUseCase(
    private val repository: ModelPickerRepository
) {
    operator fun invoke(query: String): List<UnifiedModel> {
        return repository.filterModels(query)
    }
}

/**
 * Queue Manager - Orchestrates queue operations with retry logic
 */
class MessageQueueManager(
    private val repository: MessageQueueRepository,
    private val modelRepository: ModelRepository,
    private val config: QueueConfig = QueueConfig()
) {
    private val _queueState = MutableStateFlow<List<QueuedMessage>>(emptyList())
    val queueState: StateFlow<List<QueuedMessage>> = _queueState.asStateFlow()

    suspend fun enqueue(content: String, modelId: String, priority: Int = config.defaultPriority): QueuedMessage {
        val message = EnqueueMessageUseCase(repository, modelRepository).invoke(content, modelId, priority)
        refreshQueueState()
        return message
    }

    suspend fun processNext(): Boolean {
        return ProcessQueueUseCase(repository).invoke()
    }

    suspend fun retryMessage(id: String): Boolean {
        val message = repository.getQueueStatus().find { it.id == id } ?: return false
        if (!message.isRetryable()) return false
        
        val updated = QueuedMessage(
            id = message.id,
            content = message.content,
            modelId = message.modelId,
            priority = message.priority,
            status = MessageQueueStatus.Pending,
            createdAt = message.createdAt,
            retryCount = message.retryCount + 1,
            maxRetries = config.maxRetries
        )
        return repository.enqueue(updated)
    }

    suspend fun setPriority(id: String, priority: Int): Boolean {
        return repository.setPriority(id, priority.coerceIn(1, 5))
    }

    suspend fun clearCompleted(): Int {
        return ClearCompletedMessagesUseCase(repository).invoke()
    }

    private suspend fun refreshQueueState() {
        _queueState.value = repository.getQueueStatus()
    }
}
