package dev.chungjungsoo.gptmobile.presentation.unified

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.domain.unified.MessageQueueRepository
import dev.chungjungsoo.gptmobile.domain.unified.QueuedMessage
import dev.chungjungsoo.gptmobile.domain.unified.UnifiedModel
import dev.chungjungsoo.gptmobile.domain.unified.UnifiedModelProvider
import dev.chungjungsoo.gptmobile.domain.unified.UnifiedModelRepository
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class UnifiedPickerUiState {
    data object Loading : UnifiedPickerUiState()
    data class Success(
        val models: List<UnifiedModel>,
        val activeModel: UnifiedModel?,
        val selectedProvider: UnifiedModelProvider? = null,
        val searchQuery: String = ""
    ) : UnifiedPickerUiState()
    data class Error(val message: String) : UnifiedPickerUiState()
}

@HiltViewModel
class UnifiedModelPickerViewModel @Inject constructor(
    private val modelRepository: UnifiedModelRepository,
    private val messageQueueRepository: MessageQueueRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<UnifiedPickerUiState>(UnifiedPickerUiState.Loading)
    val uiState: StateFlow<UnifiedPickerUiState> = _uiState.asStateFlow()

    val queuedMessages: StateFlow<List<QueuedMessage>> = messageQueueRepository.queueState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private var allCachedModels: List<UnifiedModel> = emptyList()
    private var currentFilterQuery: String = ""
    private var currentSelectedProvider: UnifiedModelProvider? = null

    init {
        loadModels()
    }

    fun loadModels() {
        viewModelScope.launch {
            _uiState.value = UnifiedPickerUiState.Loading
            try {
                allCachedModels = modelRepository.getAllModels()
                val active = modelRepository.getActiveModel()
                applyFilterAndEmit(active)
            } catch (e: Exception) {
                _uiState.value = UnifiedPickerUiState.Error(e.message ?: "Failed to load models")
            }
        }
    }

    fun selectModel(model: UnifiedModel) {
        viewModelScope.launch {
            modelRepository.setActiveModel(model.id)
            modelRepository.setDefaultModel(model.id)
            val updated = modelRepository.getActiveModel()
            applyFilterAndEmit(updated)
        }
    }

    fun filterByProvider(provider: UnifiedModelProvider?) {
        currentSelectedProvider = provider
        viewModelScope.launch {
            val active = modelRepository.getActiveModel()
            applyFilterAndEmit(active)
        }
    }

    fun searchModels(query: String) {
        currentFilterQuery = query
        viewModelScope.launch {
            val active = modelRepository.getActiveModel()
            applyFilterAndEmit(active)
        }
    }

    fun enqueueMessage(content: String, priority: Int = 3, chatId: Int? = null) {
        viewModelScope.launch {
            val active = modelRepository.getActiveModel()
            val queued = QueuedMessage(
                chatId = chatId,
                content = content,
                modelId = active?.modelId ?: "",
                platformUid = active?.platformUid,
                priority = priority
            )
            messageQueueRepository.enqueue(queued)
        }
    }

    fun retryQueueItem(id: String) {
        viewModelScope.launch {
            messageQueueRepository.retryMessage(id)
        }
    }

    fun cancelQueueItem(id: String) {
        viewModelScope.launch {
            messageQueueRepository.cancelMessage(id)
        }
    }

    fun removeQueueItem(id: String) {
        viewModelScope.launch {
            messageQueueRepository.removeMessage(id)
        }
    }

    fun clearCompletedQueue() {
        viewModelScope.launch {
            messageQueueRepository.clearCompleted()
        }
    }

    fun updateItemPriority(id: String, priority: Int) {
        viewModelScope.launch {
            messageQueueRepository.setPriority(id, priority)
        }
    }

    private fun applyFilterAndEmit(activeModel: UnifiedModel?) {
        var filtered = allCachedModels
        if (currentSelectedProvider != null) {
            filtered = filtered.filter { it.provider.key == currentSelectedProvider?.key }
        }
        if (currentFilterQuery.isNotBlank()) {
            val q = currentFilterQuery.lowercase().trim()
            filtered = filtered.filter {
                it.name.lowercase().contains(q) ||
                    it.modelId.lowercase().contains(q) ||
                    (it.description?.lowercase()?.contains(q) == true)
            }
        }
        _uiState.value = UnifiedPickerUiState.Success(
            models = filtered,
            activeModel = activeModel,
            selectedProvider = currentSelectedProvider,
            searchQuery = currentFilterQuery
        )
    }
}
