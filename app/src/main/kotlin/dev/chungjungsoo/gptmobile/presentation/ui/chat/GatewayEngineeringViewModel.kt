package dev.chungjungsoo.gptmobile.presentation.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringBinding
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringJob
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringRepository
import dev.chungjungsoo.gptmobile.data.github.GatewayEngineeringSettings
import dev.chungjungsoo.gptmobile.data.github.GitHubConversationContext
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class GatewayEngineeringUiState(
    val bindings: List<GatewayEngineeringBinding> = emptyList(),
    val options: GatewayEngineeringSettings = GatewayEngineeringSettings(),
    val busy: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class GatewayEngineeringViewModel @Inject constructor(private val repository: GatewayEngineeringRepository) : ViewModel() {
    private val mutable = MutableStateFlow(GatewayEngineeringUiState())
    val state = mutable.asStateFlow()
    private var chat: Int? = null
    private var polling: Job? = null

    fun bind(chatId: Int) {
        if (chat == chatId) return
        chat = chatId
        polling?.cancel()
        polling = viewModelScope.launch {
            mutable.update { it.copy(bindings = repository.bindings(chatId), options = repository.options()) }
            repository.schedule()
            while (isActive) {
                try {
                    for (binding in repository.bindings(chatId)) {
                        repository.reconnect(binding.request.requestId)
                    }
                    mutable.update { it.copy(bindings = repository.bindings(chatId), error = null) }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (failure: Exception) {
                    mutable.update { it.copy(error = failure.message, bindings = repository.bindings(chatId)) }
                }
                delay(5_000)
            }
        }
    }

    fun start(context: GitHubConversationContext, base: String, branch: String, objective: String, options: GatewayEngineeringSettings) = action {
        repository.start(requireNotNull(chat), context, base, branch, objective, options)
    }

    fun control(binding: GatewayEngineeringBinding, expected: GatewayEngineeringJob, action: String) = action {
        repository.control(binding.request.requestId, expected, action)
    }

    fun reconnect(binding: GatewayEngineeringBinding) = action { repository.reconnect(binding.request.requestId) }

    fun markSeen(binding: GatewayEngineeringBinding) = action { repository.markSeen(binding.request.requestId) }

    private fun action(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        viewModelScope.launch {
            mutable.update { it.copy(busy = true, error = null) }
            try {
                block()
                mutable.update { it.copy(bindings = repository.bindings(chat), options = repository.options()) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                mutable.update { it.copy(error = failure.message, bindings = repository.bindings(chat)) }
            } finally {
                mutable.update { it.copy(busy = false) }
            }
        }
    }
}
