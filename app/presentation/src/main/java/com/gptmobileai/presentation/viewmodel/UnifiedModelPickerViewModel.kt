package com.gptmobileai.presentation.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gptmobileai.domain.model.*
import com.gptmobileai.domain.usecase.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * Unified Model Picker and Message Queue ViewModel
 * 
 * Orchestrates model selection and message queue operations
 */
class UnifiedModelPickerViewModel(
    private val modelPickerRepository: ModelPickerRepository,
    private val modelRepository: ModelRepository,
    private val messageQueueRepository: MessageQueueRepository,
    private val enqueueMessageUseCase: EnqueueMessageUseCase,
    private val processQueueUseCase: ProcessQueueUseCase,
    private val getQueueStatusUseCase: GetQueueStatusUseCase,
    private val clearCompletedMessagesUseCase: ClearCompletedMessagesUseCase,
    private val messageQueueManager: MessageQueueManager
) : ViewModel() {

    // UI State
    val uiState = MutableStateFlow<UiState>(UiState.Loading)
    
    val activeModel = modelPickerRepository.activeModel.asStateFlow()
    val models = modelRepository.models.asStateFlow()
    val queueState = messageQueueManager.queueState.asStateFlow()

    // Actions
    fun selectModel(modelId: String) {
        viewModelScope.launch {
            modelRepository.setActiveModel(modelId)
            uiState.value = uiState.value.copy(selectedModelId = modelId)
        }
    }

    fun setDefaultModel(modelId: String) {
        viewModelScope.launch {
            SetDefaultModelUseCase(modelPickerRepository).invoke(modelId)
        }
    }

    fun filterModels(query: String) {
        viewModelScope.launch {
            val filtered = FilterModelsUseCase(modelPickerRepository).invoke(query)
            uiState.value = uiState.value.copy(filteredModels = filtered)
        }
    }

    fun enqueueMessage(content: String, modelId: String, priority: Int = 3) {
        viewModelScope.launch {
            try {
                val message = EnqueueMessageUseCase(messageQueueRepository, modelRepository).invoke(
                    content = content,
                    modelId = modelId,
                    priority = priority
                )
                uiState.value = uiState.value.copy(lastEnqueuedMessage = message)
            } catch (e: Exception) {
                uiState.value = uiState.value.copy(error = e.message ?: "Failed to enqueue message")
            }
        }
    }

    fun processNext() {
        viewModelScope.launch {
            ProcessQueueUseCase(messageQueueRepository).invoke()
        }
    }

    fun retryMessage(id: String) {
        viewModelScope.launch {
            MessageQueueManager(
                messageQueueRepository, modelRepository
            ).retryMessage(id)
        }
    }

    fun setPriority(id: String, priority: Int) {
        viewModelScope.launch {
            messageQueueRepository.setPriority(id, priority)
        }
    }

    fun clearCompleted() {
        viewModelScope.launch {
            ClearCompletedMessagesUseCase(messageQueueRepository).invoke()
        }
    }

    fun refreshQueueState() {
        viewModelScope.launch {
            uiState.value = uiState.value.copy(queueStatus = getQueueStatusUseCase().invoke())
        }
    }

    // Auto-process queue periodically
    init {
        viewModelScope.launch {
            while (true) {
                delay(5000) // Check every 5 seconds
                processNext()
            }
        }
    }
}

sealed class UiState {
    object Loading : UiState()
    data class Success(
        val selectedModelId: String? = null,
        val filteredModels: List<UnifiedModel> = emptyList(),
        val lastEnqueuedMessage: QueuedMessage? = null,
        val queueStatus: List<QueuedMessage> = emptyList(),
        val error: String? = null
    ) : UiState()
}
